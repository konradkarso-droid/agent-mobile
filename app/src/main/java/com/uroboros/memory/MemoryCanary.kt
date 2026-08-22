package com.uroboros.memory

/**
 * Canary (2026-08-22) — снимок состояния памяти ДО и ПОСЛЕ первого реального
 * запуска sweep'а.
 *
 * Зачем вообще: заимствовано из чужого опыта (canary-скрипт praxis-open-source).
 * Там перед миграцией, менявшей поведение, снимали baseline-цифры до и после и
 * сравнивали — потому что "не упало" не означает "работает правильно". Тихий
 * дрейф поведения ловится только сравнением чисел.
 *
 * Жёсткие свойства этого класса, сознательно:
 *
 * 1. ТОЛЬКО ЧТЕНИЕ. Ни одного UPDATE/INSERT/DELETE. У canary нет никаких
 *    полномочий: он ничего не чинит, ни на что не влияет, его вывод ничего не
 *    запускает. Наблюдатель, а не участник.
 *
 * 2. Никакой Android-зависимости (см. ARCHITECTURE.md §1, чистота L1/L3):
 *    ни Log, ни Context. Вместо логирования — возврат готовой строки, которую
 *    показывает вызывающий слой. Благодаря этому класс запускается в обычном
 *    JVM-юнит-тесте на CI с подставным DAO.
 *
 * 3. Только COUNT/MIN, без загрузки строк в память. Снимок не должен сам
 *    создавать ту нагрузку, ради контроля которой снимается.
 *
 * 4. Время передаётся снаружи (now), а не берётся из System внутри — иначе
 *    класс невозможно детерминированно протестировать.
 */

/**
 * Одно измерение. Иммутабельный: снимок "до" не должен уметь измениться после
 * того, как sweep отработал — иначе сравнивать будет не с чем.
 */
data class MemorySnapshot(
    /** Момент снятия снимка (мс). */
    val takenAt: Long,
    /** Всего строк в таблице. Sweep НИЧЕГО не удаляет — это число обязано совпасть. */
    val total: Int,
    /** Просроченных на момент takenAt. Главная метрика: размер накопленного долга. */
    val expired: Int,
    /** Распределение по слоям спектра. Показывает, КУДА уехали записи. */
    val byLayer: Map<Layer, Int>,
    /** Спорные записи. Sweep их не трогает — число обязано совпасть. */
    val pendingReview: Int,
    /**
     * Самый старый истёкший expiryTime, либо null если просроченных нет.
     * Показывает, насколько давно копится долг.
     */
    val oldestExpiredAt: Long?
) {
    /** Насколько давно истёк самый старый просроченный срок, в днях. */
    val oldestDebtDays: Long?
        get() = oldestExpiredAt?.let { (takenAt - it) / MS_IN_DAY }

    companion object {
        const val MS_IN_DAY: Long = 24L * 60 * 60 * 1000
    }
}

class MemoryCanary(private val dao: StickerDao) {

    /**
     * Снять снимок. Только чтение.
     *
     * now передаётся параметром: тот же момент времени используется и для
     * countExpired, и для oldestExpiredAt, и как takenAt — иначе три запроса
     * могли бы отвечать на слегка разные вопросы.
     */
    suspend fun snapshot(now: Long = System.currentTimeMillis()): MemorySnapshot {
        val byLayer = LinkedHashMap<Layer, Int>()
        for (layer in Layer.values()) {
            byLayer[layer] = dao.countInLayer(layer.name)
        }
        return MemorySnapshot(
            takenAt = now,
            total = dao.count(),
            expired = dao.countExpired(now),
            byLayer = byLayer,
            pendingReview = dao.countPendingReview(),
            oldestExpiredAt = dao.oldestExpiredAt(now)
        )
    }

    /** Человекочитаемый вид одного снимка. */
    fun format(s: MemorySnapshot): String = buildString {
        appendLine("СНИМОК ПАМЯТИ")
        appendLine("Всего записей:      ${s.total}")
        appendLine("Просрочено:         ${s.expired}")
        appendLine("На проверке:        ${s.pendingReview}")
        val debt = s.oldestDebtDays
        appendLine(
            if (debt == null) "Долг копится:       нет просроченных"
            else "Долг копится:       $debt дн."
        )
        appendLine("По слоям:")
        for ((layer, count) in s.byLayer) {
            appendLine("  ${layer.name.padEnd(8)} $count")
        }
    }.trimEnd()

    /**
     * Сравнение "до/после". Это и есть то, ради чего canary существует.
     *
     * Две строки помечены как нарушения (!), а не просто как изменения: sweep
     * по построению не удаляет записи и не трогает reviewPending. Если эти
     * числа разошлись — sweep сделал то, чего не должен был, и это надо увидеть
     * сразу, а не выводить из распределения по слоям.
     */
    fun compare(before: MemorySnapshot, after: MemorySnapshot): String = buildString {
        appendLine("SWEEP: ДО → ПОСЛЕ")
        appendLine()

        val totalMark = if (before.total != after.total) "  !!! записи пропали" else ""
        appendLine("Всего записей:  ${before.total} → ${after.total}$totalMark")

        val reviewMark = if (before.pendingReview != after.pendingReview) "  !!! sweep не должен это трогать" else ""
        appendLine("На проверке:    ${before.pendingReview} → ${after.pendingReview}$reviewMark")

        appendLine("Просрочено:     ${before.expired} → ${after.expired}${expiredComment(before, after)}")
        appendLine()
        appendLine("По слоям:")
        for (layer in Layer.values()) {
            val b = before.byLayer[layer] ?: 0
            val a = after.byLayer[layer] ?: 0
            val delta = a - b
            val arrow = when {
                delta > 0 -> "  (+$delta)"
                delta < 0 -> "  ($delta)"
                else -> ""
            }
            appendLine("  ${layer.name.padEnd(8)} $b → $a$arrow")
        }
    }.trimEnd()

    private fun expiredComment(before: MemorySnapshot, after: MemorySnapshot): String = when {
        before.expired == 0 -> "  (долга не было — прогон ничего не проверяет)"
        after.expired == 0 -> "  (долг разобран полностью)"
        after.expired < before.expired -> "  (долг разобран частично)"
        after.expired > before.expired -> "  !!! долг вырос"
        else -> "  !!! долг не сдвинулся"
    }
}
