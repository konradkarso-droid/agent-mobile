package com.uroboros.gate.link

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.uroboros.AgentService
import com.uroboros.access.PresenceLock
import com.uroboros.gate.GateBotApi
import com.uroboros.gate.store.GateTokenStore
import com.uroboros.memory.EmergencyStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран врат: состояние канала, последние события, токен бота и пробные
 * отправки. Открывается долгим нажатием на «Подробно» на главном экране.
 *
 * ЗА СВОИМ ЗАМКОМ ([PresenceLock]), как главный экран: здесь вводится токен —
 * весь бот, — и отсюда агент пишет другим агентам.
 *
 * РАСКЛАДКА ДЛЯ ЛЕВШИ: главные действия («Послать», «Познакомиться») внизу
 * слева; «Токен» — вверху справа: его нельзя нажать случайно.
 *
 * ЧЕГО НЕ УМЕЕТ: подтверждений владельца (доверенность, знакомство, новый ключ
 * подписи) нет; список собеседников не показывает — номер видно в строках;
 * принятое показывает только до перезапуска ([GateLink.lines]).
 */
class GateActivity : AppCompatActivity() {

    private lateinit var lock: PresenceLock
    private lateinit var statusView: TextView
    private lateinit var linesView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lock = PresenceLock(this) {
            EmergencyStop.triggerManual("Остановлено вручную кнопкой на экране врат.")
            AgentService.stop(applicationContext)
        }
        setContentView(build())
        lifecycleScope.launch {
            GateLink.status.combine(GateLink.lines) { s, l -> s to l }.collect { (s, l) ->
                statusView.text = s.joinToString("\n")
                linesView.text = if (l.isEmpty()) "Событий пока нет." else l.joinToString("\n")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lock.onScreenShown()
        // Опрос живёт в службе; экран на виду — Android разрешает её поднять.
        AgentService.live(applicationContext)
    }

    override fun onStop() {
        super.onStop()
        lock.onScreenLeft()
    }

    override fun onDestroy() {
        lock.release()
        super.onDestroy()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, mono: Boolean = false) = TextView(this).apply {
        setTextColor(Color.parseColor("#123540"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextIsSelectable(true)
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun build(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.END }
        top.addView(Button(this).apply {
            text = "Токен"
            isAllCaps = false
            setOnClickListener { tokenDialog() }
        })
        root.addView(top)

        statusView = text(13f)
        linesView = text(12f, mono = true).apply { setPadding(0, dp(8), 0, 0) }
        val scroll = ScrollView(this).apply {
            addView(LinearLayout(this@GateActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusView)
                addView(linesView)
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val to = EditText(this).apply {
            hint = "кому: @имя_бота или номер"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val words = EditText(this).apply {
            hint = "слова пробного пакета"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(to)
        root.addView(words)

        val actions = LinearLayout(this).apply { gravity = Gravity.START }
        actions.addView(Button(this).apply {
            text = "Послать"
            isAllCaps = false
            setOnClickListener {
                val t = to.text.toString()
                val w = words.text.toString()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { GateLink.sendWords(t, w) }
                    words.setText("")
                }
            }
        })
        actions.addView(Button(this).apply {
            text = "Познакомиться"
            isAllCaps = false
            setOnClickListener {
                val t = to.text.toString()
                lifecycleScope.launch { withContext(Dispatchers.IO) { GateLink.introduce(t) } }
            }
        })
        root.addView(actions)
        return root
    }

    /** Ввод токена: поле скрыто, как пароль; токен никуда, кроме хранилища, не идёт. */
    private fun tokenDialog() {
        val field = EditText(this).apply {
            hint = "токен от @BotFather"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Токен бота агента")
            .setMessage("Токен хранится только в этом телефоне, закрытый ключом хранилища. Никому его не пересылайте.")
            .setView(field)
            .setPositiveButton("Сохранить") { _, _ -> saveToken(field.text.toString().trim()) }
            .setNeutralButton("Стереть") { _, _ -> clearToken() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveToken(token: String) {
        if (!GateBotApi.looksLikeToken(token)) {
            statusView.append("\nТокен не сохранён: не похож на токен (номер, двоеточие, длинный хвост).")
            return
        }
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { GateTokenStore.write(applicationContext, token) }.exceptionOrNull() }
            statusView.append(if (error == null) "\nТокен сохранён." else "\nТокен не сохранён: ${error.javaClass.simpleName}")
        }
    }

    private fun clearToken() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { GateTokenStore.clear(applicationContext) } }
            statusView.append("\nТокен стёрт.")
        }
    }
}
