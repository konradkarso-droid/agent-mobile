package com.uroboros.memory

import android.content.Context

/**
 * Укладка показаний [ReviewWitness] в SharedPreferences.
 *
 * Android-половина прибора, вынесенная сюда, чтобы сам прибор оставался чистым
 * и запускался в юнит-тесте.
 *
 * ПОЧЕМУ НЕ ТАБЛИЦА В БАЗЕ. Прибор считает то, что происходит на пути записи в
 * базу, и класть его показания туда же значило бы менять измеряемое число
 * измерением: снимок памяти считает строки, и строка прибора попала бы в этот
 * счёт. Плюс миграция схемы ради трёх целых.
 *
 * ПОЧЕМУ ОТДЕЛЬНЫЙ ФАЙЛ НАСТРОЕК, а не общий с флагами приложения. Флаги
 * разовых починок отвечают на вопрос "делали или нет" и стираются вместе с
 * переустановкой осмысленно. Показания прибора — накопленный за недели материал,
 * и их обнуление заодно с флагом было бы потерей, которую никто не заметил бы:
 * ноль в отчёте выглядит как "дел не было".
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - очистка данных приложения стирает показания вместе со всем остальным.
 *    Именно поэтому прибор печатает момент начала счёта: обнулившийся счёт
 *    отличается от честного нуля только этой строкой;
 *  - запись синхронная (commit, не apply). Это осознанная цена: apply не
 *    возвращает исход, и отказ укладки стал бы ненаблюдаем, а прибор,
 *    молчащий о собственной поломке, не прибор. Область, в которой цена
 *    допустима: сохранений единицы за прогон. Если сохранения станут частыми
 *    (например, память начнёт писать сама), путь отступления — apply плюс
 *    отдельный признак того, что исход записи больше не проверяется.
 */
class PrefsReviewWitnessStore(context: Context) : ReviewWitnessStore {

    // applicationContext: хранилище живёт столько же, сколько процесс, и не
    // должно удерживать экран.
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val persistent: Boolean = true

    /**
     * Показания прошлых запусков.
     *
     * Признак "счёт уже вёлся" — момент начала, а не сами числа: нулевые
     * проверки при известном начале означают "считаем со вторника, дел не было",
     * и это законное состояние, которое нельзя путать с "счёт не начинался".
     */
    override fun load(): ReviewCounts? {
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt == 0L) return null
        return ReviewCounts(
            checks = prefs.getLong(KEY_CHECKS, 0L),
            disputes = prefs.getLong(KEY_DISPUTES, 0L),
            failures = prefs.getLong(KEY_FAILURES, 0L),
            startedAt = startedAt
        )
    }

    /**
     * Уложить показания целиком.
     *
     * Четыре значения пишутся одной правкой: разошедшись, они дают показание,
     * которого не было ни в один момент времени — например проверки от нового
     * запуска при моменте начала от старого.
     *
     * Отказ записи выходит наружу исключением. Ловит его прибор, и он же
     * сообщает о нём в отчёте; сохранение записи в память из-за этого падать не
     * должно.
     */
    override fun save(counts: ReviewCounts) {
        val ok = prefs.edit()
            .putLong(KEY_CHECKS, counts.checks)
            .putLong(KEY_DISPUTES, counts.disputes)
            .putLong(KEY_FAILURES, counts.failures)
            .putLong(KEY_STARTED_AT, counts.startedAt)
            .commit()
        if (!ok) throw IllegalStateException("Показания прибора очереди не записаны")
    }

    private companion object {
        const val PREFS_NAME = "review_witness"
        const val KEY_CHECKS = "checks"
        const val KEY_DISPUTES = "disputes"
        const val KEY_FAILURES = "failures"
        const val KEY_STARTED_AT = "started_at"
    }
}
