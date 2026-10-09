package com.uroboros.initiative

import android.content.Context

/**
 * Где лежит счёт проверок «пишу первым» ([InitiativeTally]): настройки
 * приложения, две строки — сегодня и прошлые сутки. На диске, а не в памяти
 * процесса: счёт должен пережить кому, иначе после неё было бы не видно паузы.
 *
 * Пишется без ожидания диска (apply): потерянная при коме последняя проверка —
 * одна минута счёта, и ждать ради неё диска раз в минуту незачем.
 */
class InitiativeTallyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): InitiativeTally.State = InitiativeTally.State(
        today = InitiativeTally.decode(prefs.getString(KEY_TODAY, null)),
        previous = InitiativeTally.decode(prefs.getString(KEY_PREVIOUS, null)),
    )

    fun write(state: InitiativeTally.State) {
        prefs.edit()
            .putString(KEY_TODAY, InitiativeTally.encode(state.today))
            .putString(KEY_PREVIOUS, InitiativeTally.encode(state.previous))
            .apply()
    }

    private companion object {
        const val NAME = "uroboros_initiative_tally"
        const val KEY_TODAY = "today"
        const val KEY_PREVIOUS = "previous"
    }
}
