package com.uroboros.gate

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.allMeaningfulWords
import com.uroboros.memory.nav.Anchors
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.normalizeExact
import com.uroboros.util.TextFold
import java.time.ZoneId

/**
 * Какие записи памяти вправе увидеть чат телеграма и как они подаются модели.
 * Чистый объект: записи и вопрос на входе, выбранное и строки на выходе.
 * Читает базу и зовёт отбор gate.link.ChatLink.
 *
 * ГРАНИЦА — КТО ЧТО ВИДИТ ([Scope], решение владельца). Запрет, а не вес:
 *  - личка владельца (собеседник отмечен «это я» на экране врат) — вся
 *    память, тем же отбором, что на экране (круг,
 *    HourglassMemory.getContextWithSummary);
 *  - личка другого человека или агента — только записи, которые сказал он
 *    сам ([Sticker.speaker] — его ключ из телеграма);
 *  - группа — только записи, прозвучавшие в этой группе ([Sticker.chat]),
 *    кто бы в ней ни спрашивал, включая владельца: сказанное в личке и на
 *    экране в группу не переходит.
 * Кто есть кто, решает ключ из телеграма, а не имя: подписавшийся «Иван»
 * владельцем не становится.
 *
 * ЧЕМ ПЛАТИМ. Аккаунт телеграма владельца читает всю память вопросами: кто
 * завладел аккаунтом — вытянет её. Права от этого не меняются (их даёт экран
 * за замком, см. [ChatFeed.prochnostNote]), меняется только то, что агент
 * вспомнит вслух. Память подаётся лишь там, где агент вообще отвечает: под
 * согласием владельца (ChatReply), а оно без срока — само не погаснет.
 *
 * ПОЧЕМУ У ЧУЖИХ СВОЙ УЗКИЙ ОТБОР ([pick]), А НЕ КРУГ С ФИЛЬТРОМ. Круг берёт
 * по каждому слову вопроса не больше двух десятков записей из всей памяти и
 * только потом взвешивает; записи одного собеседника среди сотен чужих до
 * фильтра не дошли бы, и он не получил бы ничего. Узкий отбор читает сразу
 * только дозволенное и ищет в нём.
 *
 * ЗАПИСЬ БЕЗ КЛЮЧА ГОВОРЯЩЕГО И ЧАТА — только владельцу. Так лежит всё,
 * сказанное с экрана, и всё записанное до появления этих полей: чужому чату
 * такая запись не видна никогда.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - снов в телеграме нет; портрет собеседника (ChatPortrait) и своя речь
 *    агента (ChatOwnSpeech) идут отдельно, по той же границе;
 *  - узкий отбор ищет имена и предметы вопроса (nav.Anchors) и его значимые
 *    слова по началу слова; смысла не знает, лестницы ступеней и холодного
 *    окна у него нет;
 *  - чужую запись, отвергнутую или ждущую проверки, не видит никто — как и
 *    круг;
 *  - каждая поданная запись обсчитывается моделью заново на каждом ходе:
 *    ответ в телеграме от записей становится медленнее.
 */
object ChatRecall {

    /** Чья это память для чата — см. шапку, «ГРАНИЦА». */
    enum class Scope { OWNER, PERSON, GROUP }

    /** Граница чата: [direct] — личка; [senderIsOwner] — собеседник отмечен «это я». */
    fun scope(direct: Boolean, senderIsOwner: Boolean): Scope = when {
        !direct -> Scope.GROUP
        senderIsOwner -> Scope.OWNER
        else -> Scope.PERSON
    }

    /**
     * Видна ли запись чату: [speaker] — ключ собеседника, [chat] — ключ чата
     * (и ветки), как их пишет дверь записи (memory.Heard). Вторая проверка
     * после запроса к базе: запрос читает по тому же полю, а здесь граница
     * закреплена тестами и не зависит от того, как запрос написан.
     */
    fun visible(scope: Scope, record: Sticker, speaker: String, chat: String): Boolean = when (scope) {
        Scope.OWNER -> true
        Scope.PERSON -> speaker.isNotEmpty() && record.speaker == speaker
        Scope.GROUP -> chat.isNotEmpty() && record.chat == chat
    }

    /** Сколько записей узкий отбор подаёт за ход. Объявленное число, не подобранное. */
    const val NARROW_SEATS = 4

    /** Сколько последних дозволенных записей узкий отбор читает из базы. Объявленное число, не подобранное. */
    const val POOL = 400

    /** Сколько значимых слов вопроса должно найтись в записи без якоря. Объявленное число, не подобранное. */
    private const val MIN_WORDS = 2

    /** Короче этого начало слова совпадает с чем угодно — как у nav.Anchors. */
    private const val MIN_PREFIX = 4

    /**
     * Тексты записей памяти для строк окна, которые модель и так видит: слова
     * владельца — как есть, чужие — «Подпись: текст» (TgWords.memoryText).
     * Запись с таким текстом ходу не подаётся — она дала бы одну реплику
     * дважды, в ленте и воспоминанием.
     */
    fun shownTexts(lines: List<ChatFeed.Line>, ownerKey: String?): List<String> =
        lines.filter { !it.isAgent }.map { line ->
            if (ownerKey != null && line.sender == ownerKey) line.text
            else TgWords.memoryText(line.text, line.signature, owner = false) ?: line.text
        }

    /**
     * Узкий отбор: записи из [pool] (уже только дозволенные чату) к вопросу
     * [question], лучшие первыми, не больше [limit]. Запись проходит, если в
     * ней есть имя или предмет вопроса (nav.Anchors) либо не меньше
     * [MIN_WORDS] значимых слов вопроса и не меньше половины их. Порядок: вес
     * якорей, число слов, свежесть. Не берутся записи из [shown] (модель
     * видит их в ленте) и записи из одних вопросов, просьб или обычаев — как
     * в круге. [dictionary] null — якорями служат только латинские слова.
     */
    fun pick(
        question: String,
        pool: List<Sticker>,
        dictionary: LemmaDictionary?,
        shown: Collection<String>,
        limit: Int = NARROW_SEATS,
    ): List<Sticker> {
        val anchors = Anchors.of(question, dictionary)
        val words = allMeaningfulWords(question)
        if (anchors.isEmpty() && words.size < MIN_WORDS) return emptyList()
        val seen = shown.mapTo(HashSet()) { normalizeExact(it) }
        data class Scored(val record: Sticker, val anchorWeight: Int, val words: Int)
        return pool.asSequence()
            .filter { normalizeExact(it.content) !in seen }
            .filterNot {
                RiskTrigger.isOnlyQuestions(it.content) || RiskTrigger.isOnlyRequests(it.content) ||
                    RiskTrigger.isOnlySocial(it.content)
            }
            .map { record ->
                val folded = TextFold.fold(record.content)
                Scored(
                    record,
                    anchors.filter { Anchors.hits(it, folded) }.sumOf { Anchors.weight(it.kind) },
                    words.count { hasWord(folded, it) },
                )
            }
            .filter { it.anchorWeight > 0 || (it.words >= MIN_WORDS && it.words * 2 >= words.size) }
            .sortedWith(
                compareByDescending<Scored> { it.anchorWeight }
                    .thenByDescending { it.words }
                    .thenByDescending { it.record.createdAt }
            )
            .take(limit)
            .map { it.record }
            .toList()
    }

    /** Есть ли в приведённом тексте слово с тем же началом, что у [word]: без двух последних букв, но не короче [MIN_PREFIX]. */
    private fun hasWord(folded: String, word: String): Boolean {
        val prefix = if (word.length - 2 >= MIN_PREFIX) word.dropLast(2) else word
        var from = 0
        while (true) {
            val at = folded.indexOf(prefix, from)
            if (at < 0) return false
            if (at == 0 || !folded[at - 1].isLetter()) return true
            from = at + 1
        }
    }

    /**
     * Строка записи узкого отбора для модели. В личке — как любая запись
     * собеседника («Твои слова …: «Подпись: текст».», ProvenanceLabels): он и
     * есть «ты». В группе «ты» — не обязательно автор записи, поэтому подпись
     * не называет лица: «В этом чате, на днях: «Подпись: текст».». Слова
     * владельца лежат в памяти без подписи — в группе она ставится здесь
     * ([ownerKey], [ownerName]), иначе не видно, кто говорил.
     * [past] — запись из прошлого разговора этого чата.
     */
    fun narrowLine(
        record: Sticker,
        scope: Scope,
        now: Long,
        past: Boolean,
        ownerKey: String?,
        ownerName: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (scope != Scope.GROUP) {
            return ProvenanceLabels.recordForModel(record, now, zone, recordEpisode = if (past) 0 else 1, currentEpisode = 1)
        }
        val time = if (past) ProvenanceLabels.pastEpisodeTime(record.createdAt, now, zone)
        else ProvenanceLabels.ageForModel(record.createdAt, now, zone)
        val text = if (ownerKey != null && ownerName != null && record.speaker == ownerKey) "$ownerName: ${record.content}" else record.content
        return "В этом чате, $time: «$text»."
    }

    /** Сообщение агента с записями хода; null — подавать нечего. Форма — как на экране (ConversationJournal.recallOf). */
    fun message(lines: List<String>): String? = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")

    private fun scopeWords(scope: Scope) = when (scope) {
        Scope.OWNER -> "владелец — вся память"
        Scope.PERSON -> "собеседник — только его слова"
        Scope.GROUP -> "группа — только сказанное в ней"
    }

    /** Строка экрана врат для лички владельца: [fed] подано, [circle] — строка круга этого хода. */
    fun ownerMeter(chatName: String, fed: Int, circle: String): String =
        "Записи ($chatName): ${scopeWords(Scope.OWNER)} · подано $fed · $circle"

    /** Строка экрана врат для узкого отбора: [pool] — дозволенных записей прочитано, [fed] — подано. */
    fun narrowMeter(chatName: String, scope: Scope, pool: Int, fed: Int): String =
        "Записи ($chatName): ${scopeWords(scope)} · дозволенных $pool" +
            (if (pool == POOL) " (читаю не больше $POOL последних)" else "") + " · " +
            when {
                pool == 0 -> "подавать нечего"
                fed == 0 -> "к вопросу не подошла ни одна"
                else -> "подано $fed"
            }

    /** Строка экрана врат, когда память не прочиталась: ход идёт без записей. */
    fun failedMeter(chatName: String, why: String): String = "Записи ($chatName): не прочитались — $why; отвечаю без них"
}
