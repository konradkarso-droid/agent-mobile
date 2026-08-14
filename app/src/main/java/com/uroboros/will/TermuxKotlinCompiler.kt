package com.uroboros.will

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import com.uroboros.memory.ActionGate
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.GateResult
import com.uroboros.util.DataSieve
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Компилирует небольшие самодостаточные Kotlin-фрагменты через kotlinc внутри Termux
 * (не пересобирает всё приложение — см. item 6b/7a). Требует установленного Termux
 * с allow-external-apps=true и выданного пользователем разрешения RUN_COMMAND.
 */

sealed class CompileResult {
    data class Success(val stdout: String) : CompileResult()
    data class CompileFailure(val stdout: String, val stderr: String, val exitCode: Int) : CompileResult()
    data class Unavailable(val reason: String) : CompileResult()
    data class Denied(val reason: String) : CompileResult()
}

class TermuxKotlinCompiler(private val context: Context) {

    private var callsSinceCleanup = 0

    suspend fun compile(fragment: String): CompileResult {
        val verdict = ActionGate.evaluate(
            ActionRequest(
                type = ActionType.EXTERNAL_PROCESS,
                requestedBy = "TermuxKotlinCompiler",
                provenance = ActionProvenance.MODEL_OUTPUT,
                crossesDeviceBoundary = true,
                isReversible = true
            )
        )
        if (verdict.result != GateResult.ALLOW) {
            return CompileResult.Denied(verdict.reason)
        }

        if (!isTermuxAvailable()) {
            return CompileResult.Unavailable(
                "Termux не установлен, не имеет разрешения RUN_COMMAND, " +
                "или allow-external-apps не включён в termux.properties"
            )
        }

        val fragmentId = UUID.randomUUID().toString()
        val encoded = Base64.encodeToString(fragment.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = buildString {
            append("mkdir -p ~/uroboros_tote && ")
            append("echo \"\$1\" | base64 -d > ~/uroboros_tote/\$2.kt && ")
            append("timeout 60s kotlinc ~/uroboros_tote/\$2.kt -include-runtime ")
            append("-d ~/uroboros_tote/\$2.jar; ")
            append("EXIT=\$?; rm -f ~/uroboros_tote/\$2.kt; exit \$EXIT")
        }

        val (requestId, deferred) = TermuxResultService.registerWait()

        val pendingIntent = android.app.PendingIntent.getService(
            context, requestId,
            Intent(context, TermuxResultService::class.java)
                .putExtra(TermuxResultService.EXTRA_REQUEST_ID, requestId),
            android.app.PendingIntent.FLAG_ONE_SHOT or
                (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE else 0)
        )

        val runIntent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-c", script, "_", encoded, fragmentId)
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        try {
            context.startService(runIntent)
        } catch (e: Exception) {
            TermuxResultService.cancelWait(requestId)
            return CompileResult.Unavailable("не удалось запустить Termux: ${e.message}")
        }

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            TermuxResultService.cancelWait(requestId)
            return CompileResult.Unavailable("Termux не ответил вовремя")
        }

        maybeCleanup()

        val cappedStdout = DataSieve.capBytes(result.stdout, MAX_OUTPUT_BYTES)
        val cappedStderr = DataSieve.capBytes(result.stderr, MAX_OUTPUT_BYTES)

        return if (result.exitCode == 0) {
            CompileResult.Success(cappedStdout)
        } else {
            CompileResult.CompileFailure(cappedStdout, cappedStderr, result.exitCode)
        }
    }

    private fun isTermuxAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.termux", 0)
            context.packageManager.checkPermission(
                "com.termux.permission.RUN_COMMAND",
                context.packageName
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun maybeCleanup() {
        callsSinceCleanup++
        if (callsSinceCleanup < CLEANUP_EVERY_N_CALLS) return
        callsSinceCleanup = 0

        val (requestId, deferred) = TermuxResultService.registerWait()
        val pendingIntent = android.app.PendingIntent.getService(
            context, requestId,
            Intent(context, TermuxResultService::class.java)
                .putExtra(TermuxResultService.EXTRA_REQUEST_ID, requestId),
            android.app.PendingIntent.FLAG_ONE_SHOT or
                (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE else 0)
        )
        val cleanupIntent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("-c", "find ~/uroboros_tote -type f -mmin +60 -delete 2>/dev/null; exit 0")
            )
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }
        try {
            context.startService(cleanupIntent)
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            // очистка — best-effort, не критично, если не получилось
        } finally {
            TermuxResultService.cancelWait(requestId)
        }
    }

    companion object {
        private const val TOTAL_TIMEOUT_MS = 70_000L
        private const val CLEANUP_TIMEOUT_MS = 15_000L
        private const val MAX_OUTPUT_BYTES = 8_000
        private const val CLEANUP_EVERY_N_CALLS = 20
    }
}
