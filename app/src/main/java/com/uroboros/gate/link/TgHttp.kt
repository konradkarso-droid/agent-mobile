package com.uroboros.gate.link

import com.uroboros.gate.GateBotApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Сеть Bot API: запрос и ответ, отправка файла, скачивание файла. */
class TgHttp(private val token: String) {

    /** Ответ телеграма; [readTimeoutMs] — для опроса больше его ожидания. */
    fun call(url: String, readTimeoutMs: Int = 30_000, body: ByteArray? = null, contentType: String? = null): GateBotApi.Reply {
        val (code, bytes) = exchange(url, readTimeoutMs, body, contentType, maxBytes = MAX_REPLY_BYTES)
        return GateBotApi.reply(String(bytes, Charsets.UTF_8), code)
    }

    fun sendDocument(chatId: String, fileName: String, bytes: ByteArray): GateBotApi.Reply {
        val boundary = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { String.format("%02z", it) }
        val body = GateBotApi.documentBody(chatId, fileName, bytes, boundary)
        return call(GateBotApi.methodUrl(token, "sendDocument"), body = body, contentType = "multipart/form-data; boundary=$boundary")
    }

    /**
     * Текст в чат (тело — [GateBotApi.messageBody]). Краткие сетевые обрывы
     * HTTPS повторяются несколько раз, чтобы ответ веб-поиска не терялся в
     * Telegram после разрыва TLS-соединения.
     */
    fun sendMessage(
        chatId: Long, text: String, replyTo: Long?, thread: Long?, disablePreview: Boolean = false,
    ): GateBotApi.Reply {
        val url = GateBotApi.methodUrl(token, "sendMessage")
        val body = GateBotApi.messageBody(chatId, text, replyTo, thread, disablePreview)
        var attempt = 1
        while (true) {
            try {
                val (code, bytes) = exchange(
                    url,
                    readTimeoutMs = 30_000,
                    body = body,
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8",
                    maxBytes = MAX_REPLY_BYTES,
                )
                return GateBotApi.reply(String(bytes, Charsets.UTF_8), code)
            } catch (e: IOException) {
                if (attempt >= SEND_RETRIES || !isTransientNetworkFailure(e)) throw e
                Thread.sleep(SEND_RETRY_DELAY_MS[attempt - 1])
                attempt++
            }
        }
    }

    /** Скачать файл по пути из `getFile`; больше [maxBytes] — не дочитывается, IOException. */
    fun download(path: String, maxBytes: Int): ByteArray {
        val (code, bytes) = exchange(GateBotApi.fileUrl(token, path), 30_000, null, null, maxBytes)
        if (code != 200) throw IOException("файл не скачан: HTTP $code")
        return bytes
    }

    private fun exchange(url: String, readTimeoutMs: Int, body: ByteArray?, contentType: String?, maxBytes: Int): Pair<Int, ByteArray> {
        require(URL(url).host == GateBotApi.HOST) { "адрес не телеграма" }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = readTimeoutMs
                c.instanceFollowRedirects = false
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", contentType)
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                return code to (stream?.use { readUpTo(it, maxBytes) } ?: ByteArray(0))
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            throw IOException(GateBotApi.scrub(e.javaClass.simpleName + ": " + (e.message ?: ""), token))
        }
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("ответ больше $maxBytes Б")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** Объявленное число: два повтора после первой попытки. */
        const val SEND_RETRIES = 3
        val SEND_RETRY_DELAY_MS = longArrayOf(500L, 1_500L)

        internal fun isTransientNetworkFailure(error: IOException): Boolean {
            val message = (error.javaClass.simpleName + " " + (error.message ?: "")).lowercase()
            return message.contains("ssl") ||
                message.contains("handshake") ||
                message.contains("connection closed") ||
                message.contains("connection reset") ||
                message.contains("unexpected end of file") ||
                message.contains("timed out") ||
                message.contains("sockettimeout") ||
                message.contains("eofexception")
        }

        /** Объявленное число, не подобранное: сотня обновлений с запасом. */
        const val MAX_REPLY_BYTES = 4 * 1024 * 1024
    }
}
