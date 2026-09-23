package com.uroboros.access

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Замок на экране агента: всё, что идёт между человеком и агентом, заперто,
 * пока человек не подтвердит себя отпечатком или PIN самого телефона.
 *
 * ОТ ЧЕГО ЗАЩИЩАЕТ. От случайных рук: телефон дали посмотреть, взяли со стола
 * разблокированным. Без замка всё, что такой человек напишет агенту, легло бы
 * в память как слова владельца, и отличить это потом было бы нечем.
 *
 * ОТ ЧЕГО НЕ ЗАЩИЩАЕТ. От того, кто умеет: отладочную сборку с включённой
 * отладкой по USB вскрывают без всякого экрана, а экспорт кладёт базу
 * открытым файлом. Замок закрывает экран, а не данные.
 *
 * КОГДА СПРАШИВАЕТ. При каждом появлении экрана: при запуске и при возврате
 * из другого приложения. Своего срока простоя нет намеренно. Блокировка самого
 * телефона ловит только того, кто взял его после того, как погас экран, — там
 * телефон спросит PIN и без нас. Главный случай — телефон, переданный из рук в
 * руки разблокированным, — ловит только проверка при возврате на экран.
 *
 * ПРОВЕРЯЕТ СИСТЕМА, А НЕ МЫ. Приложение не хранит никакого секрета и не
 * считает неверные попытки: паузы и блокировку после ошибок ведёт Android.
 * Цена этого — о неудачных попытках приложение не узнаёт вовсе.
 *
 * НЕТ БЛОКИРОВКИ ЭКРАНА — ЗАМОК НЕ ОТКРЫВАЕТСЯ. Если на телефоне не задан ни
 * PIN, ни рисунок, системе нечего спросить. Барьер при этом остаётся закрытым
 * и говорит, что поставить в настройках. Открыться в таком случае значило бы,
 * что снятие PIN в настройках телефона тихо выключает замок.
 *
 * ПРИЧИНА НЕ НАЗЫВАЕТСЯ. Экран замка одинаков для владельца и для чужого: он не
 * говорит, что кого-то в чём-то заподозрили, — только что нужен отпечаток или
 * PIN.
 *
 * СТОП ДОСТУПЕН И ПРИ ЗАКРЫТОМ ЗАМКЕ. Остановка — действие в безопасную
 * сторону: она только запрещает. Снять стоп можно лишь после открытия замка,
 * так что чужой, нажавший его, может помешать, но не навредить. Прятать
 * аварийный тормоз за проверкой значило бы задержать его ровно тогда, когда он
 * нужен.
 *
 * ЭТО НЕ АВАРИЙНЫЙ СТОП. Замок живёт отдельно от EmergencyStop и не пользуется
 * им: будь он сделан через стоп, открытие замка снимало бы и стоп, взведённый
 * владельцем, и тот исчезал бы молча.
 *
 * РАБОТУ БЕЗ ЧЕЛОВЕКА НЕ ОСТАНАВЛИВАЕТ. Разбор памяти и сны идут в службе
 * переднего плана и замком не затрагиваются: подтверждать себя там некому.
 *
 * ЧЕГО НЕ УМЕЕТ:
 * - окно, открытое кодом ПОСЛЕ того, как замок встал (например, по окончании
 *   фоновой работы), появится поверх замка — Android кладёт новое окно выше
 *   прежних. Окна, открытые до замка, он перекрывает;
 * - на Android ниже 13 в списке недавних приложений виден снимок экрана агента
 *   до замка: скрыть только его, не запретив снимки экрана вообще, там нечем;
 * - пересоздание экрана (например, поворот) начинает замок заново и спрашивает
 *   ещё раз: состояние живёт в экране, а не в процессе.
 */
class PresenceLock(
    private val activity: Activity,
    private val onEmergencyStop: () -> Unit,
) {
    private var locked = true

    /**
     * Системный запрос открыт. На Android 10 ввод PIN уходит в отдельное
     * системное окно, и наш экран при этом получает «ушёл с экрана». Без этого
     * признака замок запирался бы заново посреди собственной проверки.
     */
    private var asking = false

    private var cancelSignal: CancellationSignal? = null
    private var cover: Dialog? = null
    private var messageView: TextView? = null
    private var settingsButton: Button? = null

    init {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
    }

    /** Экран появился. Вызывать из onStart. */
    fun onScreenShown() {
        if (!locked) return
        showCover()
        if (!asking) ask()
    }

    /**
     * Экран ушёл. Вызывать из onStop. Замок встаёт сразу, а не при возврате:
     * тогда при возвращении на экран содержимое агента не мелькнёт ни на кадр.
     */
    fun onScreenLeft() {
        if (asking) return
        locked = true
        showCover()
    }

    /** Экран уничтожается. Вызывать из onDestroy. */
    fun release() {
        cancelSignal?.cancel()
        cancelSignal = null
        cover?.dismiss()
        cover = null
    }

    private fun ask() {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            showNoScreenLock()
            return
        }
        settingsButton?.visibility = View.GONE
        messageView?.text = MESSAGE_ASK
        val signal = CancellationSignal()
        cancelSignal = signal
        asking = true
        try {
            buildPrompt().authenticate(signal, activity.mainExecutor, callback)
        } catch (e: Exception) {
            asking = false
            messageView?.text = "Системная проверка не открылась: ${e.message ?: e.javaClass.simpleName}.\n\n$MESSAGE_RETRY"
        }
    }

    private fun buildPrompt(): BiometricPrompt {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(TITLE)
            .setSubtitle("Отпечаток или PIN телефона")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            asking = false
            cancelSignal = null
            locked = false
            cover?.dismiss()
            cover = null
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            asking = false
            cancelSignal = null
            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                showNoScreenLock()
            } else {
                messageView?.text = "Не открыто: $errString\n\n$MESSAGE_RETRY"
            }
        }

        // Неузнанный отпечаток: системный запрос остаётся открытым и сам
        // предлагает попробовать снова, делать здесь нечего.
        override fun onAuthenticationFailed() = Unit
    }

    private fun showNoScreenLock() {
        messageView?.text =
            "На телефоне не задана блокировка экрана, поэтому подтвердить, кто у экрана, " +
                "нечем. Пока её нет, агент заперт.\n\nПоставьте PIN, рисунок или пароль: " +
                "Настройки телефона → Безопасность → Блокировка экрана. Потом нажмите «Открыть»."
        settingsButton?.visibility = View.VISIBLE
    }

    private fun showCover() {
        if (cover?.isShowing == true) return
        if (activity.isFinishing || activity.isDestroyed) return

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAGE)
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        // Стоп — в верхнем правом углу, как на главном экране: дальняя точка
        // для большого пальца левой руки, задеть случайно трудно.
        val stopRow = LinearLayout(activity).apply { gravity = Gravity.END }
        stopRow.addView(Button(activity).apply {
            text = "Стоп"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(COLOR_STOP)
            setOnClickListener { onEmergencyStop() }
        })
        root.addView(stopRow)

        root.addView(spacer())

        root.addView(TextView(activity).apply {
            text = TITLE
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        val message = TextView(activity).apply {
            text = MESSAGE_ASK
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, 0)
        }
        messageView = message
        root.addView(message)

        root.addView(spacer())

        // Главное действие — внизу слева, под большим пальцем левой руки.
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        actions.addView(Button(activity).apply {
            text = "Открыть"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(COLOR_PRIMARY)
            setOnClickListener { if (!asking) ask() }
        })
        val settings = Button(activity).apply {
            text = "Настройки блокировки"
            isAllCaps = false
            setTextColor(COLOR_TEXT)
            visibility = View.GONE
            setOnClickListener {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        settingsButton = settings
        actions.addView(settings)
        root.addView(actions)

        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(root)
        dialog.setCancelable(false)
        // «Назад» уводит приложение в фон, а не снимает замок: иначе замок
        // закрывался бы одной кнопкой.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                activity.moveTaskToBack(true)
                true
            } else {
                keyCode == KeyEvent.KEYCODE_BACK
            }
        }
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
        cover = dialog
    }

    private fun spacer() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics,
    ).toInt()

    private companion object {
        const val TITLE = "Агент заперт"
        const val MESSAGE_ASK = "Нужен отпечаток или PIN телефона."
        const val MESSAGE_RETRY = "Нажмите «Открыть», чтобы попробовать снова."

        // Цвета главного экрана: фон страницы, текст, основное действие, стоп.
        val COLOR_PAGE = Color.parseColor("#EAF3F6")
        val COLOR_TEXT = Color.parseColor("#123540")
        val COLOR_PRIMARY = Color.parseColor("#17697B")
        val COLOR_STOP = Color.parseColor("#D6453E")
    }
}
