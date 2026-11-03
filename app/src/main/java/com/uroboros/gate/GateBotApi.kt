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
 * в [updatesUrl]); из сообщения — то, что нужно вратам и словам чата
 * ([TgWords]): текст, сущности, ответ на чьё, ветку, пересылку как признак;
 * подписи к файлу, картинки, голос не читает.
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

    /**
     * Кто я: номер бота, имя пользователя и видит ли он все сообщения групп
     * (`can_read_all_group_messages` — приватность выключена; null — телеграм не
     * сказал).
     */
    data class Me(val id: Long, val username: String?, val readsAllGroupMessages: Boolean? = null)

    fun me(result: Any?): Me? {
        val o = TgJson.obj(result) ?: return null
        val id = TgJson.long(o["id"]) ?: return null
        return Me(id, TgJson.str(o["username"]), TgJson.bool(o["can_read_all_group_messages"]))
    }

    /** Файл в сообщении: как скачать и какого он размера (null — телеграм не сказал). */
    data class Doc(val fileId: String, val size: Long?)

    /** Сущность текста (`entities`): вид и место в единицах UTF-16; [userId] — у `text_mention`. */
    data class Entity(val type: String, val offset: Int, val length: Int, val userId: Long? = null)

    /**
     * Сообщение из обновления — то, что нужно вратам (до скачивания файла) и
     * словам чата ([TgWords]). Поля слов — с умолчаниями: вратам они не нужны.
     *
     * [replyToTopicRoot] — сообщение, на которое «ответ», — служебное создание
     * ветки форума (`forum_topic_created`): так телеграм помечает каждое
     * сообщение ветки, и ответом оно не является (см. [TgWords]).
     */
    data class Message(
        val chatId: Long,
        val privateChat: Boolean,
        val fromId: Long?,
        val fromUsername: String?,
        val senderChatId: Long?,
        val edited: Boolean,
        val dateSec: Long,
        val doc: Doc?,
        val messageId: Long = 0,
        val chatType: String? = null,
        val chatTitle: String? = null,
        val threadId: Long? = null,
        val topicMessage: Boolean = false,
        val fromIsBot: Boolean = false,
        val fromFirstName: String? = null,
        val fromLastName: String? = null,
        val text: String? = null,
        val entities: List<Entity> = emptyList(),
        val replyToFromId: Long? = null,
        val replyToMessageId: Long? = null,
        val replyToTopicRoot: Boolean = false,
        val forwarded: Boolean = false,
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
        val reply = TgJson.obj(m["reply_to_message"])
        return Message(
            chatId = chatId,
            privateChat = TgJson.str(chat["type"]) == "private",
            fromId = from?.let { TgJson.long(it["id"]) },
            fromUsername = from?.let { TgJson.str(it["username"]) },
            senderChatId = TgJson.obj(m["sender_chat"])?.let { TgJson.long(it["id"]) },
            edited = edited,
            dateSec = TgJson.long(m["date"]) ?: 0L,
            doc = doc,
            messageId = TgJson.long(m["message_id"]) ?: 0L,
            chatType = TgJson.str(chat["type"]),
            chatTitle = TgJson.str(chat["title"]),
            threadId = TgJson.long(m["message_thread_id"]),
            topicMessage = TgJson.bool(m["is_topic_message"]) == true,
            fromIsBot = from?.let { TgJson.bool(it["is_bot"]) } == true,
            fromFirstName = from?.let { TgJson.str(it["first_name"]) },
            fromLastName = from?.let { TgJson.str(it["last_name"]) },
            text = TgJson.str(m["text"]),
            entities = TgJson.list(m["entities"]).orEmpty().mapNotNull { e -> entity(TgJson.obj(e)) },
            replyToFromId = reply?.let { TgJson.obj(it["from"]) }?.let { TgJson.long(it["id"]) },
            replyToMessageId = reply?.let { TgJson.long(it["message_id"]) },
            replyToTopicRoot = reply?.containsKey("forum_topic_created") == true,
            forwarded = m["forward_origin"] != null || m["forward_from"] != null || m["forward_date"] != null,
        )
    }

    private fun entity(e: Map<String, Any?>?): Entity? {
        if (e == null) return null
        val type = TgJson.str(e["type"]) ?: return null
        val offset = TgJson.long(e["offset"])?.toInt() ?: return null
        val length = TgJson.long(e["length"])?.toInt() ?: return null
        return Entity(type, offset, length, TgJson.obj(e["user"])?.let { TgJson.long(it["id"]) })
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

    /** Номер ушедшего сообщения из ответа `sendMessage`; null — телеграм не сказал. */
    fun sentMessageId(result: Any?): Long? = TgJson.obj(result)?.let { TgJson.long(it["message_id"]) }

    // --- Отправка текста ---

    /**
     * Предел текста одного сообщения у телеграма — 4096 знаков (помню, FAQ и
     * описание `sendMessage`). Длиннее отправка отказывает целиком, поэтому
     * режет зовущий ([TgWords.cut]).
     */
    const val TEXT_LIMIT = 4096

    /**
     * Тело `sendMessage` — форма `application/x-www-form-urlencoded`: текст
     * идёт как есть, без разметки (`parse_mode` не ставится — знаки `*` и `_`
     * из ответа модели остаются знаками). [replyTo] — ответом на это сообщение
     * (если оно исчезло — всё равно уходит); [thread] — в эту ветку форума.
     */
    fun messageBody(chatId: Long, text: String, replyTo: Long?, thread: Long?): ByteArray {
        require(text.isNotEmpty() && text.length <= TEXT_LIMIT) { "текст пустой или длиннее $TEXT_LIMIT" }
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val parts = ArrayList<String>(4)
        parts += "chat_id=$chatId"
        parts += "text=" + enc(text)
        if (thread != null) parts += "message_thread_id=$thread"
        if (replyTo != null && replyTo > 0) parts += "reply_parameters=" + enc("{\"message_id\":$replyTo,\"allow_sending_without_reply\":true}")
        return parts.joinToString("&").toByteArray(Charsets.UTF_8)
    }

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
