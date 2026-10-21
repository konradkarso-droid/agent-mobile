package com.uroboros.gate.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.uroboros.gate.GateAck
import com.uroboros.gate.GateAcquaintance
import com.uroboros.gate.GateCard
import com.uroboros.gate.GateCards
import com.uroboros.gate.GateEvidence
import com.uroboros.gate.GateKeyRecord
import com.uroboros.gate.GateProxy
import com.uroboros.gate.GateVouch
import com.uroboros.gate.GateVouches
import com.uroboros.memory.nav.PersonKey

/*
 * Строки базы врат. Каждая строка переводится в запись врат ([toRecord]) и
 * обратно (`of`) здесь, в одном месте: что значит запись, решают объекты
 * врат (`com.uroboros.gate`), а здесь — только как она лежит.
 *
 * ЗАПИСИ НЕ ПЕРЕПИСЫВАЮТСЯ, только получают конец: у доверенности — отзыв, у
 * знакомства — снятие, у карточки — конец показания, у исходящего — ответ.
 * Остальные поля строки после вставки не меняются (это держит [GateDao]: в
 * нём нет обновлений, кроме полей конца).
 *
 * Слов пакетов здесь нет: только номера и моменты. Слова идут в память агента
 * тем, кто подключит канал, со своим провенансом.
 */

/** Номер принятого пакета ([com.uroboros.gate.GateNumbers]). */
@Entity(tableName = "gate_numbers", primaryKeys = ["sender", "number"], indices = [Index("sentMs")])
data class GateNumberRow(val sender: String, val number: Long, val sentMs: Long)

/** Ключ агента, который уже встречался ([com.uroboros.gate.GateWho], «впервые»). */
@Entity(tableName = "gate_known_keys")
data class GateKnownKeyRow(@PrimaryKey val agentKey: String, val firstSeenMs: Long)

@Entity(tableName = "gate_proxies")
data class GateProxyRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agent: String,
    val principal: String,
    val kind: String,
    val confirmedMs: Long?,
    val untilMs: Long?,
    val revokedMs: Long?,
) {
    fun toRecord() = GateProxy(PersonKey(agent), PersonKey(principal), GateProxy.Kind.valueOf(kind), confirmedMs, untilMs, revokedMs)

    companion object {
        fun of(p: GateProxy) = GateProxyRow(
            agent = p.agent.id, principal = p.principal.id, kind = p.kind.name,
            confirmedMs = p.confirmedMs, untilMs = p.untilMs, revokedMs = p.revokedMs,
        )
    }
}

/** «Чем подтверждена» доверенность [proxyId]. */
@Entity(tableName = "gate_proxy_evidence", indices = [Index("proxyId")])
data class GateEvidenceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proxyId: Long,
    val kind: String,
    val atMs: Long,
    val text: String,
) {
    fun toRecord() = GateEvidence(GateEvidence.Kind.valueOf(kind), atMs, text)

    companion object {
        fun of(proxyId: Long, e: GateEvidence) = GateEvidenceRow(proxyId = proxyId, kind = e.kind.name, atMs = e.atMs, text = e.text)
    }
}

@Entity(tableName = "gate_acquaintances")
data class GateAcquaintanceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agent: String,
    val confirmedMs: Long?,
    val revokedMs: Long?,
) {
    fun toRecord() = GateAcquaintance(PersonKey(agent), confirmedMs, revokedMs)

    companion object {
        fun of(a: GateAcquaintance) = GateAcquaintanceRow(agent = a.agent.id, confirmedMs = a.confirmedMs, revokedMs = a.revokedMs)
    }
}

/** Принятое поручительство ([GateVouches.Record]); снятие — тоже строка, с `withdraw`. */
@Entity(tableName = "gate_vouches", indices = [Index("agent")])
data class GateVouchRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucher: String,
    val agent: String,
    val toKey: String,
    val withdraw: Boolean,
    val receivedMs: Long,
) {
    fun toRecord() = GateVouches.Record(GateVouch(PersonKey(voucher), PersonKey(agent), PersonKey(toKey), withdraw), receivedMs)

    companion object {
        fun of(r: GateVouches.Record) = GateVouchRow(
            voucher = r.vouch.voucher.id, agent = r.vouch.agent.id, toKey = r.vouch.to.id,
            withdraw = r.vouch.withdraw, receivedMs = r.receivedMs,
        )
    }
}

/**
 * Показание карточки ([GateCards.Reading]). Поля карточки — столбцами: список
 * полей закрыт ([GateCard.Field]), и новое поле — новая версия формы и новая
 * миграция, а не строка в свободном списке.
 */
@Entity(tableName = "gate_card_readings", indices = [Index("agent")])
data class GateCardReadingRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agent: String,
    val name: String?,
    val repo: String?,
    val about: String?,
    val receivedMs: Long,
    val endedMs: Long?,
    /** Ключ подписи из карточки; у строк до подписей — пусто. */
    val signKey: String? = null,
    /** Ключ шифрования из карточки; у строк до шифрования и у карточек без него — пусто. */
    val encKey: String? = null,
) {
    fun toRecord(): GateCards.Reading {
        val fields = LinkedHashMap<GateCard.Field, String>()
        name?.let { fields[GateCard.Field.NAME] = it }
        repo?.let { fields[GateCard.Field.REPO] = it }
        about?.let { fields[GateCard.Field.ABOUT] = it }
        signKey?.let { fields[GateCard.Field.KEY] = it }
        encKey?.let { fields[GateCard.Field.ENC] = it }
        return GateCards.Reading(GateCard(PersonKey(agent), fields), receivedMs, endedMs)
    }

    companion object {
        fun of(r: GateCards.Reading) = GateCardReadingRow(
            agent = r.card.agent.id,
            name = r.card.fields[GateCard.Field.NAME],
            repo = r.card.fields[GateCard.Field.REPO],
            about = r.card.fields[GateCard.Field.ABOUT],
            receivedMs = r.receivedMs,
            endedMs = r.endedMs,
            signKey = r.card.fields[GateCard.Field.KEY],
            encKey = r.card.fields[GateCard.Field.ENC],
        )
    }
}

/** Какую свою карточку ([GateCard.fingerprint]) я последней отправил агенту [peer]. */
@Entity(tableName = "gate_card_sent")
data class GateCardSentRow(@PrimaryKey val peer: String, val fingerprint: String, val sentMs: Long)

/** Отправленный пакет слов ([com.uroboros.gate.GateOutgoing]) без попыток; ответ — пустой, пока его нет. */
@Entity(tableName = "gate_outgoing", primaryKeys = ["toKey", "number"])
data class GateOutgoingRow(
    val toKey: String,
    val number: Long,
    val firstMs: Long,
    val ackResult: String?,
    val ackGotMs: Long?,
    val ackSentMs: Long?,
    val ackedMs: Long?,
) {
    /** Ответ, если он есть. Свой ключ в строке не хранится — «принял» в исходящих всегда мне ([me]). */
    fun ack(me: PersonKey): GateAck? = ackResult?.let {
        GateAck(
            from = PersonKey(toKey), to = me, number = number,
            result = GateAck.Result.valueOf(it), gotMs = ackGotMs!!, sentMs = ackSentMs!!,
        )
    }
}

/** Попытка отправки пакета: момент по моим часам. */
@Entity(tableName = "gate_outgoing_attempts", primaryKeys = ["toKey", "number", "atMs"])
data class GateAttemptRow(val toKey: String, val number: Long, val atMs: Long)

/** Ключ подписи агента, как я его знаю ([GateKeyRecord]). */
@Entity(tableName = "gate_sign_keys")
data class GateSignKeyRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agent: String,
    val signKey: String,
    val seenMs: Long,
    val trustedMs: Long?,
    val how: String,
    val endedMs: Long?,
) {
    fun toRecord() = GateKeyRecord(PersonKey(agent), signKey, seenMs, trustedMs, GateKeyRecord.How.valueOf(how), endedMs)

    companion object {
        fun of(r: GateKeyRecord) = GateSignKeyRow(
            agent = r.agent.id, signKey = r.key, seenMs = r.seenMs, trustedMs = r.trustedMs, how = r.how.name, endedMs = r.endedMs,
        )
    }
}
