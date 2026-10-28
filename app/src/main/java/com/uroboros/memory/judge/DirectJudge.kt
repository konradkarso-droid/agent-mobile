package com.uroboros.memory.judge

import kotlin.math.exp
import kotlin.math.max

/**
 * Прямой судья — сито перед печатным судьёй ([MemoryJudge]): отношение двух
 * записей читается из внутреннего состояния модели, без инструкции судьи и без
 * печати ответа. Чистая логика: ни базы, ни модели, ни Android.
 *
 * ЗАЧЕМ. Печатный судья на каждую пару читает свою инструкцию и обе записи в
 * двух порядках и печатает цифру. Прямой читает только записи: модель
 * обсчитывает «Запись А: … Запись Б: …», с одного её слоя снимается среднее
 * состояние по токенам записи Б, и выученное направление ([DirectJudgeDirections])
 * переводит его в долю «спорят». Это в несколько раз меньше обсчёта на пару.
 *
 * СИТО, А НЕ СУДЬЯ. Пара, которую прямое чтение уверенно называет «не спорят»
 * в обоих порядках, печатному судье не идёт. Всё остальное идёт ему как раньше.
 * Вердикт «спорят» прямое чтение не выносит никогда: его ложных «спорят» на
 * живой памяти больше, чем у печатного, и человеку пришлось бы разбирать их.
 *
 * ПОДАЧА — ЧАСТЬ МЕХАНИЗМА. Направление выучено на тексте ровно такой формы:
 * пустая системная часть ([WALL]), «Запись А: …\nЗапись Б:» ([head]) и запись Б
 * с пробелом впереди ([tail]). Другая подача — другое состояние, и направление
 * к нему не относится. Менять форму можно только вместе с новым направлением.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - его доля — не вероятность правды, а положение на выученной оси; уверенность
 *    ничего не говорит о правоте;
 *  - учено на сочинённых парах (утверждения и реплики), а не на памяти
 *    владельца: обмен именами «я — Иван, а ты?» и дважды записанную одну запись
 *    оно склонно называть спором — такие пары просто уходят печатному судье;
 *  - направление верно только для той модели, накладки и слоя, на которых снято.
 *    На чужой модели число выходит ровное и неверное. Ловит это эталон
 *    ([etalonRefusal]) в начале каждого прогона, а не само число;
 *  - спор, сказанный без общих слов, до сита не доходит: пары отбирает
 *    [JudgeSieve], как и для печатного судьи.
 */
object DirectJudge {

    /** Где в приложении лежит направление (assets). */
    const val ASSET = "judge/pryamoy_sud.txt"

    /** Системная часть подачи: пустая. См. «ПОДАЧА» в шапке. */
    const val WALL = "<|im_start|>system\n<|im_end|>\n"

    /** Начало подачи: запись А и метка записи Б. */
    fun head(first: String): String = "<|im_start|>user\nЗапись А: $first\nЗапись Б:"

    /** Отрезок, по которому читается состояние: запись Б. */
    fun tail(second: String): String = " $second"

    /**
     * Ниже этой доли «спорят» в ОБОИХ порядках пара печатному судье не идёт.
     *
     * Подобрано тем же чтением, что на телефоне (доли стенда и телефона на
     * одних парах совпадают до сотых). На случайных парах живой памяти при нём
     * отбрасывается около трёх четвертей пула.
     *
     * Граница снизу — споры с частичным отрицанием («Твою память я починил» —
     * «Память твою я пока не трогал»): когда направление учится без пары, такие
     * читаются слабее всех, самые слабые — около 0,36–0,42. Порог держит запас
     * ниже них. Прямые противоположности («живу в Туле» — «живу в Омске»)
     * читаются выше 0,9 и границы не задают.
     *
     * Пропадают пары, где печатный сказал «спорят» лишь в одном порядке, но
     * спором он их и сам не считает: его вердикт «спорят» — только в обоих
     * порядках.
     *
     * Живых споров известно мало. Перепроверяется строкой «Прямой судья:» в
     * отчёте прогона и спорами, подтверждёнными человеком.
     */
    const val SIEVE_THRESHOLD = 0.30

    /** Пару дальше, печатному судье: «спорят» хоть в одном порядке не ниже порога. */
    fun keep(pForward: Double, pBackward: Double): Boolean = max(pForward, pBackward) >= SIEVE_THRESHOLD

    /**
     * Эталон: пары, ответ на которые известен. Взяты из учебного набора
     * нарочно — проверяется не точность, а то, что направление подходит к
     * загруженной модели: на своей модели учебный спор читается почти единицей,
     * несвязанная пара — почти нулём.
     */
    val ETALON_DISPUTE: Pair<String, String> = "Я живу в Туле." to "Я живу в Омске."
    val ETALON_UNRELATED: Pair<String, String> = "Я живу в Туле." to "Сестру зовут Оля."

    /** Доля «спорят» у эталонного спора, ниже которой эталон не узнан. Объявленное число. */
    const val ETALON_DISPUTE_MIN = 0.5

    /**
     * Узнан ли эталон: спор не ниже [ETALON_DISPUTE_MIN] в обоих порядках,
     * несвязанная пара ниже порога сита в обоих. null — узнан; иначе причина
     * словами для экрана, и сито в этом прогоне выключено.
     */
    fun etalonRefusal(disputeF: Double, disputeB: Double, unrelatedF: Double, unrelatedB: Double): String? {
        val seen = "спор ${share(disputeF)}/${share(disputeB)}, несвязанные ${share(unrelatedF)}/${share(unrelatedB)}"
        return when {
            minOf(disputeF, disputeB) < ETALON_DISPUTE_MIN -> "эталон не узнан ($seen) — направление не от этой модели?"
            max(unrelatedF, unrelatedB) >= SIEVE_THRESHOLD -> "эталон не узнан ($seen) — направление не от этой модели?"
            else -> null
        }
    }

    /** Доля для экрана: два знака после запятой, запятой. */
    fun share(p: Double): String = String.format(java.util.Locale.ROOT, "%.2f", p).replace('.', ',')
}

/**
 * Направление прямого судьи: как из состояния слоя получить доли трёх ответов
 * (спорят, согласуются, не связаны). Логистическая регрессия: состояние
 * приводится к учебному масштабу ((x − mu) / sd), умножается на веса каждого
 * ответа, к сумме прибавляется сдвиг, и доли — нормированная экспонента.
 *
 * Числа — из файла [DirectJudge.ASSET]; откуда они и как пересобрать — в
 * описании рядом с файлом. Руками их не правят.
 */
class DirectJudgeDirections private constructor(
    val version: Int,
    val layer: Int,
    val width: Int,
    private val mu: FloatArray,
    private val sd: FloatArray,
    private val weights: Array<FloatArray>,
    private val bias: FloatArray,
) {

    /** Доля ответа «спорят» для состояния [x]; null — состояние не той ширины. */
    fun pDispute(x: FloatArray): Double? {
        if (x.size != width) return null
        val logits = DoubleArray(weights.size) { k ->
            var s = bias[k].toDouble()
            val w = weights[k]
            for (j in 0 until width) s += w[j] * ((x[j] - mu[j]) / sd[j]).toDouble()
            s
        }
        val top = logits.max()
        val e = logits.map { exp(it - top) }
        return e[0] / e.sum()
    }

    /** Итог разбора файла: направление или причина, почему его нет. */
    sealed class Parsed {
        class Ok(val directions: DirectJudgeDirections) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    companion object {
        /** Ответов три, и «спорят» — первый: в этом порядке идут веса в файле. */
        private val WEIGHT_KEYS = listOf("w_spor", "w_soglasie", "w_nesvyazany")

        /**
         * Разобрать текст файла. Строки «ключ значения…»; строки с «#» и пустые
         * пропускаются. Любая неполнота — [Parsed.Bad], а не направление с
         * нулями: сито на испорченном направлении отбрасывало бы пары молча.
         */
        fun parse(text: String): Parsed {
            val rows = text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val cut = line.indexOf(' ')
                    if (cut < 0) line to "" else line.substring(0, cut) to line.substring(cut + 1).trim()
                }
            val version = rows["версия"]?.toIntOrNull() ?: return Parsed.Bad("нет версии")
            val layer = rows["слой"]?.toIntOrNull() ?: return Parsed.Bad("нет слоя")
            val width = rows["ширина"]?.toIntOrNull()?.takeIf { it > 0 } ?: return Parsed.Bad("нет ширины")
            fun numbers(key: String, n: Int): FloatArray? {
                val parts = rows[key]?.split(' ')?.filter { it.isNotEmpty() } ?: return null
                if (parts.size != n) return null
                val out = FloatArray(n)
                for (i in 0 until n) out[i] = parts[i].toFloatOrNull() ?: return null
                return out
            }
            val mu = numbers("mu", width) ?: return Parsed.Bad("строка mu не той длины")
            val sd = numbers("sd", width) ?: return Parsed.Bad("строка sd не той длины")
            if (sd.any { it <= 0f }) return Parsed.Bad("в sd не положительное число")
            val weights = WEIGHT_KEYS.map { numbers(it, width) ?: return Parsed.Bad("строка $it не той длины") }
            val bias = numbers("b", WEIGHT_KEYS.size) ?: return Parsed.Bad("строка b не той длины")
            return Parsed.Ok(DirectJudgeDirections(version, layer, width, mu, sd, weights.toTypedArray(), bias))
        }
    }
}
