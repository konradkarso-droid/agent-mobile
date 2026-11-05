package com.uroboros.gate

import com.uroboros.llm.ConversationJournal
import com.uroboros.llm.Glance
import com.uroboros.memory.nav.Episodes
import com.uroboros.memory.nav.Prochnost

/**
 * Что модель читает на ходе чата телеграма: лента своего чата и больше ничего
 * из памяти. Стену ставит движок сам (LlmEngine.generateChatFlow).
 *
 * ПОЧЕМУ БЕЗ ЗАПИСЕЙ ПАМЯТИ. Решение владельца: в телеграме агент опирается
 * на стену и ленту чата. Записи с экрана — разговоры владельца; поданные
 * здесь, они ушли бы пересказом к другому человеку (агент принимает «можно
 * делиться» на свой счёт), а угонщик телеграма владельца вытянул бы память
 * вопросами. Граница — здесь: сюда не приходит ничего, кроме строк ленты чата,
 * того, что код собрал из них же (нить [ChatThread], обещания этого чата), и
 * доски приборов, когда агент сам на неё глянул (ChatGlance), — это приборы,
 * не память.
 *
 * ГОЛОСА — правило подачи моста агента: агент — ассистент; все остальные, и
 * владелец в телеграме тоже, — собеседник с подписью «Имя:». Подряд идущие
 * чужие строки — одним сообщением собеседника, по строке на реплику, подпись
 * с первого знака (пробел перед ней сдвигает границу реплики на токен).
 *
 * ЧТО ЧИТАЕТ МОДЕЛЬ — только строки, которые кольцо отдаёт модели
 * (AddressSorter.modelReads), и свои ответы агента. Общее группы до модели
 * доходит только выжимкой нити.
 *
 * ОКНО — СКАЧКАМИ, А НЕ ПО СТРОКЕ ([window]). Начало окна стоит на месте, пока
 * лента от него до текущей строки влезает в [BUDGET_CHARS], а когда перестаёт
 * влезать — переезжает разом на [STEP_CHARS] вперёд. Зачем: движок берёт
 * обсчитанное начало запроса готовым, только если оно совпало токен в токен,
 * а модель с Mamba-слоями — только начало, снятое снимком (`gguf_lib.cpp`,
 * «СНИМОК ЛЕНТЫ»). Окно, режущееся с конца по строке, меняет начало запроса с
 * каждой новой репликой, как только лента длиннее окна, и совпадения не
 * бывает никогда.
 *
 * ГДЕ ВСТАЁТ НАЧАЛО — на сетке шагом [STEP_CHARS] по смещению строки от начала
 * чата: сумме [cost] всех читаемых строк перед ней. Не от того, сколько строк
 * сейчас прочитано из базы, поэтому начало одно и то же при любом числе
 * прочитанных строк и после перезапуска, и хранить его не нужно. Смещение до
 * первой прочитанной строки считает база (ChatLineDao.readCostBefore) той же
 * мерой, что [cost]: буквы как кодовые точки — так считает `LENGTH` в SQLite, —
 * а не единицы UTF-16, как `String.length` (эмодзи — две). Разойдись меры —
 * сетка сдвигалась бы всякий раз, когда строка уходит из прочитанного в
 * подсчёт базы, и окно прыгало бы зря.
 *
 * Начинается окно со строки собеседника: ответ агента без реплики, на которую
 * он отвечал, читался бы его собственной затравкой. Текущая строка — всегда,
 * даже длиннее окна.
 *
 * СТРОКИ СИСТЕМЫ — блоками «[Мне от системы: …]» в сообщении собеседника, как
 * у экрана (ConversationJournal.toAgent). Собирает их код, не текст людей.
 * Место каждой — по тому, как часто она меняется:
 *  - первым сообщением окна — где агент ([placeLine]) и голова нити (что ушло
 *    за начало окна, ChatThread.headText): они меняются только со скачком
 *    окна, и начало запроса между скачками остаётся тем же;
 *  - перед текущей репликой — конец нити: фон группы (ChatThread.tailText),
 *    обещания этого чата (ChatPromises.chatText), — и проверка на прочность:
 *    они свои на каждом ходе.
 *    Поставленные в начало, они меняли бы начало запроса каждый ход.
 *
 * РАЗГОВОР — ОКНО НЕ ЗАХОДИТ ЗА ЕГО НАЧАЛО ([conversations]). Новый разговор
 * открывают тишина дольше [CONVERSATION_SILENCE_MS] между читаемыми строками и
 * установка сборки между ними (как «Начать заново» у эпизода экрана,
 * memory.nav.Episodes). Зачем: малая модель повторяет свои старые ответы из
 * ленты слово в слово — и ошибочные, и написанные прежней сборкой, — а видя
 * в ленте ответы о приборах без вызова, сама приборы не зовёт. На чистой
 * ленте этого нет. Строки прежних разговоров остаются в ленте чатов (подхват,
 * обещания, суд читают её целиком); модель видит от них только выжимку нити
 * (ChatThread). Сетка окна внутри разговора — от его начала.
 *
 * ЧЕГО НЕ УМЕЕТ: окно меряется знаками, а не токенами; что дальше окна, модель
 * видит только выжимкой нити; строк о себе, снов, записей — нет; после скачка
 * окна чат обсчитывается заново один раз; если читаемые строки так коротки,
 * что прочитанные не набирают и окна, начало встаёт на первую прочитанную и
 * съезжает вместе с ней.
 *
 * Чистый объект.
 */
object ChatFeed {

    /** Объявленное число, не подобранное: окно ленты чата для модели, знаков. */
    const val BUDGET_CHARS = 2400

    /**
     * Шаг сетки начала окна — половина окна: после скачка окно заполнено
     * наполовину и растёт до следующего скачка. Объявленное число, не
     * подобранное: меньше — чаще полный пересчёт чата, больше — меньше ленты
     * в окне сразу после скачка.
     */
    const val STEP_CHARS = BUDGET_CHARS / 2

    /**
     * Тишина в чате, после которой начинается новый разговор, — 3 часа.
     * Объявленное число, не подобранное (решение владельца), больше часа
     * эпизода экрана: в телеграме отвечают с перерывами, и разговор не должен
     * рваться на каждом. Короче — агент чаще теряет начатое; длиннее — дольше
     * живут в окне старые ответы.
     */
    const val CONVERSATION_SILENCE_MS = 3 * 60 * 60_000L

    /**
     * Номера разговоров для строк [ats] (время строк по порядку ленты):
     * тишина дольше [CONVERSATION_SILENCE_MS] или установка сборки в
     * [installedAt] между двумя строками открывают новый. null — время
     * установки не известно, делит только тишина.
     */
    fun conversations(ats: List<Long>, installedAt: Long?): List<Int> =
        Episodes.number(
            ats.mapIndexed { i, at ->
                Episodes.Point(at, closedBefore = i > 0 && installedAt != null && ats[i - 1] < installedAt && at >= installedAt)
            },
            CONVERSATION_SILENCE_MS,
        )

    /** Почему начался разговор окна: с чего и когда. [byBuild] — установка сборки, иначе тишина [silenceMs]. */
    data class Opened(val at: Long, val byBuild: Boolean, val silenceMs: Long)

    /**
     * Строка ленты для подачи: свой ответ агента или реплика с подписью.
     * [id] — номер строки в ленте чата (llm.ChatLine.id); [at] — её время, мс.
     */
    data class Line(
        val isAgent: Boolean,
        val signature: String,
        val text: String,
        val modelReads: Boolean,
        val id: Long = 0,
        val at: Long = 0,
        /** Ключ того, кто написал (ChatLine.sender); пусто — не из базы. */
        val sender: String = "",
    )

    /** Цена строки в окне: буквы текста и подписи и два знака на «: » или перенос. Мера — в шапке, «ГДЕ ВСТАЁТ НАЧАЛО». */
    fun cost(text: String, signature: String): Long =
        text.codePointCount(0, text.length).toLong() + signature.codePointCount(0, signature.length) + 2

    fun cost(line: Line): Long = cost(line.text, line.signature)

    /**
     * Окно модели: [lines] — строки окна по порядку; [grid] — точка сетки, от
     * которой оно начато; [chars] — сколько в нём знаков мерой [cost].
     */
    data class Window(val lines: List<Line>, val grid: Long, val chars: Long, val opened: Opened? = null) {
        val startId: Long get() = lines.first().id

        /** Окно начинается с начала разговора: перед ним — только прежние разговоры. */
        val startsConversation: Boolean get() = opened != null && opened.at == lines.first().at
    }

    /**
     * Окно для ответа на последнюю строку [lines] (по порядку, старые первыми).
     * [readOffset] — смещение первой читаемой строки [lines] от начала чата:
     * сумма [cost] всех читаемых строк чата перед ней. [installedAt] — когда
     * установлена сборка (для [conversations]), null — не известно.
     *
     * Начало разговора ищется среди [lines]: найдено не первой строкой — окно
     * и сетка от него, иначе — от начала чата, как до разговоров. Поэтому
     * разговор длиннее прочитанного однажды переедет с сетки разговора на
     * сетку чата: один лишний скачок окна.
     */
    fun window(
        lines: List<Line>,
        readOffset: Long,
        budgetChars: Int = BUDGET_CHARS,
        stepChars: Int = STEP_CHARS,
        installedAt: Long? = null,
    ): Window {
        require(lines.isNotEmpty() && !lines.last().isAgent) { "последней должна быть реплика собеседника" }
        require(stepChars in 1..budgetChars) { "шаг сетки — от 1 до окна" }
        val all = lines.filter { it.isAgent || it.modelReads }.let { if (it.lastOrNull() === lines.last()) it else it + lines.last() }
        val numbers = conversations(all.map { it.at }, installedAt)
        val from = numbers.indexOfFirst { it == numbers.last() }
        val opened = if (from > 0) {
            val prev = all[from - 1].at
            val at = all[from].at
            Opened(at, byBuild = installedAt != null && prev < installedAt && at >= installedAt, silenceMs = at - prev)
        } else null
        val read = if (from > 0) all.subList(from, all.size) else all
        val offsets = LongArray(read.size)
        var end = if (from > 0) 0L else readOffset
        for ((i, line) in read.withIndex()) {
            offsets[i] = end
            end += cost(line)
        }
        val grid = if (end <= budgetChars) 0L else ceilTo(end - budgetChars, stepChars.toLong())
        var start = offsets.indexOfFirst { it >= grid }.let { if (it < 0) read.lastIndex else it }
        // Последняя — реплика собеседника, так что цикл кончается на ней самое позднее.
        while (read[start].isAgent) start++
        return Window(read.subList(start, read.size).toList(), grid, end - offsets[start], opened)
    }

    private fun ceilTo(x: Long, step: Long): Long = (x + step - 1) / step * step

    /**
     * Сообщения модели для ответа на последнюю строку окна [window]. [place] —
     * строка «где я» ([placeLine]); [head] — голова нити (ChatThread) или
     * null; [tail] — конец нити одним блоком (шапка, «СТРОКИ СИСТЕМЫ») или
     * null; [prochnost] — строка проверки на прочность к
     * текущей реплике или null. Места строк — в шапке, «СТРОКИ СИСТЕМЫ».
     */
    fun messages(window: Window, place: String, head: String?, tail: String?, prochnost: String?): List<Pair<String, String>> {
        val lines = window.lines
        require(lines.isNotEmpty() && !lines.last().isAgent) { "последней должна быть реплика собеседника" }
        val out = ArrayList<Pair<String, String>>()
        val pending = ArrayList<String>()
        fun flush(last: Boolean) {
            if (pending.isEmpty()) return
            val blocks = ArrayList<String>(6)
            if (out.isEmpty()) {
                blocks += ConversationJournal.toAgent(place)
                if (!head.isNullOrBlank()) blocks += ConversationJournal.toAgent(head)
            }
            // Строка прочности — последней перед текущей репликой: её «ниже» указывает на неё.
            val notes = if (last) listOfNotNull(tail?.takeIf { it.isNotBlank() }, prochnost?.takeIf { it.isNotBlank() }) else emptyList()
            if (notes.isNotEmpty()) {
                if (pending.size > 1) blocks += pending.dropLast(1).joinToString("\n")
                notes.forEach { blocks += ConversationJournal.toAgent(it) }
                blocks += pending.last()
            } else blocks += pending.joinToString("\n")
            out += ConversationJournal.ROLE_USER to blocks.joinToString("\n\n")
            pending.clear()
        }
        for ((i, line) in lines.withIndex()) {
            if (line.isAgent) {
                flush(last = false)
                out += ConversationJournal.ROLE_ASSISTANT to line.text
            } else {
                pending += "${line.signature}: ${line.text}"
                if (i == lines.lastIndex) flush(last = true)
            }
        }
        return out
    }

    /**
     * Ходы окна для мер перехвата (llm.AnswerLadder.Ribbon.history): ход — свой
     * ответ агента и реплики собеседников перед ним, от прошлого ответа (или
     * начала окна). Номер хода — его номер среди ответов агента в окне; его же
     * принимает [without]. Реплики — текстом, без подписей: подписи — имена, а
     * не слова реплики, и мера зеркала считала бы их совпадением.
     *
     * ЧЕГО НЕ УМЕЕТ: ответов агента за началом окна не видит — повтор того, что
     * окна не видно, не ловится.
     */
    fun history(window: Window): List<ConversationJournal.Turn> {
        val out = ArrayList<ConversationJournal.Turn>()
        val asked = ArrayList<String>()
        for (line in window.lines) {
            if (!line.isAgent) {
                asked += line.text
                continue
            }
            val q = asked.joinToString("\n")
            out += ConversationJournal.Turn(userContent = q, agentContent = line.text, question = q, at = line.at)
            asked.clear()
        }
        return out
    }

    /** Реплики собеседников после последнего ответа агента в окне — текстом, по строке: вопрос этого хода. */
    fun question(window: Window): String =
        window.lines.takeLastWhile { !it.isAgent }.joinToString("\n") { it.text }

    /**
     * Окно без ходов [turns] (номера — как у [history]): ответ агента и реплики
     * перед ним уходят из окна. Текущая реплика остаётся всегда.
     *
     * Начало окна от этого сдвигается, поэтому снимок ленты такому запросу не
     * положен (llm.AnswerLadder.Ribbon.stableMessages) — он обсчитывается от стены.
     */
    fun without(window: Window, turns: Set<Int>): Window {
        if (turns.isEmpty()) return window
        val keep = ArrayList<Line>()
        val pending = ArrayList<Line>()
        var turn = 0
        for (line in window.lines) {
            if (!line.isAgent) {
                pending += line
                continue
            }
            if (turn !in turns) {
                keep += pending
                keep += line
            }
            pending.clear()
            turn++
        }
        keep += pending
        return Window(keep, window.grid, keep.sumOf(::cost))
    }

    /**
     * Сколько первых сообщений [messages] не изменится на следующем ходе, пока
     * окно не прыгнуло, — граница снимка ленты (LlmEngine.generateChatFlow):
     * все, кроме последнего. Последнее несёт хвост нити и строку прочности
     * этого хода, на следующем оно станет прошлым уже без них.
     */
    fun stableMessages(messages: List<Pair<String, String>>): Int = (messages.size - 1).coerceAtLeast(0)

    /**
     * Строка проверки на прочность к реплике чата [line] или null. Реплика из
     * телеграма никогда не «от владельца»: права даёт экран за замком, а не
     * аккаунт телеграма, — даже отмеченный «это я». [agentSaid] — свои ответы
     * агента в этом чате, по порядку; [selfLines] — строки о себе (стена).
     */
    fun prochnostNote(line: String, agentSaid: List<String>, selfLines: List<String>): String? {
        val trigger = prochnostTrigger(line)
        val found = if (trigger is Prochnost.Trigger.Active && Prochnost.Kind.OWN_WORDS in trigger.kinds)
            Prochnost.foundIn(agentSaid, line) else emptyList()
        return Prochnost.note(trigger, line, selfLines, found)
    }

    /** Строки о себе из стены [wall] — для [prochnostNote]: всё до блока приборов, по строке, без пустых. */
    fun selfLines(wall: String): List<String> =
        wall.substringBefore(Glance.WALL_BLOCK).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Включение проверки на прочность для реплики чата — всегда как не от владельца (см. [prochnostNote]). */
    fun prochnostTrigger(line: String): Prochnost.Trigger = Prochnost.trigger(line, fromOwner = false, painShift = null)

    /**
     * Строка «где я» — первым блоком окна. [chatTitle] — название группы;
     * [ownerSignature] — подпись владельца, если он отмечен и пишет в этом окне.
     */
    fun placeLine(direct: Boolean, chatTitle: String?, ownerSignature: String?): String {
        val where = if (direct) "Это телеграм, личка. Собеседник подписан своим именем из телеграма."
        else "Это телеграм, группа${chatTitle?.let { " «$it»" } ?: ""}. Каждая реплика подписана именем автора; не всё в группе сказано мне."
        return if (ownerSignature == null) where else "$where «$ownerSignature» — мой владелец, отмечен на экране телефона."
    }
}
