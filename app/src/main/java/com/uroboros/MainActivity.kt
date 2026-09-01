package com.uroboros

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.databinding.ActivityMainBinding
import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.CONTEXT_SIZE
import com.uroboros.llm.JournalStore
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.ConfidenceLevel
import com.uroboros.memory.DatabaseExporter
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.SourceKind
import com.uroboros.memory.StopCause
import com.uroboros.memory.TrustedMediator
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone
import com.uroboros.will.SimplePendingQuerySource
import com.uroboros.will.ToteResult
import com.uroboros.will.TermuxKotlinCompiler
import com.uroboros.will.tasks.KotlinCodingTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediator: TrustedMediator
    private lateinit var llmEngine: LlmEngine
    private lateinit var watchdog: DeviceSafetyWatchdog
    private lateinit var termuxCompiler: TermuxKotlinCompiler
    private lateinit var codingTask: KotlinCodingTask
    private lateinit var pendingQuerySource: SimplePendingQuerySource

    private var isToteRunning = false

    /**
     * Лента разговора. Живёт столько же, сколько экран: переживает поворот
     * не сама по себе, а потому что процесс тот же — граница названа в
     * [ConversationJournal].
     *
     * Агентский цикл ленту НЕ ведёт. Он ходит своим путём, через
     * [LlmEngine.generateFlow], и там каждый запрос по-прежнему сам по
     * себе. Мешать разговор человека с работой цикла в одной ленте нельзя:
     * это два разных собеседника в одном тексте, и модель прочитала бы
     * чужие реплики как свои.
     */
    private val journal = ConversationJournal.shared

    /**
     * Сохранение ленты на диск. Заводится лениво: база открывается при
     * первом обращении, а не при каждом создании активности.
     *
     * Лента о хранилище не знает — связка живёт в [onCreate] через
     * оповещения журнала. Так рядом с каждым `appendTurn` не нужно помнить
     * о сохранении, а забытое место молчало бы до первого перезапуска.
     */
    private val journalStore by lazy { JournalStore(applicationContext) }

    // Ссылка на корутину идущего TOTE-цикла. Нужна ровно для одного:
    // аварийный стоп должен прервать уже начатую работу, а не только запретить
    // будущую. Флаг EmergencyStop закрывает гейт — но цикл, который уже крутится,
    // от закрытого гейта не останавливается: он продолжает генерировать текст и
    // получать отказы, пока не кончится энергия. Это минуты работы после нажатия
    // кнопки с надписью "Стоп". Поэтому слоя два: флаг (запрет) + cancel (обрыв).
    private var toteJob: Job? = null

    // Панель метрик собирается из четырёх независимых кусков, у каждого своя
    // частота обновления: строка про железо меняется сама по себе из потоков,
    // строка параметров движка — один раз за загрузку модели, строка кэша —
    // при загрузке и после каждой генерации, строка чисел — после каждой
    // генерации. Держать их отдельно и склеивать при отрисовке проще, чем
    // гонять один текст и бояться затереть чужую половину.
    private var engineParamsLine: String? = null
    private var promptCacheLine: String? = null

    /**
     * Что стало с сохранённым на диске разговором — строка для человека.
     *
     * Три состояния, а не два: поднят (сколько ходов), отказано (почему),
     * не сохранился очередной ход. Без неё пустая лента после запуска
     * выглядит одинаково и когда сохранять было нечего, и когда сохранение
     * сломано. null прячет строку — на чистом запуске сообщать не о чем.
     */
    private var journalRestoreLine: String? = null

    /**
     * Что лежит на диске прямо сейчас: активная лента и архив закрытых
     * разговоров.
     *
     * ДВА ЧИСЛА, А НЕ СУММА. Сумма скрыла бы, что активная лента пуста, —
     * то есть ровно то, что человеку и надо видеть после закрытия
     * разговора. Про архив строка молчит, пока он пуст: сообщать о
     * хранилище, в которое ещё ничего не клали, нечего.
     *
     * ПОЧЕМУ ОТДЕЛЬНО ОТ [journalLine]. Та строка описывает ленту в памяти
     * процесса, эта — таблицу на диске. Величины разные и расходятся
     * законно: лента пуста после запуска, пока разговор не поднят, а на
     * диске он при этом есть. Слить их в одну значило бы, что «поднимать
     * нечего» и «есть что поднять, но не подняли» выглядят одинаково.
     *
     * Тот же разрез, что у двух строк о контрольной точке ниже, и заведён
     * он по той же причине.
     *
     * ЧЕГО ЭТА СТРОКА НЕ ГОВОРИТ. Только есть ли материал, но не
     * поднимется ли он: на это отвечает [journalRestoreLine], и ответ у
     * него бывает отрицательным при непустом диске. В частности, ход,
     * записанный до загрузки модели, ложится с пустым отпечатком и делает
     * подъём невозможным навсегда — здесь он считается наравне с
     * остальными, потому что на диске он есть.
     *
     * null прячет строку — до первого чтения показывать нечего, а ноль
     * означал бы «на диске пусто», то есть измеренную величину вместо
     * незнания.
     */
    private var journalDiskLine: String? = null
    private var lastMetricsLine: String? = null

    /**
     * Две строки о контрольной точке, намеренно НЕ слитые в одну.
     *
     * Первая — что лежит на диске: размер и насколько отстала. Читается при
     * загрузке модели, то есть отвечает на вопрос «а прошлая запись вообще
     * состоялась». Вторая — что случилось при последней попытке в этом
     * запуске.
     *
     * Слить их было бы соблазнительно: обе про точку, обе короткие. Но
     * пропало бы ровно то, ради чего они заводятся. Точка пишется при уходе
     * приложения в фон, и приложение может умереть раньше, чем запись
     * кончится. Одна строка сказала бы «не пробовали» и после чистого
     * запуска, и после неудавшейся записи — то есть молчала бы именно в том
     * случае, который надо увидеть.
     */
    private var checkpointDiskLine: String? = null
    private var checkpointActionLine: String? = null

    // Палитра совпадает с activity_main.xml. Смысл цвета, а не украшение:
    // бирюзовый = обычное главное действие, фиолетовый = канал речи агента
    // (тем же цветом помечен блок "Ответ агента"). Красный нигде, кроме
    // аварийного стопа, не используется.
    private val colorIdle = ColorStateList.valueOf(Color.parseColor("#17697B"))
    private val colorToteRunning = ColorStateList.valueOf(Color.parseColor("#5B4B9E"))

    private val prefs by lazy { getSharedPreferences("uroboros_prefs", Context.MODE_PRIVATE) }

    /**
     * Метка источника ДЛЯ ЭКРАНА — для человека, который смотрит содержимое
     * памяти кнопкой "Показать память".
     *
     * Не путать с [provenancePhrase]: та же величина, но два разных читателя.
     * Функции намеренно НЕ объединены, хотя различаются только словами. Если
     * их слить, любая правка подписи на экране молча поменяет текст, который
     * читает модель, — а это уже смена смысла запроса, и обнаружится она не
     * на экране, а по странным ответам через неделю.
     */
    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
    }

    /**
     * Тот же источник, но словами, обращёнными К МОДЕЛИ — и не меткой, а
     * подлежащим со сказуемым.
     *
     * Причина появления (27.08.2026). Провенанс в записях лежал с 24.08, но в
     * запрос уходил технической меткой вида "[от пользователя]". Модель такую
     * метку не читает как указание, чьи это слова, и вела себя соответственно:
     * на вопрос "что ты знаешь про рубанок" отвечала "в моей памяти нет
     * информации об этом инструменте" — при том что запись лежала прямо в
     * запросе, — а найденную запись пересказывала дословно вместе с чужим
     * местоимением ("МОЙ любимый инструмент" вместо "ваш").
     *
     * Форма выбрана так, чтобы работу делала грамматика, а не инструкция.
     * "Пользователь сказал: «...»" само отвечает на вопрос, чья это речь, —
     * и стоит ДЕШЕВЛЕ прежней метки, тогда как объяснение теми же словами в
     * отдельном абзаце стоило бы полусотни токенов в каждом запросе.
     */
    private fun provenancePhrase(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "Пользователь сказал"
        SourceKind.AGENT_INFERRED.name -> "Ты сам вывел"
        SourceKind.OCR_EXTRACTED.name -> "Распознано на картинке"
        // Честнее сказать "не записан", чем подставить пользователя или себя.
        // Перепутать, кто что сказал, система права не имеет.
        else -> "Источник не записан"
    }

    /**
     * Смещения в тексте [renderJournal], с которых начинается каждая
     * реплика человека. По ним прыгают кнопки хода.
     *
     * Считаются при отрисовке, а не ищутся поиском по готовому тексту:
     * подпись «Вы: » может встретиться и внутри ответа модели — сегодня
     * она уже цитировала разговор целиком, — и поиск наткнулся бы на
     * цитату. Здесь же смещение известно точно, потому что мы сами
     * собираем строку.
     */
    private val turnOffsets = mutableListOf<Int>()

    /** На каком ходе стоит взгляд. -1 — ещё никуда не прыгали. */
    private var navTurnIndex = -1

    /**
     * Ходы, у которых блок записей развёрнут.
     *
     * Свёрнуто по умолчанию: подробности нужны, когда ответ удивил, а это
     * не каждый ход. Развёрнутая по умолчанию лента стала бы вчетверо
     * длиннее, и прыжки кнопками хода поехали бы вместе с ней.
     *
     * Живёт на активности, а не в журнале: это состояние экрана, а не
     * разговора. После поворота всё свернётся обратно — потеря, которую
     * не жалко, в отличие от самой ленты.
     */
    private val expandedTurns = mutableSetOf<Int>()

    /**
     * Цвет кликабельной части строки записей. Бирюзовый — цвет обычного
     * действия в приложении; фиолетовый занят речью агента, красный —
     * аварийным стопом.
     */
    private val colorRecordsLink = Color.parseColor("#17697B")

    /**
     * Лента в том виде, в каком её читает человек.
     *
     * Показывается ВОПРОС, а не реплика целиком: записи памяти уходят в
     * модель, но читать разговор вперемешку с цитатами, которых человек
     * не писал, невозможно. Разделение держится тем, что оба текста лежат
     * в одном ходе, см. [ConversationJournal.Turn].
     *
     * С 28.08.2026 записи всё же видны — но отдельной строкой и свёрнуто.
     * Это не отмена прежнего решения, а другой читатель: в реплику они по
     * прежнему не возвращаются, разговор остаётся разговором, а рядом
     * стоит признак того, на чём ответ стоял. Повод — за час живого
     * разговора настоящее воспоминание и выдумка четырежды оказались
     * неотличимы на глаз.
     *
     * Подписи, а не цвет: поле результата — обычный текст, и раскрасить
     * в нём куски по-разному стоило бы отдельной работы со стилями. Цвет
     * речи агента в приложении уже занят и означает канал целиком, а не
     * отдельную реплику.
     */
    private fun renderJournal(pendingQuestion: String? = null): CharSequence {
        turnOffsets.clear()
        val out = SpannableStringBuilder()

        // Строка записей стоит МЕЖДУ вопросом и ответом — в том порядке, в
        // каком всё и произошло: человек спросил, отбор подложил записи,
        // модель ответила. Под ответом она читалась бы как его часть.
        fun addRecordsBlock(index: Int, turn: ConversationJournal.Turn) {
            if (turn.records.isEmpty()) {
                // Говорится прямо, а не пропускается. Пустой отбор — это и
                // есть главный признак того, что ответ ни на чём не стоял;
                // молчание в этом месте читалось бы как "всё в порядке".
                out.append("\n[записей нет]")
                return
            }
            val fresh = turn.records.count { it.firstSeenTurn == index }
            val expanded = index in expandedTurns
            out.append("\n[записей ${turn.records.size}, новых $fresh] ")
            val start = out.length
            out.append(if (expanded) "скрыть" else "показать")
            out.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) = toggleTurnRecords(index)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = colorRecordsLink
                        ds.isUnderlineText = false
                    }
                },
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (!expanded) return
            for (use in turn.records) {
                // "с хода N" важнее, чем кажется: из-за отсева повторов
                // запись, легшая на первом ходе, на шестом не подставляется
                // заново — но модели она всё это время видна. Без пометки
                // верный ответ выглядел бы выдумкой.
                val origin =
                    if (use.firstSeenTurn == index) "новая"
                    else "с хода ${use.firstSeenTurn + 1}"
                out.append("\n  • ").append(origin).append(": ").append(use.text)
            }
        }

        fun addTurn(question: String, answer: String?, turn: ConversationJournal.Turn?, index: Int) {
            if (out.isNotEmpty()) out.append("\n\n")
            turnOffsets += out.length
            out.append("Вы: ").append(question)
            if (turn != null) addRecordsBlock(index, turn)
            out.append("\n\n").append("Агент: ")
            if (answer != null) out.append(answer)
        }

        journal.history().forEachIndexed { index, turn ->
            addTurn(turn.question, turn.agentContent, turn, index)
        }
        // У начатого хода записей ещё нет: они помечаются уложенными только
        // после того, как ответ получен.
        if (pendingQuestion != null) addTurn(pendingQuestion, null, null, -1)
        return out
    }

    /**
     * Развернуть или свернуть записи одного хода.
     *
     * Во время генерации ничего не делает. Причина: токены дописываются
     * прямо в поле по одному, и в нём в этот момент лежит текст, которого
     * в ленте ещё нет. Перерисовка стёрла бы уже показанную часть ответа —
     * молча, и человек решил бы, что генерация сорвалась.
     */
    private fun toggleTurnRecords(index: Int) {
        if (!binding.buttonGenerate.isEnabled) return
        if (!expandedTurns.remove(index)) expandedTurns += index
        binding.textResults.text = renderJournal()
    }

    /**
     * Прыжок на начало реплики: её первая строка встаёт под верх экрана.
     *
     * `post` нужен потому, что сразу после смены текста разметка ещё не
     * пересчитана и `layout` пуст — прыжок ушёл бы в никуда.
     */
    private fun scrollToTurn(index: Int) {
        if (index !in turnOffsets.indices) return
        navTurnIndex = index
        binding.textResults.post {
            val layout = binding.textResults.layout ?: return@post
            val line = layout.getLineForOffset(turnOffsets[index])
            binding.scrollResults.smoothScrollTo(0, binding.textResults.top + layout.getLineTop(line))
        }
    }

    /**
     * Видимость строки хода. У кнопок в ней условия РАЗНЫЕ, и в этом всё
     * дело.
     *
     * Прыжки показываются, только когда прыгать есть куда. Порог в
     * [TURN_NAV_MIN_TURNS] хода взят как наименьший, при котором кнопка
     * делает что-то, чего не делает взмах. На меньшем разговоре они бы
     * только отнимали высоту у самого разговора.
     *
     * Отрезание показывается, как только в ленте есть хоть один ход:
     * плохой ответ бывает и первым, и прятать от него единственное
     * лекарство нельзя. Свести оба условия к одному значило бы спрятать
     * отрезание ровно там, где оно нужнее всего.
     *
     * Сама строка видна, если видна хоть одна кнопка в ней.
     */
    private fun renderTurnNavVisibility() {
        val turns = journal.turnCount
        val jumps = if (turns >= TURN_NAV_MIN_TURNS) View.VISIBLE else View.GONE
        binding.buttonTurnPrev.visibility = jumps
        binding.buttonTurnNext.visibility = jumps
        binding.buttonDropTurn.visibility = if (turns >= 1) View.VISIBLE else View.GONE
        binding.rowTurnNav.visibility = if (turns >= 1) View.VISIBLE else View.GONE
    }

    /**
     * Доехать вниз, но только если человек и так внизу.
     *
     * Пока идёт ответ, поле должно ехать за текстом — иначе человек
     * смотрит в неподвижный экран, а ответ растёт за его нижним краем.
     * Но если он в этот момент отлистал вверх перечитать прошлый ход,
     * дёргать экран нельзя: это отняло бы у него место, куда он смотрел.
     * Поэтому едем только из положения «уже внизу».
     *
     * Запас в [AUTOSCROLL_BOTTOM_SLACK] пикселей нужен, потому что
     * «внизу» на глаз и «внизу» до пикселя — разные вещи: строка
     * дорисовалась, и человек уже формально не внизу, хотя не двигался.
     */
    private fun autoScrollIfAtBottom() {
        val scroll = binding.scrollResults
        val child = scroll.getChildAt(0) ?: return
        val distanceToBottom = child.bottom - (scroll.height + scroll.scrollY)
        if (distanceToBottom <= AUTOSCROLL_BOTTOM_SLACK) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Заполненность ленты для панели чисел.
     *
     * Показывается ЗАНЯТОЕ, а не оставшееся: занятое — измеренный движком
     * размер прошлого запроса, оставшееся пришлось бы предсказывать, а
     * длины следующего вопроса и ответа мы не знаем. Первое число честное,
     * второе было бы гаданием с видом факта.
     *
     * До первого прогона счётчик пуст: числа ещё нет, а подставлять ноль
     * значило бы показать пустую ленту как измеренную.
     */
    private fun journalLine(): String {
        if (journal.isEmpty) return "Лента: пуста"
        val used = journal.lastPromptTokens
        // Размер снимка состояния — разовое измерение, а не постоянная
        // метрика: оно решает, идти в контрольные точки или в постоянное
        // уведомление. Спрашивается у движка при каждой отрисовке, потому
        // что растёт вместе с лентой, и интересна как раз эта зависимость.
        // Защита на lateinit нужна: панель рисуется и до создания движка.
        val state = if (::llmEngine.isInitialized) {
            val mb = llmEngine.stateSizeBytes / (1024 * 1024)
            if (mb > 0) " · снимок $mb МБ" else ""
        } else ""
        // Ноль при НЕПУСТОЙ ленте означает "не измерено", а не "пусто", и
        // показывать его как измеренную величину нельзя: правило написано
        // абзацем выше, а случай появился, когда лента научилась
        // подниматься с диска. Ход, сохранённый до отчёта движка, и строки
        // старее столбца дают тот же ноль.
        //
        // Молчание здесь дороже удобства: на этом же числе стоит проверка
        // "влезет ли следующий ход". Ноль сказал бы, что места полно, ход
        // ушёл бы в движок, и тот молча выбросил бы половину разговора.
        // Признать незнание — единственный честный вид.
        val fill = if (used > 0) {
            "занято $used из $CONTEXT_SIZE ток. (${journal.fillPercent(CONTEXT_SIZE)}%)"
        } else {
            "занято: неизвестно до первого ответа"
        }
        return "Лента: ходов ${journal.turnCount} · $fill$state"
    }

    /**
     * Перечитывает с диска число сохранённых ходов и обновляет панель.
     *
     * ЧИТАЕТСЯ ПО СОБЫТИЮ, А НЕ ПРИ КАЖДОЙ ОТРИСОВКЕ — в отличие от
     * [journalLine], которая целиком выводится из ленты в памяти. Здесь
     * обращение к базе, а панель перерисовывается на каждое изменение
     * зоны сторожа, то есть постоянно.
     *
     * Отсюда обязанность: вызывать после КАЖДОГО действия, меняющего
     * диск, — записи хода, стирания ленты, отрезания. Забытое место не
     * упадёт и не промолчит, а покажет старое число, то есть соврёт. Это
     * хуже отсутствия строки, и потому список мест назван здесь, а не
     * оставлен на память.
     *
     * ОБА ЧИСЛА БЕРУТСЯ ОДНИМ ВЫЗОВОМ. Прочитай их порознь — между
     * чтениями могло бы пролезть закрытие разговора, и на экран попала бы
     * пара, которой на диске не было ни в один момент: ходов 2, в архиве
     * 0. Причина подробнее в KDoc `JournalStore.counts`.
     *
     * ОТКАЗ ЧТЕНИЯ ГОВОРИТСЯ СЛОВАМИ, а не показывается нулями. Показать
     * «в архиве 0» на нечитаемой базе значило бы объявить потерю обычной
     * работой — а после закрытия разговора архив это единственное место,
     * где ходы остались.
     */
    private suspend fun refreshJournalDiskLine() {
        val counts = journalStore.counts()
        journalDiskLine = when {
            counts == null -> "Лента на диске: не читается"
            counts.active > 0 && counts.archived > 0 ->
                "Лента на диске: ходов ${counts.active} · в архиве ${counts.archived}"
            counts.active > 0 -> "Лента на диске: ходов ${counts.active}"
            counts.archived > 0 -> "Лента на диске: пусто · в архиве ${counts.archived}"
            else -> "Лента на диске: пусто"
        }
        renderMetricsPanel()
    }

    /**
     * Разговор упёрся в край.
     *
     * Почему диалог, а не молчаливая очистка. Укладки — переноса
     * разговора в память записями — ещё нет, поэтому закрытие ленты
     * означает, что собеседник обнуляется. Такое решение принимает
     * человек, а не счётчик.
     *
     * Ходы при этом уходят в архив, а не стираются, — но для собеседника
     * разницы нет: архив в запрос не идёт. Он спасает материал от потери,
     * не память агента от обнуления.
     *
     * Почему нельзя просто продолжить. При `n_past >= n_ctx - 1` движок
     * сам, посреди генерации, выбрасывает половину ленты и ничего об этом
     * не сообщает. То есть выбор не между "остановиться" и "работать
     * дальше", а между "остановиться явно" и "потерять середину
     * разговора молча".
     */
    private fun showJournalFullDialog() {
        AlertDialog.Builder(this)
            .setTitle("Лента заполнена")
            .setMessage(
                "В разговоре ${journal.turnCount} ходов, занято " +
                    "${journal.lastPromptTokens} из ${CONTEXT_SIZE} токенов. " +
                    "Продолжать нельзя: движок начнёт молча выбрасывать середину разговора.\n\n" +
                    "Начать заново — значит закрыть разговор: все ${journal.turnCount} ходов " +
                    "уйдут в архив на этом устройстве и с экрана исчезнут. Из архива они не " +
                    "возвращаются ни в разговор, ни в память агента — это хранилище, а не " +
                    "откат. Всё, что должно остаться агенту, отметьте кнопкой сохранения до " +
                    "закрытия."
            )
            .setPositiveButton("Закрыть и начать заново") { _, _ ->
                journal.clear()
                expandedTurns.clear()
                binding.textResults.text = ""
                renderMetricsPanel()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setToteRunningState(running: Boolean) {
        isToteRunning = running
        // Подписи короткие: подсказки о жестах вынесены одной строкой в
        // разметку, поэтому переносить внутри кнопки больше нечего.
        binding.buttonGenerate.text = if (running) "Спросить" else "Генерировать"
        binding.buttonGenerate.backgroundTintList = if (running) colorToteRunning else colorIdle
    }

    // Числа форматируются через Locale.US намеренно: на экране рядом стоят
    // значения из разных источников, и разделитель у них должен быть один.
    private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun fmt0(value: Double): String = String.format(Locale.US, "%.0f", value)

    private fun fmtSec(millis: Double): String = fmt1(millis / 1000.0)

    /**
     * Зона сторожа по-русски. На экран не должно попадать слово FATIGUE:
     * человек, который читает этот экран, не обязан знать, как названа
     * константа в коде.
     */
    private fun zoneLabel(zone: SafetyZone): String = when (zone) {
        SafetyZone.COMFORT -> "норма"
        SafetyZone.WARNING -> "нагрев"
        SafetyZone.FATIGUE -> "утомление"
        SafetyZone.CRITICAL -> "критическая"
    }

    private fun renderMetricsPanel() {
        val zone = watchdog.zone.value
        val power = watchdog.power.value

        val charge = if (power.percentKnown) "${power.percent}%" else "?"
        val plug = if (power.charging) " (заряжается)" else ""
        binding.textHardware.text = "Зона: ${zoneLabel(zone)} · батарея $charge$plug · " +
            "${fmt1(power.temperatureCelsius)}°C"

        // Внутрь шторки — всё, что нужно при разборе. Строка железа выше сюда
        // больше не входит: она обязана быть видна независимо от того,
        // раскрыта шторка или нет.
        // Строка ленты стоит ВЫШЕ чисел прогона намеренно: она отвечает на
        // вопрос "сколько разговора ещё поместится", и увидеть её надо до
        // того, как упрёшься. Считается при каждой отрисовке, а не хранится
        // отдельной переменной, как соседи: она целиком выводится из ленты,
        // и второе место, где она может разойтись с лентой, заводить незачем.
        binding.textMetrics.text =
            listOfNotNull(
                engineParamsLine, promptCacheLine, journalLine(),
                journalDiskLine, journalRestoreLine,
                checkpointDiskLine, checkpointActionLine,
                lastMetricsLine,
            )
                .joinToString("\n")
    }

    /**
     * Ход текущей работы отдельной строкой вне шторки.
     *
     * Отделено от панели метрик намеренно. Обсчёт длинного запроса идёт
     * минутами, и всё это время строка процентов — единственное, что отличает
     * работающую программу от зависшей. Убери она под свёрнутую шторку, и
     * человек остался бы перед неподвижным экраном.
     *
     * null прячет строку целиком: пустая строка занимала бы место и читалась
     * бы как «что-то сломалось и не написало».
     */
    private fun showProgress(text: String?) {
        if (text == null) {
            binding.textProgress.visibility = View.GONE
            binding.textProgress.text = ""
        } else {
            binding.textProgress.visibility = View.VISIBLE
            binding.textProgress.text = text
        }
    }

    /**
     * Раскрыть или свернуть шторку с диагностикой.
     *
     * Состояние запоминается между запусками: тот, кто разбирается с
     * поломкой, не должен раскрывать её заново после каждого перезапуска
     * приложения, а перезапусков при работе с моделью много.
     */
    private fun setDetailsExpanded(expanded: Boolean) {
        binding.textMetrics.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.buttonDetails.text = if (expanded) "Свернуть ▴" else "Подробно ▾"
        prefs.edit().putBoolean(KEY_DETAILS_EXPANDED, expanded).apply()
    }

    /**
     * Стереть числа прошлого прогона.
     *
     * Вызывается ПЕРВОЙ строкой каждого обработчика запуска, до всех проверок.
     * Причина (хвост 20, 27.08.2026): если запуск не состоялся — пустое поле
     * ввода, взведённый стоп, незагруженная модель, — на экране оставались
     * числа предыдущего прогона и читались как относящиеся к нынешнему. Из
     * трёх подряд замеров два были испорчены именно так.
     *
     * Строка кэша стены здесь НЕ трогается намеренно: она описывает состояние
     * файла на диске, а не прогон, и обнулять её значило бы врать, что кэша
     * нет. Строка параметров движка не трогается по той же причине.
     */
    private fun clearRunMetrics() {
        lastMetricsLine = null
        showProgress(null)
        renderMetricsPanel()
    }

    /**
     * Вытаскивает из лога библиотеки строку с фактическими параметрами загрузки.
     *
     * Зачем вообще: контекст, число потоков и размер батча в нашем коде не
     * задаются — LlmEngine берёт их из getRecommendedParams(), а та выбирает
     * уровень по объёму памяти телефона. Прочитать код библиотеки и вывести
     * ожидаемые значения можно, но это будет вывод, а не факт. Лог движка —
     * единственное место, где написано, что получилось на самом деле.
     *
     * Ищем сначала по "ctx=" — это те данные, ради которых всё затевалось, и
     * такой поиск переживёт переименование самой строки. Если не нашли, честно
     * говорим об этом, а не показываем пустоту: молчащий экран невозможно
     * отличить от сломанного.
     */
    private fun extractEngineParams(log: String): String {
        val lines = log.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.lastOrNull { it.contains("ctx=") }?.let { return "Движок: $it" }
        lines.lastOrNull { it.contains("Loading model", ignoreCase = true) }
            ?.let { return "Движок: $it" }
        return "Движок: строка параметров в логе не найдена (нет ни ctx=, ни Loading model)"
    }

    /**
     * Фактический режим потоков движка плюс состояние его собственного
     * теплового регулятора.
     *
     * Важно, что показывается именно фактический, а не заданный: у библиотеки
     * есть свой тепловой авто-режим, который может понизить режим сам. Если
     * когда-нибудь мы зададим «производительность», а на экране останется
     * «баланс» — значит нас понизили, и мерить мы будем не то, что задали.
     */
    private fun threadModeLine(): String {
        val mode = llmEngine.getEffectiveThreadMode()
        val modeName = when (mode) {
            0 -> "экономия"
            1 -> "баланс"
            2 -> "производительность"
            else -> "неизвестный"
        }
        val auto = if (llmEngine.isEngineAutoModeEnabled()) "вкл" else "выкл"
        return "Режим потоков: $mode ($modeName) · авто-режим движка: $auto"
    }

    /**
     * Разбивка времени генерации по стадиям, в долях от общего.
     *
     * Читается так: если почти всё лежит в «прямой проход», упор в память или
     * в число потоков, и лечится это настройками движка. Если заметная доля
     * ушла в сэмплер, детокенизацию или стоп-строки — тормозит не железо, а
     * обвязка, и лечится это в другом месте.
     */
    private fun breakdownLine(b: GGMLEngine.DecodeBreakdown): String? {
        if (b.totalUs <= 0L || b.tokens <= 0L) return null

        val total = b.totalUs.toDouble()
        fun share(part: Long): String = "${fmt0(part * 100.0 / total)}%"

        val perTokenMs = total / 1000.0 / b.tokens
        return "Разбивка: прямой проход ${share(b.decodeUs)}, " +
            "сэмплер ${share(b.sampleUs)}, детокенизация ${share(b.detokUs)}, " +
            "стоп-строки ${share(b.stopUs)} · ${fmt1(perTokenMs)} мс на токен"
    }

    /**
     * Отчёт по одной генерации.
     *
     * Два секундомера здесь не дублируют друг друга. Движок меряет себя
     * изнутри и НЕ видит задержек, которые добавляет наша же обёртка
     * LlmEngine.generateFlow при утомлении. Поэтому расхождение между
     * "Движок" и "Секундомер" — это ровно та часть, которую тратим мы сами.
     */
    private fun metricsReport(
        metrics: DecodingMetrics?,
        breakdown: GGMLEngine.DecodeBreakdown?,
        wallMs: Long,
        firstTokenAtMs: Long?,
        worstZone: SafetyZone,
        tokenLimit: Int,
        promptShape: String
    ): String {
        val lines = mutableListOf<String>()

        // Первой строкой: из ЧЕГО состоял запрос. Стоит перед секундомером
        // потому, что все остальные числа читаются только вместе с ней —
        // "до 1-го токена 19 с" ничего не значит, пока неизвестно, что
        // обсчитывалось.
        lines += promptShape

        val ttft = firstTokenAtMs?.let { fmtSec(it.toDouble()) } ?: "—"
        lines += "Секундомер: всего ${fmtSec(wallMs.toDouble())} с, до 1-го токена $ttft с"

        if (metrics == null) {
            lines += "Движок метрик не прислал"
        } else {
            lines += "Движок: ${fmt1(metrics.tokensPerSecond.toDouble())} ток/с, " +
                "до 1-го токена ${fmtSec(metrics.timeToFirstTokenMs.toDouble())} с"
            lines += "Токенов: запрос ${metrics.tokensEvaluated}, " +
                "ответ ${metrics.tokensPredicted}"
            lines += "Память: пик ${fmt0(metrics.peakMemoryMB.toDouble())} МБ " +
                "(${fmt0(metrics.memoryUsagePercent.toDouble())}%)"

            // Скорость по нашему секундомеру считается ПОСЛЕ первого токена:
            // иначе в неё попадёт обсчёт запроса, и число будет несопоставимо
            // с тем, что показал движок.
            val decodeMs = wallMs - (firstTokenAtMs ?: 0L)
            if (decodeMs > 0L && metrics.tokensPredicted > 0) {
                val wallRate = metrics.tokensPredicted * 1000.0 / decodeMs
                lines += "Секундомер без обсчёта запроса: ${fmt1(wallRate)} ток/с"
            }
        }

        breakdown?.let { breakdownLine(it) }?.let { lines += it }

        // Ответ, упёршийся в потолок, обрывается на полуслове и внешне
        // неотличим от законченного — человек читает огрызок как ответ. Здесь
        // это называется прямо. Сравнение только на равенство: перебрать
        // потолок движок не может, а меньшее значение означает, что модель
        // закончила сама.
        if (metrics != null && tokenLimit > 0 && metrics.tokensPredicted >= tokenLimit) {
            lines += "ВНИМАНИЕ: ответ ОБРЕЗАН на потолке $tokenLimit токенов — " +
                "последняя фраза оборвана, и продолжения не будет. Это наш " +
                "предел длины, а не конец мысли модели."
        }

        lines += "Худшая зона за прогон: ${zoneLabel(worstZone)}"
        if (worstZone == SafetyZone.FATIGUE || worstZone == SafetyZone.CRITICAL) {
            lines += "ВНИМАНИЕ: в этой зоне тормозим МЫ САМИ — по 100 мс на каждый " +
                "токен, это потолок 10 ток/с независимо от модели."
        }

        return lines.joinToString("\n")
    }

    /**
     * Текст красной полосы. Пишется для человека, который не читает код: что
     * встало, почему встало и что сделать дальше — в трёх строках.
     */
    private fun stopReasonText(cause: StopCause?): String = when (cause) {
        is StopCause.ByUser -> cause.note
        // Эта ветка сегодня недостижима: ActionGate проверяет флаг, но сам его
        // нигде не взводит (и не должен — обычный отказ гейта не повод вешать
        // глобальный незакрывающийся стоп). Ветка написана заранее, потому что
        // when по sealed-типу всё равно требует её, а дописывать текст в спешке,
        // когда автоматический взвод появится, — худший момент для этого.
        is StopCause.ByGate ->
            "Система сама заблокировала действие.\n${cause.verdict.reason}"
        // Стоп без причины — это уже баг: причина ставится в том же вызове, что
        // и флаг. Врать "всё в порядке" тут нельзя, поэтому говорим прямо.
        null -> "Причина не записана — это ошибка в самой программе, сообщите о ней."
    }

    private fun renderStopState(active: Boolean) {
        if (!active) {
            binding.textStopBanner.visibility = View.GONE
            return
        }
        binding.textStopBanner.visibility = View.VISIBLE
        binding.textStopBanner.text =
            "АВАРИЙНЫЙ СТОП\n\n" +
                stopReasonText(EmergencyStop.lastCause()) +
                "\n\nДействия агента заблокированы. Нажмите на эту полосу, чтобы снять стоп."
    }

    /**
     * Снятие стопа — только через диалог, где причина показана ещё раз.
     * Смысл не в лишнем касании, а в том, что флаг не самосбрасывается: человек
     * должен увидеть, ПОЧЕМУ всё встало, прежде чем разрешить продолжить.
     */
    private fun showClearStopDialog() {
        AlertDialog.Builder(this)
            .setTitle("Снять аварийный стоп?")
            .setMessage(
                stopReasonText(EmergencyStop.lastCause()) +
                    "\n\nПосле снятия агент снова сможет выполнять действия."
            )
            .setPositiveButton("Снять стоп") { _, _ -> EmergencyStop.clear() }
            .setNegativeButton("Оставить стоп", null)
            .show()
    }

    private fun scanModelFolder(folderUri: Uri): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(this, folderUri)
            ?: throw IllegalStateException("папка недоступна")
        return tree.listFiles().filter { it.isFile && it.name?.endsWith(".gguf", ignoreCase = true) == true }
    }

    private fun loadModelAndUpdateUi(uri: Uri, displayName: String) {
        binding.textModelStatus.text = "Загрузка модели \"$displayName\"..."
        binding.buttonLoadModel.isEnabled = false
        lifecycleScope.launch {
            val ok = llmEngine.loadModelFromUri(uri)
            binding.buttonLoadModel.isEnabled = true
            if (ok) {
                prefs.edit().putString(KEY_LAST_MODEL_URI, uri.toString()).apply()
                binding.textModelStatus.text = "Модель загружена: $displayName"
                binding.buttonGenerate.isEnabled = true
                // Момент выбран не случайно: строка про ctx/threads/batch
                // пишется библиотекой ровно при загрузке и за прогон не
                // меняется. Читать её здесь — значит не заводить ради неё
                // отдельный жест и не отнимать долгое нажатие у другой кнопки.
                engineParamsLine = extractEngineParams(llmEngine.getDebugLog()) +
                    "\n" + threadModeLine()
                // Кэш обсчитанной системной стены. Читается сразу после загрузки
                // намеренно: на ВТОРОМ холодном запуске файл кэша уже лежит на
                // диске, и его размер виден ещё до первого вопроса. То есть
                // "кэш подхватился" видно раньше, чем это подтвердит секундомер.
                promptCacheLine = llmEngine.getPromptCacheReport()
                // Тот же момент и по той же причине: путь к точке считается от
                // отпечатка загрузки, до неё его просто нет. Заодно это первое,
                // что человек увидит после перезапуска, — успела ли вчерашняя
                // запись при уходе в фон.
                checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
                renderMetricsPanel()
                // Только теперь: до загрузки модели отпечатка ещё нет, а
                // без него сохранённой ленте не с чем сверяться.
                maybeOfferRestore()
            } else {
                binding.textModelStatus.text = "Ошибка загрузки модели \"$displayName\""
            }
        }
    }

    /**
     * Предлагает поднять сохранённый разговор — если его есть чем поднять.
     *
     * Порядок важен: сперва хранилище проверяет отпечаток загрузки, целость
     * нумерации и сохранность записей, и только потом спрашивают человека.
     * Наоборот было бы хуже — получить согласие и отказать после него.
     *
     * Ничего не делает при непустой ленте: подъём поверх живого разговора
     * сбил бы нумерацию ходов.
     */
    private fun maybeOfferRestore() {
        if (!journal.isEmpty) return
        lifecycleScope.launch {
            when (val result = journalStore.load(llmEngine.loadFingerprint)) {
                is JournalStore.LoadResult.Empty -> Unit
                is JournalStore.LoadResult.Refused -> {
                    journalRestoreLine = "Разговор: ${result.reason}"
                    renderMetricsPanel()
                }
                is JournalStore.LoadResult.Restored ->
                    showRestoreDialog(result.turns, result.promptTokens)
            }
        }
    }

    /**
     * Выбор человека: продолжить сохранённый разговор или стереть его.
     *
     * ЦЕНА ПОДЪЁМА НАЗВАНА В САМОМ ДИАЛОГЕ. Текст ленты с диска поднимается,
     * а обсчитанное движком начало — нет: это контрольные точки, они не
     * написаны. Значит первый ход после подъёма оплачивает пересчёт всего
     * разговора. Молча отнять у человека минуту нельзя, поэтому и
     * спрашиваем.
     *
     * ОТМЕНИТЬ НЕЛЬЗЯ, и это не строгость. Оставь человек выбор несделанным
     * — лента в памяти пуста, следующий ход ляжет под нулевым номером
     * поверх сохранённого, и на диске окажется смесь двух разговоров.
     * Поэтому исхода ровно два, оба необратимые, и оба названы.
     */
    private fun showRestoreDialog(
        saved: List<ConversationJournal.Turn>,
        promptTokens: Int,
    ) {
        // Спрашиваем ДО вопроса человеку, а поднимаем — после согласия.
        // Проверка дешёвая: файл либо есть, либо нет. Обещать быстрый первый
        // ответ и не суметь хуже, чем честно предупредить о пересчёте.
        val costText = if (llmEngine.hasStateCheckpoint) {
            "Рядом лежит сохранённое состояние движка. Если оно подойдёт к этому " +
                "разговору, первый ответ придёт как обычно; если не подойдёт, движок " +
                "пересчитает разговор целиком, и первый ответ будет заметно дольше. " +
                "Что вышло — увидите строкой «Точка:» в шторке «Подробно»."
        } else {
            "Движку придётся пересчитать его целиком: первый ответ придёт заметно " +
                "дольше обычного, при длинном разговоре это минуты. Дальше скорость " +
                "обычная."
        }
        AlertDialog.Builder(this)
            .setTitle("Сохранённый разговор")
            .setMessage(
                "На диске лежит разговор из ${saved.size} ходов.\n\n" +
                    "Если продолжить, $costText\n\n" +
                    "Начать заново — значит закрыть этот разговор: его ${saved.size} ходов " +
                    "уйдут в архив на устройстве, но ни на экран, ни к агенту не вернутся."
            )
            .setPositiveButton("Продолжить разговор") { _, _ ->
                if (journal.restore(saved)) {
                    // Через существующий вход, а не новый: величина та же
                    // самая, и второе место, где она задаётся, разошлось бы
                    // с первым молча. Ноль вход отбрасывает сам — значит
                    // "не измерено" остаётся "не измерено", а не становится
                    // измеренным нулём.
                    journal.notePromptTokens(promptTokens)
                    journalRestoreLine = "Разговор поднят с диска: ходов ${saved.size}"
                    binding.textResults.text = renderJournal()
                    binding.scrollResults.post {
                        binding.scrollResults.fullScroll(View.FOCUS_DOWN)
                    }
                    renderTurnNavVisibility()
                    // ТОЧКА ПОДНИМАЕТСЯ ТОЛЬКО ЗДЕСЬ — следом за успешно
                    // поднятой лентой и никогда сама по себе. Поднять её к
                    // пустой ленте значило бы, что следующий короткий вопрос
                    // окажется целиком внутри поднятого состояния: движку
                    // нечего будет обсчитывать, и он вернёт ноль токенов.
                    //
                    // Отказ подъёма ничего не ломает: не поднялось — значит
                    // работаем как раньше, с пересчётом. Поэтому исход не
                    // проверяется ветвлением, а просто показывается.
                    lifecycleScope.launch {
                        llmEngine.restoreStateCheckpoint()
                        checkpointActionLine = llmEngine.getStateCheckpointReport()
                        checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
                        renderMetricsPanel()
                    }
                } else {
                    journalRestoreLine = "Разговор с диска не поднят: лента уже не пуста"
                }
                renderMetricsPanel()
            }
            .setNegativeButton("Начать заново") { _, _ ->
                // Точка уходит вместе с лентой. Останься она — при следующем
                // запуске она описывала бы разговор, которого больше нет, то
                // есть обгоняла бы пустую ленту.
                //
                // Лента при этом не стирается, а уходит в архив: человек
                // отказался ПРОДОЛЖАТЬ разговор, а не велел его уничтожить.
                // Смешивать эти два решения нельзя — второго он не принимал.
                journalRestoreLine = null
                renderMetricsPanel()
                lifecycleScope.launch {
                    journalRestoreLine = when (val result = journalStore.archive()) {
                        is JournalStore.ArchiveResult.Archived ->
                            "Разговор закрыт: ${result.count} ходов ушли в архив"
                        // Подъём только что прочитал с диска непустую ленту,
                        // значит пустота здесь означает, что она исчезла между
                        // двумя обращениями. Молчать об этом нельзя.
                        is JournalStore.ArchiveResult.Empty ->
                            "Разговор: закрывать было нечего, на диске уже пусто"
                        is JournalStore.ArchiveResult.Refused -> result.reason
                    }
                    llmEngine.clearStateCheckpoint()
                    checkpointActionLine = llmEngine.getStateCheckpointReport()
                    checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
                    // Перерисовку делает он же, последним действием.
                    refreshJournalDiskLine()
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Спрашивает и убирает последний ход из ленты.
     *
     * ПОДТВЕРЖДЕНИЕ ОБЯЗАТЕЛЬНО. Механизм терминальный: ниже нет никого,
     * кто отсеял бы лишнее, а отменить отрезание нечем. Поэтому диалог
     * показывает начало ответа, который уйдёт, — человек должен видеть,
     * тот ли это ход, а не помнить.
     *
     * ПОРЯДОК: СНАЧАЛА ДИСК, ПОТОМ ПАМЯТЬ. При отказе диска лента в памяти
     * остаётся нетронутой, и оба хранилища по-прежнему говорят одно.
     * Обратный порядок при том же отказе оставил бы на диске ход, которого
     * в памяти уже нет, и следующий запуск поднял бы отрезанное обратно.
     *
     * ВО ВРЕМЯ ГЕНЕРАЦИИ НЕДОСТУПНО. В поле в этот момент лежит текст,
     * которого в ленте ещё нет, а закрытие хода произойдёт после. Отрезать
     * в этот момент значит отрезать не тот ход.
     */
    private fun showDropTurnDialog() {
        if (!binding.buttonGenerate.isEnabled) return
        val last = journal.history().lastOrNull() ?: return
        val index = journal.turnCount - 1
        val preview = last.agentContent.take(DROP_PREVIEW_CHARS).replace("\n", " ")
        val tail = if (last.agentContent.length > DROP_PREVIEW_CHARS) "…" else ""

        AlertDialog.Builder(this)
            .setTitle("Убрать последний ход?")
            .setMessage(
                "Уйдёт ход ${index + 1} — вопрос и ответ на него:\n\n" +
                    "«$preview$tail»\n\n" +
                    "Ход стирается и с экрана, и с диска, вернуть его нечем. " +
                    "Записи памяти, сохранённые на этом ходе, остаются: лента и " +
                    "память — разные слои."
            )
            .setPositiveButton("Убрать") { _, _ ->
                lifecycleScope.launch {
                    when (val result = journalStore.dropFrom(index)) {
                        is JournalStore.DropResult.Refused -> {
                            journalRestoreLine = "Разговор: ${result.reason}"
                        }
                        else -> {
                            // Пустой исход (на диске хода уже нет) отрезание в
                            // памяти НЕ отменяет: расхождение двух хранилищ
                            // чинится тем, что лента становится короче, а не
                            // тем, что лишний ход остаётся на экране.
                            journal.dropLastTurn()
                            navTurnIndex = -1
                            journalRestoreLine =
                                if (result is JournalStore.DropResult.Empty) {
                                    "Разговор: ход убран с экрана, на диске его уже не было"
                                } else {
                                    "Разговор: ход ${index + 1} убран"
                                }
                            binding.textResults.text = renderJournal()
                            renderTurnNavVisibility()
                            // Лента стала короче — значит точка теперь знает
                            // ход, которого в ленте нет, то есть обгоняет её.
                            // Следующий запуск подал бы движку запрос, целиком
                            // лежащий внутри поднятого состояния, и получил бы
                            // ноль токенов на пустом месте. Стереть дешевле:
                            // цена — один пересчёт.
                            llmEngine.clearStateCheckpoint()
                            checkpointActionLine = llmEngine.getStateCheckpointReport()
                            checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
                        }
                    }
                    // И при отказе тоже: отказ говорит, что ход остался,
                    // а не что диск не изменился — проверить дешевле, чем
                    // предполагать.
                    refreshJournalDiskLine()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showModelChooser(models: List<DocumentFile>) {
        val names = models.map { it.name ?: "(без имени)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите модель")
            .setItems(names) { _, which ->
                val chosen = models[which]
                loadModelAndUpdateUi(chosen.uri, chosen.name ?: "(без имени)")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun tryAutoLoadOnStartup() {
        val folderUriString = prefs.getString(KEY_MODEL_FOLDER_URI, null)
        if (folderUriString == null) {
            binding.textModelStatus.text = "Модель не загружена"
            return
        }
        val folderUri = Uri.parse(folderUriString)

        val models = try {
            scanModelFolder(folderUri)
        } catch (e: SecurityException) {
            binding.textModelStatus.text =
                "Не удалось получить доступ к сохранённой папке (права утеряны) — выберите папку заново"
            return
        } catch (e: Exception) {
            binding.textModelStatus.text =
                "Не удалось прочитать сохранённую папку (${e.javaClass.simpleName}) — выберите папку заново"
            return
        }

        if (models.isEmpty()) {
            binding.textModelStatus.text =
                "В сохранённой папке не найдено файлов моделей (.gguf) — выберите другую папку или добавьте файл"
            return
        }

        val lastUriString = prefs.getString(KEY_LAST_MODEL_URI, null)
        val remembered = models.firstOrNull { it.uri.toString() == lastUriString }
        when {
            remembered != null -> loadModelAndUpdateUi(remembered.uri, remembered.name ?: "(без имени)")
            models.size == 1 -> loadModelAndUpdateUi(models[0].uri, models[0].name ?: "(без имени)")
            else -> {
                binding.textModelStatus.text = "Найдено несколько моделей — выберите вручную"
                showModelChooser(models)
            }
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(KEY_MODEL_FOLDER_URI, uri.toString()).apply()

            val models = try {
                scanModelFolder(uri)
            } catch (e: Exception) {
                binding.textModelStatus.text = "Не удалось прочитать выбранную папку (${e.javaClass.simpleName})"
                return@registerForActivityResult
            }

            when {
                models.isEmpty() -> binding.textModelStatus.text =
                    "В выбранной папке не найдено файлов моделей (.gguf)"
                models.size == 1 -> loadModelAndUpdateUi(models[0].uri, models[0].name ?: "(без имени)")
                else -> {
                    binding.textModelStatus.text = "Найдено несколько моделей — выберите"
                    showModelChooser(models)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mediator = TrustedMediator(applicationContext)
        watchdog = DeviceSafetyWatchdog(applicationContext, lifecycleScope)
        llmEngine = LlmEngine(applicationContext, watchdog)
        termuxCompiler = TermuxKotlinCompiler(applicationContext)
        pendingQuerySource = SimplePendingQuerySource()
        codingTask = KotlinCodingTask(
            termuxCompiler = termuxCompiler,
            llmEngine = llmEngine,
            mediator = mediator,
            watchdog = watchdog,
            pendingQuerySource = pendingQuerySource,
            onAnswer = { answer ->
                binding.textAnswerLabel.visibility = View.VISIBLE
                binding.textAnswer.visibility = View.VISIBLE
                binding.textAnswer.text = answer
            }
        )

        setToteRunningState(false)

        // ---- Шторка с диагностикой (2026-08-27) ----
        //
        // Свёрнута по умолчанию при самом первом запуске, дальше — как оставил
        // человек. Место, освобождённое десятком строк метрик, уходит области
        // ответа: она и есть то, ради чего экран существует.
        setDetailsExpanded(prefs.getBoolean(KEY_DETAILS_EXPANDED, false))
        binding.buttonDetails.setOnClickListener {
            setDetailsExpanded(binding.textMetrics.visibility != View.VISIBLE)
        }

        // ---- Аварийный стоп (2026-08-24) ----
        //
        // Кнопка теперь действительно работает, поэтому прежнее isEnabled = false
        // убрано. Подтверждения перед остановкой НЕТ намеренно: диалог "вы
        // уверены?" в аварийной ситуации стоит секунды и лишнее касание. Защита
        // от случайного нажатия здесь другая, физическая — кнопка стоит в дальнем
        // верхнем правом углу, самой труднодостижимой точке для большого пальца
        // левой руки. Осознанно дотянуться можно, задеть — почти нет.
        binding.buttonStop.setOnClickListener {
            // Порядок важен: сначала запрет, потом обрыв. Если оборвать корутину
            // первой, между обрывом и взводом флага остаётся окно, в котором
            // что-то ещё успело бы пройти через гейт.
            EmergencyStop.triggerManual("Остановлено вручную кнопкой на экране.")
            toteJob?.cancel()
            Toast.makeText(this, "Аварийный стоп взведён", Toast.LENGTH_SHORT).show()
        }

        binding.textStopBanner.setOnClickListener { showClearStopDialog() }

        // Состояние читается из потока, а не выставляется в обработчике кнопки.
        // Разница принципиальная: так полоса появится и в том случае, если стоп
        // взведёт что-то внутри системы, а не палец пользователя.
        lifecycleScope.launch {
            EmergencyStop.active.collect { active -> renderStopState(active) }
        }

        // Строка про железо обновляется из потоков, а не по нажатию. Разница
        // важная: уход в утомление видно в тот момент, когда он произошёл, а
        // не задним числом при следующем отчёте.
        renderMetricsPanel()

        // Лента переживает пересоздание активности (она одна на процесс), а
        // поле результата — нет: оно создаётся заново и приходит пустым.
        // Поэтому разговор надо отрисовать здесь, иначе после поворота
        // человек увидит чистый экран при живой ленте — и решит, что
        // разговор потерян, хотя в модель по-прежнему уходит вся история.
        // Это хуже настоящей потери: расхождение между тем, что видно, и
        // тем, что происходит.
        // Без этого кликабельная часть строки записей не отзовётся на
        // касание вовсе. Поле ленты лежит внутри прокрутки и растёт по
        // содержимому, поэтому собственная прокрутка у него пустая и с
        // внешней не спорит; если всё же начнёт дёргаться при взмахе —
        // виновата эта строка.
        binding.textResults.movementMethod = LinkMovementMethod.getInstance()

        if (!journal.isEmpty) {
            binding.textResults.text = renderJournal()
            binding.scrollResults.post { binding.scrollResults.fullScroll(View.FOCUS_DOWN) }
        }
        renderTurnNavVisibility()

        // Прыжок на реплику, а не на пиксель. Счётчик navTurnIndex живёт
        // между нажатиями, поэтому повторные касания идут по ходам подряд,
        // а не возвращают в одно и то же место.
        binding.buttonTurnPrev.setOnClickListener {
            val from = if (navTurnIndex < 0) turnOffsets.size else navTurnIndex
            scrollToTurn((from - 1).coerceAtLeast(0))
        }
        binding.buttonTurnNext.setOnClickListener {
            val from = if (navTurnIndex < 0) -1 else navTurnIndex
            scrollToTurn((from + 1).coerceAtMost(turnOffsets.size - 1))
        }
        binding.buttonDropTurn.setOnClickListener { showDropTurnDialog() }
        lifecycleScope.launch {
            combine(watchdog.zone, watchdog.power) { _, _ -> Unit }
                .collect { renderMetricsPanel() }
        }

        // Лента одна на процесс, активность пересоздаётся — поэтому
        // оповещения переустанавливаются при каждом создании, и пишет на
        // диск всегда живая активность, а не мёртвая.
        //
        // Запись уходит в отдельную корутину: функции хранилища
        // блокирующие, а держать на них главный поток нельзя. Плата
        // названа прямо: если приложение убьют ровно между закрытием хода
        // и записью, ход останется только в памяти. Следующий запуск
        // увидит дыру в нумерации и откажет в подъёме — то есть промах
        // будет назван, а не проглочен.
        journal.onTurnAppended = { index, turn ->
            val fingerprint = if (::llmEngine.isInitialized) llmEngine.loadFingerprint else null
            // Оба числа снимаются ЗДЕСЬ, а не внутри корутины: пока запись
            // идёт на диск, мог бы пройти следующий ход, и в строку легло
            // бы чужое значение.
            val tokens = journal.lastPromptTokens
            lifecycleScope.launch {
                if (!journalStore.append(index, turn, tokens, fingerprint)) {
                    journalRestoreLine = "Разговор: ход ${index + 1} на диск не записался"
                    renderMetricsPanel()
                }
                // Перечитывается и после неудачи тоже: строка описывает
                // диск, а не исход попытки. Показать после сбоя прежнее
                // число значило бы утверждать, чего мы не проверяли.
                refreshJournalDiskLine()
            }
        }
        // Закрытая лента не должна оставаться в активной таблице: иначе
        // следующий запуск предложит поднять разговор, который человек
        // только что закрыл. Но и стирать её нельзя — ходы уходят в архив,
        // и это единственное место на устройстве, где ответы агента
        // остаются после закрытия.
        //
        // ОЧЕРЁДНОСТЬ БЕЗОПАСНА, ХОТЯ ВЫГЛЯДИТ ОПАСНО. Оповещение приходит
        // ПОСЛЕ того, как лента в памяти очищена, — но архив читает ходы с
        // диска, а там они ещё целы. Начни он читать из памяти, закрытие
        // сохраняло бы пустоту.
        journal.onCleared = {
            journalRestoreLine = null
            renderMetricsPanel()
            lifecycleScope.launch {
                journalRestoreLine = when (val result = journalStore.archive()) {
                    is JournalStore.ArchiveResult.Archived ->
                        "Разговор закрыт: ${result.count} ходов ушли в архив"
                    // Лента в памяти была, а на диске её нет. Это расхождение
                    // двух хранилищ, и молчать о нём нельзя: значит ходы не
                    // записывались, и архив пуст не потому, что нечего было
                    // класть.
                    is JournalStore.ArchiveResult.Empty ->
                        "Разговор закрыт, но на диске его уже не было"
                    is JournalStore.ArchiveResult.Refused -> result.reason
                }
                // Точка уходит вместе с лентой — то же, что делает «Начать
                // заново» в диалоге восстановления, и по той же причине:
                // иначе на диске остаётся снимок разговора, которого больше
                // нет. Вреда от него нет (точка поднимается только вслед за
                // поднятой лентой, а её уже не будет), но строка «Точка на
                // диске» описывала бы пустоту, и десять мегабайт лежали бы
                // мёртвыми. Два пути закрытия должны кончаться одинаково.
                //
                // Проверка на движок нужна: оповещение приходит от ленты,
                // которая живёт дольше экрана, и модель может быть ещё не
                // загружена.
                if (::llmEngine.isInitialized) {
                    llmEngine.clearStateCheckpoint()
                    checkpointActionLine = llmEngine.getStateCheckpointReport()
                    checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
                }
                // Перерисовку делает он же, последним действием.
                refreshJournalDiskLine()
            }
        }

        // Первое чтение диска — здесь, а не после загрузки модели. Путь
        // ленты от отпечатка не зависит (в отличие от пути контрольной
        // точки), поэтому число известно всегда, и строка появится даже
        // когда модель не загрузилась вовсе.
        lifecycleScope.launch { refreshJournalDiskLine() }

        tryAutoLoadOnStartup()

        // Два разовых ремонта данных при старте. Оба идемпотентны, у каждого свой
        // флаг в prefs, и оба сознательно ОДНОРАЗОВЫЕ и видимые на экране.
        //
        // Почему не тихо при каждом запуске: молчаливое самолечение маскировало бы
        // новые баги того же класса — если записи снова начнут портиться,
        // автопочинка подчистит следы, и канарейка ничего не покажет. Инструмент
        // наблюдения и невидимый ремонтник в одной системе не уживаются.
        //
        // Каждый флаг ставится ТОЛЬКО после успешного прогона своего ремонта:
        // упавший ремонт повторится при следующем запуске, а не запишется
        // в выполненные.
        //
        // Оба идут последовательно в ОДНОЙ корутине и печатают общий отчёт.
        // Два параллельных launch писали бы в textResults наперегонки, и один
        // результат молча затирал бы другой.
        lifecycleScope.launch {
            val report = mutableListOf<String>()

            // Item 6 (2026-08-22): возвращает в спектр записи, застрявшие в RED
            // без expiryTime из-за прежнего храповика прогрева.
            if (!prefs.getBoolean(KEY_LAYER_REPAIR_DONE, false)) {
                try {
                    val repaired = mediator.repairStuckLayers()
                    prefs.edit().putBoolean(KEY_LAYER_REPAIR_DONE, true).apply()
                    report += if (repaired > 0) {
                        "Ремонт слоёв: $repaired записей возвращено в спектр.\n" +
                            "Нажмите \"Показать память\" — в снимке \"Без срока вообще\" должно уменьшиться."
                    } else {
                        "Ремонт слоёв: застрявших записей не найдено."
                    }
                } catch (e: Exception) {
                    report +=
                        "Ремонт слоёв не выполнен (${e.javaClass.simpleName}) — будет повторён при следующем запуске"
                }
            }

            // Дыра №4, ремонт данных (2026-08-24): исторические вакцина-строки
            // помечены как сказанное пользователем. Из-за этого модель пересказывала
            // человеку его же словами отчёты, которые писал сам агент.
            if (!prefs.getBoolean(KEY_PROVENANCE_REPAIR_DONE, false)) {
                try {
                    val fixed = mediator.repairToteProvenance()
                    prefs.edit().putBoolean(KEY_PROVENANCE_REPAIR_DONE, true).apply()
                    report += if (fixed > 0) {
                        "Ремонт провенанса: $fixed записей агента больше не выдаются за ваши слова."
                    } else {
                        "Ремонт провенанса: неверных пометок не найдено."
                    }
                } catch (e: Exception) {
                    report +=
                        "Ремонт провенанса не выполнен (${e.javaClass.simpleName}) — будет повторён при следующем запуске"
                }
            }

            if (report.isNotEmpty()) {
                binding.textResults.text = report.joinToString("\n\n")
            }
        }

        // Миграция на новое устройство (2026-08-21): buttonSave двухрежимная —
        // короткое нажатие сохраняет, долгое экспортирует память. Долгое нажатие
        // раньше показывало debug-лог модели — убрано по согласованию
        // (сохранность реальных данных важнее).
        //
        // Подпись и цвет этой кнопки здесь больше НЕ задаются: они живут в
        // разметке. Прежние две строки перекрывали её оттуда и возвращали
        // двухэтажный текст поверх новой вёрстки.

        binding.buttonSave.setOnClickListener {
            val text = binding.editTextInput.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                // Дыра №4: провенанс задаётся явно. Это ручной ввод пользователя —
                // единственное место в проекте, где USER_STATED действительно верен.
                mediator.saveEvent(
                    content = text,
                    source = SourceKind.USER_STATED,
                    confidence = ConfidenceLevel.OBSERVED
                )
                binding.editTextInput.text.clear()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonSave.setOnLongClickListener {
            binding.buttonSave.isEnabled = false
            binding.textResults.text = "Выгружаю базы (чекпоинт + копирование)..."
            lifecycleScope.launch {
                // Отчёт по каждой базе отдельно: одна выгруженная база и все
                // выгруженные базы обязаны выглядеть по-разному, иначе пропуск
                // неотличим от успеха. Текст собирает DatabaseExporter.describe.
                val outcomes = DatabaseExporter.exportAllToDownloads(applicationContext)
                binding.buttonSave.isEnabled = true
                binding.textResults.text = DatabaseExporter.describe(outcomes)
            }
            true
        }

        binding.buttonShow.setOnClickListener {
            binding.buttonShow.isEnabled = false
            lifecycleScope.launch {
                try {
                    val query = binding.editTextInput.text.toString().ifBlank { null }
                    // Item 6, подшаг 1c (2026-08-22): снимок канарейки в шапке.
                    // Только чтение. Прежний вызов totalStickers() убран: канарейка
                    // уже печатает "Всего записей", а два независимых запроса к базе
                    // могли бы показать два разных числа на одном экране.
                    val canaryReport = mediator.memoryCanaryReport()
                    val results = mediator.getContext(query = query, limit = 20)
                    binding.textResults.text = if (results.isEmpty()) {
                        "$canaryReport\n\n(записей для показа нет)"
                    } else {
                        val lines = results.joinToString("\n\n") { sticker ->
                            "• ${sourceLabel(sticker.source)} ${sticker.content}\n  [${sticker.layer}] (обращений: ${sticker.accessCount})"
                        }
                        "$canaryReport\n\n$lines"
                    }
                } finally {
                    binding.buttonShow.isEnabled = true
                }
            }
        }

        binding.buttonShow.setOnLongClickListener {
            binding.textResults.text = codingTask.getDebugLog()
            true
        }

        binding.buttonLoadModel.setOnClickListener {
            val folderUriString = prefs.getString(KEY_MODEL_FOLDER_URI, null)
            if (folderUriString == null) {
                pickFolderLauncher.launch(null)
                return@setOnClickListener
            }
            val folderUri = Uri.parse(folderUriString)
            val models = try {
                scanModelFolder(folderUri)
            } catch (e: Exception) {
                Toast.makeText(this, "Папка недоступна (${e.javaClass.simpleName}) — выберите заново", Toast.LENGTH_LONG).show()
                pickFolderLauncher.launch(null)
                return@setOnClickListener
            }
            if (models.isEmpty()) {
                Toast.makeText(this, "В папке нет файлов .gguf", Toast.LENGTH_SHORT).show()
            } else {
                showModelChooser(models)
            }
        }

        binding.buttonLoadModel.setOnLongClickListener {
            pickFolderLauncher.launch(null)
            true
        }

        // Короткое нажатие — обычный текстовый разговор. Аварийный стоп его НЕ
        // глушит намеренно: этот путь не проходит через ActionGate и вообще
        // ничего не делает с устройством, зато остаётся единственным способом
        // что-то спросить у системы, пока она стоит.
        binding.buttonGenerate.setOnClickListener {
            // Хвост 20: числа прошлого прогона стираются ДО всех проверок.
            // Иначе несостоявшийся запуск оставляет их на экране, и они
            // читаются как относящиеся к нынешнему.
            clearRunMetrics()

            val userText = binding.editTextInput.text.toString()
            if (userText.isBlank()) {
                val hint = if (isToteRunning) "Введите вопрос" else "Введите запрос для генерации"
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isToteRunning) {
                pendingQuerySource.submit(userText)
                binding.editTextInput.text.clear()
                Toast.makeText(this, "Вопрос отправлен агенту", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.buttonGenerate.isEnabled = false
            showProgress("Идёт генерация...")

            lifecycleScope.launch {
                val stickers = mediator.getContext(query = userText, limit = 5)
                val allRecords = stickers.map { sticker ->
                    "${provenancePhrase(sticker.source)}: «${sticker.content}»."
                }
                // Записи, уже лежащие в ленте, второй раз не кладём: отбор
                // тянет до пяти штук на КАЖДЫЙ вопрос, а лента только растёт.
                // Считано 28.08: без этого ход дорожает со 125 токенов до 325
                // и лента кончается к двадцатому ходу вместо шестидесятого,
                // причём почти всё добавленное — повторы одного и того же.
                val newRecords = journal.unseenRecords(allRecords)
                val userContent = journal.composeUserContent(newRecords, userText)

                // Проверка края ДО отправки. Движок при переполнении молча
                // выбрасывает половину ленты посреди генерации, поэтому
                // упереться незаметно нельзя — останавливаемся явно.
                if (journal.roomLeft(CONTEXT_SIZE, ANSWER_TOKEN_LIMIT) <= 0) {
                    binding.buttonGenerate.isEnabled = true
                    showProgress(null)
                    showJournalFullDialog()
                    return@launch
                }

                val messages = journal.messagesFor(userContent)

                // Экран показывает всю ленту плюс начатый ход. Поле НЕ
                // очищается: разговор копится, а не заменяется. Токены ниже
                // дописываются в конец, поэтому последней строкой стоит
                // пустой "Агент: ".
                binding.textResults.text = renderJournal(pendingQuestion = userText)

                // Состав запроса, показанный человеку. Причина появления
                // (26.08.2026): счётчик "Токенов: запрос" давал числа, которые не
                // сходились ни с одной версией происходящего — 1075 утром и 485-495
                // вечером на тех же вопросах, — и три догадки подряд оказались
                // неверны. Догадки строились потому, что СОСТАВ запроса нигде не
                // виден: подтянулась память или нет, сколько она весит, каков
                // вопрос. Здесь это названо прямо, в знаках, которые можно
                // сверить глазами с сохранёнными записями.
                //
                // Знаки, а не токены, намеренно: токенов отсюда не видно, они
                // считаются внутри движка, а выдумывать их пересчёт значило бы
                // добавить к непонятному счётчику второй такой же. Знаки —
                // то, что мы знаем точно.
                //
                // Системной стены здесь нет и быть не может: она задаётся
                // отдельно при загрузке модели (LlmEngine.configureAfterLoad) и
                // в этот текст не входит. Если счётчик токенов окажется больше
                // того, что здесь показано, разница — стена, лента и то, что
                // движок поднял из кэша.
                //
                // Числа до 27.08.2026 с новыми напрямую не сравнивать: там
                // "память" включала старую рамку целиком. И с 28.08 рамки
                // "Вопрос:" здесь больше нет: на вопрос, чья это речь, отвечает
                // роль сообщения, а не текст.
                // Пометка об ответе без опоры проверяется по САМОМУ тексту,
                // уходящему в движок, а не по условию, из которого журнал её
                // ставит. Строка отвечает на вопрос «что реально ушло»:
                // повторение здесь того же условия сделало бы её согласной с
                // журналом даже тогда, когда журнал ошибся.
                val noteTail =
                    if (userContent.startsWith(ConversationJournal.ANSWER_WITHOUT_SUPPORT)) {
                        " · пометка: прошлый ответ без опоры"
                    } else {
                        ""
                    }
                val promptShape = if (newRecords.isEmpty()) {
                    val skipped = allRecords.size
                    val tail = if (skipped > 0) " ($skipped уже в ленте)" else ""
                    "Запрос: новых записей нет$tail · вопрос ${userText.length} зн.$noteTail"
                } else {
                    "Запрос: новых записей ${newRecords.size} из ${allRecords.size} на " +
                        "${userContent.length - userText.length} зн. · " +
                        "вопрос ${userText.length} зн.$noteTail"
                }

                val startMs = System.currentTimeMillis()
                var firstTokenAtMs: Long? = null
                var engineMetrics: DecodingMetrics? = null
                // Мгновенная зона в конце прогона соврала бы: устройство может
                // уйти в утомление на середине генерации и остыть к концу.
                // Запоминаем худшее из виденного.
                var worstZone: SafetyZone = watchdog.zone.value
                // Хвост 19: считаем выданные токены сами, а не спрашиваем
                // движок. Метрики он присылает не всегда, а факт "на экране не
                // появилось ни знака" надо назвать при любом исходе.
                var tokensSeen = 0
                // Ответ копится ОТДЕЛЬНО от того, что видно на экране.
                // Правило дословности: в ленту должно лечь ровно то, что
                // выдал движок. Собирать текст обратно с экрана нельзя —
                // там он смешан с прошлыми ходами и подписями, и любое
                // расхождение на один знак оборвало бы совпадение с
                // обсчитанным началом. Признаком была бы только выросшая
                // строка "до 1-го токена", то есть поломка, о которой ничто
                // не сообщит.
                val answerText = StringBuilder()

                try {
                    llmEngine.generateConversationFlow(messages, ANSWER_TOKEN_LIMIT).collect { event ->
                        val nowZone = watchdog.zone.value
                        if (nowZone.ordinal > worstZone.ordinal) worstZone = nowZone

                        when (event) {
                            is GenerationEvent.Token -> {
                                if (firstTokenAtMs == null) {
                                    firstTokenAtMs = System.currentTimeMillis() - startMs
                                    showProgress(null)
                                }
                                tokensSeen++
                                answerText.append(event.text)
                                binding.textResults.append(event.text)
                                autoScrollIfAtBottom()
                            }
                            is GenerationEvent.Progress -> {
                                // Это обсчёт запроса (prefill) — ровно та часть,
                                // которую секундомером от нажатия до ответа
                                // невозможно было отделить от генерации.
                                showProgress(
                                    "Обсчёт запроса: ${(event.progress * 100).toInt()}%"
                                )
                            }
                            is GenerationEvent.Metrics -> {
                                engineMetrics = event.metrics
                            }
                            is GenerationEvent.Error -> {
                                binding.textResults.append("\n\n[Ошибка: ${event.message}]")
                            }
                            else -> {
                                // Done и VLM-события. Done намеренно НЕ
                                // обрабатывается здесь: библиотека не обещает,
                                // что метрики придут до него, поэтому отчёт
                                // собирается после выхода из collect.
                            }
                        }
                    }
                } finally {
                    // finally, а не ветка Done: раньше кнопка включалась только
                    // если поток закончился ожидаемым событием, и любой другой
                    // выход оставлял её навсегда серой.
                    binding.buttonGenerate.isEnabled = true
                    showProgress(null)

                    // Хвост 19 (27.08.2026): движок отдаёт ноль токенов при
                    // ТОЧНОМ, до последнего знака, повторе предыдущего запроса.
                    // На экране при этом не появлялось ничего: пустая область
                    // ответа и секундомер 0.0 с. Дважды принято за "кнопка не
                    // работает", оба раза стоило перезапуска и потерянного
                    // замера.
                    //
                    // Сказано ПОСЛЕ прогона, по факту, а не предсказано до
                    // него. Причину пустого ответа мы знаем по наблюдениям, а
                    // не из кода библиотеки; предупреждать заранее значило бы
                    // выдавать свою догадку за знание — ровно та ошибка, на
                    // которой мы уже обожглись с кэшем.
                    //
                    // ПРИЧИН У НУЛЯ НЕ ОДНА, И РАНЬШЕ НАЗЫВАЛАСЬ ТОЛЬКО ОДНА.
                    // Вторая наблюдалась: запрос, не поместившийся в окно, даёт
                    // тот же ноль и то же пустое место на экране. Разница
                    // существенная — повтор лечится дописанным знаком, а
                    // непоместившийся запрос от этого лечения не меняется
                    // вовсе, и человек по прежней подсказке лечил не ту
                    // болезнь.
                    //
                    // Различитель точный и дармовой: реплика предыдущего хода
                    // лежит в ленте ДОСЛОВНО (см. `JournalTurn`), поэтому
                    // повтор проверяется сравнением строк. Ход с нулём в ленту
                    // не пошёл, лента между попытками не выросла, значит
                    // совпадение последней реплики означает совпадение всего
                    // запроса.
                    //
                    // Это НЕ предсказание: сравнение делается после отказа,
                    // чтобы назвать причину, а не до отправки, чтобы её
                    // предугадать. Прежнее решение не отменяется.
                    if (tokensSeen == 0) {
                        // Ход не состоялся, в ленту он не идёт: ноль токенов
                        // это отказ движка, а не реплика разговора. Записи,
                        // подобранные под этот вопрос, тоже НЕ помечаются
                        // уложенными — они никуда не ушли.
                        val sameAsPrevious =
                            journal.history().lastOrNull()?.userContent == userContent
                        // Числа только измеренные. Занятость — отчёт движка,
                        // длина вопроса — сосчитанные знаки. Вес вопроса в
                        // токенах не называется: измерить его нечем, движок
                        // отдаёт это число лишь по итогам прогона, а прогона
                        // не было. Названная оценка попала бы на экран
                        // наравне с замером.
                        val explanation = if (sameAsPrevious) {
                            // Следствие названо прямо, потому что оно неприятное:
                            // лента не выросла, значит повтор того же вопроса даст
                            // тот же запрос до последнего знака и тот же ноль.
                            // Дописать знак за человека нельзя — тогда в движок
                            // уйдёт не то, что он набрал.
                            "Запрос совпал с предыдущим до последнего знака — сверено " +
                                "дословно. Допишите любой знак в САМЫЙ КОНЕЦ текста и " +
                                "нажмите снова: этого хватает, и уже обсчитанное начало " +
                                "запроса при этом не теряется."
                        } else {
                            "Это не повтор: запрос сверен с предыдущим дословно и " +
                                "отличается. Значит совет «допишите знак» здесь не " +
                                "поможет.\n\n" +
                                "Занято ${journal.lastPromptTokens} из $CONTEXT_SIZE токенов, " +
                                "вопрос — ${userText.length} зн. Сколько токенов весит сам " +
                                "вопрос, измерить нечем. Известно другое: запрос, не " +
                                "поместившийся в окно, даёт такой же ноль. Проверяется это " +
                                "одним способом — задать вопрос заметно короче."
                        }
                        // Собирается через SpannableStringBuilder, а не
                        // сложением строк: лента теперь несёт кликабельные
                        // участки, и обычное сложение их бы потеряло.
                        binding.textResults.text = SpannableStringBuilder(renderJournal()).apply {
                            if (!journal.isEmpty) append("\n\n")
                            append(
                                "Модель не выдала ни одного знака.\n\n" + explanation +
                                    "\n\nХод в ленту не записан, записи не помечены " +
                                    "уложенными: разговор остался таким же, каким был до " +
                                    "нажатия."
                            )
                        }
                    } else {
                        // ВСЕ найденные отбором, а не только новые. Новизну
                        // журнал считает сам по своему отображению: если
                        // считать её в двух местах, экран однажды разойдётся
                        // с тем, что ушло в модель. Уже лежавшие записи от
                        // этого не задваиваются — их номер хода в журнале
                        // остаётся прежним.
                        // Размер запроса берём у движка, а не считаем сами:
                        // пересчёт знаков в токены завышает на треть, замерено
                        // 28.08 на тексте стены.
                        //
                        // Стоит ДО закрытия хода намеренно: закрытие хода
                        // уводит его на диск вместе с этим числом. Обнови
                        // счётчик после — и в строку ушло бы значение
                        // предыдущего хода, расхождение на один ход, которое
                        // ничем себя не выдаёт.
                        engineMetrics?.let { journal.notePromptTokens(it.tokensEvaluated) }
                        journal.appendTurn(
                            userContent = userContent,
                            agentContent = answerText.toString(),
                            question = userText,
                            records = allRecords,
                        )
                        // Ход закрыт — перерисовываем ленту целиком.
                        //
                        // Во время генерации ход рисовался как начатый
                        // (turn = null), то есть БЕЗ строки записей: пока
                        // ответа нет, отбор не считается уложенным. Без
                        // перерисовки здесь строка появлялась бы только при
                        // следующей отрисовке, то есть на следующем ходе, и
                        // человек читал бы свежий ответ, не видя, была ли под
                        // ним опора, — то есть в тот момент, когда признак
                        // нужнее всего.
                        //
                        // Тем же движением возвращаются кликабельные участки:
                        // в тексте, дописанном по токенам, их нет, и записи
                        // только что закрытого хода иначе не развернуть.
                        binding.textResults.text = renderJournal()
                    }
                    // Ход закрыт: ходов стало больше, кнопки хода могли
                    // впервые понадобиться. Смещения уже пересчитаны — обе
                    // ветки выше перерисовывают ленту.
                    renderTurnNavVisibility()
                    // Читается сразу после завершения: движок хранит разбивку
                    // ПОСЛЕДНЕЙ генерации, и следующий запуск её затрёт.
                    val breakdown = runCatching { llmEngine.getLastDecodeBreakdown() }.getOrNull()
                    // Перечитываем после генерации: запись кэша делает сама
                    // библиотека по ходу обсчёта, и на ПЕРВОМ холодном запуске
                    // строка меняется с "пуст" на размер файла именно здесь.
                    // Если она осталась пустой — кэш не пишется, и это надо
                    // видеть сразу, а не через сутки по неизменившемуся времени.
                    promptCacheLine = llmEngine.getPromptCacheReport()
                    lastMetricsLine = metricsReport(
                        metrics = engineMetrics,
                        breakdown = breakdown,
                        wallMs = System.currentTimeMillis() - startMs,
                        firstTokenAtMs = firstTokenAtMs,
                        worstZone = worstZone,
                        tokenLimit = ANSWER_TOKEN_LIMIT,
                        promptShape = promptShape
                    )
                    renderMetricsPanel()
                }
            }
        }

        // Запуск TOTE-цикла переехал сюда с долгого нажатия на "Генерировать"
        // (2026-08-27). Причина: на той кнопке жест был невидим, а сама кнопка
        // тем временем меняет смысл — во время цикла она становится "Спросить".
        // Два разных дорогих действия на одной кнопке, оба скрытые.
        //
        // Жест остался ДОЛГИМ намеренно, см. пояснение в разметке: цикл это
        // двадцать минут работы и нагрева, и удержание — физическая защита от
        // случайного запуска, которая не стоит ни диалога, ни лишнего касания.
        binding.buttonCycle.setOnLongClickListener {
            // Хвост 20, как и на "Генерировать": числа прошлого прогона
            // стираются до всех проверок, чтобы отказ запуска не оставил их
            // читаться как нынешние.
            clearRunMetrics()

            if (isToteRunning) {
                Toast.makeText(this@MainActivity, "Цикл уже идёт", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            // Запуск цикла под взведённым стопом запрещён. Формально гейт всё
            // равно отказал бы каждой компиляции, но цикл успел бы намолотить
            // итераций впустую и выйти по энергии — с экрана это выглядело бы
            // как поломка, а не как работающий запрет.
            if (EmergencyStop.isActive()) {
                Toast.makeText(
                    this@MainActivity,
                    "Взведён аварийный стоп — снимите его в красной полосе вверху",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnLongClickListener true
            }
            if (!llmEngine.isLoaded) {
                Toast.makeText(this@MainActivity, "Сначала загрузите модель", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            setToteRunningState(true)
            binding.textAnswerLabel.visibility = View.GONE
            binding.textAnswer.visibility = View.GONE
            binding.textResults.text = "Запускаю TOTE-цикл (компиляция + LLM)..."
            toteJob = lifecycleScope.launch {
                try {
                    when (val result = codingTask.run()) {
                        is ToteResult.Success -> {
                            binding.textResults.text =
                                "УСПЕХ за ${result.iterations} итераций:\n\n${result.finalState.code}"
                        }
                        is ToteResult.Evacuated -> {
                            binding.textResults.text =
                                "ЭВАКУАЦИЯ после ${result.iterations} итераций: ${result.reason}\n\n" +
                                    "Последняя ошибка компиляции:\n${result.lastOutcome?.detail ?: "(нет данных)"}\n\n" +
                                    "Последний код:\n${result.lastState.code}"
                        }
                        is ToteResult.HardStopped -> {
                            binding.textResults.text =
                                "ЖЁСТКИЙ СТОП после ${result.iterations} итераций (достигнут лимит)\n\n" +
                                    "Последняя ошибка компиляции:\n${result.lastOutcome?.detail ?: "(нет данных)"}\n\n" +
                                    "Последний код:\n${result.lastState.code}"
                        }
                    }
                } catch (e: CancellationException) {
                    // Обрыв — это нормальный исход, а не сбой. Но экран не должен
                    // остаться с текстом "Запускаю цикл...", как будто он висит.
                    binding.textResults.text =
                        "Цикл прерван аварийным стопом.\n\n" +
                            "Начатая работа отменена. Причина — в красной полосе вверху экрана."
                    throw e
                } finally {
                    setToteRunningState(false)
                    toteJob = null
                }
            }
            true
        }
    }

    /**
     * Запись контрольной точки при уходе приложения в фон.
     *
     * ПОЧЕМУ ИМЕННО ЗДЕСЬ. Обсчитанное движком начало разговора умирает вместе
     * с процессом, а процесс Android убивает первым делом — модель занимает
     * два с лишним гигабайта. Уход в фон и есть последний момент, когда
     * спасать ещё есть что.
     *
     * ПОЧЕМУ НЕ ЧАЩЕ. Точка растёт вместе с лентой: одиннадцать мегабайт на
     * шестистах токенах, около полутора сотен на полной. Писать её каждый ход
     * значит платить эту цену в каждом ходе ради потери, которая случается раз
     * за сессию. Отставшая точка при этом не врёт: движок сверяет запрос с
     * поднятым состоянием потокенно и досчитывает несовпавший хвост — цена
     * отставания измеряется в пересчитанных ходах, а не в неверных ответах.
     *
     * ЧЕМ ПЛАТИМ, названо прямо. Приложение могут убить раньше, чем запись
     * кончится. Тогда на диске остаётся ПРЕЖНЯЯ точка целой (запись идёт во
     * временный файл, см. [LlmEngine.saveStateCheckpoint]), а строка об удаче
     * не появится. То есть промах уводит в «точка отстала», а не в «точка
     * испорчена».
     *
     * ЭТО ПОКА ЗАМЕР, а не окончательное устройство. Выбрать момент раньше
     * можно будет, только зная, сколько запись занимает, — а узнать это
     * неоткуда, кроме как записав. Число в строке и есть ответ: если оно
     * окажется в секундах, момент придётся двигать.
     *
     * Пустая лента не сохраняется: в ней нет ничего, кроме системной стены, а
     * её движок и так поднимает своим кэшем.
     */
    override fun onStop() {
        super.onStop()
        if (!::llmEngine.isInitialized || journal.isEmpty) return

        // lifecycleScope, а не свой: при уходе в фон активность не
        // уничтожается, и корутина спокойно доживает. При настоящем закрытии
        // она оборвётся — это и есть тот самый промах, названный выше.
        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            llmEngine.saveStateCheckpoint()
            val tookMs = System.currentTimeMillis() - startedAt
            // Секундомер наш, а не движка: движок не знает, сколько заняли
            // переименование файла и ожидание чужой блокировки. Ради этого
            // числа запись сюда и поставлена.
            checkpointActionLine =
                llmEngine.getStateCheckpointReport() + " · ${fmtSec(tookMs.toDouble())} с"
            checkpointDiskLine = llmEngine.getStateCheckpointDiskReport()
            renderMetricsPanel()
        }
    }

    companion object {
        private const val KEY_MODEL_FOLDER_URI = "model_folder_uri"
        private const val KEY_LAST_MODEL_URI = "last_model_uri"
        private const val KEY_LAYER_REPAIR_DONE = "layer_repair_done_2026_08_22"
        private const val KEY_PROVENANCE_REPAIR_DONE = "provenance_repair_done_2026_08_24"
        private const val KEY_DETAILS_EXPANDED = "details_expanded"

        /**
         * Потолок длины ответа для обычного вопроса с экрана.
         *
         * Раньше здесь не стояло ничего и потолок брался из умолчания
         * [LlmEngine.generateFlow]. Названо явно по двум причинам: чтобы смена
         * умолчания в движке не поменяла нам поведение молча, и чтобы отчёт о
         * генерации мог СРАВНИТЬ с этим числом длину ответа и сказать, что
         * ответ обрезан.
         *
         * Наблюдалось живьём 26.08.2026: `Токенов: ответ 512`, текст кончается
         * посреди фразы словом «Стоит», и на экране об этом ни строки.
         */
        private const val ANSWER_TOKEN_LIMIT = 512

        /**
         * С какого числа ходов показывать кнопки хода. Наименьшее, при
         * котором кнопка делает что-то, чего не делает взмах: на двух
         * ходах прыгать некуда, а высоту у разговора они отнимут.
         */
        private const val TURN_NAV_MIN_TURNS = 3

        /**
         * Сколько знаков ответа показывать в вопросе об отрезании. Хватает,
         * чтобы узнать ход, и мало, чтобы диалог не превратился в чтение.
         */
        private const val DROP_PREVIEW_CHARS = 120

        /**
         * Запас в пикселях, внутри которого прокрутка считается стоящей
         * внизу. Нужен потому, что «внизу» на глаз и «внизу» до пикселя
         * — разные вещи: строка дорисовалась, и человек формально уже не
         * внизу, хотя пальцем не двигал. Без запаса автопрокрутка
         * замирала бы посреди ответа.
         */
        private const val AUTOSCROLL_BOTTOM_SLACK = 48
    }
}
