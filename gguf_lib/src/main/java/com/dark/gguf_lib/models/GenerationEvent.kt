package com.uroboros.llm

import android.content.Context
import android.net.Uri
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.flow.Flow

/**
 * Thin project-level wrapper around [GGMLEngine].
 *
 * Deliberately minimal for this first integration pass: text-only, loads one
 * model, exposes streaming generation. Vision (VLM) and RAG are NOT wired
 * here yet — RAG stays unused per the earlier decision (HourglassMemory
 * already covers that role), VLM comes later as its own step.
 *
 * NOT YET process-isolated — this runs gguf_lib in-process, same as the rest
 * of the app. Process isolation (android:isolatedProcess + AIDL) is the next
 * hardening step once this path is confirmed working on-device.
 */
class LlmEngine(private val context: Context) {

    private val engine = GGMLEngine()

    val isLoaded: Boolean get() = engine.isLoaded

    /**
     * Load a model from an absolute file path, using device-tier-appropriate
     * defaults (context size / KV cache quantization) from [GGMLEngine.getRecommendedParams].
     */
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
            // Conservative default sampling — fine to tune later.
            engine.setSampling(temperature = 0.7f, topK = 40, topP = 0.9f, minP = 0.05f)
        }
        return ok
    }

    /**
     * Load a model picked via the system file picker (SAF `content://` URI).
     * Preferred over [loadModel] with a raw path — no broad storage
     * permission needed, only the temporary grant SAF gives for this one file.
     */
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
            engine.setSampling(temperature = 0.7f, topK = 40, topP = 0.9f, minP = 0.05f)
        }
        return ok
    }

    /** Streaming generation for a single prompt. See [GenerationEvent] for the event types. */
    fun generateFlow(prompt: String, maxTokens: Int = 512): Flow<GenerationEvent> =
        engine.generateFlow(prompt, maxTokens)

    fun stopGeneration() = engine.stopGeneration()

    suspend fun unload() = engine.unload()
}
