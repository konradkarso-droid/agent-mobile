package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import java.time.ZoneId

/**
 * Что из хранилища врат нужно почте ([GatePost]). Хранилище на телефоне —
 * `gate.store.GateStore`; что каждый вызов значит, сказано там.
 */
interface GatePostStore {
    fun takeSignedCard(card: GateCard, seal: GateWire.Seal?, nowMs: Long): Pair<GateKeys.Taken, GateCards.Taken?>
    fun addVouch(r: GateVouches.Record)
    fun takeAck(ack: GateAck, nowMs: Long): GateOutbox.Match
    fun encKeyOf(agent: PersonKey): String?
    fun cardSentTo(peer: PersonKey): String?
    fun markCardSent(peer: PersonKey, fingerprint: String, nowMs: Long)
    fun sent(to: PersonKey, number: Long, nowMs: Long, form: ByteArray)
    fun resent(to: PersonKey, number: Long, nowMs: Long)
    fun outbox(): List<GateOutgoing>

    /** Подписанная форма отправленного пакета; null — не сохранена. */
    fun formOf(to: PersonKey, number: Long): ByteArray?
}

/**
 * Почта врат: что сделать с пришедшей формой и что отправить — между каналом
 * (он везёт байты) и вратами с хранилищем (они решают и помнят).
 *
 * ВСЁ СВОЁ — ПОДПИСАНО. Каждая форма, которую собирает почта, подписана моим
 * ключом ([GateSign.seal]); неподписанного наш агент не шлёт. Конверт не
 * подписывается — подписана форма внутри ([GateBox]).
 *
 * ВСЁ, КРОМЕ КАРТОЧКИ, — В КОНВЕРТЕ, если ключ шифрования собеседника
 * известен по его карточке. Не известен — открыто, со строкой прибора
 * ([GateBox.sentLine]). Карточка всегда открыта: она сама везёт ключи.
 *
 * НОВОМУ СОБЕСЕДНИКУ — СНАЧАЛА КАРТОЧКА. Без неё у него нет ни ключа подписи
 * (моё подписанное он примет «есть, но проверить нечем»), ни ключа
 * шифрования. Поэтому перед любой формой ему, если моя нынешняя карточка ему
 * ещё не ушла (по отпечатку, [GatePostStore.cardSentTo]), идёт карточка. На
 * чужую карточку отвечается своей по тому же правилу — так два агента
 * знакомятся, и петли нет: одну и ту же карточку второй раз не шлют.
 *
 * «ОТПРАВЛЕНО» ЗАПИСЫВАЕТСЯ ПОСЛЕ ОТВЕТА КАНАЛА ([delivered]): почта только
 * собирает [Send]; не ушло — ни карточка не отмечена, ни пакет не в исходящих,
 * и в следующий раз всё соберётся заново. «Ушло в канал» — не «дошло»: дошло
 * — когда придёт «принял» ([GateOutbox]).
 *
 * ПОВТОР берёт сохранённую подписанную форму байт в байт: тот же номер и тот
 * же момент первой отправки — иначе получатель не узнал бы повтор. Конверт
 * каждый раз новый. Пакет без сохранённой формы не повторяется — повторять
 * нечего; его строка прибора дойдёт до «не дошло».
 *
 * ЧЕГО НЕ УМЕЕТ: принятое ни в память, ни модели не кладёт — только строки;
 * за кого говорит отправитель ([GateWho]) не решает; поручительство кладёт,
 * но не считает; ключ подписи при переезде прежним не подписывает (новый
 * телефон — новый ключ, собеседники подтвердят руками); глубину разговора
 * ([GateTurns]) не держит — пакет слов собирается только по действию владельца
 * ([words]), сам агент без него пакетов слов не шлёт.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
class GatePost(
    private val me: PersonKey,
    private val intake: GateIntake,
    private val store: GatePostStore,
    private val signer: GateSigner,
    encryptionKey: String,
    private val now: () -> Long,
    private val nameOf: (PersonKey) -> String? = { null },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Моя карточка: только ключи — имени и «о себе» пока нет. */
    val myCard = GateCard(me, linkedMapOf(GateCard.Field.KEY to signer.publicKey, GateCard.Field.ENC to encryptionKey))

    private val myCardForm: ByteArray by lazy { GateSign.seal(GateWire.writeCard(myCard), signer) }

    /** Отправленный пакет слов — что записать в исходящие после ответа канала. */
    class Outgoing(val to: PersonKey, val number: Long, val form: ByteArray, val resend: Boolean)

    /**
     * Что отправить одному собеседнику [to], в этом порядке ([forms]). [to] null
     * — номер собеседника ещё не известен (знакомство по имени пользователя).
     * [withCard] — среди форм моя карточка; [outgoing] — среди них пакет слов.
     */
    class Send(val to: PersonKey?, val forms: List<ByteArray>, val lines: List<String>, val withCard: Boolean, val outgoing: Outgoing?)

    /** Итог приёма: строки прибора и, если надо, ответ тому же собеседнику. */
    class Got(val lines: List<String>, val reply: Send?)

    private fun who(k: PersonKey) = nameOf(k)?.takeIf { it.isNotBlank() } ?: k.id

    // --- Отправка ---

    /** Собрать отправку подписанной формы [signed] собеседнику [to] (см. правила выше). */
    private fun send(to: PersonKey, signed: ByteArray, outgoing: Outgoing? = null): Send {
        val forms = ArrayList<ByteArray>()
        val lines = ArrayList<String>()
        val withCard = store.cardSentTo(to) != myCard.fingerprint
        if (withCard) forms += myCardForm
        val enc = store.encKeyOf(to)
        forms += if (enc != null) GateBox.close(signed, enc) else signed
        GateBox.sentLine(who(to), enc != null)?.let { lines += it }
        return Send(to, forms, lines, withCard, outgoing)
    }

    /** Только карточка — знакомство; [to] null — номер собеседника ещё не известен (по имени пользователя). */
    fun card(to: PersonKey?): Send = Send(to, listOf(myCardForm), emptyList(), withCard = true, outgoing = null)

    /**
     * Пробный пакет слов [words] собеседнику [to] по действию моего владельца:
     * агент говорит за себя, в личке, утверждение со слов владельца.
     */
    fun words(to: PersonKey, words: String): Send {
        val t = now()
        val p = GatePacket(
            from = me, represents = me, to = to, number = GateWire.newNumber(), sentMs = t, place = Place.DIRECT,
            saidBy = me, about = emptyList(), aboutUnclear = false, atMs = t, zone = zone.id,
            source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT, topic = emptyList(), exact = emptyMap(),
            words = words,
        )
        val signed = GateSign.seal(GateWire.write(p), signer)
        return send(to, signed, Outgoing(to, p.number, signed, resend = false))
    }

    /** Повторы, которым пора ([GateOutbox.State.Resend]), с сохранённой формой. */
    fun due(): List<Send> {
        val t = now()
        return store.outbox().filter { GateOutbox.state(it, t) == GateOutbox.State.Resend }.mapNotNull { o ->
            val form = store.formOf(o.to, o.number) ?: return@mapNotNull null
            send(o.to, form, Outgoing(o.to, o.number, form, resend = true))
        }
    }

    /**
     * Канал принял всё из [s] в мой момент [nowMs] — записать, что ушло. [to] —
     * кому на самом деле: при знакомстве по имени номер даёт ответ канала.
     */
    fun delivered(s: Send, nowMs: Long, to: PersonKey? = s.to) {
        if (s.withCard && to != null) store.markCardSent(to, myCard.fingerprint, nowMs)
        val o = s.outgoing ?: return
        if (o.resend) store.resent(o.to, o.number, nowMs) else store.sent(o.to, o.number, nowMs, o.form)
    }

    // --- Приём ---

    fun receive(g: GateTelegram.Incoming.Gate): Got {
        val t = now()
        return when (g.form) {
            GateWire.Form.CARD -> onCard(intake.receiveCard(g.raw, g.sender, t), g.sender, t)
            GateWire.Form.PACKET -> onPacket(intake.receive(g.raw, g.sender, t), g.sender, t, closed = false)
            GateWire.Form.VOUCH -> onVouch(intake.receiveVouch(g.raw, g.sender, t), g.sender, t, closed = false)
            GateWire.Form.ACK -> onAck(intake.receiveAck(g.raw, g.sender, t), g.sender, t, closed = false)
            GateWire.Form.KONVERT -> when (val k = intake.receiveKonvert(g.raw, g.sender, t)) {
                is GateIntake.KonvertVerdict.Refused -> Got(listOf(k.words), null)
                is GateIntake.KonvertVerdict.Packet -> onPacket(k.verdict, g.sender, t, closed = true)
                is GateIntake.KonvertVerdict.Vouch -> onVouch(k.verdict, g.sender, t, closed = true)
                is GateIntake.KonvertVerdict.Ack -> onAck(k.verdict, g.sender, t, closed = true)
            }
        }
    }

    private fun encLine(sender: PersonKey, closed: Boolean): String? =
        GateBox.receivedLine(who(sender), closed, keyKnown = store.encKeyOf(sender) != null)

    private fun onCard(v: GateIntake.CardVerdict, sender: PersonKey, t: Long): Got = when (v) {
        is GateIntake.CardVerdict.Refused -> Got(listOf(v.words), null)
        is GateIntake.CardVerdict.Accepted -> {
            val (keys, cards) = store.takeSignedCard(v.card, v.seal, t)
            val lines = listOfNotNull(GateKeys.meterLine(sender, keys.change, nameOf), cards?.let { GateCards.meterLine(it, nameOf, zone) })
            val reply = if (store.cardSentTo(sender) != myCard.fingerprint) card(sender) else null
            Got(lines, reply)
        }
    }

    private fun onPacket(v: GateIntake.Verdict, sender: PersonKey, t: Long, closed: Boolean): Got {
        val lines = ArrayList<String>()
        encLine(sender, closed)?.let { lines += it }
        when (v) {
            is GateIntake.Verdict.Accepted -> {
                lines += "Пришло от ${who(sender)}: «${v.packet.words}»"
                lines += GateKeys.meterLine(sender, v.sign, nameOf)
            }
            is GateIntake.Verdict.Refused -> lines += "Врата: ${v.words}"
        }
        val ack = GateAcks.answer(v, me, gotMs = t, sentMs = now()) ?: return Got(lines, null)
        return Got(lines, send(sender, GateSign.seal(GateWire.writeAck(ack), signer)))
    }

    private fun onVouch(v: GateIntake.VouchVerdict, sender: PersonKey, t: Long, closed: Boolean): Got {
        val lines = ArrayList<String>()
        encLine(sender, closed)?.let { lines += it }
        when (v) {
            is GateIntake.VouchVerdict.Refused -> lines += v.words
            is GateIntake.VouchVerdict.Accepted -> {
                store.addVouch(GateVouches.Record(v.vouch, t))
                lines += "Поручительство: ${who(sender)} " +
                    (if (v.vouch.withdraw) "снимает поручительство за " else "ручается за ") + who(v.vouch.agent)
                lines += GateKeys.meterLine(sender, v.sign, nameOf)
            }
        }
        return Got(lines, null)
    }

    private fun onAck(v: GateIntake.AckVerdict, sender: PersonKey, t: Long, closed: Boolean): Got {
        val lines = ArrayList<String>()
        encLine(sender, closed)?.let { lines += it }
        when (v) {
            is GateIntake.AckVerdict.Refused -> lines += v.words
            is GateIntake.AckVerdict.Accepted -> when (val m = store.takeAck(v.ack, t)) {
                GateOutbox.Match.Unknown -> lines += GateOutbox.meterUnknown(v.ack, nameOf)
                is GateOutbox.Match.Already -> Unit
                is GateOutbox.Match.Answered -> {
                    lines += GateOutbox.meterLine(m.outgoing, t, nameOf)
                    lines += GateClock.meterLine(sender, GateClock.of(m.outgoing), nameOf)
                }
            }
        }
        // На «принял» не отвечают (GateAck): ответа здесь нет при любом итоге.
        return Got(lines, null)
    }
}
