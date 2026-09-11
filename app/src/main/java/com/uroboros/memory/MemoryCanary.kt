package com.uroboros.memory

/**
 * Canary — снимок состояния памяти и способ сравнить два снимка.
 *
 * Предмет класса — состояние памяти целиком, а сравнение "до и после" это один из
 * способов его читать, а не назначение класса. Первым поводом был sweep, и compare()
 * до сих пор написан под него, но snapshot() и format() несут и числа, к sweep
 * отношения не имеющие — например счётчик пользы (item 3). Добавляя сюда очередное
 * число, смотрите, в какую из двух половин оно ложится: в снимок ложится всё, в
 * сравнение — только то, про что можно сказать, каким должно быть изменение.
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
    val oldestExpiredAt: Long?,
    /**
     * Ближайший ещё НЕ наступивший срок, либо null если таких нет.
     * Отвечает на вопрос "когда долг появится сам, если просто ждать".
     */
    val nextExpiryAt: Long?,
    /**
     * Сколько строк вообще без срока (expiryTime IS NULL). Такие записи не
     * попадут в sweep никогда — ожидание их не затронет.
     */
    val withoutExpiry: Int,
    /**
     * Item 3, счётчик пользы. Три числа держатся вместе и порознь мало что значат:
     * общая сумма отвечает на вопрос "механизм хоть раз сработал", число записей
     * со счётом показывает, размазан счёт по многим или собрался на одной, максимум
     * даёт верхнюю границу разброса. Ноль в сумме читается как "ни разу", а не как
     * "записи бесполезны".
     */
    val userMatchesTotal: Int,
    val withUserMatches: Int,
    val maxUserMatches: Int
) {
    /** Насколько давно истёк самый старый просроченный срок, в днях. */
    val oldestDebtDays: Long?
        get() = oldestExpiredAt?.let { (takenAt - it) / MS_IN_DAY }

    /** Через сколько дней наступит ближайшее истечение. 0 = меньше суток. */
    val daysUntilNextExpiry: Long?
        get() = nextExpiryAt?.let { (it - takenAt) / MS_IN_DAY }

    companion object {
        const val MS_IN_DAY: Long = 24L * 60 * 60 * 1000
    }
}

class MemoryCanary(private val dao: StickerDao) {

    private companion object {
        /** Ширина колонки подписей. Задана самой длинной: "След. истечение:". */
        const val LABEL_WIDTH = 18
    }

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
            oldestExpiredAt = dao.oldestExpiredAt(now),
            nextExpiryAt = dao.nextExpiryAt(now),
            withoutExpiry = dao.countWithoutExpiry(),
            userMatchesTotal = dao.sumUserMatches(),
            withUserMatches = dao.countWithUserMatches(),
            maxUserMatches = dao.maxUserMatches()
        )
    }

    /**
     * Человекочитаемый вид одного снимка.
     *
     * СЖАТО ПО ПРАВИЛУ: спокойное показание уходит в хвост соседней строки,
     * тревожное занимает свою. Долга нет — он приписан к числу записей; долг
     * появился — обе величины стоят порознь, и блок на экране становится выше
     * ровно тогда, когда есть на что смотреть. Высота здесь сама по себе
     * признак, и ради неё сжатие и сделано.
     *
     * НОЛЬ НИКОГДА НЕ ИСЧЕЗАЕТ ВМЕСТЕ СО СТРОКОЙ. Пустые слои свёрнуты в
     * "прочие 0", а не выброшены: "слоя нет в строке" и "в слое ноль записей"
     * должны остаться различимыми, иначе показание подменяется молчанием.
     *
     * СУММА ПО СЛОЯМ СВЕРЯЕТСЯ С ЧИСЛОМ ЗАПИСЕЙ. Пока слои печатались
     * столбиком, расхождение надо было заметить, сложив шесть чисел в уме, —
     * то есть практически никогда. Ловится этим ровно один промах: запись с
     * чужим значением слоя не попадает ни в один счётчик и пропадает из
     * распределения, оставаясь в базе.
     */
    fun format(s: MemorySnapshot): String = buildString {
        appendLine("СНИМОК ПАМЯТИ")
        if (s.expired == 0) {
            appendLine("${label("Всего записей:")}${s.total} · просрочено 0, долга нет")
        } else {
            appendLine("${label("Всего записей:")}${s.total}")
            appendLine("${label("Просрочено:")}${s.expired}")
            val debt = s.oldestDebtDays
            appendLine(
                if (debt == null) "${label("Долг копится:")}срок не записан"
                else "${label("Долг копится:")}$debt дн."
            )
        }
        appendLine("${label("На проверке:")}${s.pendingReview}")
        // Без этой строки "просрочено 0" не отличить от "сроков нет вовсе".
        val untilNext = s.daysUntilNextExpiry
        appendLine(
            when {
                untilNext == null -> "${label("След. истечение:")}нет запланированных"
                untilNext == 0L -> "${label("След. истечение:")}менее суток"
                else -> "${label("След. истечение:")}через $untilNext дн."
            }
        )
        appendLine("${label("Без срока:")}${s.withoutExpiry} из ${s.total}")
        appendLine(
            if (s.userMatchesTotal == 0) "${label("Отметок пользы:")}ни одной"
            else "${label("Отметок пользы:")}${s.userMatchesTotal} у ${s.withUserMatches} записей, макс. ${s.maxUserMatches}"
        )
        appendLine("${label("Слои:")}${layerLine(s)}")
    }.trimEnd()

    /** Подпись, добитая до общей колонки. Шире самой длинной из подписей выше. */
    private fun label(text: String): String = text.padEnd(LABEL_WIDTH)

    /**
     * Слои одной строкой: непустые по убыванию, пустые числом.
     *
     * Расхождение суммы с общим числом записей приписывается прямо сюда, а не
     * печатается отдельной строкой: оно относится к этому счёту и без него
     * читается как загадка.
     */
    private fun layerLine(s: MemorySnapshot): String {
        val filled = s.byLayer.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key.name} ${it.value}" }
        val empty = s.byLayer.count { it.value == 0 }
        val counted = s.byLayer.values.sum()
        val mismatch =
            if (counted != s.total) "  !!! сумма $counted ≠ всего ${s.total}" else ""
        val body = when {
            filled.isEmpty() && empty == 0 -> "слоёв не сосчитано"
            filled.isEmpty() -> "все $empty по нулю"
            empty == 0 -> filled
            else -> "$filled · прочие 0"
        }
        return body + mismatch
    }

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

        // Без восклицательных знаков: рост "без срока" — это не нарушение.
        // Запись, дошедшая до конца спектра, законно остаётся без следующего
        // срока. Строка нужна, чтобы этот переход было видно, а не чтобы ловить.
        appendLine("Без срока:      ${before.withoutExpiry} → ${after.withoutExpiry}")

        // Отметки пользы — обычная строка изменения, БЕЗ восклицательных знаков,
        // хотя sweep их и не трогает. Между двумя снимками пользователь мог задать
        // вопрос, и тогда счётчик вырастет совершенно законно. Знак нарушения,
        // способный сработать на правильном поведении, обесценивает все остальные
        // знаки в этом отчёте.
        appendLine("Отметок пользы: ${before.userMatchesTotal} → ${after.userMatchesTotal}")

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
