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
        applyThreadMode()
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
                engine.updateSamplerParams(SAMPLER_PARAMS_JSON)
                // BIBLE soft-wall: задаётся один раз при загрузке (static), не на каждый
                // generateFlow-вызов — сохраняет KV-cache prefix reuse в native-движке.
                engine.setSystemPrompt(BibleSoftWall.TEXT)
                applyStreamingLatency()
        }
        return ok
    }

    suspend fun loadModelFromUri(uri: Uri): Boolean {
        val params = GGMLEngine.getRecommendedParams(context)
        applyThreadMode()
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
                engine.updateSamplerParams(SAMPLER_PARAMS_JSON)
                engine.setSystemPrompt(BibleSoftWall.TEXT)
                applyStreamingLatency()
        }
        return ok
    }

    /**
     * Токены выдаются наружу порциями: движок копит текст в буфере и вызывает
     * обратный вызов, только когда буфер набрался. Умолчание — 256 байт, оно
     * рассчитано на межпроцессное общение через Binder, где каждый вызов стоит
     * десятки микросекунд. У нас библиотека работает прямым вызовом внутри
     * своего же процесса, и платить этой ценой не за что.
     *
     * Что это меняло на практике: короткий ответ помещался в одну-две порции,
     * поэтому на экране 11 секунд не появлялось ничего, а потом ответ возникал
     * целиком. Выглядело как «медленно», хотя генерация всё это время шла.
     * Побочный эффект был хуже самой задержки: наш секундомер мерил не скорость
     * генерации, а момент доставки, и его числа были бессмысленны.
     *
     * 64 — значение, рекомендованное документацией библиотеки ровно для этого
     * случая (прямой JNI внутри процесса, наименьшая задержка).
     */
    private fun applyStreamingLatency() {
        engine.setTokenBatchSize(STREAMING_BATCH_BYTES)
    }

    /**
     * Профиль потоков движка.
     *
     * До этой правки не вызывался никогда, поэтому работало умолчание —
     * "баланс", а оно на восьмиядерном процессоре отдавало генерации всего два
     * потока (обсчёту запроса — четыре). Это было видно в логе загрузки как
     * threads_gen=2, но принималось за свойство железа, пока строку не вывели
     * на экран.
     *
     * Вызывается ПЕРЕД load() намеренно. Документация библиотеки разрешает
     * менять профиль на ходу, так что технически момент неважен, — но если
     * настройка успевает до загрузки, движок печатает фактическое число
     * потоков в своей же строке лога. То есть результат правки виден числом,
     * а не подтверждается нашим словом.
     *
     * Оговорка из документации, которая нас пока не касается: VLM-проектор
     * запоминает число потоков при своей инициализации и на смену профиля не
     * реагирует. Когда дойдём до картинок, его придётся перезагружать.
     */
    private fun applyThreadMode() {
        engine.setThreadMode(THREAD_MODE_PERFORMANCE)
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

    /**
     * Разбивка времени последней генерации по стадиям. Только чтение, на
     * поведение не влияет.
     *
     * Зачем: отвечает на вопрос, который иначе решается гаданием, — упёрлись мы
     * в пропускную способность памяти или во что-то другое. Если почти всё
     * время лежит в decode (прямой проход модели), добавление потоков даст
     * мало. Если заметная доля уходит на сэмплер, детокенизацию или проверку
     * стоп-строк, значит тормозит не железо.
     */
    fun getLastDecodeBreakdown(): GGMLEngine.DecodeBreakdown = engine.getLastDecodeBreakdown()

    /**
     * Режим потоков, который движок исполняет ФАКТИЧЕСКИ.
     *
     * Отличается от заданного: у библиотеки есть собственный тепловой
     * регулятор, который может понизить режим самостоятельно. То есть заданное
     * и работающее — разные величины, и на экране должно быть работающее.
     */
    fun getEffectiveThreadMode(): Int = engine.getEffectiveThreadMode()

    /** Включён ли собственный тепловой авто-режим библиотеки. */
    fun isEngineAutoModeEnabled(): Boolean = engine.isAutoModeEnabled()

    suspend fun unload() = engine.unload()

    private companion object {
        /** Профиль потоков движка: 0 — экономия, 1 — баланс, 2 — производительность. */
        const val THREAD_MODE_PERFORMANCE = 2

        /** Байт, накапливаемых движком перед выдачей порции токенов. */
        const val STREAMING_BATCH_BYTES = 64

        /**
         * Штраф за повтор токена. Значение живёт здесь в единственном
         * экземпляре намеренно: раньше та же строка стояла в двух путях
         * загрузки, и правка одного из них молча разошлась бы со вторым.
         *
         * История значения. Было 1.3 — молоток против зацикливания из пункта
         * 2. Молоток сработал, но платой оказалась рваная русская речь:
         * штраф давит токены, встречавшиеся недавно, а в русском недавно
         * встречавшееся — это падежные окончания, предлоги и служебные слова,
         * то есть сам язык. Отсюда «исходного коллекции» вместо «исходной
         * коллекции»: нужное окончание было только что использовано и попало
         * под штраф.
         *
         * 1.05 — нижняя граница обычного рабочего диапазона, близко к тому,
         * как модель обучалась говорить. Если зацикливание вернётся, поднимать
         * следует до 1.1, а не обратно до 1.3.
         */
        const val SAMPLER_PARAMS_JSON = "{\"repeat_penalty\":1.05,\"penaltyLastN\":64}"
    }
}
