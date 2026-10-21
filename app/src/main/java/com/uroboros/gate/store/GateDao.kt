package com.uroboros.gate.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Доступ к базе врат. Функции не `suspend`, как у базы ленты разговора
 * ([com.uroboros.llm.JournalDatabase]): обращение блокирует поток, и работать
 * в главном потоке база не разрешает — промах будет громким.
 *
 * ОБНОВЛЕНИЙ НЕТ, КРОМЕ ПОЛЕЙ КОНЦА (см. [GateRows]): подтверждение и отзыв
 * доверенности, подтверждение и снятие знакомства, конец показания карточки,
 * ответ на исходящий, доверие и конец ключа подписи. Каждое пишется только в пустое поле (`IS NULL`): второй
 * отзыв не сдвигает момент первого. Исключение одно — «какую карточку я
 * последней отправил агенту»: это указатель, а не история, и он заменяется.
 */
@Dao
interface GateDao {

    // --- Номера принятых пакетов ---

    @Query("SELECT COUNT(*) FROM gate_numbers WHERE sender = :sender AND number = :number")
    fun numberCount(sender: String, number: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertNumber(row: GateNumberRow)

    @Query("DELETE FROM gate_numbers WHERE sentMs <= :ms")
    fun deleteNumbersSentUpTo(ms: Long): Int

    // --- Известные ключи ---

    @Query("SELECT agentKey FROM gate_known_keys")
    fun knownKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertKnownKey(row: GateKnownKeyRow)

    // --- Доверенности и свидетельства ---

    @Query("SELECT * FROM gate_proxies ORDER BY id")
    fun proxies(): List<GateProxyRow>

    @Insert
    fun insertProxy(row: GateProxyRow): Long

    @Query("UPDATE gate_proxies SET confirmedMs = :ms WHERE id = :id AND confirmedMs IS NULL")
    fun confirmProxy(id: Long, ms: Long): Int

    @Query("UPDATE gate_proxies SET revokedMs = :ms WHERE id = :id AND revokedMs IS NULL")
    fun revokeProxy(id: Long, ms: Long): Int

    @Query("SELECT * FROM gate_proxy_evidence WHERE proxyId = :proxyId ORDER BY atMs, id")
    fun evidence(proxyId: Long): List<GateEvidenceRow>

    @Insert
    fun insertEvidence(row: GateEvidenceRow): Long

    // --- Знакомства ---

    @Query("SELECT * FROM gate_acquaintances ORDER BY id")
    fun acquaintances(): List<GateAcquaintanceRow>

    @Insert
    fun insertAcquaintance(row: GateAcquaintanceRow): Long

    @Query("UPDATE gate_acquaintances SET confirmedMs = :ms WHERE id = :id AND confirmedMs IS NULL")
    fun confirmAcquaintance(id: Long, ms: Long): Int

    @Query("UPDATE gate_acquaintances SET revokedMs = :ms WHERE id = :id AND revokedMs IS NULL")
    fun revokeAcquaintance(id: Long, ms: Long): Int

    // --- Поручительства ---

    @Query("SELECT * FROM gate_vouches ORDER BY id")
    fun vouches(): List<GateVouchRow>

    @Insert
    fun insertVouch(row: GateVouchRow): Long

    // --- Карточки ---

    @Query("SELECT * FROM gate_card_readings ORDER BY id")
    fun cardReadings(): List<GateCardReadingRow>

    @Insert
    fun insertCardReading(row: GateCardReadingRow): Long

    @Query("UPDATE gate_card_readings SET endedMs = :ms WHERE agent = :agent AND endedMs IS NULL")
    fun endCardReading(agent: String, ms: Long): Int

    @Query("SELECT fingerprint FROM gate_card_sent WHERE peer = :peer")
    fun cardSent(peer: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putCardSent(row: GateCardSentRow)

    // --- Ключи подписи ---

    @Query("SELECT * FROM gate_sign_keys ORDER BY id")
    fun signKeys(): List<GateSignKeyRow>

    @Insert
    fun insertSignKey(row: GateSignKeyRow): Long

    @Query("UPDATE gate_sign_keys SET trustedMs = :ms WHERE agent = :agent AND signKey = :key AND trustedMs IS NULL AND endedMs IS NULL")
    fun trustSignKey(agent: String, key: String, ms: Long): Int

    @Query("UPDATE gate_sign_keys SET endedMs = :ms WHERE agent = :agent AND signKey = :key AND endedMs IS NULL")
    fun endSignKey(agent: String, key: String, ms: Long): Int

    // --- Исходящие ---

    @Query("SELECT * FROM gate_outgoing ORDER BY firstMs")
    fun outgoing(): List<GateOutgoingRow>

    @Query("SELECT * FROM gate_outgoing_attempts ORDER BY atMs")
    fun attempts(): List<GateAttemptRow>

    @Insert
    fun insertOutgoing(row: GateOutgoingRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAttempt(row: GateAttemptRow)

    @Query(
        "UPDATE gate_outgoing SET ackResult = :result, ackGotMs = :gotMs, ackSentMs = :sentMs, ackedMs = :ackedMs " +
            "WHERE toKey = :toKey AND number = :number AND ackResult IS NULL",
    )
    fun setAck(toKey: String, number: Long, result: String, gotMs: Long, sentMs: Long, ackedMs: Long): Int

    @Query("DELETE FROM gate_outgoing_attempts WHERE EXISTS (SELECT 1 FROM gate_outgoing o WHERE o.toKey = gate_outgoing_attempts.toKey AND o.number = gate_outgoing_attempts.number AND o.firstMs <= :ms)")
    fun deleteAttemptsFirstSentUpTo(ms: Long): Int

    @Query("DELETE FROM gate_outgoing WHERE firstMs <= :ms")
    fun deleteOutgoingFirstSentUpTo(ms: Long): Int

    // --- Для строки прибора ---

    @Query(
        "SELECT (SELECT COUNT(*) FROM gate_proxies) AS proxies, (SELECT COUNT(*) FROM gate_acquaintances) AS acquaintances, " +
            "(SELECT COUNT(*) FROM gate_vouches) AS vouches, (SELECT COUNT(DISTINCT agent) FROM gate_card_readings) AS cards, " +
            "(SELECT COUNT(*) FROM gate_outgoing) AS outgoing, (SELECT COUNT(*) FROM gate_numbers) AS numbers",
    )
    fun counts(): GateCounts
}

/** Сколько чего в базе врат — для строки прибора. */
data class GateCounts(
    val proxies: Int,
    val acquaintances: Int,
    val vouches: Int,
    val cards: Int,
    val outgoing: Int,
    val numbers: Int,
)
