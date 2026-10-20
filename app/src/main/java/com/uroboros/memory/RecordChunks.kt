package com.uroboros.memory

import java.time.Instant
import java.time.ZoneId

/**
 * Какие строки записей обсчитывать ночью заранее — для склейки готовых
 * записей (что это и чего склейка не умеет — `record_splice.h` в нативной
 * части).
 *
 * КАК СТРОКА ДОЛЖНА СОВПАСТЬ С ХОДОМ. Кусок берётся в ход, только если текст
 * строки совпал знак в знак, подпись времени тоже. Подпись считается по
 * календарным дням ([ProvenanceLabels.ageForModel]), поэтому у записи, сделанной
 * до сегодняшнего дня, она весь день одна. Ночью строка собирается тем же
 * правилом ([ProvenanceLabels.line]), что и в ходе, к моменту сборки — и
 * годится на весь наступающий день. Записи сегодняшнего дня не собираются:
 * их подпись («меньше часа назад», «сегодня утром») меняется в течение дня.
 *
 * ОТКУДА СТРОКИ. Ход запоминает части строк, ушедших модели ([Candidate]):
 * кто сказал, что, когда сделана запись. Ночь берёт чаще бравшиеся.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - строка ночи всегда с «в прошлом разговоре». Если наутро разговор
 *    продолжится тем же эпизодом, у старой записи этой приставки не будет, и
 *    кусок не найдётся — запись посчитается целиком;
 *  - своя речь агента (ProvenanceLabels.ownSpeechForModel) не собирается: за
 *    ней стоит поправка на дрейф показаний, своя на каждый ход;
 *  - строки только собеседника-владельца ([OWNER]): других собеседников пока нет.
 */
object RecordChunks {

    /** Чей разговор — часть отпечатка кусков. Другие собеседники получат свои куски. */
    const val OWNER = "владелец"

    /**
     * Строка записи, ушедшая модели: части без времени ([ProvenanceLabels.Parts])
     * и время записи; [uses] — сколько ходов её брали, [lastUsedAt] — когда в
     * последний раз.
     */
    data class Candidate(
        val who: String,
        val body: String,
        val createdAt: Long,
        val uses: Int = 1,
        val lastUsedAt: Long = 0L,
    ) {
        /** Одна запись — один кандидат, как бы её ни брали. */
        val key: String get() = "$who\u0000$body\u0000$createdAt"
    }

    /** Кандидат из частей строки хода; null — у строки нет частей (источник не записан). */
    fun candidate(parts: ProvenanceLabels.Parts?, createdAt: Long): Candidate? =
        parts?.let { Candidate(it.who, it.body, createdAt) }

    /**
     * Добавить строки хода [used] к запомненным [known]: у известных растёт
     * счёт, новые встают с одним. Держится не больше [MAX_CANDIDATES] — давно
     * не бравшиеся уходят первыми.
     */
    fun remember(known: List<Candidate>, used: List<Candidate>, now: Long): List<Candidate> {
        val byKey = LinkedHashMap<String, Candidate>()
        known.forEach { byKey[it.key] = it }
        used.distinctBy { it.key }.forEach { c ->
            val was = byKey[c.key]
            byKey[c.key] = if (was == null) c.copy(uses = 1, lastUsedAt = now) else was.copy(uses = was.uses + 1, lastUsedAt = now)
        }
        return byKey.values.sortedByDescending { it.lastUsedAt }.take(MAX_CANDIDATES)
    }

    /**
     * Строки на ночь к моменту [now]: записи до сегодняшнего дня (по поясу
     * [zone]), чаще бравшиеся — первыми, не больше [limit].
     */
    fun linesFor(candidates: List<Candidate>, now: Long, zone: ZoneId = ZoneId.systemDefault(), limit: Int = NIGHT_LINES): List<String> {
        val dayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return candidates
            .filter { it.createdAt < dayStart }
            .sortedWith(compareByDescending<Candidate> { it.uses }.thenByDescending { it.lastUsedAt })
            .take(limit)
            .map { ProvenanceLabels.line(ProvenanceLabels.Parts(it.who, it.body), ProvenanceLabels.pastEpisodeTime(it.createdAt, now, zone)) }
            .distinct()
    }

    /**
     * Сколько запомненных строк держать. Объявленное число, не подобранное:
     * с запасом против строк на ночь ([NIGHT_LINES]).
     */
    const val MAX_CANDIDATES = 400

    /**
     * Сколько строк обсчитывать за ночь. Объявленное число, не подобранное:
     * строка ~30 токенов, итого порядка двух тысяч токенов — минуты счёта на
     * телефоне, и папка кусков остаётся под своим потолком.
     */
    const val NIGHT_LINES = 60

    /** Строк в одном вызове движка: между порциями сверка со сторожем. Объявленное число. */
    const val BATCH = 4
}
