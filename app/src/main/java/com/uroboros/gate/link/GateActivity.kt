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
 * слева; «Токен», «Отвечать», «Мой тг», «Имена» — вверху справа: это решения
 * владельца о канале ([ChatPrefs]), их нельзя нажать случайно.
 *
 * ЧЕГО НЕ УМЕЕТ: подтверждений владельца (доверенность, знакомство, новый ключ
 * подписи) нет; «Мой тг» предлагает только тех, кто уже писал боту в личку;
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
            combine(GateLink.status, ChatLink.status, GateLink.lines) { s, w, l -> Triple(s, w, l) }.collect { (s, w, l) ->
                statusView.text = (s + w).joinToString("\n")
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

        fun topButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.END }
        top.addView(topButton("Отвечать") { consentDialog() })
        top.addView(topButton("Токен") { tokenDialog() })
        root.addView(top)
        val top2 = LinearLayout(this).apply { gravity = Gravity.END }
        top2.addView(topButton("Имена") { namesDialog() })
        top2.addView(topButton("Мой тг") { ownerDialog() })
        root.addView(top2)

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

    /**
     * Согласие агенту отвечать в телеграме — на срок ([com.uroboros.memory.StandingConsent]).
     * Здесь и только здесь оно даётся и снимается.
     */
    private fun consentDialog() {
        val now = System.currentTimeMillis()
        val held = ChatPrefs.consent(this, now)
        val days = com.uroboros.memory.StandingConsent.DEFAULT_TERM_MS / (24 * 60 * 60 * 1000)
        val b = AlertDialog.Builder(this).setTitle("Ответы в телеграме")
        if (held == null) {
            b.setMessage(
                "Разрешить агенту отвечать в телеграме на $days дней? Он отвечает обращённым к нему, " +
                    "опираясь на свою стену и ленту чата; ваша память с экрана туда не идёт. " +
                    "Каждое сообщение — в журнале действий. Снять можно здесь же или аварийным стопом."
            )
                .setPositiveButton("Разрешить") { _, _ -> ChatPrefs.grantConsent(this); ChatLink.refresh(this) }
                .setNegativeButton("Отмена", null)
        } else {
            b.setMessage("Разрешено до ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(held.expiresAt))}.")
                .setPositiveButton("Продлить на $days дней") { _, _ -> ChatPrefs.grantConsent(this); ChatLink.refresh(this) }
                .setNeutralButton("Снять") { _, _ -> ChatPrefs.revokeConsent(this); ChatLink.refresh(this) }
                .setNegativeButton("Отмена", null)
        }
        b.show()
    }

    /** «Это я»: выбрать свой аккаунт среди тех, кто писал боту в личку. */
    private fun ownerDialog() {
        lifecycleScope.launch {
            val peers = withContext(Dispatchers.IO) {
                runCatching { com.uroboros.llm.ChatDatabase.getInstance(applicationContext).chatLineDao().directPeers(5) }.getOrDefault(emptyList())
            }
            val held = ChatPrefs.owner(this@GateActivity)
            val b = AlertDialog.Builder(this@GateActivity).setTitle("Мой телеграм")
            if (peers.isEmpty()) {
                b.setMessage(
                    (held?.let { "Сейчас отмечен: ${it.name} (${it.key.id}).\n\n" } ?: "") +
                        "Напишите боту из своего телеграма в личку, потом откройте это окно снова — здесь появится ваше имя."
                )
            } else {
                b.setItems(peers.map { "${it.name} (${it.sender})" }.toTypedArray()) { _, i ->
                    val p = peers[i]
                    AlertDialog.Builder(this@GateActivity)
                        .setTitle("Это я?")
                        .setMessage(
                            "${p.name} (${p.sender}) — ваш аккаунт. Сказанное с него ляжет в вашу память без подписи. " +
                                "Права по-прежнему даёт только этот экран."
                        )
                        .setPositiveButton("Это я") { _, _ ->
                            ChatPrefs.setOwner(this@GateActivity, com.uroboros.memory.nav.PersonKey(p.sender), p.name)
                            ChatLink.refresh(this@GateActivity)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            if (held != null) b.setNeutralButton("Снять отметку") { _, _ -> ChatPrefs.clearOwner(this@GateActivity); ChatLink.refresh(this@GateActivity) }
            b.setNegativeButton("Закрыть", null).show()
        }
    }

    /** Имена агента для сортировщика в группах — все формы через запятую. */
    private fun namesDialog() {
        val field = EditText(this).apply {
            hint = "Александр, Саша, …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(ChatPrefs.names(this@GateActivity).joinToString(", "))
        }
        AlertDialog.Builder(this)
            .setTitle("Имена агента")
            .setMessage("Все формы, какими агента зовут в группах: падежи, уменьшительные, латиницей. Через запятую.")
            .setView(field)
            .setPositiveButton("Сохранить") { _, _ -> ChatPrefs.setNames(this, field.text.toString()); ChatLink.refresh(this) }
            .setNegativeButton("Отмена", null)
            .show()
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
