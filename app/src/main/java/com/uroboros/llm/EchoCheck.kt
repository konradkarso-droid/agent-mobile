package com.uroboros.llm

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sentences

/**
 * Эхо в ответе агента: предложение, повторяющее уже сказанное, вместо ответа.
 * Чистая логика — без Android и без состояния, чтобы закрепляться тестами.
 *
 * ЧЕТЫРЕ ВИДА, У КАЖДОГО СВОЙ ИСТОЧНИК, И СЧЁТ У НИХ РАЗДЕЛЬНЫЙ:
 *  - повтор себя — предложение ответа повторяет предложение одного из прошлых
 *    ответов агента в ленте;
 *  - петля в ответе — предложение ответа повторяет более раннее предложение
 *    ЭТОГО ЖЕ ответа (см. [LOOP_SHARE]). Повтор себя её не видит: он сверяет
 *    только с прошлыми ответами, а петля рождается внутри одного;
 *  - зеркало — предложение ответа собрано из слов реплики владельца этого же
 *    хода («А сейчас не вспомнишь?» → «Нет, сейчас не вспомнишь»);
 *  - ответ служебной строке — предложение ответа собрано из слов служебного
 *    блока реплики этого же хода (см. [serviceBlocks]).
 * Слитые в один счёт, они не дали бы увидеть, какой из них пошёл на убыль.
 * Петля к тому же — единственный вид, который растёт до потолка длины ответа:
 * штраф движка за повтор смотрит назад на десятки токенов, а круг петли из
 * перечисления записей длиннее, и к его началу штраф прошлый круг уже не
 * видит.
 *
 * ПО ПРЕДЛОЖЕНИЯМ, А НЕ ПО ОТВЕТУ ЦЕЛИКОМ. Эхо — одна фраза внутри ответа, и
 * мера схожести целых ответов на живых примерах выходила низкой — она бы
 * промолчала. Предложения режет [Sentences], слова меряет
 * [RiskTrigger.echoStems]: разбиение и основа слова у всего проекта одни.
 *
 * ЗЕРКАЛО СВЕРЯЕТСЯ С `question`, А НЕ С `userContent`. В реплике целиком лежат
 * процитированные записи памяти, и они раздули бы совпадение: пересказ записи
 * — законный ответ, а не эхо. Ход, начатый агентом (пустой `question`),
 * зеркала не имеет — отражать было нечего.
 *
 * СЧЁТ ИДЁТ ПО ЛЕНТЕ, из её ходов: так он переживает перезапуск вместе с
 * лентой и не хранит своего состояния.
 *
 * ЧЕГО НЕ УМЕЕТ. Сравнение по основам слов, а не по смыслу: перефраз без общих
 * слов не ловится; законный пересказ с общими словами («ты спросил про
 * рубанок — …») ловится ложно. Поэтому это только прибор для человека (строка
 * «Эхо:»): в запрос к модели ничего из него не идёт — почему, см.
 * [ConversationJournal.composeUserContent].
 */
object EchoCheck {

    // Три числа ниже — объявленные, не подобранные. Сравнение по основам, не по
    // смыслу; перепроверять по строке «Эхо:». Порог ответа служебной строке
    // и длина её показа — ниже, у [SERVICE_SHARE].

    /**
     * Сколько основ должно быть в предложении ответа, чтобы оно учитывалось.
     * Из одной основы («Помню.») повтор неотличим от короткого ответа по делу.
     */
    const val MIN_STEMS = 2

    /** Какая доля основ предложения должна найтись в том, с чем сверяем. */
    const val MIN_SHARE = 0.8

    /** Со сколькими прошлыми ответами агента сверяется повтор себя. */
    const val EARLIER_ANSWERS = 3

    /**
     * Какая доля основ предложения должна найтись в одном более раннем
     * предложении того же ответа, чтобы это была петля. Выше [MIN_SHARE]
     * намеренно: строки записей с подписью («С твоих слов в прошлом разговоре,
     * на днях: …») делят между собой почти все основы подписи, и при 0.8
     * перечисление РАЗНЫХ записей подписями ловилось как петля. Объявленное
     * число, проверенное на 295 ответах агента одной памяти (лента и архив,
     * ответы стенда на модели 3B): при 0.9 поймано 4 — три настоящие петли и
     * одна цитата, названная в ответе дважды; при 1.0 — те же 4. Перепроверять
     * по части «петля в ответе» строки «Эхо:»: ловит перечисление разных
     * записей — поднимать; пропускает видимую петлю — опускать, но не до
     * [MIN_SHARE].
     */
    const val LOOP_SHARE = 0.9

    /**
     * Какая доля основ предложения ответа должна найтись в служебном блоке.
     * Ниже порога зеркала ([MIN_SHARE]) намеренно: прибор ничего не
     * запускает, только показывает, и ошибаться ему можно в сторону лишнего.
     *
     * Живой пример — ответ «Понял, повторил слова пользователя.» на блок «В
     * прошлом ответе ты повторил слова пользователя вместо своих.»: основ в
     * ответе четыре, в блоке нашлись три («понял» — нет), доля 0.75. Порог
     * зеркала 0.8 его пропустил бы. 0.6 — объявленное число, не подобранное:
     * пример проходит с запасом на ещё одно своё слово в предложении из пяти
     * основ. Проверено на одном этом примере (EchoCheckTest). Перепроверять
     * по третьей части строки «Эхо:»: если там часто стоит пересказ по делу,
     * поднимать, но не выше 0.75 — иначе перестанет ловиться сам пример.
     */
    const val SERVICE_SHARE = 0.6

    /**
     * Сколько знаков начала служебного блока показывать в строке «Эхо:» —
     * объявленное число: строка одна, блок бывает в несколько предложений.
     */
    const val SERVICE_HEAD_CHARS = 40

    /**
     * Что поймано в одном ответе: предложения ответа, как они стоят в нём, или
     * null — не поймано. [serviceBlock] — служебный блок, на который пришёлся
     * [serviceReply], целиком; оба null или оба нет. Цитаты — только для
     * человека на экране: в запрос к модели они не идут (см.
     * [ConversationJournal.composeUserContent]).
     */
    data class Result(
        val selfRepeat: String?,
        val mirror: String?,
        val serviceReply: String? = null,
        val serviceBlock: String? = null,
        /** Первое предложение, повторившее более раннее в этом же ответе. */
        val loop: String? = null,
        /** Номер [loop] среди предложений ответа, с единицы; null — петли нет. */
        val loopAt: Int? = null,
    ) {
        // Петля сюда не входит: [any] — про эхо чужого или прошлого, а петля
        // — про сам ответ, и счёт у неё свой.
        val any: Boolean get() = selfRepeat != null || mirror != null || serviceReply != null
    }

    /**
     * Эхо в ответе последнего хода ленты, или null — ходов нет.
     *
     * Меряется ПЕРВЫЙ ответ хода: при перехвате ([EchoIntercept]) — отброшенный
     * (`rejected`), а не тот, что лёг на его место. Иначе перехват стирал бы
     * с экрана ровно то, что ловит, и частота повторов модели стала бы не
     * видна. Прошлые ответы — те, что видела модель (`agentContent`).
     *
     * @param turns ходы ленты от старых к новым.
     */
    fun ofLast(turns: List<ConversationJournal.Turn>): Result? {
        val last = turns.lastOrNull() ?: return null
        val earlier = turns.dropLast(1).takeLast(EARLIER_ANSWERS).map { it.agentContent }
        return check(last.rejected ?: last.agentContent, last.question, earlier, serviceBlocks(last))
    }

    /**
     * Служебные блоки реплики хода: всё, что собрано в `userContent` помимо
     * речи владельца и строк записей, — пометка об опоре, строка состояния,
     * предложение спросить о сне, сверка, строка хода, начатого агентом.
     *
     * КАК ОТДЕЛЯЮТСЯ, и почему не делением на блоки сразу: и вопрос, и запись
     * могут сами содержать пустую строку (запись — сохранённая речь владельца),
     * и деление по ней разрезало бы их на куски, похожие на служебные.
     *  - Вопрос снимается с КОНЦА реплики: [ConversationJournal.composeUserContent]
     *    всегда ставит его последним блоком. Пустой вопрос (ход начат агентом)
     *    ничего не снимает — вся реплика служебная.
     *  - Записи вынимаются из остатка по своим готовым строкам из
     *    [ConversationJournal.Turn.records]: там лежат ровно те строки записей
     *    и снов, что склеены в блок записей, а новые на этом ходе — их часть.
     *    Длинные вынимаются первыми, чтобы короткая, лежащая внутри длинной,
     *    не оставила от неё обрывков.
     *  - Остаток делится по пустой строке; пустые куски (бывший блок записей,
     *    от которого остались одни переводы строк) отбрасываются.
     * Внутри служебных блоков готовых строк записей нет: сверка говорит о
     * записях их текстами без провенанса и кавычек (см. `DisputeNotice`), а
     * не готовыми строками.
     */
    fun serviceBlocks(turn: ConversationJournal.Turn): List<String> {
        var rest = turn.userContent
        if (turn.question.isNotEmpty()) rest = rest.removeSuffix(turn.question)
        for (record in turn.records.map { it.text }.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            rest = rest.replace(record, "")
        }
        return rest.split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val BLANK_LINE = Regex("\\n\\s*\\n")

    /**
     * Эхо в одном ответе.
     *
     * Петля ЧЕГО НЕ УМЕЕТ: повтор другими словами не ловится, как и у прочих
     * видов. Ловит она и законный повтор — цитату, названную в ответе дважды
     * («правило «…»» во вступлении и в списке): по словам их не различить.
     * Поэтому сама по себе петля — только прибор.
     *
     * Ответ служебной строке ЧЕГО НЕ УМЕЕТ: ловит только совпадение слов, а
     * не пересказ своими словами («Понял, отвечу по-своему» на пометку об
     * эхе не поймается). Служебный блок, похожий по словам на вопрос, даст
     * двойной счёт: одно предложение встанет и зеркалом, и ответом служебной
     * строке — разводить их здесь нечем.
     *
     * @param question реплика владельца этого хода; пустая — ход начат агентом.
     * @param earlierAnswers прошлые ответы агента, с которыми сверяется повтор себя.
     * @param serviceBlocks служебные блоки реплики этого хода (см. [serviceBlocks]).
     */
    fun check(
        answer: String,
        question: String,
        earlierAnswers: List<String>,
        serviceBlocks: List<String> = emptyList(),
    ): Result {
        val questionStems = if (question.isBlank()) emptySet() else RiskTrigger.echoStems(question)
        val earlierSentences = earlierAnswers
            .flatMap { Sentences.split(it) }
            .map { RiskTrigger.echoStems(it) }
            .filter { it.isNotEmpty() }
        val serviceStems = serviceBlocks.map { it to RiskTrigger.echoStems(it) }.filter { it.second.isNotEmpty() }

        var selfRepeat: String? = null
        var mirror: String? = null
        var serviceReply: String? = null
        var serviceBlock: String? = null
        var loop: String? = null
        var loopAt: Int? = null
        // Предложения этого ответа, уже пройденные, — для петли.
        val ownEarlier = ArrayList<Set<String>>()
        for ((index, sentence) in Sentences.split(answer).withIndex()) {
            val stems = RiskTrigger.echoStems(sentence)
            if (stems.size < MIN_STEMS) continue
            // Одно более раннее предложение, а не все его слова вместе — как у
            // повтора себя.
            if (loop == null && ownEarlier.any { covered(stems, it, LOOP_SHARE) }) {
                loop = sentence
                loopAt = index + 1
            }
            ownEarlier.add(stems)
            if (mirror == null && questionStems.isNotEmpty() && covered(stems, questionStems, MIN_SHARE)) {
                mirror = sentence
            }
            // Одно предложение одного прошлого ответа, а не все их слова вместе:
            // слова, рассыпанные по трём ответам, — это не повтор фразы.
            if (selfRepeat == null && earlierSentences.any { covered(stems, it, MIN_SHARE) }) {
                selfRepeat = sentence
            }
            // Один блок, а не все служебные слова вместе — по той же причине.
            if (serviceReply == null) {
                serviceStems.firstOrNull { covered(stems, it.second, SERVICE_SHARE) }?.let {
                    serviceReply = sentence
                    serviceBlock = it.first
                }
            }
        }
        return Result(selfRepeat, mirror, serviceReply, serviceBlock, loop, loopAt)
    }

    /**
     * Предложения ответа агента, которые — его собственная речь, а не чужие
     * слова. Для места поиска «своя речь» (memory.nav.OwnSpeech).
     *
     * ЧЕМ ОТЛИЧАЕТСЯ ОТ [check]. Та же мера — разбиение [Sentences], основы
     * [RiskTrigger.echoStems], доля [MIN_SHARE], не меньше [MIN_STEMS] основ, —
     * но другой вопрос. Прибору эха пересказ записи — законный ответ, а не эхо,
     * и он сверяет только с `question`. Здесь пересказ записи и эхо реплики —
     * одинаково не своё: это слова владельца («Сегодня я отдыхаю — выходной»),
     * и в «своей речи» они вернули бы чужое лицо. Поэтому предложение не своё,
     * если оно собрано из слов реплики владельца этого хода или из слов одной
     * из записей, поданных в этот ход.
     *
     * Предложение короче [MIN_STEMS] основ своим не считается: из одной основы
     * («Помню.») не отличить свою речь от чужой, а искать по нему нечего.
     *
     * ЧЕГО НЕ УМЕЕТ: пересказ записи своими словами останется «своим».
     */
    fun ownSentences(answer: String, question: String, records: List<String>): List<String> {
        val questionStems = if (question.isBlank()) emptySet() else RiskTrigger.echoStems(question)
        val recordStems = records.map { RiskTrigger.echoStems(it) }.filter { it.isNotEmpty() }
        return Sentences.split(answer).filter { sentence ->
            val stems = RiskTrigger.echoStems(sentence)
            stems.size >= MIN_STEMS &&
                !(questionStems.isNotEmpty() && covered(stems, questionStems, MIN_SHARE)) &&
                recordStems.none { covered(stems, it, MIN_SHARE) }
        }
    }

    /**
     * Строка прибора «Эхо:» к последнему ответу. Печатается всегда: «нет»
     * должно отличаться от «не считалось».
     */
    fun meter(result: Result?): String {
        if (result == null) return "Эхо: ответа ещё нет"
        return "Эхо: повтор себя — ${say(result.selfRepeat)} · зеркало — ${say(result.mirror)}" +
            " · ответ служебной строке — ${sayService(result.serviceReply, result.serviceBlock)}" +
            " · петля в ответе — ${sayLoop(result.loop, result.loopAt)}"
    }

    private fun sayLoop(sentence: String?, at: Int?): String =
        if (sentence == null || at == null) "нет" else "с предложения $at «$sentence»"

    private fun say(sentence: String?): String = if (sentence == null) "нет" else "«$sentence»"

    // Блок показывается началом и в одну строку: переводы строк внутри него
    // разорвали бы строку прибора.
    private fun sayService(sentence: String?, block: String?): String {
        if (sentence == null || block == null) return "нет"
        val flat = block.replace(Regex("\\s+"), " ")
        val head = if (flat.length > SERVICE_HEAD_CHARS) flat.take(SERVICE_HEAD_CHARS) + "…" else flat
        return "«$sentence» → «$head»"
    }

    // Деление, а не умножение порога: частное 4/5 округляется в то же число,
    // что и запись 0.8, и ровно пороговая доля проходит.
    private fun covered(stems: Set<String>, source: Set<String>, share: Double): Boolean =
        stems.count { it in source }.toDouble() / stems.size >= share
}
