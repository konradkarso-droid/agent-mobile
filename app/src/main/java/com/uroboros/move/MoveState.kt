package com.uroboros.move

import android.content.Context
import com.uroboros.gate.store.GateTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Замирание агента при переезде и след переезда для экрана. Зачем два вида
 * замирания и почему их нельзя слить — у [Move], «два живых агента».
 *
 *  - ВРЕМЕННОЕ ([hold]) — в памяти процесса, на время передачи или приёма.
 *    Умер процесс — умерла и передача, замирание уходит вместе с ней;
 *  - НАСОВСЕМ ([commit]) — на диске: агент отсюда переехал. Каждый запуск
 *    процесса снова взводит аварийный стоп (UroborosApp), а [holdReason]
 *    держит то, чего стоп не держит: разговор и «Сохранить» на экране, сон.
 *
 * Чего держит стоп сам: телеграм, ответы в нём, суд, шаги ночи, «пишу
 * первым», действия цикла. Снятый человеком стоп на переехавшем агенте
 * оживляет только это — телеграм без токена молчит, а разговор и сон держит
 * [holdReason].
 *
 * ЧЕГО НЕ УМЕЕТ: переехавший агент сам не оживает и не стирается — это делает
 * человек; файл настроек [PREFS] при переезде не едет (иначе новый телефон
 * считал бы себя переехавшим).
 */
object MoveState {

    const val PREFS = "uroboros_move"
    private const val MOVED_AT = "moved_at"
    private const val MOVED_SESSION = "moved_session"
    private const val ARRIVED_AT = "arrived_at"
    private const val ARRIVED_LINE = "arrived_line"

    private val _hold = MutableStateFlow<String?>(null)

    /** Временное замирание: причина словами; null — нет. */
    val hold: StateFlow<String?> get() = _hold

    fun holdFor(reason: String?) {
        _hold.value = reason
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сессия, с которой агент отсюда переехал; null — не переезжал. */
    fun movedSession(c: Context): String? = prefs(c).getString(MOVED_SESSION, null)

    fun movedAt(c: Context): Long? = prefs(c).getLong(MOVED_AT, 0L).takeIf { it > 0 }

    /**
     * Замереть насовсем и спрятать токен (шаг 3 у [Move]). Запись — с
     * ожиданием диска: «готово» уходит на новый телефон только после неё.
     */
    fun commit(c: Context, session: String, now: Long = System.currentTimeMillis()) {
        if (movedSession(c) != session) {
            check(prefs(c).edit().putString(MOVED_SESSION, session).putLong(MOVED_AT, now).commit()) { "отметка переезда не записалась" }
        }
        GateTokenStore.park(c)
    }

    /** Почему агент сейчас не говорит и не спит из-за переезда; null — говорит. */
    fun holdReason(c: Context): String? =
        _hold.value ?: movedAt(c)?.let { movedNote(it) }

    fun movedNote(at: Long): String =
        "агент переехал на другой телефон ${moment(at)}; здесь — копия памяти на момент переезда"

    /** Причина аварийного стопа на переехавшем агенте — её показывает красная полоса. */
    fun stopNote(at: Long): String = movedNote(at).replaceFirstChar { it.uppercase() } + "."

    /** Итог применения на новом телефоне — в строку «Комы». */
    fun noteArrived(c: Context, line: String, now: Long = System.currentTimeMillis()) {
        prefs(c).edit().putLong(ARRIVED_AT, now).putString(ARRIVED_LINE, line).commit()
    }

    /** Строка о переезде для «Подробно»; null — переездов не было. */
    fun meterLine(c: Context): String? {
        val p = prefs(c)
        movedAt(c)?.let { return "Переезд: отсюда ${moment(it)} — агент живёт на другом телефоне, здесь замер" }
        val arrived = p.getLong(ARRIVED_AT, 0L).takeIf { it > 0 } ?: return null
        return "Переезд: сюда ${moment(arrived)} — " + p.getString(ARRIVED_LINE, "итог не записан")
    }

    private fun moment(ms: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(ms))
}
