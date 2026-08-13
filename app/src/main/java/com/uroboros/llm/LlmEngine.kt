package com.uroboros.llm

import android.content.Context
import android.net.Uri
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LlmEngine(
    private val context: Context,
    private val watchdog: DeviceSafetyWatchdog
) {

    private val engine = GGMLEngine()

    val isLoaded: Boolean get() = engine.isLoaded

    suspend fun loadModel(modelPath: String): Boolean {
        val params = GGMLEngine.getRecommendedParams(context)
        val ok = engine.load(
            path = modelPath,
            contextSize = params.contextSize,
            threads = params.threads,
            batchSize = params.batchSize,
            flashAttn = params.flashAttn,
            useMmap = params.useMmap,
            useMlock = params.useMlock,
                cacheTypeK = params.cacheTypeK,
                cacheTypeV = params.cacheTypeV,
        )
        if (ok) {
                engine.setSampling(temperature = 0.7f, topK = 40, topP = 0.9f, minP = 0.05f, mirostat = 0)
                engine.updateSamplerParams("{\"repeat_penalty\":1.3,\"penaltyLastN\":64}")
        }
        return ok
    }

    suspend fun loadModelFromUri(uri: Uri): Boolean {
        val params = GGMLEngine.getRecommendedParams(context)
        val ok = engine.load(
            context = context,
            uri = uri,
            contextSize = params.contextSize,
            threads = params.threads,
            batchSize = params.batchSize,
            flashAttn = params.flashAttn,
            useMmap = params.useMmap,
            useMlock = params.useMlock,
                cacheTypeK = params.cacheTypeK,
                cacheTypeV = params.cacheTypeV,
        )
        if (ok) {
                engine.setSampling(temperature = 0.7f, topK = 40, topP = 0.9f, minP = 0.05f, mirostat = 0)
                engine.updateSamplerParams("{\"repeat_penalty\":1.3,\"penaltyLastN\":64}")
        }
        return ok
    }

    /**
     * Обёртка над generateFlow, которая физический watchdog реально исполняет:
     * FATIGUE — задержка между токенами; CRITICAL или истёкший хард-таймаут —
     * генерация останавливается до выхода из опасной зоны. Модель не участвует
     * в этом решении (Bible principle #1).
     */
    fun generateFlow(prompt: String, maxTokens: Int = 512): Flow<GenerationEvent> = flow {
        watchdog.markInferenceStarted()
        try {
            engine.generateFlow(prompt, maxTokens).collect { event ->
                val zone = watchdog.zone.value

                if (zone == SafetyZone.CRITICAL || watchdog.shouldForceCooldown()) {
                    engine.stopGeneration()
                    watchdog.resetInferenceTimer()
                    return@collect
                }

                if (zone == SafetyZone.FATIGUE) {
                    delay(100)
                }

                emit(event)
            }
        } finally {
            watchdog.resetInferenceTimer()
        }
    }

    fun stopGeneration() {
        engine.stopGeneration()
        watchdog.resetInferenceTimer()
    }

    fun getDebugLog(): String = engine.getDebugLog()

    suspend fun unload() = engine.unload()
}
