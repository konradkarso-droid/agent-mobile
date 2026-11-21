package com.uroboros.search

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.uroboros.memory.ActionProvenance
import com.uroboros.memory.ActionRequest
import com.uroboros.memory.ActionType
import com.uroboros.memory.EmergencyStop
import com.uroboros.memory.GateResult
import com.uroboros.memory.GatedAction
import com.uroboros.safety.DeviceSafetyWatchdog
import com.uroboros.safety.SafetyZone
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_SEARCH_KEY_LENGTH = 256

internal class SearchPlatform(
    app: Context,
    private val watchdog: DeviceSafetyWatchdog,
    private val processScope: CoroutineScope,
) {
    private val preferences = app.getSharedPreferences("web_search", Context.MODE_PRIVATE)

    private fun blocked() = EmergencyStop.isActive() || watchdog.zone.value == SafetyZone.CRITICAL

    private val engine = WebSearch(
        history = object : SearchHistory {
            override fun read(): List<SearchEntry> {
                val entries = JSONArray(preferences.getString(HISTORY, "[]"))
                return (0 until entries.length()).map { index ->
                    val item = entries.getJSONObject(index)
                    SearchEntry(
                        id = item.getString("id"),
                        requestId = item.getString("request"),
                        query = item.getString("query"),
                        channel = SearchChannel.valueOf(item.getString("channel")),
                        at = item.getLong("at"),
                        status = SearchStatus.valueOf(item.getString("status")),
                        count = item.getInt("count"),
                    )
                }
            }

            override fun write(entries: List<SearchEntry>): Boolean {
                val rows = JSONArray()
                for (entry in entries) rows.put(JSONObject()
                    .put("id", entry.id)
                    .put("request", entry.requestId)
                    .put("query", entry.query)
                    .put("channel", entry.channel.name)
                    .put("at", entry.at)
                    .put("status", entry.status.name)
                    .put("count", entry.count))
                return preferences.edit().putString(HISTORY, rows.toString()).commit()
            }
        },
        stopped = combine(EmergencyStop.active, watchdog.zone) { stop, zone -> stop || zone == SafetyZone.CRITICAL },
        blocked = ::blocked,
        available = ::hasKey,
        authorize = { channel ->
            GatedAction.evaluate(
                app,
                ActionRequest(
                    type = ActionType.NETWORK_CALL,
                    requestedBy = "web-search/" + channel.name,
                    provenance = ActionProvenance.USER,
                    crossesDeviceBoundary = true,
                    isReversible = false,
                ),
            ).result == GateResult.ALLOW
        },
        provider = BraveSearch(::readKey),
    )

    val state get() = engine.state

    suspend fun fromApp(query: String): SearchReply =
        processScope.async(Dispatchers.IO) { engine.search(query, SearchChannel.APP) }.await()

    suspend fun fromTelegram(
        query: String,
        messageId: String,
        stillOwner: () -> Boolean,
        onAccepted: () -> Unit,
    ): SearchReply = withContext(Dispatchers.IO) {
        engine.search(query, SearchChannel.TELEGRAM, messageId, stillOwner, onAccepted)
    }

    suspend fun snapshot(): SearchView = withContext(Dispatchers.IO) { engine.snapshot() }

    /** Наличие настройки. Ответ провайдера проверяется при самом поиске. */
    fun hasKey(): Boolean = preferences.contains(KEY)

    /** Ключ вводит владелец во Вратах. Пустое поле снимает ключ. */
    suspend fun changeKey(value: String): Boolean = withContext(Dispatchers.IO) {
        val token = value.trim()
        if (token.isEmpty()) return@withContext preferences.edit().remove(KEY).commit()
        if (token.length > MAX_SEARCH_KEY_LENGTH || token.any { it !in '!'..'~' }) return@withContext false
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            preferences.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        }.getOrDefault(false)
    }

    private fun readKey(): String? {
        val encoded = preferences.getString(KEY, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        check(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        return String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        return existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    companion object {
        private const val HISTORY = "history"
        private const val KEY = "key"
        private const val KEY_ALIAS = "uroboros_web_search_wrap"
        private const val IV_SIZE = 12
    }
}
