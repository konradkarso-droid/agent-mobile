package com.uroboros.memory.nav

import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Сортировщик обращения: кому сказано сообщение чата — агенту, об агенте,
 * непонятно кому или всем. Чистая логика: ни базы, ни модели, ни Android, ни
 * телеграма.
 *
 * ЗАЧЕМ. В общем чате к агенту обращена малая часть сообщений, остальное люди
 * и агенты говорят друг другу. Если всё сказанное в чате считать сказанным
 * агенту, чужие разговоры лягут в память как «мне сказали», уйдут ночному
 * судье и потянут агента отвечать на то, что спрашивали не его. Модель
 * читать каждое сообщение не успевает: короткое сообщение — секунды, и не
 * параллельно с ответом (один контекст движка). Поэтому кольцо решает код, до
 * модели, а модель читает только то, что кольцо ей отдаёт.
 *
 * КОЛЬЦА ([Ring]) И КУДА ОНИ ИДУТ — одно место, функции под ними:
 *  - [Ring.TO_AGENT] — к агенту: в память как «сказанное мне», ночному судье,
 *    модели, агент отвечает;
 *  - [Ring.ABOUT_AGENT] — об агенте в третьем лице: модели и в память как
 *    «говорили обо мне»; судье — нет, отвечать — нет;
 *  - [Ring.DOUBTFUL] — непонятно, к агенту ли: модели, в память с пометкой
 *    «сомнительно обращено», судье — нет; агент переспрашивает. Переспросить
 *    законно, и сколько раз понадобится — столько и переспрашивает;
 *  - [Ring.GENERAL] — общее: в память не идёт, модели не идёт; это общий
 *    фон беседы для спектрометра и карты окна по ленте чата.
 * Каждое сообщение чата видят спектрометр и лента чата, в каком бы кольце оно
 * ни было: кольцо решает, кто ещё его получит, а не видно ли оно вообще.
 *
 * ПРИЗНАКИ КАНАЛА ОТДЕЛЬНО ОТ ДОГАДОК ПО ТЕКСТУ ([Route.channel] и
 * [Route.text]). Канал — то, что сообщает сам телеграм и что подделать текстом
 * нельзя: личка, ответ на сообщение агента, упоминание его учётной записи.
 * Текст — догадка: имя в обращении, «ты» по ходу разговора. Канал решает
 * первым; догадки по тексту записываются и тогда, когда решил канал, — чтобы
 * по прибору было видно, совпадают ли они.
 *
 * ПОРЯДОК РЕШЕНИЯ — в [sort], правило за правилом; причина кольца словами —
 * [Route.reason].
 *
 * ИМЕНА АГЕНТА ОБЪЯВЛЯЕТ ВЛАДЕЛЕЦ ([Self.names]) — все формы, какими агента
 * зовут: падежи, уменьшительные, латиницей. Формы здесь не выводятся: имя
 * агента — не слово словаря, а ошибка в выводе формы отдала бы агенту чужое
 * слово. Пока список пуст, по имени не сортируется вовсе, и прибор так и
 * говорит ([Tally.meterLine]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - кольцо — по форме, а не по смыслу. «Ты» в группе почти всегда сказано
 *    кому-то другому, поэтому «ты» без признака разговора с агентом в общее и
 *    уходит; «по ходу разговора» значит только одно: сообщение прямо перед
 *    этим — агента этому человеку или этого человека агенту ([Prior]).
 *    Разговор через чужие сообщения посередине не виден;
 *  - имя агента, совпавшее с обычным словом («Астра» — и цветок), читается
 *    именем; имя целым словом, а не частью слова;
 *  - обращение без запятой узнаётся, только когда имя первое в предложении и
 *    за ним «ты», просьба или знак ([isAddressAt]); «Астра права» —
 *    третье лицо, «об агенте»;
 *  - упоминание учётной записи агента считается обращением, даже если
 *    сообщение — ответ другому: канал сильнее;
 *  - команда боту (`/start@бот`) — признак «не мне», если адрес не агента; что
 *    бы ни было в сообщении, исполнять его команды агент не будет — это не
 *    дело сортировщика, а граница прав (права даёт код).
 */
object AddressSorter {

    enum class Ring { TO_AGENT, ABOUT_AGENT, DOUBTFUL, GENERAL }

    /**
     * Сообщение чата — то, что должен отдать вход из телеграма. Ключи персон —
     * из проверяемого канала ([PersonKey]), никогда имя или ник.
     *
     * [replyTo] — автор сообщения, на которое это отвечает
     * (`reply_to_message.from.id`), null — не ответ. [mentions] — упоминания
     * через «@» (сущность `mention`), без «@», как написаны; [textMentions] —
     * упоминания без ника (сущность `text_mention`, ключ персоны); [commands] —
     * команды ботам (сущность `bot_command`), как написаны, с «/».
     */
    data class Message(
        val place: Place,
        val sender: PersonKey,
        val text: String,
        val replyTo: PersonKey? = null,
        val mentions: List<String> = emptyList(),
        val textMentions: List<PersonKey> = emptyList(),
        val commands: List<String> = emptyList(),
    )

    /** Агент в этом чате: ключ его учётной записи, его ники (без «@») и объявленные владельцем имена. */
    data class Self(
        val key: PersonKey,
        val usernames: Set<String> = emptySet(),
        val names: List<String> = emptyList(),
    )

    /**
     * Сообщение того же чата прямо перед этим — для «по ходу разговора».
     * [ring] — его кольцо (для сообщения агента — null); [replyTo] — кому
     * оно было ответом (для сообщения агента — кому агент ответил).
     */
    data class Prior(val sender: PersonKey, val ring: Ring?, val replyTo: PersonKey?)

    /** Признаки канала: от телеграма, текстом не подделать. */
    enum class Sign(val label: String) {
        SCREEN("экран владельца"),
        DIRECT("личка"),
        REPLY_TO_ME("ответ на моё сообщение"),
        MENTION("упоминание моей учётной записи"),
        REPLY_TO_OTHER("ответ другому"),
        COMMAND_TO_OTHER("команда боту"),
    }

    /** Догадки по тексту. */
    enum class Guess(val label: String) {
        NAME_ADDRESS("имя в обращении"),
        NAME_MENTION("имя не в обращении"),
        SECOND_OR_REQUEST("«ты» или просьба"),
        TALKING("по ходу разговора со мной"),
        AFTER_MINE("сразу после моего сообщения другому"),
    }

    /** Итог: кольцо, почему, и всё найденное — канал отдельно от текста. */
    data class Route(val ring: Ring, val reason: String, val channel: List<Sign>, val text: List<Guess>)

    /** Что агент делает с сообщением в ответ. */
    enum class Answer { ANSWER, ASK, SILENT }

    /** С какой пометкой сообщение ложится в память; [NONE] — не ложится. */
    enum class Memory { TOLD_ME, TOLD_ABOUT_ME, DOUBTFUL, NONE }

    fun answer(ring: Ring): Answer = when (ring) {
        Ring.TO_AGENT -> Answer.ANSWER
        Ring.DOUBTFUL -> Answer.ASK
        Ring.ABOUT_AGENT, Ring.GENERAL -> Answer.SILENT
    }

    fun memory(ring: Ring): Memory = when (ring) {
        Ring.TO_AGENT -> Memory.TOLD_ME
        Ring.ABOUT_AGENT -> Memory.TOLD_ABOUT_ME
        Ring.DOUBTFUL -> Memory.DOUBTFUL
        Ring.GENERAL -> Memory.NONE
    }

    /** Судье — только обращённое к агенту. */
    fun judged(ring: Ring): Boolean = ring == Ring.TO_AGENT

    /** Модели — всё, кроме общего. */
    fun modelReads(ring: Ring): Boolean = ring != Ring.GENERAL

    /**
     * Слова, после которых имя — обращение и без запятой («Эй Астра», «Слушай
     * Астра»). Объявленный список, не подобранный; дополняется здесь.
     */
    val CALL_WORDS = setOf("эй", "привет", "слушай", "здравствуй", "здравствуйте", "хай", "ау", "алло")

    /**
     * Кольцо сообщения [m] для агента [self]; [prior] — сообщение чата прямо
     * перед ним, null — нет или не известно.
     *
     * Правила по порядку:
     *  1. своё сообщение агента — общее: себе не отвечают и себя не судят;
     *  2. экран и личка — к агенту: там говорят только с ним;
     *  3. ответ на сообщение агента, упоминание его учётной записи — к агенту;
     *  4. ответ другому или команда боту: имя в обращении — сомнительно (канал
     *     говорит «другому», текст — «мне»); имя не в обращении — об агенте;
     *     иначе общее;
     *  5. без признаков канала: имя в обращении — к агенту; имя не в обращении
     *     — об агенте; по ходу разговора с агентом с «ты» или просьбой — к
     *     агенту, без них — сомнительно; «ты» или просьба сразу после сообщения
     *     агента другому — сомнительно; иначе общее.
     */
    fun sort(m: Message, self: Self, prior: Prior? = null): Route {
        val guesses = guesses(m, self, prior)
        fun route(ring: Ring, reason: String, channel: List<Sign>) = Route(ring, reason, channel, guesses)

        if (m.sender == self.key) return route(Ring.GENERAL, "своё сообщение", emptyList())
        when (m.place) {
            Place.SCREEN -> return route(Ring.TO_AGENT, Sign.SCREEN.label, listOf(Sign.SCREEN))
            Place.DIRECT -> return route(Ring.TO_AGENT, Sign.DIRECT.label, listOf(Sign.DIRECT))
            Place.GROUP -> Unit
        }

        val mine = self.usernames.mapTo(HashSet()) { fold(it.removePrefix("@")) }
        val channel = ArrayList<Sign>(3)
        if (m.replyTo == self.key) channel += Sign.REPLY_TO_ME
        val mentioned = m.mentions.any { fold(it.removePrefix("@")) in mine } ||
            self.key in m.textMentions ||
            m.commands.any { c -> commandAddress(c)?.let { fold(it) in mine } == true }
        if (mentioned) channel += Sign.MENTION
        if (m.replyTo != null && m.replyTo != self.key) channel += Sign.REPLY_TO_OTHER
        if (m.commands.any { c -> commandAddress(c)?.let { fold(it) !in mine } ?: true }) channel += Sign.COMMAND_TO_OTHER

        val toMe = channel.filter { it == Sign.REPLY_TO_ME || it == Sign.MENTION }
        if (toMe.isNotEmpty()) return route(Ring.TO_AGENT, toMe.joinToString(", ") { it.label }, channel)

        val nameAddress = Guess.NAME_ADDRESS in guesses
        val nameMention = Guess.NAME_MENTION in guesses
        val notMe = channel.filter { it == Sign.REPLY_TO_OTHER || it == Sign.COMMAND_TO_OTHER }
        if (notMe.isNotEmpty()) {
            val why = notMe.joinToString(", ") { it.label }
            return when {
                nameAddress -> route(Ring.DOUBTFUL, "$why, но ${Guess.NAME_ADDRESS.label}", channel)
                nameMention -> route(Ring.ABOUT_AGENT, "$why, ${Guess.NAME_MENTION.label}", channel)
                else -> route(Ring.GENERAL, why, channel)
            }
        }

        val second = Guess.SECOND_OR_REQUEST in guesses
        return when {
            nameAddress -> route(Ring.TO_AGENT, Guess.NAME_ADDRESS.label, channel)
            nameMention -> route(Ring.ABOUT_AGENT, Guess.NAME_MENTION.label, channel)
            Guess.TALKING in guesses && second ->
                route(Ring.TO_AGENT, "${Guess.TALKING.label}, ${Guess.SECOND_OR_REQUEST.label}", channel)
            Guess.TALKING in guesses -> route(Ring.DOUBTFUL, "${Guess.TALKING.label}, без «ты» и просьбы", channel)
            Guess.AFTER_MINE in guesses && second ->
                route(Ring.DOUBTFUL, "${Guess.SECOND_OR_REQUEST.label} ${Guess.AFTER_MINE.label}", channel)
            else -> route(Ring.GENERAL, "признаков обращения нет", channel)
        }
    }

    /** Все догадки по тексту — считаются всегда, при любом исходе канала. */
    private fun guesses(m: Message, self: Self, prior: Prior?): List<Guess> {
        val out = ArrayList<Guess>(3)
        when (nameUse(m.text, self.names)) {
            NameUse.ADDRESS -> out += Guess.NAME_ADDRESS
            NameUse.MENTION -> out += Guess.NAME_MENTION
            NameUse.NONE -> Unit
        }
        val form = PersonForm.of(m.text)
        if (form.aboutAddressee || form.imperative) out += Guess.SECOND_OR_REQUEST
        if (prior != null && m.sender != self.key) {
            val talking = (prior.sender == self.key && prior.replyTo == m.sender) ||
                (prior.sender == m.sender && prior.ring == Ring.TO_AGENT)
            if (talking) out += Guess.TALKING
            else if (prior.sender == self.key) out += Guess.AFTER_MINE
        }
        return out
    }

    private enum class NameUse { ADDRESS, MENTION, NONE }

    /** Есть ли имя агента в [text] и стоит ли оно хоть раз обращением. */
    private fun nameUse(text: String, names: List<String>): NameUse {
        val forms = names.map(::fold).filter { it.isNotBlank() }.toSet()
        if (forms.isEmpty()) return NameUse.NONE
        var seen = false
        for (sentence in Sentences.split(text)) {
            val words = WORD.findAll(sentence).toList()
            for ((i, w) in words.withIndex()) {
                if (fold(w.value) !in forms) continue
                seen = true
                if (isAddressAt(sentence, words, i)) return NameUse.ADDRESS
            }
        }
        return if (seen) NameUse.MENTION else NameUse.NONE
    }

    /**
     * Стоит ли имя (слово [i] предложения) обращением. Да, если:
     *  - оно первое (или сразу за [CALL_WORDS]) и за ним знак (, ! ? : тире),
     *    конец предложения, «ты» или просьба: «Астра, глянь», «Астра?», «Астра
     *    ты где», «Эй Астра»;
     *  - перед ним запятая, а за ним знак или конец: «Привет, Астра!», «Скажи,
     *    Астра, как…», «…, Астра?».
     * Иначе имя — в рассказе: «Астра права», «я думаю, Астра права».
     */
    private fun isAddressAt(sentence: String, words: List<MatchResult>, i: Int): Boolean {
        val w = words[i]
        val after = sentence.substring(w.range.last + 1).trimStart()
        val before = sentence.substring(0, w.range.first).trimEnd()
        val signAfter = after.isEmpty() || after[0] in ADDRESS_MARKS
        val lead = i == 0 || (i == 1 && fold(words[0].value) in CALL_WORDS)
        if (lead) {
            if (signAfter) return true
            val rest = PersonForm.ofSentence(after)
            if (PersonForm.Person.SECOND in rest || PersonForm.Person.IMPERATIVE in rest ||
                PersonForm.Person.WE_WITH_YOU in rest
            ) return true
            return false
        }
        return before.endsWith(",") && (after.isEmpty() || after[0] in CLOSE_MARKS)
    }

    /** «/start@astra_bot» → «astra_bot»; без адреса — null. */
    private fun commandAddress(command: String): String? =
        command.substringAfter('@', "").takeIf { it.isNotEmpty() }

    private fun fold(s: String): String = TextFold.fold(s.trim())

    private val WORD = Regex("""\p{L}[\p{L}\p{N}_\-]*""")
    private const val ADDRESS_MARKS = ",!?:;—–-.…"
    private const val CLOSE_MARKS = ",!?.…;:"

    /**
     * Счёт по одному чату — строка прибора: сколько сообщений в каком кольце,
     * сколько прочитала модель, сколько раз агент переспросил. Неизменяемый:
     * [plus] даёт новый.
     *
     * Прочитано моделью и переспрошено — то, что случилось, а не то, что
     * положено по кольцу: модель могла быть занята, переспрос — не уйти. Это
     * отмечает тот, кто зовёт ([read], [asked]).
     */
    data class Tally(
        val rings: Map<Ring, Int> = emptyMap(),
        val modelRead: Int = 0,
        val asks: Int = 0,
    ) {
        operator fun plus(route: Route): Tally = copy(rings = rings + (route.ring to ((rings[route.ring] ?: 0) + 1)))
        fun read(): Tally = copy(modelRead = modelRead + 1)
        fun asked(): Tally = copy(asks = asks + 1)

        val total: Int get() = rings.values.sum()

        /**
         * Строка прибора. Печатается всегда: «сообщений не было» и «имён нет»
         * отличаются от поломки. [chat] — как чат назвать на экране; [names] —
         * объявленные имена агента.
         */
        fun meterLine(chat: String, names: List<String>): String {
            val noNames = if (names.none { it.isNotBlank() }) " · имён агента нет — по имени не сортирую" else ""
            if (total == 0) return "Обращение ($chat): сообщений не было$noNames"
            fun n(r: Ring) = rings[r] ?: 0
            return "Обращение ($chat): к агенту ${n(Ring.TO_AGENT)} · про агента ${n(Ring.ABOUT_AGENT)} · " +
                "сомнительно ${n(Ring.DOUBTFUL)} (переспросов $asks) · общее ${n(Ring.GENERAL)} · " +
                "прочитано моделью $modelRead из $total$noNames"
        }
    }
}
