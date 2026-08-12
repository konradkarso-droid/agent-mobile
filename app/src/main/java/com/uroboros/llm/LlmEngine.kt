package com.uroboros.llm

import android.content.Context
import android.net.Uri
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.flow.Flow

class LlmEngine(private val context: Context) {

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

    fun generateFlow(prompt: String, maxTokens: Int = 512): Flow<GenerationEvent> =
        engine.generateFlow(prompt, maxTokens)

    fun stopGeneration() = engine.stopGeneration()
    fun getDebugLog(): String = engine.getDebugLog()

    suspend fun unload() = engine.unload()
}
