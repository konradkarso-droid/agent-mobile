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

    // Item 9 (2026-08-21): true пока идёт codingTask.run(). Единственный флажок,
    // управляющий тем, что означает нажатие buttonGenerate прямо сейчас —
    // см. setToteRunningState().
    private var isToteRunning = false

    // Цвета кнопки buttonGenerate по состоянию — сознательно не розовый дефолт темы.
    private val colorIdle = ColorStateList.valueOf(Color.parseColor("#1565C0"))   // синий — обычный режим
    private val colorToteRunning = ColorStateList.valueOf(Color.parseColor("#EF6C00")) // оранжевый — режим "вопрос агенту"

    // Автозагрузка модели, вариант B (2026-08-21): запоминаем URI выбранной ПАПКИ
    // (не отдельного файла — папка не теряет право доступа после takePersistableUriPermission,
    // в отличие от одиночного файла из OpenDocument). При каждом запуске сканируем папку
    // на .gguf-файлы заново — список файлов внутри может измениться.
    private val prefs by lazy { getSharedPreferences("uroboros_prefs", Context.MODE_PRIVATE) }

    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
    }

    private fun setToteRunningState(running: Boolean) {
        isToteRunning = running
        binding.buttonGenerate.text = if (running) {
            "Спросить\n(кор. — вопрос агенту)"
        } else {
            "Генерировать\n(кор. — разово, дл. — цикл)"
        }
        binding.buttonGenerate.backgroundTintList = if (running) colorToteRunning else colorIdle
    }

    /** Сканирует сохранённую папку на файлы с расширением .gguf. Может бросить SecurityException,
     * если право доступа к папке было утеряно — вызывающий код должен это обработать явно. */
    private fun scanModelFolder(folderUri: Uri): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(this, folderUri)
            ?: throw IllegalStateException("папка недоступна")
        return tree.listFiles().filter { it.isFile && it.name?.endsWith(".gguf", ignoreCase = true) == true }
    }

    /** Общий путь загрузки модели по URI — используется и автозагрузкой, и ручным выбором
     * из диалога-списка. Запоминает последний выбор отдельно от папки. */
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

    /** Показывает диалог-список найденных моделей на выбор — используется и при
     * неоднозначном автовыборе на старте, и по короткому нажатию "Загрузить модель"
     * (переключение модели вручную, когда папка уже выбрана). */
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

    /**
     * Автозагрузка при старте (2026-08-21, item: автозагрузка модели). Явные сообщения
     * на каждый исход отказа (решение: пользователь не программист, тихий откат
     * непонятен) — единственное, что молчит, это самый первый запуск без сохранённой
     * папки вообще (это не ошибка, а норма).
     */
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
        tryAutoLoadOnStartup()

        binding.buttonSave.setOnClickListener {
            val text = binding.editTextInput.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Введите текст", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                mediator.saveEvent(text)
                binding.editTextInput.text.clear()
                Toast.makeText(this@MainActivity, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonSave.setOnLongClickListener {
            Toast.makeText(this@MainActivity, "Долгое нажатие сработало", Toast.LENGTH_SHORT).show()
            val log = llmEngine.getDebugLog()
            binding.textResults.text = if (log.isBlank()) {
                "Лог пуст"
            } else {
                log
            }
            true
        }

        binding.buttonShow.setOnClickListener {
            binding.buttonShow.isEnabled = false
            lifecycleScope.launch {
                try {
                    val query = binding.editTextInput.text.toString().ifBlank { null }
                    val results = mediator.getContext(query = query, limit = 20)
                    val total = mediator.totalStickers()
                    binding.textResults.text = if (results.isEmpty()) {
                        "Пока пусто. Всего записей в базе: $total"
                    } else {
                        val lines = results.joinToString("\n\n") { sticker ->
                            "• ${sourceLabel(sticker.source)} ${sticker.content}\n  [${sticker.layer}] (обращений: ${sticker.accessCount})"
                        }
                        "Всего записей в базе: $total\n\n$lines"
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

        // Автозагрузка модели (2026-08-21, вариант B): короткое нажатие теперь означает
        // "выбрать/сменить папку с моделями" при первой настройке, а если папка уже
        // выбрана — пересканировать и показать список для ручного переключения модели.
        // Долгое нажатие — принудительно выбрать папку заново (сменить папку целиком).
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
    }
}
