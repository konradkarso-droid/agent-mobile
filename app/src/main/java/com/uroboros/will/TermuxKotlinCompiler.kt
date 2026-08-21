package com.uroboros.will

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Base64
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.util.DataSieve
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

sealed class CompileResult {
    data class Success(val stdout: String) : CompileResult()
    data class CompileFailure(val stdout: String, val stderr: String, val exitCode: Int) : CompileResult()
    data class Unavailable(val reason: String) : CompileResult()
    data class Denied(val reason: String) : CompileResult()
}

/**
 * Item 8a stage B result — bytecode size of a standalone-compiled fragment,
 * used by BytecodeShrinkEscalator to refine stage C's gray-zone verdicts.
 */
sealed class BytecodeMeasurement {
    data class Success(val jarSizeBytes: Long) : BytecodeMeasurement()
    data class CompileFailure(val stderr: String) : BytecodeMeasurement()
    data class Unavailable(val reason: String) : BytecodeMeasurement()
    data class Denied(val reason: String) : BytecodeMeasurement()
}

class TermuxKotlinCompiler(private val context: Context) {

    private var callsSinceCleanup = 0

    suspend fun compile(fragment: String): CompileResult {
        // Дыра №3 (аудит 2026-08-21): проход теперь через GatedAction, а не напрямую
        // через ActionGate — сам вердикт тот же, но каждая проверка (ALLOW и DENY)
        // попадает в action_evidence. Раньше след не писался нигде.
        val verdict = GatedAction.evaluate(
            context,
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

        val availability = checkTermuxAvailability()
        if (availability != null) {
            return CompileResult.Unavailable(availability)
        }

        val fragmentId = UUID.randomUUID().toString()
        val encoded = Base64.encodeToString(fragment.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = buildString {
            append("mkdir -p ~/uroboros_tote && ")
            // Попытка отключить jansi — не гарантирует тишину (см. фильтрацию ниже),
            // но не вредит, оставляем на случай других версий окружения.
            append("export JDK_JAVA_OPTIONS=\"-Dorg.fusesource.jansi.internal.CLibrary.disable=true\" && ")
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
            context.startForegroundService(runIntent)
        } catch (e: Exception) {
            TermuxResultService.cancelWait(requestId)
            return CompileResult.Unavailable("не удалось запустить Termux: ${e.javaClass.simpleName}: ${e.message}")
        }

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            TermuxResultService.cancelWait(requestId)
            return CompileResult.Unavailable("Termux не ответил вовремя (${TOTAL_TIMEOUT_MS}мс) — возможно, неверный ключ PendingIntent extra или allow-external-apps не включён")
        }

        maybeCleanup()

        val cappedStdout = DataSieve.capBytes(result.stdout, MAX_OUTPUT_BYTES)
        val rawStderr = DataSieve.capBytes(result.stderr, MAX_OUTPUT_BYTES)
        val cleanedStderr = stripEnvironmentNoise(rawStderr)

        return when {
            result.exitCode == 0 -> CompileResult.Success(cappedStdout)
            // После вычистки шума ничего осмысленного не осталось — значит, упал
            // сам инструмент/окружение (например, jansi), а не код. Не отдаём это
            // модели как "ошибку в коде", иначе она начнёт "чинить" системный мусор.
            cleanedStderr.isBlank() || isInfrastructureFailure(cleanedStderr) ->
                CompileResult.Unavailable("сбой компилятора/окружения (не код): ${rawStderr.take(300)}")
            else -> CompileResult.CompileFailure(cappedStdout, cleanedStderr, result.exitCode)
        }
    }

    /**
     * Item 8a stage B: компилирует [fragment] изолированно (как и compile()) и
     * измеряет размер получившегося .jar в байтах — реальный сигнал объёма
     * скомпилированной логики, не поддающийся косметическому переформатированию
     * текста. Используется BytecodeShrinkEscalator только для серой зоны stage C.
     */
    suspend fun measureBytecodeSize(fragment: String): BytecodeMeasurement {
        val verdict = GatedAction.evaluate(
            context,
            ActionRequest(
                type = ActionType.EXTERNAL_PROCESS,
                requestedBy = "TermuxKotlinCompiler.measureBytecodeSize",
                provenance = ActionProvenance.MODEL_OUTPUT,
                crossesDeviceBoundary = true,
                isReversible = true
            )
        )
        if (verdict.result != GateResult.ALLOW) {
            return BytecodeMeasurement.Denied(verdict.reason)
        }

        val availability = checkTermuxAvailability()
        if (availability != null) {
            return BytecodeMeasurement.Unavailable(availability)
        }

        val fragmentId = UUID.randomUUID().toString()
        val encoded = Base64.encodeToString(fragment.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val script = buildString {
            append("mkdir -p ~/uroboros_tote && ")
            append("export JDK_JAVA_OPTIONS=\"-Dorg.fusesource.jansi.internal.CLibrary.disable=true\" && ")
            append("echo \"\$1\" | base64 -d > ~/uroboros_tote/\$2.kt && ")
            append("timeout 60s kotlinc ~/uroboros_tote/\$2.kt -include-runtime ")
            append("-d ~/uroboros_tote/\$2.jar 2>~/uroboros_tote/\$2.err; ")
            append("EXIT=\$?; ")
            append("if [ \$EXIT -eq 0 ]; then SIZE=\$(wc -c < ~/uroboros_tote/\$2.jar); echo \"BYTECODE_SIZE:\$SIZE\"; fi; ")
            append("cat ~/uroboros_tote/\$2.err >&2; ")
            append("rm -f ~/uroboros_tote/\$2.kt ~/uroboros_tote/\$2.jar ~/uroboros_tote/\$2.err; ")
            append("exit \$EXIT")
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
            context.startForegroundService(runIntent)
        } catch (e: Exception) {
            TermuxResultService.cancelWait(requestId)
            return BytecodeMeasurement.Unavailable("не удалось запустить Termux: ${e.javaClass.simpleName}: ${e.message}")
        }

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            TermuxResultService.cancelWait(requestId)
            return BytecodeMeasurement.Unavailable("Termux не ответил вовремя (${TOTAL_TIMEOUT_MS}мс)")
        }

        maybeCleanup()

        val stdout = DataSieve.capBytes(result.stdout, MAX_OUTPUT_BYTES)
        val rawStderr = DataSieve.capBytes(result.stderr, MAX_OUTPUT_BYTES)

        if (result.exitCode != 0) {
            return BytecodeMeasurement.CompileFailure(stripEnvironmentNoise(rawStderr))
        }

        val sizeLine = stdout.lineSequence().firstOrNull { it.startsWith("BYTECODE_SIZE:") }
        val size = sizeLine?.removePrefix("BYTECODE_SIZE:")?.trim()?.toLongOrNull()
        return if (size != null) {
            BytecodeMeasurement.Success(size)
        } else {
            BytecodeMeasurement.Unavailable("не удалось разобрать размер байткода из вывода: ${stdout.take(200)}")
        }
    }

    /** Убирает известный посторонний шум (например, предупреждения jansi) из stderr. */
    private fun stripEnvironmentNoise(text: String): String {
        val noiseMarkers = listOf(
            "jansi",
            "libjansi.so",
            "Failed to load native library",
            "in namespace (default)"
        )
        return text.lineSequence()
            .filterNot { line -> noiseMarkers.any { marker -> line.contains(marker, ignoreCase = true) } }
            .joinToString("\n")
            .trim()
    }

    /** Признаки того, что упал сам инструмент/окружение, а не код в .kt-файле. */
    private fun isInfrastructureFailure(stderr: String): Boolean {
        val markers = listOf(
            "UnsatisfiedLinkError",
            "libc.so.6",
            "java.lang.NoClassDefFoundError",
            "Could not find or load main class",
            "OutOfMemoryError"
        )
        return markers.any { stderr.contains(it, ignoreCase = true) }
    }

    /** Возвращает null если всё ок, иначе точную причину недоступности. */
    private fun checkTermuxAvailability(): String? {
        val packageFound = try {
            context.packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!packageFound) {
            return "пакет com.termux не найден (Termux не установлен или установлен под другим package name)"
        }

        val permissionState = context.packageManager.checkPermission(
            "com.termux.permission.RUN_COMMAND",
            context.packageName
        )
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            return "пакет com.termux найден, но com.termux.permission.RUN_COMMAND НЕ выдано " +
                "(checkPermission вернул $permissionState, нужно ${PackageManager.PERMISSION_GRANTED}). " +
                "Проверь Настройки → Приложения → ${context.packageName} → Разрешения"
        }

        return null
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
            context.startForegroundService(cleanupIntent)
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            // best-effort
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
