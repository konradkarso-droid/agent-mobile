package com.uroboros.safety

import android.content.Context

/**
 * Хранилище событий «дома» ([HomeEvents]): маленький файл настроек, не база —
 * событий не больше [HomeEvents.MAX_EVENTS], миграции не нужно (как
 * llm.ConversationTimes). Переживает кому и перезапуск.
 *
 * Пишут двое: поток зоны в ProcessObjects и обрыв в llm.LlmEngine — с разных
 * потоков, поэтому чтение и запись под одним замком.
 */
class HomeEventStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): List<HomeEvents.Event> = synchronized(LOCK) { load() }

    fun onZone(zone: SafetyZone, power: PowerState, at: Long) = synchronized(LOCK) {
        HomeEvents.withZone(load(), zone, power, at)?.let(::save)
    }

    fun onCut(byHeat: Boolean, at: Long) = synchronized(LOCK) {
        save(HomeEvents.withCut(load(), byHeat, at))
    }

    /** До какого времени события рассказаны (HomeEvents, «РАССКАЗ»); null — ни разу. */
    fun toldUpTo(): Long? = synchronized(LOCK) {
        prefs.getLong(TOLD, -1L).takeIf { it >= 0 }
    }

    /** Рассказ ушёл в движок в момент [at]: всё кончившееся до него рассказано. */
    fun markTold(at: Long) = synchronized(LOCK) {
        prefs.edit().putLong(TOLD, at).apply()
    }

    private fun load(): List<HomeEvents.Event> =
        prefs.getString(KEY, null).orEmpty().lineSequence().mapNotNull { HomeEvents.decode(it) }.toList()

    private fun save(events: List<HomeEvents.Event>) {
        prefs.edit().putString(KEY, events.joinToString("\n") { HomeEvents.encode(it) }).apply()
    }

    private companion object {
        const val NAME = "home_events"
        const val KEY = "events"
        const val TOLD = "told_up_to"

        /** Замок на процесс: хранилищ может быть создано несколько, файл один. */
        val LOCK = Any()
    }
}
