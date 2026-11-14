package com.uroboros.llm

import android.content.Context

/**
 * Сколько потоков ставить на выдачу ответа — выбор человека, по замеру.
 *
 * ЗАЧЕМ. Движок сам ставит выдаче не больше трёх потоков (gguf_lib.cpp,
 * apply_thread_mode, «ПОТОКИ НА ВЫДАЧУ»). Где у выдачи потолок, зависит от
 * памяти и ядер телефона и находится только замером: на одном телефоне
 * четвёртый поток ничего не дал, на другом может дать. Поэтому число
 * выбирается на экране, а скорость видна строкой «выдача … ток/с».
 *
 * ПРИВЯЗАНО К ТЕЛЕФОНУ: настройка не едет при переезде (move.MoveAndroid),
 * потому что найдена под ядра этого телефона.
 *
 * Чего не умеет: подобрать число само; больше потоков, чем ядер, движок не
 * поставит, и строка покажет, сколько стоит на деле.
 */
object GenerationThreads {

    /** Файл настроек — привязан к телефону (move.MoveAndroid.SKIP_FILES). */
    const val PREFS = "uroboros_engine_threads"
    private const val KEY = "generation"

    /** 0 — как решил движок. Порядок перебора по нажатию. */
    val CHOICES = listOf(0, 4, 5, 6, 7, 8, 2)

    fun get(c: Context): Int = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun set(c: Context, n: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, n).apply()
    }

    /** Следующий выбор после [n]; незнакомое — к «как решил движок». */
    fun next(n: Int): Int {
        val at = CHOICES.indexOf(n)
        return if (at < 0) CHOICES[0] else CHOICES[(at + 1) % CHOICES.size]
    }

    /**
     * Строка экрана без ссылки: [chosen] — выбор, [actual] — сколько стоит
     * на деле (0 — модель не загружалась).
     */
    fun line(chosen: Int, actual: Int): String {
        val now = if (actual > 0) "$actual" else "?"
        val how = if (chosen == 0) "как решил движок" else "задано $chosen"
        return "Потоков на выдачу: $now ($how)"
    }
}
