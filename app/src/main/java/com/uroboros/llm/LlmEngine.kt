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
import java.io.File
import java.security.MessageDigest

class LlmEngine(
    private val context: Context,
    private val watchdog: DeviceSafetyWatchdog
) {

    private val engine = GGMLEngine()

    /** Папка кэша системного промпта для текущей модели, либо null — если создать не удалось. */
    private var promptCacheDir: File? = null

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
            val file = File(modelPath)
            configureAfterLoad(sourceIdentity = file.name + ":" + file.length())
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
            configureAfterLoad(sourceIdentity = uri.toString())
        }
        return ok
    }

    /**
     * Единственное место, где настраивается уже загруженная модель.
     *
     * Раньше этот блок стоял дословно в обоих путях загрузки — по пути к файлу
     * и по выданному системой доступу к папке. Расхождение между двумя копиями
     * было бы поломкой того сорта, который не видно: один способ открыть модель
     * работал бы настроенным, другой — нет, а на экране оба выглядят одинаково.
     * Штраф за повтор токена уже пришлось выносить в константу по этой же
     * причине; здесь та же мера, только целиком.
     *
     * Порядок вызовов сохранён ровно как был. Он не случаен: системная стена
     * задаётся ПОСЛЕ базовых параметров сэмплера и один раз на загрузку, а не
     * на каждый запрос, — иначе движок не сможет переиспользовать уже
     * обсчитанное начало запроса.
     *
     * @param sourceIdentity откуда взялась модель — имя и размер файла либо
     *        строка выданного доступа. Участвует в имени папки кэша, см.
     *        [applyPromptCache].
     */
    private fun configureAfterLoad(sourceIdentity: String) {
        applyPromptCache(sourceIdentity)
        engine.setSampling(temperature = 0.7f, topK = 40, topP = 0.9f, minP = 0.05f, mirostat = 0)
        engine.updateSamplerParams(SAMPLER_PARAMS_JSON)
        // BIBLE soft-wall: задаётся один раз при загрузке (static), не на каждый
        // generateFlow-вызов — сохраняет KV-cache prefix reuse в native-движке.
        engine.setSystemPrompt(BibleSoftWall.TEXT)
        applyStreamingLatency()
    }

    /**
     * Кэш обсчитанной системной стены на диске.
     *
     * Зачем. Системная стена — это больше тысячи токенов, и при каждом холодном
     * запуске движок считал её заново, около полутора минут. Библиотека умеет
     * сохранять уже посчитанное состояние на диск и поднимать его при следующем
     * запуске; надо только указать ей папку. Сохранение и восстановление она
     * делает сама, на пути генерации, — звать нам нечего.
     *
     * Почему папка своя на каждую модель. В имени файла кэша библиотека
     * использует отпечаток ТОЛЬКО текста системной стены. Модели в ключе нет.
     * Значит, поменяв модель и оставив стену прежней, мы получили бы совпадение
     * имён и поднятое состояние от чужой модели. Ошибка была бы молчаливой —
     * ни строки на экране, просто ответы не те. Поэтому модель разводится по
     * папкам нами.
     *
     * Из чего складывается имя папки: метаданные загруженной модели (название,
     * число параметров, размер, тип квантования) плюс имя и размер исходного
     * файла. Метаданные берутся у уже загруженной модели, поэтому имя папки
     * одинаково независимо от способа открытия. Имя файла нужно вдобавок к ним:
     * две сборки одной и той же модели (например, обычная и с аблитерацией)
     * вполне могут нести одинаковые метаданные, а различаются именно файлом.
     *
     * Папки прежних моделей удаляются здесь же. Иначе несколько экспериментов
     * с квантованием оставили бы на телефоне по нескольку десятков мегабайт
     * каждый, и никто бы об этом не узнал.
     *
     * Не `cacheDir`, а `filesDir` — намеренно. Содержимое `cacheDir` система
     * вправе стереть при нехватке места; кэш то работал бы, то нет, без всякого
     * следа. `filesDir` система сама не трогает.
     */
    private fun applyPromptCache(sourceIdentity: String) {
        promptCacheDir = null

        val fingerprint = shortHash((engine.getModelInfoJson() ?: "") + "|" + sourceIdentity)
        val root = File(context.filesDir, PROMPT_CACHE_ROOT)
        val dir = File(root, fingerprint)

        if (!dir.isDirectory && !dir.mkdirs()) return

        // Папки других моделей больше не нужны.
        root.listFiles()?.forEach { old ->
            if (old.isDirectory && old.name != fingerprint) old.deleteRecursively()
        }

        promptCacheDir = dir
        engine.setPromptCacheDir(dir.absolutePath)
    }

    /**
     * Что происходит с кэшем системной стены, человеческими словами.
     *
     * Нужно потому, что задающий метод библиотеки не возвращает ничего: он молча
     * ничего не делает и при недоступной папке, и при пустой системной стене.
     * Отличить «работает» от «молчит» можно двумя способами, оба здесь:
     * файл кэша, появившийся на диске после первого ответа, и строка
     * «Prompt cache dir set» в собственном логе движка.
     */
    fun getPromptCacheReport(): String {
        val dir = promptCacheDir
            ?: return "Кэш стены: ПАПКА НЕ СОЗДАНА — обсчёт будет каждый раз заново"

        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val bytes = files.sumOf { it.length() }
        val confirmed = runCatching { engine.getDebugLog().contains(PROMPT_CACHE_LOG_MARKER) }
            .getOrDefault(false)

        val state = when {
            files.isEmpty() -> "пуст (заполнится после первого ответа)"
            else -> "${files.size} ф., ${bytes / (1024 * 1024)} МБ"
        }
        val engineSays = if (confirmed) "движок папку принял" else "движок о папке не сообщил"

        return "Кэш стены: $state · $engineSays · ${dir.name}"
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

    /** Короткий отпечаток строки — только для имени папки, не для безопасности. */
    private fun shortHash(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Профиль потоков движка: 0 — экономия, 1 — баланс, 2 — производительность. */
        const val THREAD_MODE_PERFORMANCE = 2

        /** Байт, накапливаемых движком перед выдачей порции токенов. */
        const val STREAMING_BATCH_BYTES = 64

        /** Папка внутри filesDir, где лежат кэши по одной на модель. */
        const val PROMPT_CACHE_ROOT = "prompt_cache"

        /**
         * Строка, которую движок печатает в свой лог, приняв папку кэша.
         * Ищется по подстроке намеренно: полный текст строки содержит путь и
         * может измениться, а этот кусок переживёт правку формата.
         */
        const val PROMPT_CACHE_LOG_MARKER = "Prompt cache dir set"

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
