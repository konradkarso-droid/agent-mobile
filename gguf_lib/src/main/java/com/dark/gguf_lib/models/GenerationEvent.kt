package com.dark.gguf_lib.models

/**
 * Streaming events emitted by [com.dark.gguf_lib.GGMLEngine.generateFlow] and
 * related streaming generation calls.
 *
 * A typical stream is a sequence of [Token] events, optionally interleaved
 * with [Progress] / [VlmStageMetrics] / cache-status events, terminated by
 * either [Done] or [Error] — never both.
 */
sealed class GenerationEvent {

    /** One decoded text chunk. Chunks are UTF-8 safe but not necessarily whole words. */
    data class Token(val text: String) : GenerationEvent()

    /** Generation finished normally. Terminal event. */
    data object Done : GenerationEvent()

    /** Generation failed. [message] is human-readable. Terminal event. */
    data class Error(val message: String) : GenerationEvent()

    /** Coarse progress signal (0.0–1.0), e.g. during prompt evaluation. */
    data class Progress(val progress: Float) : GenerationEvent()

    /** Final decoding performance snapshot for this generation run. */
    data class Metrics(val metrics: com.dark.gguf_lib.models.DecodingMetrics) : GenerationEvent()

    /**
     * Vision-stage timing for a VLM generation call.
     *
     * @param vlmEncodeMs Time spent in the vision encoder (ViT pass).
     * @param vlmDecodeMs Time spent decoding the image tokens into the LLM context.
     * @param imageTokens Number of tokens the image was encoded into.
     */
    data class VlmStageMetrics(
        val vlmEncodeMs: Float,
        val vlmDecodeMs: Float,
        val imageTokens: Int,
    ) : GenerationEvent()

    /**
     * VT (vision-tower) cache lookup result for one image.
     *
     * @param hit Whether the cached embedding was reused instead of re-encoding.
     * @param nTokens Number of embedding tokens involved.
     * @param nEmbd Embedding dimension.
     */
    data class VtCacheStatus(
        val hit: Boolean,
        val nTokens: Int,
        val nEmbd: Int,
    ) : GenerationEvent()

    /**
     * VLM-KV cache lookup result — whether the post-image-prefill LLM state
     * was restored from cache, skipping both the ViT pass and image-prefill.
     */
    data class VlmKvCacheStatus(
        val hit: Boolean,
        val nTokens: Int,
    ) : GenerationEvent()
}
