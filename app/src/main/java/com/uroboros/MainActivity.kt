package com.uroboros

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uroboros.databinding.ActivityMainBinding
import com.uroboros.memory.TrustedMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediator: TrustedMediator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediator = TrustedMediator(applicationContext)

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

        binding.buttonShow.setOnClickListener {
            lifecycleScope.launch {
                val query = binding.editTextInput.text.toString().ifBlank { null }
                val results = mediator.getContext(query = query, limit = 20)
                val total = mediator.totalStickers()

                binding.textResults.text = if (results.isEmpty()) {
                    "Пока пусто. Всего записей в базе: $total"
                } else {
                    val lines = results.joinToString("\n\n") { sticker ->
                        "• ${sticker.content}\n  (обращений: ${sticker.accessCount})"
                    }
                    "Всего записей в базе: $total\n\n$lines"
                }
            }
        }
    }
}
