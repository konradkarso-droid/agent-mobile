package com.uroboros

import android.content.Context

/**
 * Какая модель выбрана последней. Хранится в настройках экрана, а читают двое:
 * экран при запуске и тело агента (AgentService), когда само загружает модель,
 * чтобы судить. Одно место для имени настроек и ключа — разойдись они, тело
 * искало бы модель не там, где её оставил экран, и молча не судило бы.
 */
object ModelPrefs {
    const val NAME = "uroboros_prefs"
    const val KEY_LAST_MODEL_URI = "last_model_uri"

    /** Ссылка на последнюю выбранную модель; null — модель ни разу не выбиралась. */
    fun lastModelUri(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_LAST_MODEL_URI, null)
}
