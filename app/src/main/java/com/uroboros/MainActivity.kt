package com.uroboros

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    // Не вынесено в colors.xml: этот файл не видели, чтобы не гадать на неполном
    // контексте, а хардкод здесь — единственное место, где цвет вообще решается.
    private val colorIdle = ColorStateList.valueOf(Color.parseColor("#1565C0"))   // синий — обычный режим
    private val colorToteRunning = ColorStateList.valueOf(Color.parseColor("#EF6C00")) // оранжевый — режим "вопрос агенту"

    // Item 3 / Track A (2026-08-20): debug-only отображение provenance стикера.
    // Также переиспользуется в buttonGenerate для реального блока памяти в промпте
    // (см. Sticker→prompt injection, 2026-08-20).
    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
    }

    /**
     * Item 9: единственное место, где решается текст/цвет/поведение buttonGenerate.
     * running=false — обычный режим (кор. — генерация, дл. — запуск TOTE-цикла);
     * running=true — режим вопроса (кор. — отправить вопрос в идущий цикл,
     * дл. — заблокировано, чтобы не запустить второй цикл поверх идущего).
     */
    private fun setToteRunningState(running: Boolean) {
        isToteRunning = running
        binding.buttonGenerate.text = if (running) {
            "Спросить\n(кор. — вопрос агенту)"
        } else {
            "Генерировать\n(кор. — разово, дл. — цикл)"
        }
        binding.buttonGenerate.backgroundTintList = if (running) colorToteRunning else colorIdle
    }

    private val pickModelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            binding.textModelStatus.text = "Загрузка модели..."
            binding.buttonLoadModel.isEnabled = false
            lifecycleScope.launch {
                val ok = llmEngine.loadModelFromUri(uri)
                binding.buttonLoadModel.isEnabled = true
                if (ok) {
                    binding.textModelStatus.text = "Модель загружена"
                    binding.buttonGenerate.isEnabled = true
                } else {
                    binding.textModelStatus.text = "Ошибка загрузки модели"
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

        // Item 8a диагностика (2026-08-17): детальный лог TOTE-цикла (компиляция +
        // вердикты структурной проверки C/B по каждой итерации). Перенесено сюда с
        // "Показать память" — просмотр/снятие reviewPending переехал на долгое
        // нажатие "Загрузить модель" (см. ниже).
        binding.buttonShow.setOnLongClickListener {
            binding.textResults.text = codingTask.getDebugLog()
            true
        }

        binding.buttonLoadModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        // Перенесено с "Показать память" (2026-08-17) — просмотр/снятие reviewPending
        // по-прежнему нужно (единственный способ разморозить записи, помеченные
        // RiskTrigger), просто освободили слот под лог TOTE-цикла выше.
        binding.buttonLoadModel.setOnLongClickListener {
            lifecycleScope.launch {
                val pending = mediator.getPendingReview()
                if (pending.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Замороженных записей нет", Toast.LENGTH_SHORT).show()
                } else {
                    val preview = pending.joinToString("\n\n") { sticker ->
                        "• ${sticker.content}\n  [${sticker.layer}]"
                    }
                    binding.textResults.text = "Разблокировано ${pending.size} записей:\n\n$preview"
                    mediator.clearAllPendingReview()
                    Toast.makeText(this@MainActivity, "Снят флаг у ${pending.size} записей", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        // Item 9 (2026-08-21): короткое нажатие теперь ветвится по isToteRunning —
        // пока цикл идёт, это поле отправляет вопрос в канал pendingQuerySource
        // вместо обычной генерации (см. setToteRunningState()).
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
                // Item 3 / Track A → prompt injection (2026-08-20): подмешиваем контекст
                // из памяти в user-turn (не в system_prompt — сломало бы KV-кэш system-
                // части, см. заметку в backlog). limit=5 — временный плейсхолдер, НЕ
                // откалиброван; пересмотреть после 5b(c) (rolling context auto-trim) и
                // реальных данных с целевого устройства.
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

        // Item 7a шаг 4б / Item 9 (2026-08-21): запуск реального TOTE-цикла.
        // Заблокировано, пока isToteRunning=true — иначе можно было бы случайно
        // запустить второй цикл поверх уже идущего (это и была скрытая гонка,
        // которую заодно чинит переиспользование этой же кнопки под вопросы).
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
}
