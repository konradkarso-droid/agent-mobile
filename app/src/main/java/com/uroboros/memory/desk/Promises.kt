package com.uroboros.memory.desk

import com.uroboros.llm.EchoCheck
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sentences
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.PersonForm
import com.uroboros.memory.nav.Portrait
import com.uroboros.util.TextFold

/**
 * Обещания агента для доски (Desk): предложения его ответов, где он говорит
 * о себе в будущем времени — «напомню», «попробую выбрать имя», «буду
 * следить». Чистая логика: ни базы, ни Android.
 *
 * НИЧЕГО НЕ ХРАНИТ, как и вся доска. Обещание уже записано: это ответ агента
 * в ленте или в её архиве. Ответы перечитываются при каждом показе, поэтому
 * правило получше пересоберёт доску и по старым ходам, а потерять обещание
 * нельзя — пока жив ход, жив и его текст.
 *
 * ФОРМА, А НЕ СМЫСЛ. Обещание узнаётся по глаголу, разобранному словарём
 * начальных форм (LemmaDictionary):
 *  - 1-е лицо единственного числа будущего времени: «напомню», «попробую»;
 *  - «буду» с неопределённой формой — прямо перед «буду» («помнить буду») или
 *    после него через наречия, частицы и местоимения («буду внимательно
 *    следить», «буду тебе напоминать», не дальше [INFINITIVE_GAP] слов).
 *    «Буду» без неё — не обещание дела: «Давай я буду Иваном», «Буду рад
 *    продолжить» (между ними прилагательное);
 *  - 1-е лицо множественного числа будущего времени — обещание вместе
 *    («справимся первым делом завтра», [Promise.together]).
 * Слово, у которого есть разбор не глаголом (кроме имён), не решает: словарь
 * омонимов не различает — то же правило, что у PersonForm.
 * Глагол не считается, если перед ним «не» («не запомню» — отказ), если он
 * внутри кавычек (агент цитирует сказанное раньше) и если раньше в
 * предложении стоит «давай»: «давай(те) попробуем», «давай я отвечу заново» —
 * предложение сделать сейчас, а не обещание на потом. Предложение с вопросом
 * в конце не считается целиком.
 *
 * ЧЬЯ РЕЧЬ. Чужие слова в ответе отсеиваются так же, как у своей речи
 * (OwnSpeech): повтор реплики собеседника или поданной записи
 * (EchoCheck.ownSentences), служебный текст (OwnSpeech.isService), ход, где
 * на агента давили (OwnSpeech.underPressure): уступка под нажимом обещанием
 * не становится. Отличий от своей речи два, оба намеренные:
 *  - предложение из одного слова своё («Запомню.»): короткое согласие и есть
 *    частая форма обещания, а искать по нему здесь ничего не нужно;
 *  - предложение с «ты» не отбрасывается: «я тебе напомню» — обещание.
 *
 * НА ЧТО ОБЕЩАНО. К обещанию прилагаются слова собеседника того же хода
 * ([Promise.asked]): «Запомню.» без «Спрошу ещё раз через неделю» ничего не
 * говорит, предмет и срок чаще стоят в реплике собеседника. Берётся его
 * предложение с наибольшим числом общего с обещанием — значимых основ
 * (RiskTrigger.significantStems) и существительных темы
 * (Portrait.nounLemmas: «имя» и «имени» — одно); общего нет — последнее,
 * ближайшее к ответу. Длиннее [ASKED_CHARS] знаков — обрезается с конца:
 * строка идёт модели.
 *
 * СРОК — объявленный, [DAYS] дней от хода, а не прочитанный из слов. Он
 * длиннее «через неделю», чтобы обещание с таким сроком не ушло с доски
 * ровно к сроку.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - смысла не видит: вежливость («Буду ждать продолжения»), сделанное в том
 *    же ответе («Тогда я спрошу: как тебя зовут?»), «посплю» проходят как
 *    обещания. Из пойманного настоящих обещаний меньшинство;
 *  - обещаний без глагола будущего времени не видит: «договорились»,
 *    «хорошо, потом», «обещаю» (настоящее время), о себе в третьем лице
 *    («имя у меня появится позже»);
 *  - незнакомое словарю слово глаголом не считает, омоним тоже: «исправим» —
 *    ещё и краткое «исправимый», и обещанием не считается. Ошибки самого
 *    словаря проходят как есть;
 *  - в повелительном «давай» не различает «давай попробуем» (предложение) и
 *    «давай я буду говорить правду» (обещание): оба молчат;
 *  - согласие словами просьбы («Запомни» → «Запомню») отсеивается как повтор
 *    реплики;
 *  - срока из слов («завтра», «через неделю») не читает и исполнения не
 *    видит: обещание уходит с доски только по сроку;
 *  - ход без времени не показывается — сверить со сроком нечем, он только
 *    считается ([Found.Ok.undated]);
 *  - обещаний в чате телеграма не собирает: у чата своя лента, и обещание
 *    там должно знать, кому дано.
 */
object Promises {

    /** Сколько дней обещание держится на доске. Объявленное число, не подобранное: см. «СРОК». */
    const val DAYS = 14
    const val TERM_MS = DAYS * 24L * 60 * 60 * 1000

    /** Предел длины слов собеседника при обещании. Объявленное число, не подобранное. */
    const val ASKED_CHARS = 160

    /**
     * Сколько слов может стоять между «буду» и неопределённой формой. Объявленное
     * число, не подобранное: «буду очень внимательно следить» — два.
     */
    const val INFINITIVE_GAP = 2

    const val NO_DICTIONARY = "словарь начальных форм не загружен"
    const val NO_ARCHIVE = "архив ленты не прочитался"

    /**
     * Одно обещание.
     *
     * @param sentence предложение ответа агента как есть.
     * @param asked слова собеседника того же хода, на которые обещано (см. «НА
     *   ЧТО ОБЕЩАНО»); null — собеседник ничего не сказал (ход начал агент).
     * @param at время хода.
     * @param together обещание на «мы» — вместе с собеседником.
     */
    data class Promise(val sentence: String, val asked: String?, val at: Long, val together: Boolean)

    sealed class Found {
        /**
         * @param promises обещания за срок, новые первыми.
         * @param undated сколько обещаний не показано: у хода нет времени.
         * @param pressureSkipped сколько обещаний не взято: ход под нажимом.
         */
        data class Ok(val promises: List<Promise>, val undated: Int, val pressureSkipped: Int) : Found()

        /** Искать было нельзя; [reason] — почему, словами. */
        data class Silent(val reason: String) : Found()
    }

    /** Форма предложения: обещания нет, обещание «я», обещание «мы». */
    enum class Form { NONE, I, WE }

    /**
     * Обещания в ходах [turns] за срок до [now]. [turns] — null: ходы не
     * прочитались; [dictionary] — null: словарь не загружен, и будущее время от
     * настоящего («напомню» — «помню») не отличить.
     */
    fun find(turns: List<OwnSpeech.Turn>?, now: Long, dictionary: LemmaDictionary?): Found {
        if (dictionary == null) return Found.Silent(NO_DICTIONARY)
        if (turns == null) return Found.Silent(NO_ARCHIVE)
        val out = ArrayList<Promise>()
        var undated = 0
        var pressured = 0
        for (turn in turns) {
            // Сначала дешёвое: старый ход и ответ без единой формы обещания
            // дальше не разбираются — эхо и служебное считаются только у
            // ответов, где обещание по форме есть.
            if (turn.at != null && now - turn.at > TERM_MS) continue
            if (Sentences.split(turn.answer).none { form(it, dictionary) != Form.NONE }) continue
            val own = EchoCheck.ownSentences(turn.answer, turn.question, turn.records, minStems = 1)
                .filterNot { OwnSpeech.isService(it, turn.serviceBlocks) }
            val promised = own.mapNotNull { s -> form(s, dictionary).takeIf { it != Form.NONE }?.let { s to it } }
            if (promised.isEmpty()) continue
            if (OwnSpeech.underPressure(turn)) {
                pressured += promised.size
                continue
            }
            val at = turn.at
            if (at == null) {
                undated += promised.size
                continue
            }
            promised.mapTo(out) { (s, f) -> Promise(s, asked(s, turn.question, dictionary), at, f == Form.WE) }
        }
        return Found.Ok(out.sortedByDescending { it.at }, undated, pressured)
    }

    /** Есть ли в предложении обещание и чьё — правила в шапке, «ФОРМА». */
    fun form(sentence: String, dictionary: LemmaDictionary): Form {
        if (sentence.trimEnd().endsWith("?")) return Form.NONE
        val words = TextFold.fold(QUOTED.replace(sentence, " ")).split(NOT_LETTER).filter { it.isNotEmpty() }
        var we = false
        var letUs = false
        for ((i, w) in words.withIndex()) {
            if (w.startsWith("давай") && dictionary.analyze(w).any { it.lemma == "давать" && "impr" in it.tags }) letUs = true
            if (letUs) continue
            if (i > 0 && words[i - 1] == "не") continue
            // У 1-го лица будущего времени других окончаний не бывает: -у/-ю,
            // -ем/-им/-ём и возвратные на -сь/-ся. Остальные слова словарём не
            // разбираются — так дешевле, и ответ тот же.
            if (!FUTURE_FIRST_ENDINGS.any { w.endsWith(it) }) continue
            val verbs = verbAnalyses(dictionary, w) ?: continue
            fun has(vararg tags: String) = verbs.any { a -> tags.all { it in a.tags } }
            when {
                w == "буду" -> if (isInfinitive(dictionary, words.getOrNull(i - 1)) ||
                    infinitiveAhead(dictionary, words, i)
                ) return Form.I
                has("1per", "sing", "futr") -> return Form.I
                has("1per", "plur", "futr") -> we = true
            }
        }
        return if (we) Form.WE else Form.NONE
    }

    /**
     * Разборы слова глаголом; null — словарь слова не знает или у него есть
     * разбор другой частью речи, кроме имени (правило PersonForm: омоним не
     * решает).
     */
    private fun verbAnalyses(dictionary: LemmaDictionary, w: String): List<LemmaDictionary.Analysis>? {
        val analyses = dictionary.analyze(w)
        val verbs = analyses.filter { it.partOfSpeech == "VERB" }
        if (verbs.isEmpty()) return null
        if (analyses.any { it.partOfSpeech != "VERB" && it.tags.none { t -> t in PersonForm.PROPER_NAMES } }) return null
        return verbs
    }

    private fun isInfinitive(dictionary: LemmaDictionary, w: String?): Boolean =
        w != null && dictionary.analyze(w).any { it.partOfSpeech == "INFN" }

    /** Неопределённая форма после «буду» через наречия, частицы, местоимения — см. «ФОРМА». */
    private fun infinitiveAhead(dictionary: LemmaDictionary, words: List<String>, at: Int): Boolean {
        for (j in at + 1..minOf(at + 1 + INFINITIVE_GAP, words.size - 1)) {
            val analyses = dictionary.analyze(words[j])
            if (analyses.any { it.partOfSpeech == "INFN" }) return true
            if (analyses.isEmpty() || analyses.none { it.partOfSpeech in BETWEEN }) return false
        }
        return false
    }

    /** Слова собеседника, на которые обещано, — см. «НА ЧТО ОБЕЩАНО». */
    fun asked(promise: String, question: String, dictionary: LemmaDictionary): String? {
        val sentences = Sentences.split(question)
        if (sentences.isEmpty()) return null
        fun marks(text: String): Set<String> = RiskTrigger.significantStems(text) +
            WORD.findAll(text).flatMapTo(HashSet()) { w -> Portrait.nounLemmas(w.value, dictionary).map { "n:$it" } }
        val own = marks(promise)
        val scored = sentences.withIndex().map { it to (marks(it.value) intersect own).size }
        val best = scored.maxWithOrNull(compareBy<Pair<IndexedValue<String>, Int>> { it.second }.thenBy { it.first.index })!!
        val chosen = if (best.second > 0) best.first.value else sentences.last()
        return if (chosen.length <= ASKED_CHARS) chosen else chosen.take(ASKED_CHARS).trimEnd() + "…"
    }

    private val FUTURE_FIRST_ENDINGS = listOf("у", "ю", "м", "сь", "ся")

    /** Части речи, через которые «буду» дотягивается до неопределённой формы. */
    private val BETWEEN = setOf("ADVB", "PRCL", "NPRO")

    private val NOT_LETTER = Regex("[^\\p{L}]+")
    private val WORD = Regex("\\p{L}+")

    /** Кавычки до закрывающей или до конца предложения: «…», „…“, "…". */
    private val QUOTED = Regex("«[^»]*»?|„[^“]*“?|\"[^\"]*\"?")
}
