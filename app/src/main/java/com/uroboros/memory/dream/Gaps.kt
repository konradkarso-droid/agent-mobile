package com.uroboros.memory.dream

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sentences
import com.uroboros.util.TextFold

/**
 * Пробелы в знании агента — материал любопытства: прибор «Пробелы:» и второй
 * источник выхода «спросить» (CuriosityAsk.decideGap). Чистая логика: ходы на
 * входе, пробелы на выходе.
 *
 * ЧТО ТАКОЕ ПРОБЕЛ. Агент сам признал, что не знает («не знаю», «нет
 * информации», [IGNORANCE]), — в ответ на реплику владельца. Любопытство
 * растёт не на пустом месте и не на полном знании, а на частичном: агенту
 * сказано ровно столько, чтобы понять, что он чего-то не знает. Догадкой
 * пробел не находится: модель чаще выдумывает, чем признаётся, и
 * выдуманного источник не видит.
 *
 * ПРЕДМЕТ — значимые основы ([RiskTrigger.significantStems]), общие у реплики
 * владельца и у самой фразы «не знаю», без слов-связок ([FILLER]). Общее, а
 * не всё из реплики: на «…строить и дебажить… Ты как?» агент не знает, как
 * сам себя чувствует, а не что такое «дебажить». Нет общих основ — пробела
 * нет, молчим: выход терминальный, сомнение — в сторону «не спрашивать».
 * Исключение — вопрос о самом собеседнике («Что знаешь обо мне?» → «о вас нет
 * информации»): там общих основ нет по построению, и предмет — сам
 * собеседник ([Gap.aboutOwner]).
 *
 * НЕ ХРАНИТСЯ, А СОБИРАЕТСЯ — из ленты и архива при каждом обращении, как
 * доска. Ни таблицы, ни отметок: всё, что знает механизм, выводится из
 * сказанного, и правило можно поменять без перехода базы.
 *  - Тот же пробел — тот же предмет (равенство наборов основ). Признанный в
 *    двух разговорах — один пробел с двумя разговорами.
 *  - Спрошен — агент задал вопрос, покрывающий предмет (не меньше половины
 *    его основ, см. [covers]).
 *  - Закрыт — владелец после признания сказал утверждение (не вопрос),
 *    покрывающее предмет: «Колодка рубанка не деревянная», «Я — Админ».
 *    Закрывает данное, а не отметка.
 *  - НЕ ДОШЛО — агент признал незнание, когда ответ уже был сказан раньше.
 *    Это не пробел для вопроса (спросить снова — услышать «я же говорил»), а
 *    сбой вспоминания: ответ в памяти есть, до модели не дошёл. Прибор
 *    показывает такие отдельно — для разбора навигации, а не для агента.
 *
 * АДРЕС ВОПРОСА — собеседник, тот, чья реплика вызвала признание (сейчас канал
 * один, экран). Спрашивается владелец облака, а не кто угодно: так и чужие
 * слова не уходят в чужой разговор.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - выдуманный ответ вместо «не знаю» не виден;
 *  - голое «Не знаю.» без слов предмета пробела не даёт: предмет берётся из
 *    самой фразы признания;
 *  - список оборотов незнания и список слов-связок — объявленные, не
 *    подобранные;
 *  - предмет — основы слов, а не смысл: «деревянная» и «дерева» могут дать
 *    разные основы, и тогда закрытие не находится;
 *  - пробел о собеседнике данными не закрывается: любая его фраза о себе
 *    закрывала бы всё;
 *  - пробел частичный (в памяти что-то есть) или полный — по ленте не
 *    различить; это видит отбор хода (строка «Круг:»), а не эта сборка;
 *  - лицо собеседника одно — до телеграма.
 */
object Gaps {

    /**
     * Обороты, которыми агент признаёт незнание, — в приведённом тексте
     * ([TextFold]). Объявленный список.
     */
    private val IGNORANCE = listOf(
        "не знаю", "не помню", "нет информации", "не имею информации", "нет данных",
        "нет сведений", "не располагаю", "неизвестн", "не могу сказать",
        "не могу вспомнить", "не могу оценить", "в моих записях нет",
    )

    /**
     * Слова-связки: значимыми по длине бывают, а предмета не задают. Объявленный
     * список; сравнивается по основам, как предмет.
     */
    private val FILLER: Set<String> = RiskTrigger.significantStems(
        "сегодня вчера завтра сейчас теперь тогда пока всегда никогда " +
            "просто вообще очень может можно нужно"
    )

    /** Владелец о себе: первое лицо в его реплике. */
    private val OWNER_SELF = Regex("(?<!\\p{L})(?:я|мне|меня|мной|обо мне)(?!\\p{L})")

    /** Агент о собеседнике: второе лицо во фразе «не знаю». */
    private val ABOUT_YOU = Regex("(?<!\\p{L})(?:вы|вас|вам|вами|ты|тебе|тебя|тобой)(?!\\p{L})")

    /**
     * Ход по порядку ленты: архив, затем открытая лента.
     *
     * @property conversation номер разговора (индекс архива; открытой ленте —
     *   любой, не совпадающий с архивными).
     */
    data class Turn(val question: String, val answer: String, val conversation: Int)

    enum class State { OPEN, ASKED, CLOSED }

    /**
     * Пробел.
     *
     * @property subject основы предмета; пусто у пробела о собеседнике.
     * @property aboutOwner предмет — сам собеседник.
     * @property question реплика владельца при первом признании.
     * @property admission фраза агента, которой он тогда признал незнание.
     * @property conversations разговоры, где признан.
     * @property closedBy утверждение владельца, закрывшее пробел; null — не закрыт.
     * @property missed признания уже ПОСЛЕ закрытия — реплики владельца, на
     *   которые агент ответил «не знаю», хотя ответ был сказан.
     */
    data class Gap(
        val subject: Set<String>,
        val aboutOwner: Boolean,
        val question: String,
        val admission: String,
        val conversations: Set<Int>,
        val state: State,
        val closedBy: String?,
        val missed: List<String>,
    )

    /** Фраза ответа, где агент признаёт незнание; null — такой нет. */
    fun admission(answer: String): String? =
        Sentences.split(answer).firstOrNull { s -> TextFold.fold(s).let { f -> IGNORANCE.any { it in f } } }

    /**
     * Предмет признания: основы, общие у реплики и фразы «не знаю». Пустой набор
     * при [aboutOwner] — пробел о собеседнике; null — пробела нет.
     */
    fun subjectOf(question: String, admission: String): Pair<Set<String>, Boolean>? {
        val shared = RiskTrigger.significantStems(question)
            .intersect(RiskTrigger.significantStems(admission)) - FILLER
        if (shared.isNotEmpty()) return shared to false
        val aboutOwner = OWNER_SELF.containsMatchIn(TextFold.fold(question)) &&
            ABOUT_YOU.containsMatchIn(TextFold.fold(admission))
        return if (aboutOwner) emptySet<String>() to true else null
    }

    /**
     * Текст покрывает предмет: в нём не меньше половины основ предмета. Не
     * «все»: в предмет попадают и служебные для смысла слова («сделана» в «Из
     * какого дерева сделана колодка?»), и ответ «Колодка рубанка не
     * деревянная» их не повторяет. Не «хоть одна»: «наших» в чужой фразе не
     * закрывает «наших прошлых разговоров». Объявленная доля, не подобранная.
     */
    private fun covers(text: String, subject: Set<String>): Boolean {
        if (subject.isEmpty()) return false
        val found = RiskTrigger.significantStems(text).count { it in subject }
        return found * 2 >= subject.size
    }

    private fun askedIn(answer: String, subject: Set<String>): Boolean =
        Sentences.split(answer).any { it.trimEnd().endsWith('?') && covers(it, subject) }

    private class Building(
        val subject: Set<String>, val aboutOwner: Boolean, val question: String, val admission: String,
        val conversations: MutableSet<Int>, var asked: Boolean = false,
        var closedBy: String? = null, val missed: MutableList<String> = mutableListOf(),
    )

    /** Пробелы по ходам ленты, в порядке первого признания. */
    fun of(turns: List<Turn>): List<Gap> {
        val gaps = mutableListOf<Building>()
        for (turn in turns) {
            // Сначала ход как продолжение уже признанных: закрывает ли его
            // реплика владельца, спрашивает ли агент.
            for (g in gaps) {
                if (g.closedBy == null &&
                    Sentences.split(turn.question).any { !it.trimEnd().endsWith('?') && covers(it, g.subject) }
                ) g.closedBy = turn.question
                if (!g.asked && askedIn(turn.answer, g.subject)) g.asked = true
            }
            // Потом — новое признание на этом ходе.
            if (turn.question.isBlank()) continue
            val said = admission(turn.answer) ?: continue
            val (subject, aboutOwner) = subjectOf(turn.question, said) ?: continue
            val same = gaps.firstOrNull { it.subject == subject && it.aboutOwner == aboutOwner }
            if (same == null) {
                // Спросить можно и в том же ответе: «Не знаю. А кто это?».
                gaps += Building(
                    subject, aboutOwner, turn.question, said, mutableSetOf(turn.conversation),
                    asked = askedIn(turn.answer, subject),
                )
            } else {
                same.conversations += turn.conversation
                if (same.closedBy != null) same.missed += turn.question
            }
        }
        return gaps.map {
            Gap(
                subject = it.subject, aboutOwner = it.aboutOwner, question = it.question,
                admission = it.admission, conversations = it.conversations, missed = it.missed, closedBy = it.closedBy,
                state = when {
                    it.closedBy != null -> State.CLOSED
                    it.asked -> State.ASKED
                    else -> State.OPEN
                },
            )
        }
    }

    /** Касается ли пробел реплики хода: реплика покрывает предмет ([covers]). */
    fun touches(gap: Gap, question: String): Boolean = covers(question, gap.subject)

    private const val QUOTE = 40

    private fun short(text: String): String {
        val t = text.replace('\n', ' ').trim()
        return if (t.length <= QUOTE) t else t.take(QUOTE).trimEnd() + "…"
    }

    /**
     * Строка прибора «Пробелы:». Печатается всегда: молчащая сборка неотличима
     * от сломанной.
     *
     * @param question реплика текущего хода — касается ли её открытый пробел;
     *   null — хода не было.
     */
    fun meterLine(gaps: List<Gap>, question: String?): String {
        if (gaps.isEmpty()) return "Пробелы: признанных «не знаю» нет"
        val open = gaps.filter { it.state == State.OPEN }
        val parts = mutableListOf(
            "открыто ${open.size}, спрошено ${gaps.count { it.state == State.ASKED }}, " +
                "закрыто ${gaps.count { it.state == State.CLOSED }}",
        )
        open.lastOrNull()?.let { g ->
            parts += "последний открытый: «${short(g.question)}»" +
                (if (g.aboutOwner) " (о собеседнике)" else "") +
                (if (g.conversations.size > 1) " — в ${g.conversations.size} разговорах" else "")
        }
        if (question != null) {
            val touching = open.count { touches(it, question) }
            parts += if (touching == 0) "к этому ходу: не по теме" else "к этому ходу: по теме $touching"
        }
        gaps.filter { it.missed.isNotEmpty() }.forEach { g ->
            parts += "не дошло: «${short(g.missed.last())}» — ответ был «${short(g.closedBy!!)}»"
        }
        return "Пробелы: " + parts.joinToString(" · ")
    }
}
