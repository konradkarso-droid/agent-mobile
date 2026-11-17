package com.uroboros.memory.dream

import com.uroboros.memory.Sentences
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.util.TextFold
import java.util.concurrent.ConcurrentHashMap

/**
 * Заявки агента о себе в ответе модели и их опора в памяти агента.
 *
 * МОДЕЛЬ СОЧИНЯЕТ, АГЕНТ НЕ ПОДХВАТЫВАЕТ. Малая модель на вопрос о себе без
 * материала досочиняет: «Снилось — что я скатился на лыжах», «Пракс
 * ответила», «помню разговор 14 дней назад». Ответ уходит как есть — что
 * говорит модель, решает она. Но у агента есть своя память — сны ночи, слова
 * людей, записи, — и сказанное без опоры в ней агент себе не берёт:
 *  - на следующем ходе модели сказано, что прошлый ответ этого не держит
 *    ([note]: экран — ConversationJournal.composeUserContent, телеграм —
 *    хвост нити в gate.link.ChatLink);
 *  - своя речь не возвращает такой ход как «Я говорил» (OwnSpeech.sift) —
 *    ни в ответ, ни в облако агента (SelfGapReader), ни в проверку на
 *    прочность «ты сам говорил» (nav.Prochnost);
 *  - обещания из такого хода не считаются (desk.Promises.find).
 * Ход с заявкой без опоры отмечается целиком ([Checker.mark]): соседние
 * предложения того же ответа («Буквально с горы.») — продолжение выдумки,
 * хотя заявки по форме в них нет.
 *
 * ЗАЯВКА ([Kind]) — предложение ответа, не вопрос, с одной из форм (закрытый
 * список, [kindOf]):
 *  - [Kind.DREAM] — «снилось», «приснилось», «во сне»;
 *  - [Kind.ATTRIBUTION] — «ты говорил / сказал / рассказывал / писал /
 *    упоминал / обещал», «говоришь, что»;
 *  - [Kind.MEMORY] — «помню», «вспомнил», «мы говорили / обсуждали», «у нас
 *    был разговор», «в прошлый раз»; «не помню» — не заявка.
 *
 * ОПОРА — по виду, у каждого свой источник: сон, сверенный со всей памятью,
 * «подтверждал» бы любой разговор о лыжах. Слова-темы заявки — по правилу
 * сюжетов (DreamPlot.Words: имена и предметы в начальной форме); «сон» сам
 * темой сна не считается.
 *  - сон — описание ночи, поданное на этом ходе, и записи последней ночи перед
 *    ходом. Заявка о сне без слов-тем держится, только если описание ночи было:
 *    иначе модели было нечего рассказывать;
 *  - «ты говорил» — слова этого собеседника: его реплика хода и его записи в
 *    памяти (Sticker.speaker; у владельца — без ключа или с его ключом);
 *  - «помню» — окно разговора, записи хода и вся память до хода.
 * Держится, если одно слово-тема заявки нашлось в источнике, а из двух и
 * больше — хотя бы два вместе в одном источнике: «Иван» и «Телеграм» по
 * отдельности есть в сотне записей и не подтверждают ничего. Объявленное
 * правило, сверено глазами на одной выгрузке памяти.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - действия не ловит: «Пракс ответила», «пишу ей» — формы такой заявки нет;
 *  - заявку без этих слов не видит: «говорили о твоих днях — садоводство…»;
 *  - заявка о памяти и о чужих словах без слов-тем не сверяется — нечем;
 *  - числа и даты не сверяет: «14 дней назад», «4 ноября»;
 *  - «во сне» в смысле «пока спал» («одно прерывание во сне») читается
 *    заявкой о сне: ход без описания ночи отметится зря;
 *  - тема — слово, а не смысл; сон о лыжах «подтвердит» запись, где лыжи
 *    упомянуты в другом смысле;
 *  - сны берутся за последние ночи, какие прочитал вызывающий: ход старше —
 *    без снов, и его заявка о сне отметится;
 *  - без словаря начальных форм слов-тем нет, и сверка не идёт вовсе.
 */
object SelfClaim {

    enum class Kind { DREAM, ATTRIBUTION, MEMORY }

    /** Заявка: предложение, вид и его слова-темы. */
    data class Claim(val sentence: String, val kind: Kind, val themes: Set<String>)

    /** Заявка без опоры и слова-темы, которых в источнике не нашлось. */
    data class Unsupported(val claim: Claim, val missing: List<String>)

    /**
     * Ход для сверки. [theirWords] — реплика собеседника на этом ходе и его
     * слова в окне; [window] — окно разговора и записи хода; [dreamNote] —
     * описание ночи, поданное на этом ходе (или раньше в окне), null — не
     * подавалось; [person] — ключ собеседника (Sticker.speaker), null — владелец.
     *
     * Окно и слова собеседника — только до ответа и без ответов агента: прежний
     * ответ с той же выдумкой подтверждал бы новый (своя речь опорой не служит),
     * а вопрос после ответа («какие лыжи?») — сам ответ.
     */
    data class Turn(
        val answer: String,
        val at: Long?,
        val theirWords: List<String>,
        val window: List<String>,
        val dreamNote: String?,
        val person: String?,
    )

    /**
     * Сверка с памятью агента. [records] — память (скрытые и отвергнутые здесь
     * не опора); [dreams] — сны прочитанных ночей; [ownerKey] — ключ владельца в
     * телеграме (его записи из телеграма — тоже слова владельца).
     */
    class Checker(
        records: List<Sticker>,
        dreams: List<Dream>,
        private val words: DreamPlot.Words,
        private val ownerKey: String?,
    ) {
        private val live = records.filter { !it.reviewPending && it.rejectedAt == null }
        private val byId = live.associateBy { it.id }
        private val nights: List<Pair<Long, List<Long>>> = dreams.groupBy { it.nightAt }
            .map { (night, rows) -> night to rows.flatMap { it.ids() }.distinct() }
            .sortedBy { it.first }

        /** Заявки ответа [turn] без опоры. */
        fun unsupported(turn: Turn): List<Unsupported> =
            claimsOf(turn.answer, words).mapNotNull { claim -> missing(claim, turn)?.let { Unsupported(claim, it) } }

        /**
         * Ходы своей речи с отметкой «есть заявка без опоры»
         * (OwnSpeech.Turn.claimed). Реплика хода — слова собеседника и окно:
         * больше окна у хода архива нет.
         */
        fun mark(turns: List<OwnSpeech.Turn>): List<OwnSpeech.Turn> {
            // Описание ночи держит и следующие ходы с тем же собеседником до
            // новой ночи: «Да, сон был странный» после рассказа — не выдумка.
            val notes = HashMap<String?, Pair<Long?, String>>()
            val out = turns.toMutableList()
            for (i in turns.indices.sortedBy { turns[it].at ?: 0L }) {
                val t = turns[i]
                val note = t.dreamNote ?: notes[t.person]?.takeIf { (from, _) -> sameNight(from, t.at) }?.second
                t.dreamNote?.let { notes[t.person] = t.at to it }
                val probe = Turn(t.answer, t.at, listOf(t.question), listOf(t.question) + t.records, note, t.person)
                if (unsupported(probe).isNotEmpty()) out[i] = t.copy(claimed = true)
            }
            return out
        }

        /** Между [from] и [to] не было новой ночи (неизвестное время — не было). */
        private fun sameNight(from: Long?, to: Long?): Boolean =
            from == null || to == null || nights.none { it.first > from && it.first < to }

        /** Каких слов-тем не нашлось; null — заявка держится или сверять нечем. */
        private fun missing(claim: Claim, turn: Turn): List<String>? {
            val themes = claim.themes
            if (claim.kind == Kind.DREAM) {
                if (themes.isEmpty()) return if (turn.dreamNote == null) emptyList() else null
                val sources = listOfNotNull(turn.dreamNote).map { words.of(it) } + nightOf(turn.at).map { themesOf(it) }
                return if (holds(themes, sources)) null else themes.toList()
            }
            if (themes.isEmpty()) return null
            val before = live.filter { turn.at == null || it.createdAt < turn.at }
            val sources = when (claim.kind) {
                Kind.ATTRIBUTION ->
                    turn.theirWords.map { words.of(it) } + before.filter { saidBy(it, turn.person) }.map { themesOf(it) }
                else -> turn.window.map { words.of(it) } + before.map { themesOf(it) }
            }
            return if (holds(themes, sources)) null else themes.toList()
        }

        private fun saidBy(record: Sticker, person: String?): Boolean =
            if (person == null || person == ownerKey) record.speaker == null || record.speaker == ownerKey
            else record.speaker == person

        /** Записи последней ночи перед [at]. */
        private fun nightOf(at: Long?): List<Sticker> {
            val night = nights.lastOrNull { at == null || it.first < at } ?: return emptyList()
            return night.second.mapNotNull { byId[it] }
        }

        private fun themesOf(record: Sticker): Set<String> {
            cache[record.id]?.let { (text, themes) -> if (text == record.content) return themes }
            return words.of(record.content).also { cache[record.id] = record.content to it }
        }
    }

    /** Заявки в ответе [answer] — описание объекта, «ЗАЯВКА». */
    fun claimsOf(answer: String, words: DreamPlot.Words): List<Claim> =
        Sentences.split(answer).mapNotNull { sentence ->
            val trimmed = sentence.trim()
            if (trimmed.endsWith("?")) return@mapNotNull null
            val kind = kindOf(trimmed) ?: return@mapNotNull null
            val themes = words.of(trimmed).let { if (kind == Kind.DREAM) it - DREAM_WORDS else it }
            Claim(trimmed, kind, themes)
        }

    /** Вид заявки по форме; null — не заявка. Порядок: сон, чужие слова, память. */
    fun kindOf(sentence: String): Kind? {
        val folded = TextFold.fold(sentence)
        return when {
            DREAM.containsMatchIn(folded) && !TO_YOU_DREAM.containsMatchIn(folded) -> Kind.DREAM
            ATTRIBUTION.containsMatchIn(folded) -> Kind.ATTRIBUTION
            MEMORY.containsMatchIn(folded) && !NOT_REMEMBER.containsMatchIn(folded) -> Kind.MEMORY
            else -> null
        }
    }

    /** Держится ли заявка с темами [themes] на источниках [sources] — правило в шапке, «ОПОРА». */
    private fun holds(themes: Set<String>, sources: List<Set<String>>): Boolean {
        val need = if (themes.size == 1) 1 else 2
        return sources.any { source -> themes.count { it in source } >= need }
    }

    /**
     * Строка модели на следующем ходе — о прошлом ответе, от первого лица;
     * null — сказать нечего. Слова заявки не цитируются: цитата дала бы
     * выдумке второе вхождение в запросе, и она прочлась бы как второе
     * свидетельство (то же правило, что у memory.DisputeNotice). Называются
     * только слова, которых в памяти нет. Утверждение, а не указание: что с
     * ним делать, решает модель.
     */
    fun note(found: List<Unsupported>): String? {
        if (found.isEmpty()) return null
        return found.distinctBy { it.claim.kind }.joinToString(" ") { u ->
            val absent = u.missing.take(MISSING_SHOWN).joinToString(", ") { "«$it»" }
            when (u.claim.kind) {
                Kind.DREAM ->
                    if (u.missing.isEmpty()) "В прошлом ответе я говорил о своём сне, а описания ночи мне не подавали."
                    else "В прошлом ответе я рассказал сон, которого нет в моих снах: нет $absent."
                Kind.ATTRIBUTION -> "В прошлом ответе я приписал собеседнику слова, которых он не говорил: нет $absent."
                Kind.MEMORY -> "В прошлом ответе я вспомнил то, чего нет в моей памяти: нет $absent."
            }
        }
    }

    /** Строка прибора о прошлом ответе. [why] — почему не сверялось; null — сверялось. */
    fun meter(found: List<Unsupported>?, why: String? = null, where: String? = null): String {
        val head = "Выдумки${where?.let { " ($it)" } ?: ""}: "
        if (why != null) return head + "не сверялось — $why"
        if (found.isNullOrEmpty()) return head + "в прошлом ответе заявок о себе без опоры нет"
        return head + "прошлый ответ — " + found.joinToString("; ") { u ->
            val what = when (u.claim.kind) {
                Kind.DREAM -> "сон"
                Kind.ATTRIBUTION -> "чужие слова"
                Kind.MEMORY -> "память"
            }
            if (u.missing.isEmpty()) "$what без описания ночи" else "$what: нет " + u.missing.take(MISSING_SHOWN).joinToString(", ") { "«$it»" }
        } + " · модели сказано, своей речью и обещанием не беру"
    }

    /** Сколько недостающих слов называть. Объявленное число: строка идёт модели. */
    const val MISSING_SHOWN = 3

    /** Слова, которые в заявке о сне — сама заявка, а не её тема. */
    private val DREAM_WORDS = setOf("сон", "сновидение")

    /** Тема записи памяти по номеру: записи перечитываются на каждой сверке, а темы — нет. */
    private val cache = ConcurrentHashMap<Long, Pair<String, Set<String>>>()

    // Граница слова — через буквы, а не `\b`: в Java `\b` для кириллицы на разных
    // версиях ведёт себя по-разному (см. RiskTrigger, числа).
    private const val L = "(?<!\\p{L})"
    private const val R = "(?!\\p{L})"
    private val DREAM = Regex("$L(снил(ось|ся|ась|ись)|приснил\\p{L}*|во сне|в моем сне|мне снит\\p{L}*)$R")
    private val TO_YOU_DREAM = Regex("${L}тебе (при)?сни")
    private val ATTRIBUTION = Regex(
        "$L(ты|вы|он|она) ((сам|сама|же|мне|тогда|раньше|вчера) )?" +
            "(говорил|говорила|сказал|сказала|рассказывал|рассказывала|писал|писала|упоминал|упоминала|обещал|обещала)$R" +
            "|${L}говоришь, что$R|${L}ты сам мне$R",
    )
    private val MEMORY = Regex(
        "$L(помню|вспомнил|вспоминаю|в прошлый раз|у нас был (разговор|диалог)" +
            "|мы ((с тобой|с ней|с ним) )?(говорили|обсуждали|беседовали|разговаривали))$R",
    )
    private val NOT_REMEMBER = Regex("${L}не (помню|вспомнил|вспоминаю)$R")
}
