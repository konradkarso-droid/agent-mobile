package com.uroboros.gate

import com.uroboros.memory.nav.AddressSorter
import com.uroboros.memory.nav.ForeignSpeech
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place

/**
 * Слова чата: обычное текстовое сообщение телеграма → то, что нужно
 * сортировщику обращения ([AddressSorter.Message]), ленте чата и памяти.
 * Пакеты врат сюда не идут — их разбирает [GateTelegram].
 *
 * ЧТО НЕ СЛОВА ([Read.NotWords]) — с причиной для строки прибора:
 *  - правка уже отправленного: прочитанное и записанное не переписывается;
 *  - от имени чата (`sender_chat`): отправитель тогда — общий служебный
 *    аккаунт на всех анонимов (см. [GateTelegram]), ключа персоны нет;
 *  - без отправителя, своё сообщение бота, канал (`channel`);
 *  - не текст (картинка, голос, стикер): читать нечем;
 *  - пересланное: слова другого человека, а отправитель — пересылающий;
 *  - одна команда (`/start`): не речь — ни модели, ни в память.
 *
 * ВЕТКИ ФОРУМА. У сообщения ветки телеграм ставит `reply_to_message` на
 * служебное создание ветки, даже когда никто ни на что не отвечал (помню, не
 * смотрел — сверяется на живой группе). Принять это за ответ — значит каждое
 * сообщение ветки читать «ответом другому», и всё уйдёт в общее. Поэтому
 * ответ на создание ветки (или на сообщение с номером самой ветки) — не ответ.
 * Ветка — только у сообщений с `is_topic_message`; лента и «сообщение прямо
 * перед этим» — по ветке.
 *
 * ПОДПИСЬ ([signature]) — первое слово имени в телеграме, с заглавной; память
 * хранит чужие слова как «Имя: текст», и читает их чужой речью
 * [ForeignSpeech] — тот же путь, что у реплик других, переданных владельцем.
 * Имя, которое ForeignSpeech подписью не признаёт (ярлык, глагол, место, одни
 * знаки), заменяется на [FALLBACK_SIGNATURE]: иначе чужое «я» легло бы словами
 * владельца.
 *
 * ЧЕГО НЕ УМЕЕТ: двое с одним именем в одной группе для модели — одна
 * подпись (ключи у них разные); собеседник с именем агента подписан именем
 * агента; смещения сущностей вне текста отбрасываются молча.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object TgWords {

    /** Подпись, если имя в телеграме подписью не читается. Объявленная. */
    const val FALLBACK_SIGNATURE = "Собеседник"

    /** Слова одного сообщения: форма для сортировщика и то, что ляжет в ленту. */
    data class Words(
        val message: AddressSorter.Message,
        val chat: PersonKey,
        val thread: Long?,
        val messageId: Long,
        val name: String,
        val signature: String,
        val senderIsBot: Boolean,
        val atMs: Long,
        val chatTitle: String?,
    ) {
        val place: Place get() = message.place

        /** Ключ чата для записи памяти: с веткой — «…/номер ветки». */
        val chatRef: String get() = chatRefOf(chat, thread)
    }

    sealed class Read {
        data class Of(val words: Words) : Read()

        /** Не слова; [why] — для строки прибора. */
        data class NotWords(val why: String) : Read()
    }

    fun chatRefOf(chat: PersonKey, thread: Long?): String = if (thread == null) chat.id else "${chat.id}/$thread"

    /** Сообщение [m] глазами агента-бота [me]. */
    fun of(m: GateBotApi.Message, me: GateBotApi.Me): Read {
        if (m.edited) return Read.NotWords("правка сообщения — прочитанное не переписываю")
        if (m.senderChatId != null) return Read.NotWords("от имени чата — не читаю")
        val from = m.fromId ?: return Read.NotWords("нет отправителя")
        if (from == me.id) return Read.NotWords("своё сообщение")
        val place = when (m.chatType) {
            "private" -> Place.DIRECT
            "group", "supergroup" -> Place.GROUP
            else -> return Read.NotWords("не личка и не группа (${m.chatType ?: "вид чата не назван"}) — не читаю")
        }
        val text = m.text?.takeIf { it.isNotBlank() } ?: return Read.NotWords("не текст — читать нечем")
        if (m.forwarded) return Read.NotWords("пересланное — это слова другого, не читаю")
        val commands = slices(text, m.entities, "bot_command")
        if (commands.size == 1 && commands[0] == text.trim()) return Read.NotWords("команда «${commands[0]}» — не речь")

        val thread = m.threadId?.takeIf { m.topicMessage }
        val reply = m.replyToFromId?.takeIf {
            !m.replyToTopicRoot && !(thread != null && m.replyToMessageId == thread)
        }
        val sender = GateTelegram.userKey(from)
        val name = listOfNotNull(m.fromFirstName, m.fromLastName).joinToString(" ").trim()
            .ifEmpty { m.fromUsername ?: sender.id }
        return Read.Of(
            Words(
                message = AddressSorter.Message(
                    place = place,
                    sender = sender,
                    text = text,
                    replyTo = reply?.let { GateTelegram.userKey(it) },
                    mentions = slices(text, m.entities, "mention"),
                    textMentions = m.entities.filter { it.type == "text_mention" }.mapNotNull { e -> e.userId?.let { GateTelegram.userKey(it) } },
                    commands = commands,
                ),
                chat = GateTelegram.chatKey(m.chatId),
                thread = thread,
                messageId = m.messageId,
                name = name,
                signature = signature(m.fromFirstName, m.fromUsername),
                senderIsBot = m.fromIsBot,
                atMs = m.dateSec * 1000,
                chatTitle = m.chatTitle,
            )
        )
    }

    /** Куски текста под сущностями вида [type]; смещения — в единицах UTF-16, как у строк Kotlin. */
    private fun slices(text: String, entities: List<GateBotApi.Entity>, type: String): List<String> =
        entities.filter { it.type == type && it.offset >= 0 && it.length > 0 && it.offset + it.length <= text.length }
            .map { text.substring(it.offset, it.offset + it.length) }

    private val NAME_WORD = Regex("""\p{L}[\p{L}\-]{1,30}""")

    /**
     * Подпись «Имя:» из имени в телеграме [firstName] (запасное — имя
     * пользователя [username]). Берётся первое слово из букв, первая буква —
     * заглавная. Не читается [ForeignSpeech] подписью — [FALLBACK_SIGNATURE].
     */
    fun signature(firstName: String?, username: String?): String {
        val word = listOfNotNull(firstName, username).firstNotNullOfOrNull { NAME_WORD.find(it)?.value }
            ?.let { it.substring(0, 1).uppercase() + it.substring(1) }
        return word?.takeIf { readsAsSignature(it) } ?: FALLBACK_SIGNATURE
    }

    private fun readsAsSignature(word: String): Boolean =
        ForeignSpeech.of("$word: проверка").let { it.kind == ForeignSpeech.Kind.DIRECT && it.speaker == word }

    /**
     * Текст записи памяти: слова владельца — как есть (они его, в его облако);
     * чужие — «Подпись: текст», чтобы отбор, портрет и судья читали их чужой
     * речью. null — подпись в этом тексте чужой речью не читается: такую
     * запись не класть, иначе чужое «я» станет словами владельца.
     */
    fun memoryText(text: String, signature: String, owner: Boolean): String? {
        if (owner) return text
        val signed = "$signature: $text"
        val read = ForeignSpeech.ofRecord(signed)
        return signed.takeIf { read.isNotEmpty() && read.all { it.kind == ForeignSpeech.Kind.DIRECT && it.speaker == signature } }
    }

    /**
     * Текст не длиннее [limit] знаков: длиннее — по последнему концу
     * предложения до предела, а нет его — по пределу. Пустой — пустой.
     */
    fun cut(text: String, limit: Int = GateBotApi.TEXT_LIMIT): String {
        val t = text.trim()
        if (t.length <= limit) return t
        val head = t.substring(0, limit)
        val end = head.indexOfLast { it == '.' || it == '!' || it == '?' || it == '…' }
        return if (end >= limit / 2) head.substring(0, end + 1) else head.trimEnd()
    }
}
