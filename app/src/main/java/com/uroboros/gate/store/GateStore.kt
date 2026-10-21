package com.uroboros.gate.store

import com.uroboros.gate.GateAck
import com.uroboros.gate.GateAcquaintance
import com.uroboros.gate.GateBox
import com.uroboros.gate.GateCard
import com.uroboros.gate.GateCards
import com.uroboros.gate.GateEvidence
import com.uroboros.gate.GateIntake
import com.uroboros.gate.GateKeyRecord
import com.uroboros.gate.GateKeys
import com.uroboros.gate.GateWire
import com.uroboros.gate.GateNumbers
import com.uroboros.gate.GateOutbox
import com.uroboros.gate.GateOutgoing
import com.uroboros.gate.GateProxy
import com.uroboros.gate.GateVouches
import com.uroboros.memory.nav.PersonKey

/**
 * Записи врат в базе ([GateDatabase]): читает строки и отдаёт записи врат,
 * принимает записи и кладёт строки. Что запись значит и что с ней делать,
 * решают объекты врат (`com.uroboros.gate`); здесь — только хранение.
 *
 * [me] — мой ключ: «принял» в исходящих всегда мне, в строке он не хранится.
 *
 * СТИРАЕТСЯ ТОЛЬКО ТО, ЧТО УЖЕ НИЧЕГО НЕ ЗНАЧИТ ([cleanup]):
 *  - номера принятых пакетов, отправленных [GateIntake.Limits.repeatWindowMs]
 *    назад и раньше, — такой пакет врата и так отказывают «устарел»;
 *  - исходящие, первая отправка которых старше двух таких сроков: к концу
 *    первого срока у пакета есть ответ или «не дошло» ([GateOutbox]), второй
 *    — чтобы строка прибора о нём ещё жила.
 * Доверенности, свидетельства, знакомства, поручительства и показания
 * карточек не стираются: это история, подтверждённая владельцем или
 * принятая от агентов.
 *
 * ЧЕГО НЕ УМЕЕТ: профиль бота в канале и отметку «подвёл» не хранит — в
 * коде их ещё нет; токен бота не хранит — ему место в хранилище ключей
 * Android. Обращения блокируют поток (см. [GateDao]).
 */
class GateStore(private val db: GateDatabase, private val me: PersonKey) {

    private val dao = db.gateDao()

    /** Номера для [GateIntake]: переживают перезапуск. */
    val numbers: GateNumbers = object : GateNumbers {
        override fun contains(sender: String, number: Long) = dao.numberCount(sender, number) > 0
        override fun put(sender: String, number: Long, sentMs: Long) = dao.insertNumber(GateNumberRow(sender, number, sentMs))
        override fun forgetSentUpTo(ms: Long) {
            dao.deleteNumbersSentUpTo(ms)
        }
    }

    // --- Ключи ---

    fun knownKeys(): Set<PersonKey> = dao.knownKeys().mapTo(HashSet()) { PersonKey(it) }

    /** Запомнить ключ; уже известный не меняется (момент первой встречи остаётся первым). */
    fun rememberKey(key: PersonKey, nowMs: Long) = dao.insertKnownKey(GateKnownKeyRow(key.id, nowMs))

    // --- Доверенности ---

    /** Доверенность со своим номером в базе и свидетельствами. */
    data class StoredProxy(val id: Long, val proxy: GateProxy, val evidence: List<GateEvidence>)

    fun proxies(): List<StoredProxy> = dao.proxies().map { r -> StoredProxy(r.id, r.toRecord(), dao.evidence(r.id).map { it.toRecord() }) }

    /** Новая доверенность со свидетельствами — одним действием. Возвращает её номер. */
    fun addProxy(proxy: GateProxy, evidence: List<GateEvidence>): Long {
        var id = 0L
        db.runInTransaction {
            id = dao.insertProxy(GateProxyRow.of(proxy))
            for (e in evidence) dao.insertEvidence(GateEvidenceRow.of(id, e))
        }
        return id
    }

    /** false — уже подтверждена (момент первого подтверждения остаётся) или нет такой. */
    fun confirmProxy(id: Long, nowMs: Long): Boolean = dao.confirmProxy(id, nowMs) > 0

    /** false — уже отозвана (момент первого отзыва остаётся) или нет такой. */
    fun revokeProxy(id: Long, nowMs: Long): Boolean = dao.revokeProxy(id, nowMs) > 0

    // --- Знакомства ---

    data class StoredAcquaintance(val id: Long, val acquaintance: GateAcquaintance)

    fun acquaintances(): List<StoredAcquaintance> = dao.acquaintances().map { StoredAcquaintance(it.id, it.toRecord()) }

    fun addAcquaintance(a: GateAcquaintance): Long = dao.insertAcquaintance(GateAcquaintanceRow.of(a))

    fun confirmAcquaintance(id: Long, nowMs: Long): Boolean = dao.confirmAcquaintance(id, nowMs) > 0

    fun revokeAcquaintance(id: Long, nowMs: Long): Boolean = dao.revokeAcquaintance(id, nowMs) > 0

    // --- Поручительства ---

    fun vouches(): List<GateVouches.Record> = dao.vouches().map { it.toRecord() }

    fun addVouch(r: GateVouches.Record) {
        dao.insertVouch(GateVouchRow.of(r))
    }

    // --- Карточки ---

    fun cardReadings(): List<GateCards.Reading> = dao.cardReadings().map { it.toRecord() }

    /** Ключ шифрования [agent] из его нынешней карточки ([GateBox.encKeyOf]); null — писать открыто. */
    fun encKeyOf(agent: PersonKey): String? = GateBox.encKeyOf(cardReadings(), agent)

    // --- Ключи подписи ---

    /** Ключи подписи агентов — для [GateIntake] (`keys`) и [GateKeys]. */
    fun signKeys(): List<GateKeyRecord> = dao.signKeys().map { it.toRecord() }

    /**
     * Принять карточку с подписью [seal] ([GateKeys.takeCard]): ключ подписи —
     * первый, тот же, сменён или ждёт владельца, — и показание карточки, если
     * ключ это позволяет ([GateKeys.Taken.cardTaken]). Одним действием.
     * Показание null — карточка в показания не легла.
     */
    fun takeSignedCard(card: GateCard, seal: GateWire.Seal?, nowMs: Long): Pair<GateKeys.Taken, GateCards.Taken?> {
        var result: Pair<GateKeys.Taken, GateCards.Taken?>? = null
        db.runInTransaction {
            val before = signKeys()
            val k = GateKeys.takeCard(before, card, seal, nowMs)
            // GateKeys новые записи дописывает в конец, прежние только заканчивает.
            (k.change as? GateKeys.Change.Rotated)?.let { dao.endSignKey(card.agent.id, it.previous.key, nowMs) }
            for (r in k.records.drop(before.size)) dao.insertSignKey(GateSignKeyRow.of(r))
            result = k to (if (k.cardTaken) takeCard(card, nowMs) else null)
        }
        return result!!
    }

    /**
     * Мой владелец подтвердил ключ [key] агента [agent] ([GateKeys.confirm]).
     * false — такого ждущего ключа нет.
     */
    fun confirmSignKey(agent: PersonKey, key: String, nowMs: Long): Boolean {
        var ok = false
        db.runInTransaction {
            val records = signKeys()
            if (GateKeys.confirm(records, agent, key, nowMs) == null) return@runInTransaction
            GateKeys.current(records, agent)?.let { dao.endSignKey(agent.id, it.key, nowMs) }
            ok = dao.trustSignKey(agent.id, key, nowMs) > 0
        }
        return ok
    }

    /**
     * Принять карточку ([GateCards.take]) и сохранить итог одним действием:
     * изменилась — прежнее показание получает конец, новое ложится; та же — в
     * базе ничего не меняется. Карточку с ключом подписи принимать через
     * [takeSignedCard]: решает ключ.
     */
    fun takeCard(card: GateCard, nowMs: Long): GateCards.Taken {
        var taken: GateCards.Taken? = null
        db.runInTransaction {
            val t = GateCards.take(cardReadings(), card, nowMs)
            when (t.change) {
                is GateCards.Change.Same -> Unit
                GateCards.Change.First -> dao.insertCardReading(GateCardReadingRow.of(t.current))
                is GateCards.Change.Changed -> {
                    dao.endCardReading(card.agent.id, nowMs)
                    dao.insertCardReading(GateCardReadingRow.of(t.current))
                }
            }
            taken = t
        }
        return taken!!
    }

    /** Отпечаток моей карточки, последней отправленной [peer]; null — не отправлял. */
    fun cardSentTo(peer: PersonKey): String? = dao.cardSent(peer.id)

    fun markCardSent(peer: PersonKey, fingerprint: String, nowMs: Long) = dao.putCardSent(GateCardSentRow(peer.id, fingerprint, nowMs))

    // --- Исходящие ---

    fun outbox(): List<GateOutgoing> {
        val attempts = dao.attempts().groupBy { it.toKey to it.number }
        return dao.outgoing().map { r ->
            val times = attempts[r.toKey to r.number]?.map { it.atMs } ?: listOf(r.firstMs)
            GateOutgoing(PersonKey(r.toKey), r.number, times, r.ack(me), r.ackedMs)
        }
    }

    /** Первая отправка пакета [number] агенту [to]. */
    fun sent(to: PersonKey, number: Long, nowMs: Long) {
        db.runInTransaction {
            dao.insertOutgoing(GateOutgoingRow(to.id, number, nowMs, null, null, null, null))
            dao.insertAttempt(GateAttemptRow(to.id, number, nowMs))
        }
    }

    /** Повтор того же пакета ушёл сейчас. */
    fun resent(to: PersonKey, number: Long, nowMs: Long) = dao.insertAttempt(GateAttemptRow(to.id, number, nowMs))

    /** Принять «принял» ([GateOutbox.take]) и сохранить ответ, если он первый. */
    fun takeAck(ack: GateAck, nowMs: Long): GateOutbox.Match {
        var match: GateOutbox.Match = GateOutbox.Match.Unknown
        db.runInTransaction {
            val t = GateOutbox.take(outbox(), ack, nowMs)
            if (t.match is GateOutbox.Match.Answered) {
                dao.setAck(ack.from.id, ack.number, ack.result.name, ack.gotMs, ack.sentMs, nowMs)
            }
            match = t.match
        }
        return match
    }

    // --- Уборка и прибор ---

    fun cleanup(nowMs: Long) {
        val window = GateIntake.Limits.DECLARED.repeatWindowMs
        db.runInTransaction {
            dao.deleteNumbersSentUpTo(nowMs - window)
            dao.deleteAttemptsFirstSentUpTo(nowMs - 2 * window)
            dao.deleteOutgoingFirstSentUpTo(nowMs - 2 * window)
        }
    }

    /** «Врата: база — доверенностей 2, знакомств 3, …». */
    fun meterLine(): String {
        val c = dao.counts()
        return "Врата: база — доверенностей ${c.proxies}, знакомств ${c.acquaintances}, поручительств ${c.vouches}, " +
            "карточек ${c.cards}, исходящих ${c.outgoing}, номеров ${c.numbers}"
    }
}
