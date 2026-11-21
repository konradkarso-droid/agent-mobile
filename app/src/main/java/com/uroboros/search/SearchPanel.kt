package com.uroboros.search

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class SearchPanel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val search: SearchPlatform,
) {
    private var dialog: AlertDialog? = null
    private var observer: Job? = null

    fun close() {
        observer?.cancel()
        dialog?.dismiss()
        dialog = null
    }

    fun open() {
        close()
        val input = EditText(context).apply {
            hint = "Поисковая фраза для Brave"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(MAX_QUERY_LENGTH))
        }
        val result = TextView(context).apply {
            setTextIsSelectable(true)
            text = "Читаю историю поиска…"
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, inset)
            addView(input)
            addView(result)
        }
        val scroll = ScrollView(context).apply { addView(body) }
        val window = AlertDialog.Builder(context)
            .setTitle("Веб-поиск")
            .setView(scroll)
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Ключ Brave", null)
            .setPositiveButton("Искать", null)
            .create()
        dialog = window
        window.setOnDismissListener {
            if (dialog === window) {
                observer?.cancel()
                observer = null
                dialog = null
            }
        }
        window.show()
        window.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            close()
            showKey()
        }
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query = input.text.toString().trim()
            if (query.isEmpty()) {
                input.error = "Введи фразу для поиска"
                return@setOnClickListener
            }
            scope.launch {
                val reply = search.fromApp(query)
                if (window.isShowing && !reply.duplicate) result.text = withHistory(reply.text)
            }
        }
        observer = scope.launch {
            search.snapshot()
            search.state.collect { view -> result.text = withHistory(view.latestText()) }
        }
    }

    private fun withHistory(text: String): String {
        val history = search.state.value.historyText()
        return if (history.isEmpty()) text else "$text\n\nНедавние поиски\n$history"
    }

    private fun showKey() {
        val input = EditText(context).apply {
            hint = "Ключ Brave Search API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(256))
        }
        val window = AlertDialog.Builder(context)
            .setTitle("Ключ Brave Search")
            .setMessage("Ключ хранится на этом телефоне. Поисковику передаётся введённая фраза.")
            .setView(input)
            .setNegativeButton("Назад") { _, _ -> open() }
            .setNeutralButton("Удалить ключ", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog = window
        window.show()
        fun saveKey(value: String) {
            scope.launch {
                val saved = search.changeKey(value)
                if (!window.isShowing) return@launch
                if (saved) open() else input.error = "Ключ не удалось сохранить"
            }
        }
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val token = input.text.toString().trim()
            if (token.isEmpty()) input.error = "Введи ключ" else saveKey(token)
        }
        window.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { saveKey("") }
    }
}
