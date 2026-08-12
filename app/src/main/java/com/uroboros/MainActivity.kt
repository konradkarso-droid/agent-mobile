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
import com.uroboros.memory.TrustedMediator
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediator: TrustedMediator
    private lateinit var llmEngine: LlmEngine

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
        llmEngine = LlmEngine(applicationContext)

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

            binding.buttonSave.setOnLongClickListener {
                val log = llmEngine.getDebugLog()
                binding.textResults.text = if (log.isBlank()) {
                    "Лог пуст"
                } else {
                    log
                }
                true
            }
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
                            "• ${sticker.content}\n  [${sticker.layer}] (обращений: ${sticker.accessCount})"
                        }
                        "Всего записей в базе: $total\n\n$lines"
                    }
                } finally {
                    binding.buttonShow.isEnabled = true
                }
            }
        }

        binding.buttonShow.setOnLongClickListener {
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

        binding.buttonLoadModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        binding.buttonGenerate.setOnClickListener {
            val prompt = binding.editTextInput.text.toString()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Введите запрос для генерации", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.buttonGenerate.isEnabled = false
            binding.textResults.text = ""
            lifecycleScope.launch {
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
    }
}
