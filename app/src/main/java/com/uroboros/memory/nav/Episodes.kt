package com.uroboros.memory.nav

/**
 * Эпизод — один разговор. Номер не хранится, считается; одно правило для
 * памяти и для ленты.
 *
 * Новый эпизод открывают:
 *  - тишина дольше [EPISODE_SILENCE_MS];
 *  - закрытие ленты («Начать заново», «Лента заполнена» — архивация).
 *
 * Времена — абсолютные моменты (миллисекунды эпохи), поэтому смена пояса
 * эпизод не задевает: он считается по длительности пауз.
 *
 * ОТРИЦАТЕЛЬНАЯ ПАУЗА (часы перевели назад) эпизод не открывает и не прячет:
 * такой ход остаётся в текущем разговоре, а следующая за ним пауза считается
 * уже от него.
 *
 * Чего не умеет: время старого хода, выведенное «не позже закрытия»
 * (Coordinates.TimeSource.NOT_LATER_THAN_CLOSE), одно у всех ходов разговора —
 * внутри такого разговора пауз не видно, делит его только закрытие.
 */
object Episodes {

    /**
     * Час. Объявленное число, не подобранное. Точка отсчёта —
     * initiative.InitiativeDecision.SILENCE_MS (тоже час), но число СВОЁ:
     * правка порога инициативы не должна молча сдвигать эпизоды.
     */
    const val EPISODE_SILENCE_MS = 60L * 60 * 1000

    /**
     * Открывает ли [at] новый эпизод после [prevAt].
     *
     * @param closedBetween между ними лента закрывалась.
     */
    fun startsNew(prevAt: Long?, at: Long?, closedBetween: Boolean): Boolean {
        if (closedBetween) return true
        if (prevAt == null || at == null) return false
        val pause = at - prevAt
        return pause > EPISODE_SILENCE_MS
    }

    /** Точка последовательности: время и признак «перед ней лента закрывалась». */
    data class Point(val at: Long?, val closedBefore: Boolean = false)

    /**
     * Номера эпизодов для последовательности в её порядке (порядок ленты, а не
     * времени). Первый — 0.
     */
    fun number(points: List<Point>): List<Int> {
        val out = ArrayList<Int>(points.size)
        var episode = 0
        var prevAt: Long? = null
        for ((i, p) in points.withIndex()) {
            if (i > 0 && startsNew(prevAt, p.at, p.closedBefore)) episode++
            out += episode
            if (p.at != null) prevAt = p.at
        }
        return out
    }

    /**
     * Часы эпизодов по времени: все известные моменты (ходы ленты и архива,
     * записи памяти) и моменты закрытия лент. Отвечает, в каком эпизоде лежит
     * момент, — для записи памяти, у которой порядка ленты нет.
     *
     * Моменты сортируются по времени, поэтому отрицательной паузы здесь не
     * бывает: ход с переведёнными назад часами встаёт туда, куда указывает его
     * время.
     */
    class Clock(moments: Collection<Long>, closures: Collection<Long>) {
        private val starts: List<Long>

        init {
            val sortedClosures = closures.sorted()
            val sorted = moments.sorted()
            val out = ArrayList<Long>()
            var prev: Long? = null
            var closureAt = 0
            for (t in sorted) {
                var closed = false
                while (closureAt < sortedClosures.size && sortedClosures[closureAt] < t) {
                    if (prev != null && sortedClosures[closureAt] >= prev) closed = true
                    closureAt++
                }
                if (prev != null && startsNew(prev, t, closed)) out += t
                prev = t
            }
            starts = out
        }

        /** Номер эпизода момента [at]: сколько начал эпизодов не позже него. */
        fun episodeAt(at: Long): Int {
            var lo = 0
            var hi = starts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= at) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /**
     * Соседи хода [index]: ход до и после в том же эпизоде, или null. Соседи
     * ищутся по ленте и архиву, а не по памяти: ответов агента в памяти нет.
     */
    fun neighbours(episodes: List<Int>, index: Int): Pair<Int?, Int?> {
        val e = episodes[index]
        val before = (index - 1).takeIf { it >= 0 && episodes[it] == e }
        val after = (index + 1).takeIf { it < episodes.size && episodes[it] == e }
        return before to after
    }
}
