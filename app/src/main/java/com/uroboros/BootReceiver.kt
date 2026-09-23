package com.uroboros

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Подъём тела агента после двух ком, из которых служба сама не встаёт:
 * перезагрузки телефона и установки новой сборки. В обоих случаях процесс
 * убит, а START_STICKY не срабатывает — система не считает это смертью службы.
 *
 * Эти два сообщения присылает только система, и запуск службы переднего плана
 * из них Android разрешает, хотя приложение не на экране. Любое другое
 * сообщение приёмник игнорирует.
 *
 * ЧЕГО НЕ УМЕЕТ. Если система всё же откажет в запуске, приёмнику показать это
 * некому: отказ уходит в системный журнал, а на экране его видно как «Агент не
 * жив» и подъём, не прибавившийся к счёту (см. [AgentLife]). На MIUI без
 * «Автозапуска» сообщение о загрузке приложению может не прийти вовсе.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        AgentService.live(context.applicationContext)?.let { refusal ->
            Log.w(TAG, "Тело агента не поднялось после ${intent.action}: $refusal")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
