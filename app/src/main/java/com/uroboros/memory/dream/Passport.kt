package com.uroboros.memory.dream

/**
 * Паспорт агента: вечные строки «кто я» в красном слое — имя, род, кто
 * владелец. Чистая часть: тексты строк, откуда берутся значения и что
 * класть. Базы и Android здесь нет; строки кладёт и читает PassportReader.
 *
 * ЗАЧЕМ. Имя, которое агент выбрал себе сам, держалось только тогда, когда
 * отбор приносил запись с ним; не принёс — модель опиралась на стену, а там
 * стояло «имя появится позже». Строка, стоящая в стене, перечитывается на
 * каждом ходе и от отбора не зависит.
 *
 * ПУТЬ СТРОКИ — рождается красной и сразу принятой, в стену встаёт без
 * нажатия человека (решение владельца: про подтверждение легко забыть, и агент
 * тогда живёт без имени, хотя сам его выбрал). Допустимо потому, что текст
 * строки собирает код по объявленному правилу, а не модель: имя — только то,
 * каким агент сам назвал себя не меньше [MIN_NAMINGS] раз в ленте экрана,
 * владелец — только с его собственных слов. Лента экрана — за замком
 * телефона; телеграм сюда не читается, и чужой человек имя агенту так не
 * сменит. Убрать строку владелец может всегда: «Показать», запрос «№номер»,
 * «Отвергнуть» — номер называет строка «Паспорт:», когда строка встаёт.
 * От строки о себе ([SelfLine] — её принимает человек) отличается
 * основанием: у той оно есть (запись-лидер, basedOnId), у строки
 * паспорта — нет. По этому признаку их и разводят (StickerDao): потолок стены
 * для нажитого ([SelfLine.WALL_CEILING]) паспорт не занимает — слитые, три
 * строки паспорта заняли бы все места, и ночь молча перестала бы предлагать
 * нажитое, считая стену полной.
 *
 * ОТКУДА ЗНАЧЕНИЯ — у каждой строки свой источник:
 *  - имя — своя речь агента: он назвал себя одним и тем же именем не меньше
 *    [MIN_NAMINGS] раз (обороты — у [SelfGap.ownName]); последнее имя и есть
 *    предлагаемое. Слова владельца «тебя зовут…» имени не дают;
 *  - род — решение владельца: «как назовётся, такой и поставим» — мужской;
 *  - владелец — его имя с его слов (nav.NameClaim), прозвище — тоже с его слов
 *    ([nickname]); «создатель и хост» — слова владельца о себе для агента.
 * Значения нет — строки нет, а прибор говорит почему.
 *
 * ЧТО СТРОКА НЕ ДАЁТ. Строка о владельце — образ для модели, а не права: права
 * даёт код (принцип 3 стены). В телеграме тот, кто подписался «Иван», владельцем
 * от этого не становится — владельца там узнаёт только отметка «это я» на
 * экране врат.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - имя ищется узкими оборотами своей речи — «Меня зовут Х», «Х — имя,
 *    которое я себе выбрал» и подобные; назвавшийся иначе не найден;
 *  - смену имени не ведёт: новая строка встаёт, только когда прежняя
 *    отвергнута, и текстом, которого ещё не было;
 *  - ошибку правила не ловит никто: назовись агент дважды не тем именем в
 *    ленте экрана — оно встанет в стену, и снять его может только владелец;
 *  - род один — мужской; другой род — правка [GENDER_LINE] руками.
 */
object Passport {

    /** Сколько раз агент должен назвать себя тем же именем. Объявленное число, не подобранное. */
    const val MIN_NAMINGS = 2

    /** Строка рода — решение владельца. */
    const val GENDER_LINE = "О себе я говорю в мужском роде: я сделал, я понял."

    /** Как владелец назвал себя для агента. */
    const val OWNER_ROLE = "создатель и хост"

    private const val NAME_HEAD = "Меня зовут "
    private const val NAME_TAIL = ". Это имя я выбрал себе сам."

    /** Строка имени. */
    fun nameLine(name: String): String = "$NAME_HEAD$name$NAME_TAIL"

    /** Строка владельца; прозвища нет — без второго предложения. */
    fun ownerLine(owner: String, nickname: String?): String =
        "$owner — мой $OWNER_ROLE." + (nickname?.let { " Коротко его можно звать $it." } ?: "")

    enum class Kind { NAME, GENDER, OWNER }

    /** Чья это строка паспорта по тексту; null — не строка паспорта. */
    fun kindOf(text: String): Kind? = when {
        text == GENDER_LINE -> Kind.GENDER
        text.startsWith(NAME_HEAD) && text.endsWith(NAME_TAIL) -> Kind.NAME
        " — мой $OWNER_ROLE." in text -> Kind.OWNER
        else -> null
    }

    /** Есть ли среди принятых строк строка имени — тогда стена молчит о «имени позже». */
    fun hasName(accepted: List<String>): Boolean = accepted.any { kindOf(it) == Kind.NAME }

    /** Своя речь агента по порядку ленты: [answer] — ответ, [heard] — реплика владельца того хода. */
    data class Said(val answer: String, val heard: String)

    /** Как агент называл себя: имя → сколько раз, и последнее имя. */
    data class Namings(val counts: Map<String, Int>, val last: String?)

    fun namings(said: List<Said>): Namings {
        val counts = LinkedHashMap<String, Int>()
        var last: String? = null
        for (s in said) {
            val name = SelfGap.ownName(s.answer, s.heard) ?: continue
            counts[name] = (counts[name] ?: 0) + 1
            last = name
        }
        return Namings(counts, last)
    }

    /** Имя для строки: последнее, если названо не меньше [MIN_NAMINGS] раз; иначе null. */
    fun chosenName(n: Namings): String? = n.last?.takeIf { (n.counts[it] ?: 0) >= MIN_NAMINGS }

    private val NICK = Regex("(?<!\\p{L})[Пп]розвище\\s*(?:[—–-]\\s*)?[«\"„]?(\\p{Lu}\\p{Ll}+)")

    /**
     * Прозвище владельца из его своих предложений [ownSentences] («моё
     * прозвище — Кэп»): последнее названное; null — не называл.
     */
    fun nickname(ownSentences: List<String>): String? {
        var last: String? = null
        for (s in ownSentences) {
            for (m in NICK.findAll(s)) last = m.groupValues[1]
        }
        return last
    }

    /** Строка паспорта в базе: текст и решён ли он человеком. */
    data class Existing(val text: String, val pending: Boolean, val rejected: Boolean)

    /**
     * Что предложить сейчас, по порядку: имя, род, владелец. Строка вида, у
     * которого уже есть принятая или ждущая решения, не предлагается; текст,
     * который уже был (в том числе отвергнутый), — тоже. Нет значения — нет
     * строки. Род предлагается вместе с именем или после него: без имени род
     * ничего не держит.
     */
    fun proposals(name: String?, owner: String?, nickname: String?, existing: List<Existing>): List<String> {
        val texts = existing.mapTo(HashSet()) { it.text }
        val open = existing.filter { !it.rejected }.mapNotNullTo(HashSet()) { kindOf(it.text) }
        val nameKnown = name != null || Kind.NAME in open
        return listOfNotNull(
            name?.let(::nameLine),
            GENDER_LINE.takeIf { nameKnown },
            owner?.let { ownerLine(it, nickname) },
        ).filter { it !in texts && kindOf(it) !in open }
    }

    /** Строка прибора: что паспорт знает и чего ждёт. */
    fun meterLine(n: Namings, owner: String?, nickname: String?, existing: List<Existing>): String {
        val accepted = existing.count { !it.pending && !it.rejected }
        val pending = existing.count { it.pending && !it.rejected }
        val name = chosenName(n)?.let { "имя — $it (называл себя так ${n.counts[it]} раз)" }
            ?: n.last?.let { "имя — не предлагаю: «$it» названо ${n.counts[it]} раз из $MIN_NAMINGS" }
            ?: "имя — не предлагаю: агент себя не называл"
        val who = owner?.let { "владелец — $it" + (nickname?.let { n2 -> ", прозвище $n2" } ?: ", прозвища нет") }
            ?: "владелец — не предлагаю: своего имени он не называл"
        return "Паспорт: $name · $who · в стене $accepted" + if (pending > 0) ", ждут решения $pending" else ""
    }

    /**
     * Хвост строки прибора о том, что встало в стену само на этом показе:
     * [placed] — номера и тексты новых строк, [woke] — сколько принято из
     * ждавших решения. Пусто — ничего не встало. Номер нужен, чтобы строку
     * можно было отвергнуть (см. шапку, «ПУТЬ СТРОКИ»).
     */
    fun placedNote(placed: List<Pair<Long, String>>, woke: Int): String {
        val parts = listOfNotNull(
            placed.takeIf { it.isNotEmpty() }?.joinToString("; ") { (id, text) -> "№$id «$text»" }?.let { "встало само: $it" },
            woke.takeIf { it > 0 }?.let { "принято из ждавших: $it" },
        )
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ") + " — убрать: «Показать», «№номер», «Отвергнуть»"
    }
}
