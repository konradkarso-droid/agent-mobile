package com.uroboros.search

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Один HTTPS GET к API Brave. URL из выдачи остаются данными для показа.
 * Лимит байтов применяется к фактически прочитанному ответу. Отмена закрывает соединение.
 */
internal class BraveSearch(private val readKey: () -> String?) : SearchProvider {
    // Даже если старый DNS-запрос не прервался сразу, второй сетевой поток не запускается.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val network = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    override suspend fun search(query: String): SearchPage = suspendCancellableCoroutine { continuation ->
        val opened = AtomicReference<HttpsURLConnection?>()
        val worker = network.launch {
            try {
                val key = readKey() ?: throw IOException("ключ поиска недоступен")
                val encoded = URLEncoder.encode(query, "UTF-8")
                val request = URI("$ENDPOINT?q=$encoded&count=$SEARCH_LIMIT&text_decorations=false").toURL()
                val connection = request.openConnection() as HttpsURLConnection
                opened.set(connection)
                if (!continuation.isActive) return@launch
                continuation.resume(read(connection, key) { continuation.isActive })
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                opened.getAndSet(null)?.disconnect()
            }
        }
        continuation.invokeOnCancellation {
            worker.cancel()
            opened.getAndSet(null)?.disconnect()
        }
    }

    private fun read(connection: HttpsURLConnection, key: String, active: () -> Boolean): SearchPage {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("X-Subscription-Token", key)
        if (!active()) throw CancellationException()
        if (connection.responseCode != 200) throw IOException("ответ поисковика недоступен")
        if (connection.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json" ||
            connection.contentEncoding?.equals("identity", ignoreCase = true) == false
        ) throw IOException("неожиданный формат выдачи")
        if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("выдача превышает лимит")

        val body = connection.inputStream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                if (!active()) throw CancellationException()
                val read = input.read(buffer)
                if (read < 0) break
                if (bytes.size() + read > MAX_RESPONSE_BYTES) throw IOException("выдача превышает лимит")
                bytes.write(buffer, 0, read)
            }
            bytes.toString("UTF-8")
        }
        return parse(body)
    }

    companion object {
        private const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        internal fun parse(body: String): SearchPage = JsonReader(StringReader(body)).use { data ->
            var type: String? = null
            var page = SearchPage(emptyList())
            data.beginObject()
            while (data.hasNext()) when (data.nextName()) {
                "type" -> type = data.readString()
                "web" -> if (data.peek() == JsonToken.BEGIN_OBJECT) page = readWeb(data) else data.skipValue()
                else -> data.skipValue()
            }
            data.endObject()
            if (type != "search" || data.peek() != JsonToken.END_DOCUMENT) {
                throw IOException("неожиданный ответ поисковика")
            }
            page
        }

        private fun readWeb(data: JsonReader): SearchPage {
            val hits = mutableListOf<SearchHit>()
            var reported = false
            data.beginObject()
            while (data.hasNext()) {
                if (data.nextName() != "results" || data.peek() != JsonToken.BEGIN_ARRAY) {
                    data.skipValue()
                    continue
                }
                data.beginArray()
                var index = 0
                while (data.hasNext()) {
                    reported = true
                    if (index++ < SEARCH_LIMIT && data.peek() == JsonToken.BEGIN_OBJECT) {
                        readHit(data)?.let(hits::add)
                    } else {
                        data.skipValue()
                    }
                }
                data.endArray()
            }
            data.endObject()
            return SearchPage(hits, reported)
        }

        private fun readHit(data: JsonReader): SearchHit? {
            var title = ""
            var url = ""
            var description = ""
            data.beginObject()
            while (data.hasNext()) when (data.nextName()) {
                "title" -> title = data.readString()
                "url" -> url = data.readString()
                "description" -> description = data.readString()
                else -> data.skipValue()
            }
            data.endObject()
            return SearchHit.from(title, url, description)
        }

        private fun JsonReader.readString(): String {
            if (peek() == JsonToken.STRING) return nextString()
            skipValue()
            return ""
        }
    }
}
