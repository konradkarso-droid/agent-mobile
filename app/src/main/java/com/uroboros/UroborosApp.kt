package com.uroboros

import android.app.Application
import com.uroboros.memory.EmergencyStop
import com.uroboros.move.MoveAndroid
import com.uroboros.move.MoveState

/**
 * Начало процесса — раньше любого экрана, службы и базы.
 *
 * Здесь только то, что обязано случиться до первого открытия базы: применить
 * принятый переезд ([MoveAndroid.applyOnStart]) и, если агент отсюда уже
 * переехал, снова взвести аварийный стоп — стоп живёт в памяти процесса и
 * после комы сам не встаёт (см. [com.uroboros.move.MoveState]).
 *
 * Сбой здесь не роняет приложение: строка ложится в «Комы» (MoveState), а
 * агент поднимается как был.
 */
class UroborosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { MoveAndroid.applyOnStart(this) }
            .onFailure { MoveState.noteArrived(this, "не применён: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
        MoveState.movedAt(this)?.let { EmergencyStop.triggerManual(MoveState.stopNote(it)) }
    }
}
