package com.uroboros.memory.nav

import com.uroboros.util.TextFold

/**
 * Устаревший факт о собеседнике: его новое слово о себе отменяет старое
 * («Я живу в Москве» → «Теперь я живу в Самаре»). Чистая часть: какие пары
 * фактов спрашивать, как спросить модель, как прочесть ответ, строки итога.
 * Базу и модель зовёт dream.FactAgingStep.
 *
 * ЗАЧЕМ. Факты о собеседнике (PersonFacts) не остывают и стоят в портрете
 * первыми. Без этого механизма перемена в жизни человека оставляла в портрете
 * и старое, и новое. Поправка о себе (OwnCorrection) ловит только спор
 * словами — отрицание, другое число, «не Y, а Z»; «Теперь я живу в Самаре»
 * словами с «Я живу в Москве» не спорит.
 *
 * КТО ВПРАВЕ ОТМЕНИТЬ — только новое слово того же человека о себе: пара
 * собирается из фактов одного собеседника. Слова другого человека чужие факты
 * не трогают.
 *
 * ЧТО ДЕЛАЕТ «ОТМЕНЯЕТ» — старая строка уходит из портрета (Portrait), и
 * больше ничего. Запись остаётся в памяти: её находит отбор по словам вопроса,
 * её видят сны и суд. Решение принимает модель, а не человек, и поэтому оно
 * обратимое и узкое (решение владельца). Этим механизм и разведён с поправкой
 * о себе: та снимает запись совсем, по словам самого человека, без модели;
 * слитые, они не дали бы увидеть, кто снял — человек или модель.
 *
 * ОШИБКА — В СТОРОНУ ОСТАВИТЬ. Строка уходит только при ответе «отменяет»;
 * «нет» и непонятный ответ не меняют ничего. Спрошенная пара хранится с любым
 * ответом и второй раз не спрашивается.
 *
 * КАКИЕ ПАРЫ — факт и более новый факт того же человека, у которых есть общее
 * значимое слово в начальной форме ([topicLemmas]: существительное, глагол,
 * прилагательное, причастие, деепричастие, числительное; не служебное и не
 * местоимение). «Живу / живу», «профессия / профессию», «курю / курить».
 * Одинаковые по тексту строки парой не считаются: повтор не перемена.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - пару без общего слова («Я химик» → «Теперь пишу код») не находит;
 *  - сравнивает только строки, отмеченные «факт»: факт, названный моментом,
 *    не отменяет и не отменяется;
 *  - перемену возраста («мне 30» → «исполнился 31 год») модель не видит;
 *  - срок жизни факта без новой фразы не считает;
 *  - словарь не загружен — пар нет, шаг говорит об этом;
 *  - «отменяет» — мнение модели; ошибка уносит строку из портрета, но не из
 *    памяти.
 */
object FactAging {

    /** Пара: [old] — прежний факт, [new] — более новый. */
    data class Pair(val old: PersonFacts.Key, val new: PersonFacts.Key)

    /** Что ответила модель. */
    enum class Verdict { OUTDATES, KEEPS, UNCLEAR }

    /**
     * Сколько пар спрашивать за ночь. Объявленное число, не подобранное: на
     * стенде около 6 с на пару — заход в пару минут; остальное — в следующие
     * ночи, новые первыми.
     */
    const val PER_NIGHT = 30

    /** Потолок ответа: одно слово. */
    const val ANSWER_TOKENS = 6

    /**
     * Вопрос модели. Подобран стендом на модели телефона (Falcon-H1 7B), три
     * прогона: на 16 настоящих парах фактов одной темы (повторы и варианты
     * имени) — «нет» 16 из 16; на придуманных переменах — «отменяет» 7 из 8,
     * на придуманных уточнениях — «нет» 6 из 6. Настоящих перемен в памяти
     * на стенде не было. Для другой модели перемерять.
     */
    const val SYSTEM =
        "Тебе дают две фразы, которые один и тот же человек сказал о себе: старую и новую. " +
            "Реши: новая фраза делает старую неверной сейчас — он что-то поменял в жизни или поправил себя? " +
            "Или обе могут быть верны одновременно — новая повторяет, уточняет или добавляет? " +
            "Ответь одним словом: «отменяет» или «нет»."

    fun request(old: String, new: String): String = "Старая: «$old»\nНовая: «$new»"

    fun parse(answer: String): Verdict {
        val a = answer.lowercase()
        return when {
            "отмен" in a -> Verdict.OUTDATES
            "нет" in a -> Verdict.KEEPS
            else -> Verdict.UNCLEAR
        }
    }

    /** Части речи значимого слова (описание объекта, «КАКИЕ ПАРЫ»). */
    val CONTENT_PARTS = setOf("NOUN", "VERB", "INFN", "ADJF", "ADJS", "PRTF", "PRTS", "GRND", "NUMR")

    private val WORD = Regex("[\\p{L}-]+")

    /**
     * Начальные формы значимых слов [text]. Слово, у которого есть служебный
     * разбор (Portrait.FUNCTION_WORDS) или пометка притяжательного («мой»), не
     * даёт ничего — как у облака и портрета.
     */
    fun topicLemmas(text: String, dictionary: LemmaDictionary): Set<String> =
        WORD.findAll(text).map { it.value.trim('-') }.filter { it.isNotEmpty() }.flatMapTo(HashSet()) { word ->
            val analyses = dictionary.analyze(word)
            if (analyses.any { it.partOfSpeech in Portrait.FUNCTION_WORDS || "Apro" in it.tags }) emptyList()
            else analyses.filter { it.partOfSpeech in CONTENT_PARTS }.map { it.lemma }
        }

    /**
     * Пары для этой ночи. [facts] — строки портрета каждого собеседника,
     * отмеченные «факт» (по собеседнику, свежие первыми, как Portrait.passed);
     * [asked] — уже спрошенные пары; [outdated] — уже устаревшие строки (с ними
     * пар не собирается). Новые первыми, не больше [limit].
     */
    fun toAsk(
        facts: List<List<Portrait.Line>>,
        dictionary: LemmaDictionary,
        asked: Set<Pair>,
        outdated: Set<PersonFacts.Key>,
        limit: Int = PER_NIGHT,
    ): Pending {
        val all = ArrayList<kotlin.Pair<Pair, Long>>()
        for (lines in facts) {
            val live = lines.filter { PersonFacts.Key(it.recordId, it.sentence) !in outdated }
            val lemmas = live.associateWith { topicLemmas(it.sentence, dictionary) }
            for (new in live) for (old in live) {
                if (old.createdAt >= new.createdAt || fold(old.sentence) == fold(new.sentence)) continue
                if ((lemmas.getValue(old) intersect lemmas.getValue(new)).isEmpty()) continue
                val pair = Pair(PersonFacts.Key(old.recordId, old.sentence), PersonFacts.Key(new.recordId, new.sentence))
                if (pair !in asked) all += pair to new.createdAt
            }
        }
        return Pending(all.sortedByDescending { it.second }.take(limit).map { it.first }, all.size)
    }

    /** [next] — что спросить сейчас; [total] — сколько всего не спрошено. */
    data class Pending(val next: List<Pair>, val total: Int)

    /**
     * Устаревшие строки: старые строки пар с ответом «отменяет» [outdates],
     * если запись нового факта ещё жива ([live] — номера живых записей).
     * Новый факт отвергли или удалили — старый снова в портрете: отменял его
     * только новый.
     */
    fun outdated(outdates: List<Pair>, live: Set<Long>): Set<PersonFacts.Key> =
        outdates.filter { it.new.recordId in live }.mapTo(HashSet()) { it.old }

        const val OUTCOME_HEAD = "Устаревшее: "

    /**
     * Итог ночного шага. [outdatedNow] — пары, где модель сказала «отменяет»,
     * номерами записей; [stoppedBy] — почему остановился раньше.
     */
    fun outcome(asked: Int, outdatedNow: List<Pair>, left: Int, stoppedBy: String?, noDictionary: Boolean = false): String {
        if (noDictionary) return "${OUTCOME_HEAD}не сравнивал — словарь начальных форм не загружен"
        if (asked == 0 && stoppedBy != null) return "${OUTCOME_HEAD}не сравнивал — $stoppedBy"
        if (asked == 0) return "${OUTCOME_HEAD}сравнивать нечего — новых пар фактов на одну тему нет"
        val gone = if (outdatedNow.isEmpty()) "не отменено ничего"
        else "отменено ${outdatedNow.size}: " + outdatedNow.joinToString(", ") { "№${it.old.recordId} ← №${it.new.recordId}" }
        val stop = stoppedBy?.let { " · остановился: $it" } ?: ""
        return "${OUTCOME_HEAD}пар $asked, $gone, осталось $left$stop"
    }

    private fun fold(s: String): String = TextFold.fold(s).trim().trimEnd('.', '!', '?', '…', ' ')
}
