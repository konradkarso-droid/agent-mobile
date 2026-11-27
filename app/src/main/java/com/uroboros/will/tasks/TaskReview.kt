package com.uroboros.will.tasks

import org.json.JSONArray
import org.json.JSONObject

/** Память о ходе работы одного диалога. Содержит только вид шага и исход, без реплик и выдержек. */
internal object TaskReview {
    // Объявленная длина окна: старые ошибки не должны бессрочно задавать поведение.
    const val WINDOW = 32

    // Модель получает одну краткую памятку и максимум одну подсказку по наблюдениям.
    const val CHECK = "1. Какой результат просит человек? 2. Что уже известно из памяти и обещаний? " +
        "3. Какое действие доступно и оправдывает цену? 4. Если видишь развилку, можно ли совместить варианты или найти третий? " +
        "5. Чем подтвердить пункт? 6. При каком пробеле уточнить или остановиться? " +
        "7. Какую прошлую ошибку стоит проверить здесь?"

    fun record(events: JSONArray, id: String, tool: String, outcome: String) {
        if ((0 until events.length()).any { events.getJSONObject(it).getString("id") == id }) return
        while (events.length() >= WINDOW) events.remove(0)
        events.put(JSONObject().put("id", id).put("tool", tool).put("outcome", outcome))
    }

    fun hint(events: JSONArray): String {
        val rows = (0 until events.length()).map(events::getJSONObject)
        val misses = rows.filter { it.getString("outcome") == "missing" }.groupingBy { it.getString("tool") }.eachCount()
        val repeated = misses.maxByOrNull { it.value }?.takeIf { it.value >= 2 } ?: return ""
        val advice = when (repeated.key) {
            "plan" -> "Сократи план и проверь формат и доступные действия."
            "memory" -> "Уточни предмет воспоминания, если нет подходящей записи."
            "web" -> "Проверь, есть ли читаемый источник по теме."
            else -> "Укажи конкретный текст, который должен получиться."
        }
        val attempts = rows.count { it.getString("tool") == repeated.key && it.getString("outcome") != "cut" }
        return "В недавних проверках ${repeated.key} без результата: ${repeated.value} из $attempts. $advice"
    }
}
