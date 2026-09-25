package com.uroboros.memory.dream

import com.uroboros.memory.Prism
import com.uroboros.memory.UnpromptedTouch

/**
 * Лидер по касаниям без подсказки агента (Sticker.userMatchUnpromptedCount) и
 * условие, при котором запись становится кандидатом на строку «о себе».
 * Счёт чистый: ни базы, ни Android — затем и вынесен, чтобы правило
 * закреплялось тестами. Читает базу [UnpromptedLeaderGauge], пишет лидера в
 * ночь [DreamRunner.run].
 *
 * ПРАВИЛО.
 *  - Считаются только записи не отвергнутые, не на проверке и без метки
 *    [Prism.IDENTITY_TAG]: отвергнутая и ждущая решения скрыты от всего
 *    остального, а запись с меткой уже лежит в RED — предлагать её в «о себе»
 *    незачем.
 *  - Лидер — запись с наибольшим счётом, если у неё не меньше [MIN_TOUCHES]
 *    касаний и счёт не меньше чем в [MIN_LEAD] раза больше счёта второй.
 *    Второй нет или у неё ноль — обгон выполнен.
 *  - Ничья на первом месте — лидера нет: из двух равных выбрать не из чего.
 *  - Кандидат — лидер, который был таким же лидером (записанным в ночь,
 *    [DreamNight.unpromptedLeaderId]) не меньше чем в [MIN_NIGHTS] из
 *    последних [NIGHTS_WINDOW] ночей.
 *
 * ПОЧЕМУ НОЧИ, А НЕ ОДИН ЗАМЕР. Счётчики только растут, и прошлого лидера из
 * них не восстановить. Лидер одного дня может быть темой одного разговора;
 * лидер нескольких ночей подряд — то, к чему владелец возвращается. Поэтому
 * лидер записывается в саму ночь, а условие смотрит на их ряд.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - касание — совпадение слов, а не смысл (см. поле в Sticker.kt): лидер
 *    говорит о словах, к которым возвращался владелец, а не о том, что ему
 *    важно;
 *  - две ночи за сутки (кнопка и сон сам) — это две ночи ряда: ряд меряет
 *    проходы, а не календарь;
 *  - у ночей до записи лидера в ряду null: они не совпадают ни с кем, и
 *    первые ночи после обновления кандидата не дадут.
 */
object UnpromptedLeader {

    /**
     * Сколько касаний без подсказки нужно лидеру. Объявленное владельцем
     * число, не подобранное: касание — одно совпадение слов, и меньше пяти
     * легко набирается одним разговором. Перепроверять по строке «Нажитое о
     * себе: … касаний K из 5»: если лидеры держатся ночами, не дотягивая до
     * пяти, — порог высок; если пять набирает случайная тема — низок.
     */
    const val MIN_TOUCHES = 5

    /**
     * Во сколько раз лидер обгоняет вторую запись. Объявленное владельцем
     * число, не подобранное: без обгона лидер — одна из нескольких равных тем,
     * и «чаще всего говорили о …» было бы неправдой. Перепроверять по «обгон
     * ×R из 1.5» на том же приборе.
     */
    const val MIN_LEAD = 1.5

    /** Из скольких последних ночей смотрится ряд. Объявлено владельцем. */
    const val NIGHTS_WINDOW = 5

    /** В скольких ночах ряда запись должна быть лидером. Объявлено владельцем. */
    const val MIN_NIGHTS = 3

    /**
     * Первое место и чем оно кончилось.
     *
     * @property topId номер записи с наибольшим счётом; при ничьей — меньший
     *   номер из равных, только чтобы строка прибора была повторяемой.
     * @property topCount её счёт; 0 — касаний без подсказки нет ни у кого.
     * @property secondCount счёт второй записи; 0 — второй нет.
     * @property tiedAtTop сколько записей делят первое место (1 — ничьей нет).
     * @property leaderId [topId], если условие лидера выполнено, иначе null.
     */
    data class Standing(
        val topId: Long?,
        val topCount: Int,
        val secondCount: Int,
        val tiedAtTop: Int,
        val leaderId: Long?,
    ) {
        /** Во сколько раз первое место обгоняет второе; null — второй нет. */
        val lead: Double? get() = if (secondCount > 0) topCount.toDouble() / secondCount else null
    }

    /** Учитывается ли запись вообще (см. «ПРАВИЛО» выше). */
    fun eligible(touch: UnpromptedTouch): Boolean =
        touch.count > 0 &&
            touch.rejectedAt == null &&
            !touch.reviewPending &&
            touch.tag != Prism.IDENTITY_TAG

    /** Первое место по касаниям без подсказки и выполнено ли условие лидера. */
    fun standing(touches: List<UnpromptedTouch>): Standing {
        val ranked = touches.filter(::eligible).sortedWith(compareBy({ -it.count }, { it.id }))
        val top = ranked.firstOrNull() ?: return Standing(null, 0, 0, 0, null)
        val tied = ranked.count { it.count == top.count }
        val second = ranked.getOrNull(1)?.count ?: 0
        val passes = tied == 1 &&
            top.count >= MIN_TOUCHES &&
            (second == 0 || top.count >= MIN_LEAD * second)
        return Standing(top.id, top.count, second, tied, if (passes) top.id else null)
    }

    /** Номер лидера, проходящего условие, или null. */
    fun leaderOf(touches: List<UnpromptedTouch>): Long? = standing(touches).leaderId

    /**
     * В скольких из ночей [nights] лидером был [id]. null в ночи не совпадает
     * ни с чем — ни с другим null, ни с номером.
     */
    fun nightsLed(id: Long?, nights: List<Long?>): Int =
        if (id == null) 0 else nights.count { it == id }

    /**
     * Кандидат ли лидер [leaderId] на строку «о себе».
     *
     * @param lastNights [DreamNight.unpromptedLeaderId] последних ночей, от
     *   новых к старым. Берутся первые [NIGHTS_WINDOW]; если ночей меньше,
     *   считается по тем, что есть, но порог [MIN_NIGHTS] тот же.
     */
    fun isCandidate(leaderId: Long?, lastNights: List<Long?>): Boolean =
        leaderId != null && nightsLed(leaderId, lastNights.take(NIGHTS_WINDOW)) >= MIN_NIGHTS

    /**
     * Сколько знаков начала записи показывать в строке прибора. Объявленное
     * число, не подобранное: чтобы узнать запись, а не прочесть её.
     */
    const val PREVIEW_CHARS = 40

    /**
     * Строка прибора «Нажитое о себе:». Печатается всегда: молчание называет
     * причину, чтобы «кандидата нет» отличалось от «не считалось».
     *
     * «Лидер» здесь — первое место, даже если условие не выполнено: прибор
     * показывает, насколько до условия далеко.
     *
     * @param content текст записи первого места; null — не прочитан.
     * @param lastNights как у [isCandidate].
     */
    fun meter(standing: Standing, content: String?, lastNights: List<Long?>): String {
        val topId = standing.topId
            ?: return "Нажитое о себе: молчу — касаний без подсказки нет"
        val nights = lastNights.take(NIGHTS_WINDOW)
        val window = nights.size
        if (standing.tiedAtTop > 1) {
            return "Нажитое о себе: молчу — ничья на первом месте: у ${standing.tiedAtTop} записей " +
                "касаний по ${standing.topCount} (из последних ночей $window)"
        }
        val led = nightsLed(topId, nights)
        val text = content?.let { preview(it) } ?: "текст не прочитан"
        val lead = standing.lead
        return if (isCandidate(standing.leaderId, nights)) {
            val leadText = lead?.let { "обгон ×${fmt(it)}" } ?: "второго нет"
            "Нажитое о себе: кандидат №$topId «$text» — ${standing.topCount} касаний, " +
                "$leadText, ночей $led из $window"
        } else {
            val leadText = lead?.let { "обгон ×${fmt(it)} из $MIN_LEAD" } ?: "обгон — второго нет"
            "Нажитое о себе: молчу — лидер №$topId «$text», " +
                "касаний ${standing.topCount} из $MIN_TOUCHES, $leadText, " +
                "ночей $led из $MIN_NIGHTS (из последних $window)"
        }
    }

    private fun preview(content: String): String {
        val flat = content.replace("\n", " ")
        return if (flat.length > PREVIEW_CHARS) flat.take(PREVIEW_CHARS) + "…" else flat
    }

    /** Одна цифра после точки, точкой — как «1.5» в той же строке. */
    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)
}
