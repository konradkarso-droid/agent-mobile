package com.uroboros.search

import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Показывает выдачу по реплике владельца, отдельно от ленты разговора. */
internal class SearchPanel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val search: SearchPlatform,
) {
    private var dialog: AlertDialog? = null
    private var observer: Job? = null

    fun close() {
        val window = dialog
        closeObserver()
        window?.dismiss()
    }

    fun open(command: SearchCommand) {
        close()
        val result = TextView(context).apply {
            setTextIsSelectable(true)
            val inset = (20 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset)
            text = when (command) {
                is SearchCommand.Find -> "Ищу…"
                SearchCommand.Deferred -> SearchCommand.DEFERRED_MESSAGE
                else -> "Читаю историю поиска…"
            }
        }
        val window = AlertDialog.Builder(context)
            .setTitle(if (command == SearchCommand.History) "История поиска" else "Веб-поиск")
            .setView(ScrollView(context).apply { addView(result) })
            .setNegativeButton("Закрыть", null)
            .create()
        dialog = window
        window.setOnDismissListener {
            if (dialog === window) closeObserver()
        }
        window.show()
        when (command) {
            is SearchCommand.Find -> scope.launch {
                val reply = search.fromApp(command.query)
                if (window.isShowing && !reply.duplicate) result.text = reply.text
            }
            SearchCommand.Last, SearchCommand.History -> observer = scope.launch {
                search.snapshot()
                search.state.collect { view ->
                    result.text = if (command == SearchCommand.History) view.historyText() else view.latestText()
                }
            }
            SearchCommand.Deferred -> Unit
        }
    }

    private fun closeObserver() {
        observer?.cancel()
        observer = null
        dialog = null
    }
}
