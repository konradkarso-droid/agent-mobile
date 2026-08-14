package com.uroboros.will

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Принимает результат выполнения команды от Termux (через PendingIntent, см. RUN_COMMAND
 * Intent Result Extras в документации termux-app). Не содержит бизнес-логики —
 * только доставляет результат обратно тому, кто ждёт (TermuxKotlinCompiler),
 * через CompletableDeferred, сматченный по requestId.
 */

data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val internalErr: Int,
    val internalErrMsg: String
)

class TermuxResultService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestId = intent?.getIntExtra(EXTRA_REQUEST_ID, -1) ?: -1
        val resultBundle: Bundle? = intent?.getBundleExtra(TERMUX_RESULT_BUNDLE_KEY)

        if (requestId != -1 && resultBundle != null) {
            val result = TermuxCommandResult(
                stdout = resultBundle.getString(KEY_STDOUT, ""),
                stderr = resultBundle.getString(KEY_STDERR, ""),
                exitCode = resultBundle.getInt(KEY_EXIT_CODE, -1),
                internalErr = resultBundle.getInt(KEY_ERR, -1),
                internalErrMsg = resultBundle.getString(KEY_ERRMSG, "")
            )
            pending.remove(requestId)?.complete(result)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        // Ключи подтверждены по исходникам termux-app (TermuxConstants.java) —
        // не менять без сверки с актуальной версией Termux.
        private const val TERMUX_RESULT_BUNDLE_KEY = "result"
        private const val KEY_STDOUT = "stdout"
        private const val KEY_STDERR = "stderr"
        private const val KEY_EXIT_CODE = "exitCode"
        private const val KEY_ERR = "err"
        private const val KEY_ERRMSG = "errmsg"

        const val EXTRA_REQUEST_ID = "com.uroboros.will.EXTRA_REQUEST_ID"

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()
        private var nextRequestId = 0

        /**
         * Регистрирует ожидание результата для нового запроса.
         * Возвращает (requestId, deferred) — requestId кладётся в PendingIntent,
         * deferred дожидается TermuxKotlinCompiler через await() с таймаутом.
         */
        @Synchronized
        fun registerWait(): Pair<Int, CompletableDeferred<TermuxCommandResult>> {
            val id = nextRequestId++
            val deferred = CompletableDeferred<TermuxCommandResult>()
            pending[id] = deferred
            return id to deferred
        }

        /** Убирает ожидание, если оно не дождалось ответа (например, по таймауту). */
        fun cancelWait(requestId: Int) {
            pending.remove(requestId)
        }
    }
}
