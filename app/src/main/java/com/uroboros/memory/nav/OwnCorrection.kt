package com.uroboros.memory.nav

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker

/**
 * Поправка о себе: собеседник сказал о себе то, что спорит с его же прежней
 * записью о себе, — прежняя замолкает. Чистая логика: решает, какие записи
 * сняты; снимает HourglassMemory (путь RejectPath.CORRECTED).
 *
 * ПОЧЕМУ БЕЗ КНОПКИ И БЕЗ СУДЬИ. О себе прав тот, о ком факт, и он уже решил —
 * сказав. «Я не работаю по субботам» после «Я работаю по субботам» — это не
 * спор двух источников, который надо рассудить, а перемена (или поправка) от
 * единственного, кто знает. Решает человек, словами; код только узнаёт, что
 * он это сделал. Поздняя фраза сильнее ранней.
 *
 * УСЛОВИЯ — все сразу ([superseded]):
 *  - обе записи — сказанное одним собеседником (Coordinates.speakerOf);
 *  - обе — о нём самом ([aboutSelf]): есть первое лицо, нет обращения к
 *    агенту, не только вопросы и просьбы;
 *  - правило противоречия видит спор (RiskTrigger.contradicts: отрицание,
 *    другое число, «не Y, а Z», замена слова);
 *  - прежняя не скрыта и не отвергнута.
 *
 * ЧТО НЕ ПОДПАДАЕТ: факты о мире («Меркурий светит…») — там голос не у
 * собеседника, их рассуждает судья с человеком; слова агента; слова одного
 * собеседника о другом.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - поправку без отрицания и без числа («Теперь я в Самаре») правило не
 *    видит — обе записи остаются, как и без этого механизма;
 *  - ложный спор снимает верную запись: «Я не стоматолог, я просто был у
 *    стоматолога» снимет «Я был у стоматолога». Тот же факт обычно повторён в
 *    новой записи, поэтому вред мал;
 *  - снятая запись не возвращается сама: вернуть смысл можно только новой
 *    фразой, которая снимет поправку;
 *  - «о себе» — по форме (лицо), со всеми промахами PersonForm.
 */
object OwnCorrection {

    /** Запись — слова собеседника о себе (описание объекта, «УСЛОВИЯ»). */
    fun aboutSelf(text: String): Boolean {
        if (RiskTrigger.assertsNothing(text)) return false
        val form = PersonForm.of(text)
        return form.aboutSpeaker && !form.aboutAddressee
    }

    /**
     * Какие записи из [pool] снимает новая запись [candidate]. Пусто — нечего
     * снимать (в том числе когда кандидат не о себе).
     */
    fun superseded(candidate: Sticker, pool: List<Sticker>): List<Sticker> {
        val speaker = Coordinates.speakerOf(candidate.source) ?: return emptyList()
        if (!aboutSelf(candidate.content)) return emptyList()
        return pool.filter { old ->
            old.id != candidate.id &&
                Coordinates.speakerOf(old.source) == speaker &&
                !old.reviewPending && old.rejectedAt == null &&
                aboutSelf(old.content) &&
                RiskTrigger.contradicts(old.content, candidate.content)
        }
    }

    /** Строка прибора о последней поправке; [corrected] — тексты снятых записей. */
    fun meter(newText: String, corrected: List<String>): String =
        if (corrected.isEmpty()) "Поправка: последняя запись ничего не сняла"
        else "Поправка: «${short(newText)}» сняла " +
            corrected.joinToString(", ") { "«${short(it)}»" }

    private fun short(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= SHORT_CHARS) flat else flat.take(SHORT_CHARS).trimEnd() + "…"
    }

    /** Сколько знаков записи показывать в строке прибора. Объявленное число. */
    const val SHORT_CHARS = 60
}
