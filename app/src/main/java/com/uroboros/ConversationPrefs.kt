package com.uroboros

import android.content.Context

/**
 * Настройка «После комы продолжать разговор сам» — постоянное решение
 * владельца, включена по умолчанию.
 *
 * Включена: сохранённая лента поднимается с диска без диалога, кто бы ни
 * поднял процесс — экран или тело агента (AgentService). Иначе агент,
 * очнувшийся без экрана, не мог бы ни продолжить разговор, ни заговорить
 * первым: поверх неподнятой ленты новый ход лёг бы под нулевым номером.
 * Выключена: как раньше — диалог «Продолжить / Начать заново», решает владелец.
 *
 * Лежит в тех же настройках, что выбранная модель (ModelPrefs): их читают и
 * экран, и тело агента.
 */
object ConversationPrefs {
    private const val KEY_AUTO_CONTINUE = "auto_continue_after_coma"

    fun autoContinue(context: Context): Boolean =
        context.getSharedPreferences(ModelPrefs.NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CONTINUE, true)

    fun setAutoContinue(context: Context, on: Boolean) {
        context.getSharedPreferences(ModelPrefs.NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_CONTINUE, on).apply()
    }
}
