package com.uroboros

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.dark.gguf_lib.models.DecodingMetrics
import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.databinding.ActivityMainBinding
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

    // Ссылка на корутину идущего TOTE-цикла. Нужна ровно для одного:
    // аварийный стоп должен прервать уже начатую работу, а не только запретить
    // будущую. Флаг EmergencyStop закрывает гейт — но цикл, который уже крутится,
    // от закрытого гейта не останавливается: он продолжает генерировать текст и
    // получать отказы, пока не кончится энергия. Это минуты работы после нажатия
    // кнопки с надписью "Стоп". Поэтому слоя два: флаг (запрет) + cancel (обрыв).
    private var toteJob: Job? = null

    // Панель метрик собирается из трёх независимых кусков, у каждого своя
    // частота обновления: строка про железо меняется сама по себе из потоков,
    // строка параметров движка — один раз за загрузку модели, строка чисел —
    // после каждой генерации. Держать их отдельно и склеивать при отрисовке
    // проще, чем гонять один текст и бояться затереть чужую половину.
    private var engineParamsLine: String? = null
    private var lastMetricsLine: String? = null

    // Палитра совпадает с activity_main.xml. Смысл цвета, а не украшение:
    // бирюзовый = обычное главное действие, фиолетовый = канал речи агента
    // (тем же цветом помечен блок "Ответ агента"). Красный нигде, кроме
    // аварийного стопа, не используется.
    private val colorIdle = ColorStateList.valueOf(Color.parseColor("#17697B"))
    private val colorToteRunning = ColorStateList.valueOf(Color.parseColor("#5B4B9E"))

    private val prefs by lazy { getSharedPreferences("uroboros_prefs", Context.MODE_PRIVATE) }

    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
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
        val head = "Зона: ${zoneLabel(zone)} · батарея $charge$plug · " +
            "${fmt1(power.temperatureCelsius)}°C"

        binding.textMetrics.text =
            listOfNotNull(head, engineParamsLine, lastMetricsLine).joinToString("\n")
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
     * Отчёт по одной генерации.
     *
     * Два секундомера здесь не дублируют друг друга. Движок меряет себя
     * изнутри и НЕ видит задержек, которые добавляет наша же обёртка
     * LlmEngine.generateFlow при утомлении. Поэтому расхождение между
     * "Движок" и "Секундомер" — это ровно та часть, которую тратим мы сами.
     */
    private fun metricsReport(
        metrics: DecodingMetrics?,
        wallMs: Long,
        firstTokenAtMs: Long?,
        worstZone: SafetyZone
    ): String {
        val lines = mutableListOf<String>()

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
                engineParamsLine = extractEngineParams(llmEngine.getDebugLog())
                renderMetricsPanel()
            } else {
                binding.textModelStatus.text = "Ошибка загрузки модели \"$displayName\""
            }
        }
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
        lifecycleScope.launch {
            combine(watchdog.zone, watchdog.power) { _, _ -> Unit }
                .collect { renderMetricsPanel() }
        }

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
            binding.textResults.text = "Экспортирую память (чекпоинт + копирование)..."
            lifecycleScope.launch {
                val exportedName = DatabaseExporter.exportToDownloads(applicationContext)
                binding.buttonSave.isEnabled = true
                binding.textResults.text = if (exportedName != null) {
                    "Экспорт готов: $exportedName\n(лежит в папке Download — оттуда переносите на новое устройство или загружайте в GitHub вручную)"
                } else {
                    "Не удалось экспортировать память — попробуйте ещё раз"
                }
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
            binding.textResults.text = ""
            lastMetricsLine = "Идёт генерация..."
            renderMetricsPanel()

            lifecycleScope.launch {
                val stickers = mediator.getContext(query = userText, limit = 5)
                val prompt = if (stickers.isEmpty()) {
                    userText
                } else {
                    val memoryBlock = stickers.joinToString("\n") { sticker ->
                        "- ${sourceLabel(sticker.source)} ${sticker.content}"
                    }
                    "Контекст из памяти:\n$memoryBlock\n\nВопрос: $userText"
                }

                val startMs = System.currentTimeMillis()
                var firstTokenAtMs: Long? = null
                var engineMetrics: DecodingMetrics? = null
                // Мгновенная зона в конце прогона соврала бы: устройство может
                // уйти в утомление на середине генерации и остыть к концу.
                // Запоминаем худшее из виденного.
                var worstZone: SafetyZone = watchdog.zone.value

                try {
                    llmEngine.generateFlow(prompt).collect { event ->
                        val nowZone = watchdog.zone.value
                        if (nowZone.ordinal > worstZone.ordinal) worstZone = nowZone

                        when (event) {
                            is GenerationEvent.Token -> {
                                if (firstTokenAtMs == null) {
                                    firstTokenAtMs = System.currentTimeMillis() - startMs
                                }
                                binding.textResults.append(event.text)
                            }
                            is GenerationEvent.Progress -> {
                                // Это обсчёт запроса (prefill) — ровно та часть,
                                // которую секундомером от нажатия до ответа
                                // невозможно было отделить от генерации.
                                lastMetricsLine =
                                    "Обсчёт запроса: ${(event.progress * 100).toInt()}%"
                                renderMetricsPanel()
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
                    lastMetricsLine = metricsReport(
                        metrics = engineMetrics,
                        wallMs = System.currentTimeMillis() - startMs,
                        firstTokenAtMs = firstTokenAtMs,
                        worstZone = worstZone
                    )
                    renderMetricsPanel()
                }
            }
        }

        binding.buttonGenerate.setOnLongClickListener {
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

    companion object {
        private const val KEY_MODEL_FOLDER_URI = "model_folder_uri"
        private const val KEY_LAST_MODEL_URI = "last_model_uri"
        private const val KEY_LAYER_REPAIR_DONE = "layer_repair_done_2026_08_22"
        private const val KEY_PROVENANCE_REPAIR_DONE = "provenance_repair_done_2026_08_24"
    }
}
