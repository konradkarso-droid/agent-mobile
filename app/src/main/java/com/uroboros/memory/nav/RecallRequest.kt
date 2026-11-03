package com.uroboros.memory.nav

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.Sentences
import com.uroboros.memory.allMeaningfulWords
import com.uroboros.util.TextFold

/**
 * Просьба владельца вспомнить давнее: «Что ты помнишь про полотенце?»,
 * «Вспомни, что я говорил про рубанок», «А что снилось в прошлый раз?».
 * Чистый объект: реплика и реплики нынешнего разговора на входе, план отбора
 * на выходе. Сам отбор — memory.HourglassMemory.getContextWithSummary и
 * memory.DolmenCircle.seat.
 *
 * ЧТО ДАЁТ ПРОСЬБА:
 *  - холод и своя речь агента (архив лент, nav.OwnSpeech) садятся раньше
 *    окна вопроса — вместе не больше [RECALL_SEATS] мест и не больше
 *    половины мест хода (DolmenCircle.recallCap);
 *  - своя речь ищется на любой вопрос с просьбой, а не только на вопрос к
 *    агенту: «что мы говорили с Praxis» — вопрос о разговоре, и ответы
 *    агента в нём — половина того, что было сказано;
 *  - слова самой просьбы («помнишь», «раньше») из слов поиска убираются
 *    ([STRIP_WORDS]): они называют действие, а не тему, и только тянут вниз
 *    долю слов вопроса у нужной записи;
 *  - в просьбе нет якоря («Помнишь что-нибудь?», «Что помнишь о жёлтом?») —
 *    холод ищет по якорям последних реплик нынешнего разговора
 *    ([Plan.contextAnchors]): обычно тема ясна из того, что говорилось только
 *    что. Слова просьбы при этом ищутся как всегда;
 *  - нет ни якоря, ни слова для поиска, ни якорей в разговоре — агенту
 *    предлагается переспросить, о чём вспомнить ([ASK_BACK]). Кроме вопроса
 *    о владельце («Помнишь меня?», «помнишь, кто я»; адрес OWNER или BOTH,
 *    nav.Coordinates): тема в нём названа — сам владелец, — и отвечает на
 *    него портрет собеседника (nav.Portrait), а не переспрос.
 *    Переспрос пишет модель: строка системы стоит в реплике, как предложение
 *    спросить о сне (llm.ConversationJournal.composeUserContent).
 *
 * ПРОСЬБА ЗАПИСЬ НЕ ГРЕЕТ. Холодная запись, поднятая просьбой, греется так
 * же, как любая холодная: только если агент взял её в ответ (экран после
 * ответа). Иначе каждая просьба поднимала бы наверх то, что всплыло, —
 * храповик «согрел — чаще всплывает — снова согрел».
 *
 * КАК УЗНАЁТСЯ (по предложениям, Sentences.split):
 *  - в предложении-вопросе (SentenceKind — знак «?», вопросительное слово в
 *    начале или «ли») — любое слово из [ASK_WORDS] или [PAST_WORDS], или
 *    оборот из [PAST_PHRASES];
 *  - слово из [ASK_WORDS] и без знака вопроса, если раньше него стоит
 *    вопросительное слово, а перед тем — только связка ([LEAD_WORDS]) или
 *    слово просьбы рассказать ([TELL_WORDS]): «А что помнишь о жёлтом,
 *    расскажи», «Расскажи, что ты помнишь», «Мне интересно, что ты помнишь»;
 *  - в любом предложении — повелительное «вспомни(те)», «припомни(те)»
 *    ([ORDER_WORDS]), а «вспомнить» — после «попробуй», «постарайся»
 *    ([TRY_WORDS]): «Попробуй вспомнить».
 * Не просьбы: «Ты помнишь неправильно», «Помнишь что было и видишь сны.»,
 * «Вспомнишь в процессе», «Хорошо, что помнишь сны» (здесь «что» — союз),
 * «Дай-ка вспомнить» (вспоминает сам владелец), «потом не можешь вспомнить».
 * «Чем раньше, тем лучше?» и «раньше времени» — не о прошлом разговоре
 * ([NOT_PAST]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - узнаёт по словам, а не по смыслу: «что там было с рубанком?» без
 *    «помнишь» просьбой не считается — давнее с якорем находит окно якорей
 *    и без просьбы (nav.Anchors, «В ХОЛОДЕ ТОЖЕ»);
 *  - время не читает: «неделю назад», «позавчера» записей того дня не
 *    поднимают; «неделю» при этом — якорь-предмет, как любое существительное;
 *  - вопрос без знака «?» и без вопросительного слова в начале («помнишь
 *    рубанок») просьбой не считается;
 *  - якоря разговора берутся из реплик владельца, а не из ответов агента:
 *    «Помнишь?» сразу после длинного ответа агента о Praxis ищет по тому,
 *    что говорил владелец.
 */
object RecallRequest {

    /** Слова просьбы в вопросе. Приведённый вид (TextFold). Объявленный список. */
    val ASK_WORDS = setOf(
        "помнишь", "помните", "помнит", "вспомнишь", "вспомните", "вспомнить", "вспомни",
        "припомнишь", "припомни", "припомнить", "вспоминаешь",
    )

    /** Повелительные формы — просьба и без вопроса. */
    val ORDER_WORDS = setOf("вспомни", "вспомните", "припомни", "припомните")

    /** Неопределённые формы — просьба без вопроса только после [TRY_WORDS]. */
    val INFINITIVE_WORDS = setOf("вспомнить", "припомнить")

    /** «Попробуй вспомнить». Не «можешь»: «потом не можешь вспомнить» — утверждение. */
    val TRY_WORDS = setOf("попробуй", "попробуйте", "постарайся", "постарайтесь")

    /** Связки перед вопросительным словом в начале предложения: «А что помнишь…». */
    val LEAD_WORDS = setOf("а", "и", "ну", "так", "ты")

    /** Просьба рассказать перед вопросительным словом: «Расскажи, что ты помнишь». */
    val TELL_WORDS = setOf("расскажи", "расскажите", "скажи", "скажите", "напиши", "интересно", "перечисли")

    /** Слова о прошлом — просьба только в вопросе. */
    val PAST_WORDS = setOf("раньше", "давно", "прежде")

    /** Обороты о прошлом разговоре — просьба только в вопросе. */
    val PAST_PHRASES = listOf("в прошлый раз", "в тот раз", "в прошлом разговоре", "прошлый разговор")

    /** Обороты, где слово о прошлом — не о прошлом разговоре. */
    val NOT_PAST = listOf("чем раньше", "раньше времени", "раньше срока")

    /**
     * Слова, без которых просьбу не задать и которые темы не несут: «Помнишь
     * что-нибудь из наших разговоров?», «Расскажи, что ты помнишь». Без них
     * «нибудь» и «расскажи» считались бы словами поиска, и просьба без темы
     * не доходила бы ни до якорей разговора, ни до переспроса. Объявленный
     * список, пополняется по замеру.
     */
    val ASK_FILLER = setOf(
        "нибудь", "либо", "расскажи", "расскажите", "скажи", "скажите", "знаешь",
        "попробуй", "попробуйте", "можешь", "сможешь", "прошлых", "прошлого", "прошлые", "прошлая",
        "наш", "наша", "наше", "наши", "наших", "нашем", "нашего", "нашей", "нашим",
        "разговоров", "разговорах", "разговоры",
    )

    /**
     * Слова, которые убираются из слов поиска на ходе с просьбой: слова
     * просьбы и прошлого, слова оборотов и связки просьбы. Больше их не убирает никто — без
     * просьбы «помнишь» остаётся словом поиска, как было.
     */
    val STRIP_WORDS: Set<String> =
        ASK_WORDS + ORDER_WORDS + INFINITIVE_WORDS + PAST_WORDS + setOf("помню", "прошлый", "прошлом", "разговоре", "разговор") + ASK_FILLER

    /**
     * Мест у холода и своей речи вместе на ходе с просьбой. Объявленное число,
     * не подобранное: половина общего потолка записей хода (DolmenCircle
     * .RECORDS_CEILING) — давнее слышно, но и нынешнее не вытеснено целиком.
     */
    const val RECALL_SEATS = 3

    /** Сколько последних реплик разговора дают якоря просьбе без якоря. Объявленное. */
    const val CONTEXT_TURNS = 3

    /** Не больше стольких якорей из разговора. Объявленное: каждый якорь — проход по базе. */
    const val CONTEXT_ANCHORS = 4

    /**
     * Строка системы агенту, когда зацепки нет. Только просьба переспросить:
     * что ответить — решает модель. Попадает в реплику блоком системы
     * (ConversationJournal.toAgent).
     */
    const val ASK_BACK =
        "Собеседник просит вспомнить, но не сказал, что именно, и в этом разговоре зацепки нет. " +
            "Спроси его одной фразой, о чём вспомнить."

    /** Узнанная просьба и слово, по которому узнана (как в реплике, приведённое). */
    data class Request(val word: String)

    /**
     * План отбора на ходе с просьбой.
     *
     * @property contextAnchors якоря из реплик разговора — только когда в самой
     *   просьбе якоря нет; иначе пусто.
     * @property askBack искать не по чему: ни якоря, ни слова, ни зацепки в разговоре.
     */
    data class Plan(
        val request: Request,
        val contextAnchors: List<Anchors.Anchor>,
        val askBack: Boolean,
    )

    /** Просьба в реплике или null. Правило — в шапке, «КАК УЗНАЁТСЯ». */
    fun of(text: String): Request? {
        for (sentence in Sentences.split(text)) {
            val folded = TextFold.fold(sentence)
            val words = folded.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            words.firstOrNull { it in ORDER_WORDS }?.let { return Request(it) }
            val inf = words.indexOfFirst { it in INFINITIVE_WORDS }
            if (inf > 0 && words[inf - 1] in TRY_WORDS) return Request(words[inf])
            val ask = words.indexOfFirst { it in ASK_WORDS }
            val qw = if (ask > 0) words.subList(0, ask).indexOfFirst { it in SentenceKind.QUESTION_WORDS } else -1
            if (qw >= 0) {
                val before = words.subList(0, qw)
                if (before.all { it in LEAD_WORDS } || before.any { it in TELL_WORDS }) return Request(words[ask])
            }
            if (SentenceKind.of(sentence).kind != SentenceKind.Kind.QUESTION) continue
            words.firstOrNull { it in ASK_WORDS }?.let { return Request(it) }
            PAST_PHRASES.firstOrNull { it in folded }?.let { return Request(it) }
            if (NOT_PAST.any { it in folded }) continue
            words.firstOrNull { it in PAST_WORDS }?.let { return Request(it) }
        }
        return null
    }

    /**
     * План на реплику [query]; null — просьбы нет, отбор прежний.
     *
     * @param recentQuestions реплики владельца нынешнего разговора, от старых к
     *   новым, без текущей (та же лента, что у окна темы, nav.WindowMap.episodeOf).
     * @param dictionary словарь начальных форм; null — якоря только латиницей (nav.Anchors).
     * @param address адрес вопроса (nav.Coordinates) — только для переспроса, см. шапку.
     */
    fun plan(
        query: String,
        recentQuestions: List<String>,
        dictionary: LemmaDictionary?,
        address: Coordinates.Address = Coordinates.Address.UNDEFINED,
    ): Plan? {
        val request = of(query) ?: return null
        if (Anchors.of(query, dictionary).isNotEmpty()) return Plan(request, emptyList(), askBack = false)
        val fromContext = recentQuestions.takeLast(CONTEXT_TURNS).asReversed()
            .flatMap { Anchors.of(it, dictionary) }
            .distinctBy { it.prefix }
            .take(CONTEXT_ANCHORS)
        val aboutOwner = address == Coordinates.Address.OWNER || address == Coordinates.Address.BOTH
        return Plan(request, fromContext, askBack = fromContext.isEmpty() && searchWords(query).isEmpty() && !aboutOwner)
    }

    /** Слова поиска реплики без слов просьбы ([STRIP_WORDS]), без потолка числа. */
    fun searchWords(query: String): List<String> = allMeaningfulWords(query).filterNot { it in STRIP_WORDS }

    /**
     * Текст, по которому своя речь агента ищется на ходе с просьбой (nav.OwnSpeech.search):
     * реплика без слов просьбы и, если они есть, слова якорей разговора. Без
     * этого «Помнишь?» нашло бы в архиве каждое «я помню».
     */
    fun topicText(query: String, plan: Plan?): String {
        if (plan == null) return query
        val kept = Regex("\\p{L}+").replace(query) { m -> if (TextFold.fold(m.value) in STRIP_WORDS) "" else m.value }
        return (listOf(kept) + plan.contextAnchors.map { it.word }).joinToString(" ")
    }

    /**
     * Часть строки «Круг:» о просьбе. Печатается всегда: «просьбы нет» должно
     * отличаться от «просьба есть, а холод пуст».
     *
     * @param coldFound сколько холодных записей нашли якоря разговора, @param coldSeated — из них усажено;
     *   считаются только когда якоря взяты из разговора.
     */
    fun meterPart(plan: Plan?, coldFound: Int = 0, coldSeated: Int = 0): String = when {
        plan == null -> "просьба вспомнить: нет"
        plan.askBack -> "просьба вспомнить: «${plan.request.word}» — зацепки нет ни в реплике, ни в разговоре, агенту предложено переспросить"
        plan.contextAnchors.isNotEmpty() ->
            "просьба вспомнить: «${plan.request.word}» · холод и своя речь — до $RECALL_SEATS мест · " +
                "якоря из разговора (${plan.contextAnchors.joinToString(", ") { it.word }}) — в холоде нашли $coldFound, мест $coldSeated"
        else -> "просьба вспомнить: «${plan.request.word}» · холод и своя речь — до $RECALL_SEATS мест"
    }
}
