package com.uroboros.gate.link

import android.content.Context
import com.uroboros.memory.ActionType
import com.uroboros.memory.StandingConsent
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.PersonMerge

/**
 * Три решения владельца для слов в телеграме — в настройках приложения:
 * согласие отвечать ([StandingConsent]), «это я» (его номер в телеграме) и
 * имена агента для сортировщика. Пишет их только экран врат за замком
 * ([GateActivity]).
 *
 * ПОЧЕМУ НЕ В БАЗЕ ПАМЯТИ. Это не записи о мире, а настройки канала: что
 * владелец разрешил и кто он в телеграме. Записи из телеграма сами несут
 * ключ того, кто сказал (memory.Sticker.speaker), поэтому снятая отметка
 * «это я» не теряет, чьи они.
 *
 * ЧЕГО НЕ УМЕЕТ: в выгрузку баз не попадает; очистка данных приложения
 * стирает все три решения вместе с остальным.
 */
object ChatPrefs {

    private const val PREFS = "uroboros_chat"
    private const val CONSENT_AT = "consent_at"
    private const val CONSENT_UNTIL = "consent_until"
    private const val OWNER_KEY = "owner_key"
    private const val OWNER_NAME = "owner_name"
    private const val NAMES = "agent_names"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- Согласие отвечать ---

    /** Действующее согласие или null (не давали, истекло, снято). */
    fun consent(context: Context, now: Long = System.currentTimeMillis()): StandingConsent? {
        val p = prefs(context)
        val at = p.getLong(CONSENT_AT, 0L).takeIf { it > 0 } ?: return null
        return StandingConsent.restored(ActionType.SEND_MESSAGE, ChatLink.REQUESTED_BY, GRANTED_BY, at, p.getLong(CONSENT_UNTIL, 0L), now)
    }

    /** Только кнопка владельца на экране врат. */
    fun grantConsent(context: Context, now: Long = System.currentTimeMillis()): StandingConsent {
        val c = StandingConsent.grantedByOwner(ActionType.SEND_MESSAGE, ChatLink.REQUESTED_BY, GRANTED_BY, now)
        prefs(context).edit().putLong(CONSENT_AT, c.grantedAt).putLong(CONSENT_UNTIL, c.expiresAt).apply()
        return c
    }

    fun revokeConsent(context: Context) {
        prefs(context).edit().remove(CONSENT_AT).remove(CONSENT_UNTIL).apply()
    }

    private const val GRANTED_BY = "владелец, кнопкой на экране врат за замком"

    // --- «Это я» ---

    /** Отметка владельца: связь его ключа на экране с номером в телеграме, и как он там назван. */
    data class Owner(val merge: PersonMerge, val name: String) {
        val key: PersonKey get() = merge.second
    }

    fun owner(context: Context): Owner? {
        val p = prefs(context)
        val key = p.getString(OWNER_KEY, null)?.takeIf { it.startsWith("tg:user:") } ?: return null
        return Owner(PersonMerge(PersonKey.OWNER, PersonKey(key)), p.getString(OWNER_NAME, null) ?: key)
    }

    fun setOwner(context: Context, key: PersonKey, name: String) {
        require(key.id.startsWith("tg:user:")) { "не ключ аккаунта телеграма" }
        prefs(context).edit().putString(OWNER_KEY, key.id).putString(OWNER_NAME, name).apply()
    }

    fun clearOwner(context: Context) {
        prefs(context).edit().remove(OWNER_KEY).remove(OWNER_NAME).apply()
    }

    // --- Имена агента ---

    /** Объявленные владельцем имена агента — все формы, как написаны. */
    fun names(context: Context): List<String> =
        prefs(context).getString(NAMES, null).orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setNames(context: Context, text: String) {
        prefs(context).edit().putString(NAMES, text.trim()).apply()
    }
}
