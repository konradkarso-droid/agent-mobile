package com.uroboros

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.databinding.ActivityMainBinding
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.ConfidenceLevel
import com.uroboros.memory.DatabaseExporter
import com.uroboros.memory.SourceKind
import com.uroboros.memory.TrustedMediator
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.will.SimplePendingQuerySource
import com.uroboros.will.ToteResult
import com.uroboros.will.TermuxKotlinCompiler
import com.uroboros.will.tasks.KotlinCodingTask
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediator: TrustedMediator
    private lateinit var llmEngine: LlmEngine
    private lateinit var watchdog: DeviceSafetyWatchdog
    private lateinit var termuxCompiler: TermuxKotlinCompiler
    private lateinit var codingTask: KotlinCodingTask
    private lateinit var pendingQuerySource: SimplePendingQuerySource

    private var isToteRunning = false

    // Палитра совпадает с activity_main.xml. Смысл цвета, а не украшение:
    // бирюзовый = обычное главное действие, фиолетовый = канал речи агента
    // (тем же цветом помечен блок "Ответ агента"). Красный нигде, кроме
    // аварийного стопа, не используется.
    private val colorIdle = ColorStateList.valueOf(Color.parseColor("#17697B"))
    private val colorToteRunning = ColorStateList.valueOf(Color.parseColor("#5B4B9E"))

    private val prefs by lazy { getSharedPreferences("uroboros_prefs", Context.MODE_PRIVATE) }

    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
    }

    private fun setToteRunningState(running: Boolean) {
        isToteRunning = running
        // Подписи короткие: подсказки о жестах вынесены одной строкой в
        // разметку, поэтому переносить внутри кнопки больше нечего.
        binding.buttonGenerate.text = if (running) "Спросить" else "Генерировать"
        binding.buttonGenerate.backgroundTintList = if (running) colorToteRunning else colorIdle
    }

    private fun scanModelFolder(folderUri: Uri): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(this, folderUri)
            ?: throw IllegalStateException("папка недоступна")
        return tree.listFiles().filter { it.isFile && it.name?.endsWith(".gguf", ignoreCase = true) == true }
    }

    private fun loadModelAndUpdateUi(uri: Uri, displayName: String) {
        binding.textModelStatus.text = "Загрузка модели \"$displayName\"..."
        binding.buttonLoadModel.isEnabled = false
        lifecycleScope.launch {
            val ok = llmEngine.loadModelFromUri(uri)
            binding.buttonLoadModel.isEnabled = true
            if (ok) {
                prefs.edit().putString(KEY_LAST_MODEL_URI, uri.toString()).apply()
                binding.textModelStatus.text = "Модель загружена: $displayName"
                binding.buttonGenerate.isEnabled = true
            } else {
                binding.textModelStatus.text = "Ошибка загрузки модели \"$displayName\""
            }
        }
    }

    private fun showModelChooser(models: List<DocumentFile>) {
        val names = models.map { it.name ?: "(без имени)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите модель")
            .setItems(names) { _, which ->
                val chosen = models[which]
                loadModelAndUpdateUi(chosen.uri, chosen.name ?: "(без имени)")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun tryAutoLoadOnStartup() {
        val folderUriString = prefs.getString(KEY_MODEL_FOLDER_URI, null)
        if (folderUriString == null) {
            binding.textModelStatus.text = "Модель не загружена"
            return
        }
        val folderUri = Uri.parse(folderUriString)

        val models = try {
            scanModelFolder(folderUri)
        } catch (e: SecurityException) {
            binding.textModelStatus.text =
                "Не удалось получить доступ к сохранённой папке (права утеряны) — выберите папку заново"
            return
        } catch (e: Exception) {
            binding.textModelStatus.text =
                "Не удалось прочитать сохранённую папку (${e.javaClass.simpleName}) — выберите папку заново"
            return
        }

        if (models.isEmpty()) {
            binding.textModelStatus.text =
                "В сохранённой папке не найдено файлов моделей (.gguf) — выберите другую папку или добавьте файл"
            return
        }

        val lastUriString = prefs.getString(KEY_LAST_MODEL_URI, null)
        val remembered = models.firstOrNull { it.uri.toString() == lastUriString }
        when {
            remembered != null -> loadModelAndUpdateUi(remembered.uri, remembered.name ?: "(без имени)")
            models.size == 1 -> loadModelAndUpdateUi(models[0].uri, models[0].name ?: "(без имени)")
            else -> {
                binding.textModelStatus.text = "Найдено несколько моделей — выберите вручную"
                showModelChooser(models)
            }
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(KEY_MODEL_FOLDER_URI, uri.toString()).apply()

            val models = try {
                scanModelFolder(uri)
            } catch (e: Exception) {
                binding.textModelStatus.text = "Не удалось прочитать выбранную папку (${e.javaClass.simpleName})"
                return@registerForActivityResult
            }

            when {
                models.isEmpty() -> binding.textModelStatus.text =
                    "В выбранной папке не найдено файлов моделей (.gguf)"
                models.size == 1 -> loadModelAndUpdateUi(models[0].uri, models[0].name ?: "(без имени)")
                else -> {
                    binding.textModelStatus.text = "Найдено несколько моделей — выберите"
                    showModelChooser(models)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mediator = TrustedMediator(applicationContext)
        watchdog = DeviceSafetyWatchdog(applicationContext, lifecycleScope)
        llmEngine = LlmEngine(applicationContext, watchdog)
        termuxCompiler = TermuxKotlinCompiler(applicationContext)
        pendingQuerySource = SimplePendingQuerySource()
        codingTask = KotlinCodingTask(
            termuxCompiler = termuxCompiler,
            llmEngine = llmEngine,
            mediator = mediator,
            watchdog = watchdog,
            pendingQuerySource = pendingQuerySource,
            onAnswer = { answer ->
                binding.textAnswerLabel.visibility = android.view.View.VISIBLE
                binding.textAnswer.visibility = android.view.View.VISIBLE
                binding.textAnswer.text = answer
            }
        )

        setToteRunningState(false)

        // Аварийный стоп есть в разметке, но обработчика у него пока нет.
        // Выключаем явно: кнопка, которая выглядит рабочей и ничего не
        // останавливает, хуже отсутствующей — она даёт ложное спокойствие.
        // Серая кнопка честно читается с экрана как "ещё не сделано".
        // Включить вместе с обработчиком (EmergencyStop.triggerManual).
        binding.buttonStop.isEnabled = false

        tryAutoLoadOnStartup()

        // Item 6, ремонт данных (2026-08-22). Разовый прогон при старте: возвращает
        // в спектр записи, застрявшие в RED без expiryTime из-за прежнего храповика
        // прогрева (потолок в Prism закрыл путь, но уже застрявшие строки правкой
        // кода не чинятся).
        //
        // Почему однократно и с выводом на экран, а не тихо при каждом запуске:
        // молчаливое самолечение маскировало бы новые баги того же класса — если
        // записи снова начнут проваливаться из спектра, автопочинка подчистит следы,
        // и канарейка ничего не покажет. Инструмент наблюдения и невидимый ремонтник
        // в одной системе не уживаются. Сама функция идемпотентна, так что флаг
        // нужен не для безопасности, а чтобы разовая операция осталась разовой.
        //
        // Флаг ставится ТОЛЬКО после успешного прогона: если ремонт упал, при
        // следующем запуске он попробует снова, а не запишется в выполненные.
        if (!prefs.getBoolean(KEY_LAYER_REPAIR_DONE, false)) {
            lifecycleScope.launch {
                try {
                    val repaired = mediator.repairStuckLayers()
                    prefs.edit().putBoolean(KEY_LAYER_REPAIR_DONE, true).apply()
                    binding.textResults.text = if (repaired > 0) {
                        "Ремонт слоёв: $repaired записей возвращено в спектр.\n" +
                            "Нажмите \"Показать память\" — в снимке \"Без срока вообще\" должно уменьшиться."
                    } else {
                        "Ремонт слоёв: застрявших записей не найдено."
                    }
                } catch (e: Exception) {
                    binding.textResults.text =
                        "Ремонт слоёв не выполнен (${e.javaClass.simpleName}) — будет повторён при следующем запуске"
                }
            }
        }

        // Миграция на новое устройство (2026-08-21): buttonSave двухрежимная —
        // короткое нажатие сохраняет, долгое экспортирует память. Долгое нажатие
        // раньше показывало debug-лог модели — убрано по согласованию
        // (сохранность реальных данных важнее).
        //
        // Подпись и цвет этой кнопки здесь больше НЕ задаются: они живут в
        // разметке. Прежние две строки перекрывали её оттуда и возвращали
        // двухэтажный текст поверх новой вёрстки.

        binding.buttonSave.setOnClickListener {
            val text = binding.editTextInput.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                // Дыра №4: провенанс задаётся явно. Это ручной ввод пользователя —
                // единственное место в проекте, где USER_STATED действительно верен.
                mediator.saveEvent(
                    content = text,
                    source = SourceKind.USER_STATED,
                    confidence = ConfidenceLevel.OBSERVED
                )
                binding.editTextInput.text.clear()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonSave.setOnLongClickListener {
            binding.buttonSave.isEnabled = false
            binding.textResults.text = "Экспортирую память (чекпоинт + копирование)..."
            lifecycleScope.launch {
                val exportedName = DatabaseExporter.exportToDownloads(applicationContext)
                binding.buttonSave.isEnabled = true
                binding.textResults.text = if (exportedName != null) {
                    "Экспорт готов: $exportedName\n(лежит в папке Download — оттуда переносите на новое устройство или загружайте в GitHub вручную)"
                } else {
                    "Не удалось экспортировать память — попробуйте ещё раз"
                }
            }
            true
        }

        binding.buttonShow.setOnClickListener {
            binding.buttonShow.isEnabled = false
            lifecycleScope.launch {
                try {
                    val query = binding.editTextInput.text.toString().ifBlank { null }
                    // Item 6, подшаг 1c (2026-08-22): снимок канарейки в шапке.
                    // Только чтение. Прежний вызов totalStickers() убран: канарейка
                    // уже печатает "Всего записей", а два независимых запроса к базе
                    // могли бы показать два разных числа на одном экране.
                    val canaryReport = mediator.memoryCanaryReport()
                    val results = mediator.getContext(query = query, limit = 20)
                    binding.textResults.text = if (results.isEmpty()) {
                        "$canaryReport\n\n(записей для показа нет)"
                    } else {
                        val lines = results.joinToString("\n\n") { sticker ->
                            "• ${sourceLabel(sticker.source)} ${sticker.content}\n  [${sticker.layer}] (обращений: ${sticker.accessCount})"
                        }
                        "$canaryReport\n\n$lines"
                    }
                } finally {
                    binding.buttonShow.isEnabled = true
                }
            }
        }

        binding.buttonShow.setOnLongClickListener {
            binding.textResults.text = codingTask.getDebugLog()
            true
        }

        binding.buttonLoadModel.setOnClickListener {
            val folderUriString = prefs.getString(KEY_MODEL_FOLDER_URI, null)
            if (folderUriString == null) {
                pickFolderLauncher.launch(null)
                return@setOnClickListener
            }
            val folderUri = Uri.parse(folderUriString)
            val models = try {
                scanModelFolder(folderUri)
            } catch (e: Exception) {
                Toast.makeText(this, "Папка недоступна (${e.javaClass.simpleName}) — выберите заново", Toast.LENGTH_LONG).show()
                pickFolderLauncher.launch(null)
                return@setOnClickListener
            }
            if (models.isEmpty()) {
                Toast.makeText(this, "В папке нет файлов .gguf", Toast.LENGTH_SHORT).show()
            } else {
                showModelChooser(models)
            }
        }

        binding.buttonLoadModel.setOnLongClickListener {
            pickFolderLauncher.launch(null)
            true
        }

        binding.buttonGenerate.setOnClickListener {
            val userText = binding.editTextInput.text.toString()
            if (userText.isBlank()) {
                val hint = if (isToteRunning) "Введите вопрос" else "Введите запрос для генерации"
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isToteRunning) {
                pendingQuerySource.submit(userText)
                binding.editTextInput.text.clear()
                Toast.makeText(this, "Вопрос отправлен агенту", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.buttonGenerate.isEnabled = false
            binding.textResults.text = ""
            lifecycleScope.launch {
                val stickers = mediator.getContext(query = userText, limit = 5)
                val prompt = if (stickers.isEmpty()) {
                    userText
                } else {
                    val memoryBlock = stickers.joinToString("\n") { sticker ->
                        "- ${sourceLabel(sticker.source)} ${sticker.content}"
                    }
                    "Контекст из памяти:\n$memoryBlock\n\nВопрос: $userText"
                }

                llmEngine.generateFlow(prompt).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            binding.textResults.append(event.text)
                        }
                        is GenerationEvent.Done -> {
                            binding.buttonGenerate.isEnabled = true
                        }
                        is GenerationEvent.Error -> {
                            binding.textResults.append("\n\n[Ошибка: ${event.message}]")
                            binding.buttonGenerate.isEnabled = true
                        }
                        else -> {
                            // Progress/Metrics/VLM-события игнорируем для текстового теста
                        }
                    }
                }
            }
        }

        binding.buttonGenerate.setOnLongClickListener {
            if (isToteRunning) {
                Toast.makeText(this@MainActivity, "Цикл уже идёт", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            if (!llmEngine.isLoaded) {
                Toast.makeText(this@MainActivity, "Сначала загрузите модель", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            setToteRunningState(true)
            binding.textAnswerLabel.visibility = android.view.View.GONE
            binding.textAnswer.visibility = android.view.View.GONE
            binding.textResults.text = "Запускаю TOTE-цикл (компиляция + LLM)..."
            lifecycleScope.launch {
                try {
                    when (val result = codingTask.run()) {
                        is ToteResult.Success -> {
                            binding.textResults.text =
                                "УСПЕХ за ${result.iterations} итераций:\n\n${result.finalState.code}"
                        }
                        is ToteResult.Evacuated -> {
                            binding.textResults.text =
                                "ЭВАКУАЦИЯ после ${result.iterations} итераций: ${result.reason}\n\n" +
                                    "Последняя ошибка компиляции:\n${result.lastOutcome?.detail ?: "(нет данных)"}\n\n" +
                                    "Последний код:\n${result.lastState.code}"
                        }
                        is ToteResult.HardStopped -> {
                            binding.textResults.text =
                                "ЖЁСТКИЙ СТОП после ${result.iterations} итераций (достигнут лимит)\n\n" +
                                    "Последняя ошибка компиляции:\n${result.lastOutcome?.detail ?: "(нет данных)"}\n\n" +
                                    "Последний код:\n${result.lastState.code}"
                        }
                    }
                } finally {
                    setToteRunningState(false)
                }
            }
            true
        }
    }

    companion object {
        private const val KEY_MODEL_FOLDER_URI = "model_folder_uri"
        private const val KEY_LAST_MODEL_URI = "last_model_uri"
        private const val KEY_LAYER_REPAIR_DONE = "layer_repair_done_2026_08_22"
    }
}
