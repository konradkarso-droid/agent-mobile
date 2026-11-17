package com.uroboros

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.uroboros.memory.judge.CourtScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Экран суда — зачем и чего не умеет, у [CourtScreen]. Показывает его служба
 * агента (AgentService) уведомлением на весь экран.
 *
 * НЕ ЭКРАН АГЕНТА. Ни разговора, ни памяти, ни кнопок: он стоит поверх экрана
 * блокировки, и за ним не должно быть ничего, что требует замка (PresenceLock).
 * Отдельная задача и не в списке недавних — открыть через него приложение нельзя.
 *
 * Закрывается сам, как только самостоятельный суд не идёт или служба сняла
 * экран, — в том числе если суд кончился раньше, чем экран открылся. Касание
 * закрывает раньше.
 */
class CourtScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.screenBrightness = CourtScreen.BRIGHTNESS }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val words = TextView(this).apply {
            text = CourtScreen.TEXT
            setTextColor(CourtScreen.TEXT_COLOR)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    words,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
                setOnClickListener { finish() }
            }
        )

        // Суд кончился (служба сняла экран) или самостоятельный суд не идёт вовсе.
        lifecycleScope.launch {
            combine(AgentService.state, AgentService.courtScreen) { st, run ->
                st is AgentService.RunState.Running && st.self && run != null && run.endedAt == null
            }.collect { needed -> if (!needed) finish() }
        }
    }

    override fun onStart() {
        super.onStart()
        AgentService.courtScreenOpened()
    }
}
