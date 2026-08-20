package com.uroboros

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

    // Item 3 / Track A (2026-08-20): debug-only отображение provenance стикера.
    // Также переиспользуется в buttonGenerate для реального блока памяти в промпте
    // (см. Sticker→prompt injection, 2026-08-20).
    private fun sourceLabel(sourceName: String): String = when (sourceName) {
        SourceKind.USER_STATED.name -> "[от пользователя]"
        SourceKind.AGENT_INFERRED.name -> "[вывод агента]"
        SourceKind.OCR_EXTRACTED.name -> "[из скриншота]"
        else -> "[?]"
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
        codingTask = KotlinCodingTask(termuxCompiler, llmEngine, mediator, watchdog)

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

        binding.buttonGenerate.setOnClickListener {
            val userText = binding.editTextInput.text.toString()
            if (userText.isBlank()) {
                Toast.makeText(this, "Введите запрос для генерации", Toast.LENGTH_SHORT).show()
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

        // Item 7a шаг 4б: реальный TOTE-цикл (KotlinCodingTask) вместо временного
        // прямого вызова TermuxKotlinCompiler. Задача пока захардкожена внутри
        // KotlinCodingTask, не берётся из текстового поля.
        binding.buttonGenerate.setOnLongClickListener {
            if (!llmEngine.isLoaded) {
                Toast.makeText(this@MainActivity, "Сначала загрузите модель", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            binding.textResults.text = "Запускаю TOTE-цикл (компиляция + LLM)..."
            lifecycleScope.launch {
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
            }
            true
        }
    }
}
