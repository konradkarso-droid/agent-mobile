package com.uroboros.gate

import java.net.URLEncoder

/**
 * Bot API телеграма — всё, что не ходит в сеть: адреса, разбор ответов,
 * тело отправки файла, чистка токена из текста. Сеть — в `gate.link.TgHttp`.
 *
 * ОДИН АДРЕС. Агент ходит в сеть только к [HOST], и адрес собирается только
 * здесь ([methodUrl], [fileUrl]). Это граница кода, а не системы: разрешение
 * Android на интернет даёт любой адрес, и запретить другие оно не умеет.
 *
 * ТОКЕН В АДРЕСЕ. Bot API требует токен в пути запроса (`/bot<токен>/…`), а
 * текст ошибки сети в Java часто несёт адрес целиком. Поэтому любой текст,
 * который идёт на экран из сети, проходит [scrub]: токен — это весь бот, и
 * строка прибора, показанная или снятая с экрана, унесла бы его.
 *
 * ЧЕГО НЕ УМЕЕТ: не проверяет, что ответил именно телеграм, — это делает
 * HTTPS; из обновлений берёт только сообщения и их правку (`allowed_updates`
 * в [updatesUrl]); пересылку, ответы, подписи к файлу не читает — вратам они
 * не нужны.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateBotApi {

    const val HOST = "api.telegram.org"

    /** Сколько сервер держит опрос без новостей, с. Объявленное число, не подобранное. */
    const val POLL_SECONDS = 50

    /** Пауза после сбоя: начало и потолок. Объявленные числа, не подобранные. */
    const val PAUSE_FIRST_MS = 30_000L
    const val PAUSE_MAX_MS = 10 * 60_000L

    fun methodUrl(token: String, method: String) = "https://$HOST/bot$token/$method"

    fun fileUrl(token: String, path: String) = "https://$HOST/file/bot$token/$path"

    /** Опрос со сдвигом [offset]: только новые сообщения и их правки. */
    fun updatesUrl(token: String, offset: Long?): String {
        val allowed = URLEncoder.encode("[\"message\",\"edited_message\"]", "UTF-8")
        return methodUrl(token, "getUpdates") + "?timeout=$POLL_SECONDS&allowed_updates=$allowed" +
            (offset?.let { "&offset=$it" } ?: "")
    }

    fun getFileUrl(token: String, fileId: String) = methodUrl(token, "getFile") + "?file_id=" + URLEncoder.encode(fileId, "UTF-8")

    /** Вид токена у @BotFather: номер, двоеточие, хвост. Проверка от опечатки, не от подделки. */
    private val TOKEN = Regex("^[0-9]{5,}:[A-Za-z0-9_-]{30,}$")

    fun looksLikeToken(s: String) = TOKEN.matches(s)

    private val TOKEN_ANYWHERE = Regex("[0-9]{5,}:[A-Za-z0-9_-]{30,}")

    /** Текст без токена: и этого [token], и всего, что похоже на токен. */
    fun scrub(text: String, token: String?): String {
        var s = text
        if (!token.isNullOrEmpty()) s = s.replace(token, "‹токен›")
        return TOKEN_ANYWHERE.replace(s, "‹токен›")
    }

    /** Пауза после сбоя номер [failures] (с первого): 30 с, 60 с… не больше 10 мин. */
    fun pauseMs(failures: Int): Long {
        var ms = PAUSE_FIRST_MS
        repeat(maxOf(0, failures - 1)) { ms = minOf(ms * 2, PAUSE_MAX_MS) }
        return ms
    }

    // --- Ответы ---

    sealed class Reply {
        class Ok(val result: Any?) : Reply()

        /** [code] — `error_code` телеграма (409 — опрашивает кто-то ещё, 401 — токен не принят). */
        data class Error(val code: Int, val description: String) : Reply()
    }

    /** Ответ телеграма целиком; не разобрался — ошибка с кодом HTTP [httpCode]. */
    fun reply(text: String, httpCode: Int): Reply {
        val o = try {
            TgJson.obj(TgJson.parse(text))
        } catch (e: TgJson.Malformed) {
            null
        } ?: return Reply.Error(httpCode, "ответ не JSON (HTTP $httpCode)")
        if (TgJson.bool(o["ok"]) == true) return Reply.Ok(o["result"])
        val code = TgJson.long(o["error_code"])?.toInt() ?: httpCode
        return Reply.Error(code, TgJson.str(o["description"]) ?: "без описания")
    }

    /** Кто я: номер бота и его имя пользователя. */
    data class Me(val id: Long, val username: String?)

    fun me(result: Any?): Me? {
        val o = TgJson.obj(result) ?: return null
        val id = TgJson.long(o["id"]) ?: return null
        return Me(id, TgJson.str(o["username"]))
    }

    /** Файл в сообщении: как скачать и какого он размера (null — телеграм не сказал). */
    data class Doc(val fileId: String, val size: Long?)

    /** Сообщение из обновления — то, что нужно вратам, до скачивания файла. */
    data class Message(
        val chatId: Long,
        val privateChat: Boolean,
        val fromId: Long?,
        val fromUsername: String?,
        val senderChatId: Long?,
        val edited: Boolean,
        val dateSec: Long,
        val doc: Doc?,
    ) {
        fun toTg(file: ByteArray?) = TgMessage(chatId, privateChat, fromId, senderChatId, edited, dateSec, file)
    }

    /** Обновление: номер (для сдвига опроса) и сообщение, если это оно. */
    data class Update(val id: Long, val message: Message?)

    fun updates(result: Any?): List<Update> = TgJson.list(result).orEmpty().mapNotNull { item ->
        val u = TgJson.obj(item) ?: return@mapNotNull null
        val id = TgJson.long(u["update_id"]) ?: return@mapNotNull null
        val edited = u["message"] == null && u["edited_message"] != null
        Update(id, message(TgJson.obj(u["message"] ?: u["edited_message"]), edited))
    }

    private fun message(m: Map<String, Any?>?, edited: Boolean): Message? {
        if (m == null) return null
        val chat = TgJson.obj(m["chat"]) ?: return null
        val chatId = TgJson.long(chat["id"]) ?: return null
        val from = TgJson.obj(m["from"])
        val doc = TgJson.obj(m["document"])?.let { d -> TgJson.str(d["file_id"])?.let { Doc(it, TgJson.long(d["file_size"])) } }
        return Message(
            chatId = chatId,
            privateChat = TgJson.str(chat["type"]) == "private",
            fromId = from?.let { TgJson.long(it["id"]) },
            fromUsername = from?.let { TgJson.str(it["username"]) },
            senderChatId = TgJson.obj(m["sender_chat"])?.let { TgJson.long(it["id"]) },
            edited = edited,
            dateSec = TgJson.long(m["date"]) ?: 0L,
            doc = doc,
        )
    }

    /**
     * Скачивать ли файл сообщения: только то, что [GateTelegram.incoming] может
     * пустить во врата, и не больше предела размера врат. Остальное не
     * скачивается — отказ скажет [GateTelegram.incoming] без файла.
     */
    fun needsFile(m: Message, limits: GateIntake.Limits = GateIntake.Limits.DECLARED): Boolean =
        !m.edited && m.senderChatId == null && m.fromId != null && m.privateChat &&
            m.doc != null && GateTelegram.worthFetching(m.doc.size, limits.copy(maxBytes = maxOf(limits.maxBytes, limits.maxKonvertBytes)))

    /** Путь файла из ответа `getFile`. */
    fun filePath(result: Any?): String? = TgJson.obj(result)?.let { TgJson.str(it["file_path"]) }

    /** Номер чата, куда ушло сообщение, из ответа `sendDocument`. */
    fun sentChatId(result: Any?): Long? = TgJson.obj(result)?.let { TgJson.obj(it["chat"]) }?.let { TgJson.long(it["id"]) }

    // --- Отправка файла ---

    /**
     * Тело `sendDocument`: `multipart/form-data` с полем `chat_id` и файлом
     * `document`. [boundary] — разделитель, которого нет в файле (у форм врат
     * случайной строки из 32 знаков hex там быть не может, но проверяется).
     */
    fun documentBody(chatId: String, fileName: String, bytes: ByteArray, boundary: String): ByteArray {
        require(!String(bytes, Charsets.ISO_8859_1).contains(boundary)) { "разделитель встретился в файле" }
        require(fileName.all { it.isLetterOrDigit() || it in "._-" }) { "имя файла — только буквы, цифры, «._-»" }
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n$chatId\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"\r\n" +
            "Content-Type: text/plain\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    }

    /** Имя файла для формы: по знаку шапки («trafaret.txt», «konvert.txt»). */
    fun fileNameOf(form: ByteArray): String = (GateWire.formOf(form)?.magic ?: "vrata") + ".txt"
}
