package com.uroboros.llm

import android.content.Context
import com.uroboros.memory.RecordChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Склейка готовых записей на стороне приложения: выключатель, строки, ушедшие
 * модели ([RecordChunks.Candidate]), и итог последней ночной сборки. Одна на
 * процесс (см. [get]).
 *
 * Хранится файлами в `filesDir`, а не в базе: это кэш, а не память. Потеря
 * файла значит только, что ночь соберёт меньше кусков; ни одна запись памяти
 * от этого не меняется.
 *
 * Выключатель по умолчанию выключен: склейка меняет ответы (ответ со склейкой —
 * другой, чем при обсчёте целиком), и включает её человек.
 */
class RecordChunkStore private constructor(private val context: Context) {

    private val lock = Mutex()
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Склейка включена. Читается на каждый запрос разговора — дёшево. */
    var on: Boolean
        get() = prefs.getBoolean(KEY_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_ON, value).apply() }

    /** Итог последней ночной сборки кусков словами; null — сборки ещё не было. */
    var nightLine: String?
        get() = prefs.getString(KEY_NIGHT, null)
        set(value) { prefs.edit().putString(KEY_NIGHT, value).apply() }

    private val file get() = File(File(context.filesDir, ROOT), CANDIDATES_FILE)

    /** Запомненные строки; не прочитались — пусто. */
    suspend fun candidates(): List<RecordChunks.Candidate> = lock.withLock { withContext(Dispatchers.IO) { read() } }

    /** Добавить строки хода (см. [RecordChunks.remember]). Сбой записи — молча мимо: это кэш. */
    suspend fun remember(used: List<RecordChunks.Candidate>, now: Long) {
        if (used.isEmpty()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching { write(RecordChunks.remember(read(), used, now)) }
            }
        }
    }

    private fun read(): List<RecordChunks.Candidate> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            RecordChunks.Candidate(
                who = o.getString("who"),
                body = o.getString("body"),
                createdAt = o.getLong("createdAt"),
                uses = o.optInt("uses", 1),
                lastUsedAt = o.optLong("lastUsedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RecordChunks.Candidate>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("who", it.who).put("body", it.body).put("createdAt", it.createdAt)
                    .put("uses", it.uses).put("lastUsedAt", it.lastUsedAt),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) tmp.delete()
    }

    companion object {
        /** Корень склейки в `filesDir`: здесь строки и папки кусков по загрузкам (см. LlmEngine). */
        const val ROOT = "record_chunks"
        private const val CANDIDATES_FILE = "candidates.json"
        private const val PREFS = "record_splice"
        private const val KEY_ON = "on"
        private const val KEY_NIGHT = "night_line"

        @Volatile
        private var instance: RecordChunkStore? = null

        fun get(context: Context): RecordChunkStore =
            instance ?: synchronized(this) {
                instance ?: RecordChunkStore(context.applicationContext).also { instance = it }
            }
    }
}
