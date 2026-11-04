package com.uroboros.llm

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Одна строка ленты чата телеграма: сообщение человека, бота или самого
 * агента — как оно пришло или ушло.
 *
 * ЛЕНТА СВОЯ НА КАЖДЫЙ ЧАТ (и ветку форума), память — одна. Строки чата не
 * ложатся в ленту экрана: там модель читает собеседника как владельца, и
 * строка друга стала бы словами владельца, а точка движка экрана — чужой.
 *
 * ЧТО ЗДЕСЬ, А ЧТО В ПАМЯТИ. Здесь — всё сказанное в чате, и общее тоже: это
 * фон беседы и «сообщение прямо перед этим» для сортировщика
 * (memory.nav.AddressSorter.Prior). В память ложится только то, что велит
 * кольцо (AddressSorter.memory) — через дверь записи, с местом (memory.Heard).
 *
 * ДАННЫЕ ТЕЛЕГРАМА. Условия телеграма запрещают учить модели на его данных;
 * поэтому лента чатов — своя база, а не таблица ленты экрана, из которой
 * собираются дневные пары учёбы. Скрипты учёбы её не читают.
 *
 * @property chat ключ чата (`tg:chat:<номер>`).
 * @property thread номер ветки форума; null — чат без веток или общая ветка.
 * @property messageId номер сообщения в чате у телеграма; 0 — не известен
 *   (ответ ушёл, а номер телеграм не вернул).
 * @property sender ключ того, кто написал (`tg:user:<номер>`); у своих
 *   сообщений — ключ бота агента.
 * @property name имя, каким человек назван в телеграме (`first_name` и
 *   `last_name`) — для экрана; не ключ.
 * @property signature подпись «Имя:» для модели и памяти (gate.TgWords.signature).
 * @property ring кольцо обращения (AddressSorter.Ring); null у своих сообщений,
 *   ушедших в телеграм; [Silence.RING] — своя строка молчания, не ушедшая (llm.Silence).
 * @property reason причина кольца словами (AddressSorter.Route.reason).
 * @property replyTo ключ автора сообщения, на которое это ответ; null — не ответ.
 * @property at время сервера телеграма, мс (у своих — время отправки).
 * @property place имя memory.nav.Place: DIRECT или GROUP.
 */
@Entity(tableName = "chat_lines", indices = [Index(value = ["chat", "thread", "id"])])
data class ChatLine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chat: String,
    val thread: Long?,
    val messageId: Long,
    val sender: String,
    val name: String,
    val signature: String,
    val isBot: Boolean,
    val isAgent: Boolean,
    val text: String,
    val ring: String?,
    val reason: String?,
    val replyTo: String?,
    val at: Long,
    val place: String,
)

/** Последний собеседник в личке — для отметки «это я» на экране врат. */
data class ChatPeer(val sender: String, val name: String, val lastAt: Long)

@Dao
interface ChatLineDao {

    @Insert
    suspend fun insert(line: ChatLine): Long

    /** Последние [limit] строк чата и ветки, новые первыми. */
    @Query(
        "SELECT * FROM chat_lines WHERE chat = :chat " +
            "AND ((:thread IS NULL AND thread IS NULL) OR thread = :thread) " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun recent(chat: String, thread: Long?, limit: Int): List<ChatLine>

    /**
     * Последние [limit] читаемых строк чата и ветки перед строкой [beforeId]
     * (её не включая), новые первыми. Читаемые — свои ответы и строки колец
     * [rings] (то, что кольцо отдаёт модели, gate.ChatFeed).
     */
    @Query(
        "SELECT * FROM chat_lines WHERE chat = :chat " +
            "AND ((:thread IS NULL AND thread IS NULL) OR thread = :thread) " +
            "AND id < :beforeId AND (isAgent = 1 OR ring IN (:rings)) " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun readBefore(chat: String, thread: Long?, beforeId: Long, rings: List<String>, limit: Int): List<ChatLine>

    /**
     * Сумма цен читаемых строк чата и ветки перед строкой [beforeId] — смещение
     * этой строки от начала чата для сетки окна. Мера — та же, что
     * gate.ChatFeed.cost: `LENGTH` в SQLite считает буквы (кодовые точки), и
     * два знака на строку. Почему меры обязаны совпадать — у ChatFeed, «ГДЕ
     * ВСТАЁТ НАЧАЛО».
     */
    @Query(
        "SELECT COALESCE(SUM(LENGTH(text) + LENGTH(signature) + 2), 0) FROM chat_lines WHERE chat = :chat " +
            "AND ((:thread IS NULL AND thread IS NULL) OR thread = :thread) " +
            "AND id < :beforeId AND (isAgent = 1 OR ring IN (:rings))"
    )
    suspend fun readCostBefore(chat: String, thread: Long?, beforeId: Long, rings: List<String>): Long

    /**
     * Читаемые строки всех чатов не раньше [since], по порядку: свои ответы и
     * строки колец [rings] — для обещаний (gate.ChatPromises).
     */
    @Query("SELECT * FROM chat_lines WHERE at >= :since AND (isAgent = 1 OR ring IN (:rings)) ORDER BY id")
    suspend fun readSince(since: Long, rings: List<String>): List<ChatLine>

    /** То же, что [readSince], для одного чата и ветки — обещания этого чата в конце нити. */
    @Query(
        "SELECT * FROM chat_lines WHERE chat = :chat " +
            "AND ((:thread IS NULL AND thread IS NULL) OR thread = :thread) " +
            "AND at >= :since AND (isAgent = 1 OR ring IN (:rings)) ORDER BY id"
    )
    suspend fun readSinceIn(chat: String, thread: Long?, since: Long, rings: List<String>): List<ChatLine>

    /** Сколько строк чата и ветки легло после строки [afterId] (своих не считая). */
    @Query(
        "SELECT COUNT(*) FROM chat_lines WHERE chat = :chat " +
            "AND ((:thread IS NULL AND thread IS NULL) OR thread = :thread) " +
            "AND id > :afterId AND isAgent = 0"
    )
    suspend fun countAfter(chat: String, thread: Long?, afterId: Long): Int

    /**
     * Время последней строки разговора с агентом — строки с кольцом из [rings]
     * или своего ответа; null — таких нет. Что считается разговором — у
     * gate.ChatQuiet.
     */
    @Query("SELECT MAX(at) FROM chat_lines WHERE isAgent = 1 OR ring IN (:rings)")
    suspend fun lastDialogAt(rings: List<String>): Long?

    /** Последние собеседники в личке, кроме ботов, новые первыми. */
    @Query(
        "SELECT sender, name, MAX(at) AS lastAt FROM chat_lines " +
            "WHERE isAgent = 0 AND isBot = 0 AND place = 'DIRECT' " +
            "GROUP BY sender ORDER BY lastAt DESC LIMIT :limit"
    )
    suspend fun directPeers(limit: Int): List<ChatPeer>
}
