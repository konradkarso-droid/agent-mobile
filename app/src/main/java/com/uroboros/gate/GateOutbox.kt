package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/**
 * Отправленный пакет слов: кому [to], под каким номером [number], когда уходил
 * ([attempts] — момент каждой попытки по моим часам, первая — первая отправка)
 * и что ответили ([ack], пришёл в мой момент [ackedMs]).
 *
 * МОМЕНТ КАЖДОЙ ПОПЫТКИ ХРАНИТ ОТПРАВИТЕЛЬ, а не пакет. Повтор несёт момент
 * первой отправки ([GatePacket.sentMs]) — иначе получатель не узнал бы повтор
 * по сроку. Для часов пары нужен момент именно той попытки, на которую пришёл
 * ответ, — он есть только здесь ([GateClock]).
 */
data class GateOutgoing(
    val to: PersonKey,
    val number: Long,
    val attempts: List<Long>,
    val ack: GateAck?,
    val ackedMs: Long?,
) {
    init {
        require(attempts.isNotEmpty()) { "у отправленного пакета есть хотя бы одна попытка" }
    }

    val firstMs: Long get() = attempts.first()
}

/**
 * Исходящие пакеты слов: когда повторять, когда бросить, какой «принял» к
 * какому пакету.
 *
 * ПОВТОР. Пакет теряется: телефон получателя выключен дольше, чем канал держит
 * недоставленное, или потерялся сам «принял». Поэтому без ответа пакет
 * уходит снова тем же номером, с растущим промежутком: [RETRY_FIRST_MS], потом
 * вдвое дольше, но не дольше [RETRY_MAX_MS]. Объявленные числа, не подобранные:
 * час — чтобы не будить канал зря, сутки — столько канал держит недоставленное
 * (Telegram), чаще после этого повторять незачем.
 *
 * БРОСИТЬ. Через [GateIntake.Limits.repeatWindowMs] от первой отправки
 * получатель сочтёт пакет устаревшим — дальше повторять бесполезно: «не
 * дошло». Это тот же срок, что у врат получателя; если у него срок другой, он
 * ответит «устарел», и это будет видно.
 *
 * Ответ, который уже есть, — конец: второй «принял» (на повтор) ничего не
 * меняет. «Принял» на номер, которого я не отправлял этому агенту, — ничего не
 * меняет, только строка прибора.
 *
 * ЧЕГО НЕ УМЕЕТ: ничего не хранит и не шлёт — список подаётся на вход и
 * возвращается; таблицы и канала нет. Сколько слать подряд одному агенту,
 * решает не он, а [GateTurns]. Карточку и поручительство не ведёт: «принял» на
 * них нет.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateOutbox {

    const val RETRY_FIRST_MS = 60 * 60_000L
    const val RETRY_MAX_MS = DAY_MS

    /** Через сколько от первой отправки повторять бесполезно (см. «бросить»). */
    val giveUpMs: Long get() = GateIntake.Limits.DECLARED.repeatWindowMs

    /** Что с пакетом сейчас. */
    sealed class State {
        /** Дошёл и взят (сейчас или раньше). */
        data class Delivered(val ack: GateAck) : State()

        /** Дошёл, но не взят; повторять незачем. */
        data class NotTaken(val ack: GateAck) : State()

        /** Ответа нет, следующая попытка — в [nextMs]. */
        data class Waiting(val nextMs: Long) : State()

        /** Ответа нет, пора повторить. */
        object Resend : State()

        /** Ответа нет за весь срок. */
        object GaveUp : State()
    }

    /** Новый отправленный пакет: первая попытка — сейчас. */
    fun sent(to: PersonKey, number: Long, nowMs: Long) = GateOutgoing(to, number, listOf(nowMs), null, null)

    /** Повтор ушёл сейчас. */
    fun resent(o: GateOutgoing, nowMs: Long) = o.copy(attempts = o.attempts + nowMs)

    fun state(o: GateOutgoing, nowMs: Long): State {
        o.ack?.let { return if (it.result.delivered) State.Delivered(it) else State.NotTaken(it) }
        if (nowMs - o.firstMs >= giveUpMs) return State.GaveUp
        val next = o.attempts.last() + interval(o.attempts.size)
        return if (nowMs >= next) State.Resend else State.Waiting(next)
    }

    /** Промежуток после [made]-й попытки: час, два, четыре… не больше суток. */
    private fun interval(made: Int): Long {
        var ms = RETRY_FIRST_MS
        repeat(made - 1) { ms = minOf(ms * 2, RETRY_MAX_MS) }
        return ms
    }

    /** К какому пакету пришёл «принял». */
    sealed class Match {
        /** Такого пакета этому агенту я не отправлял. */
        object Unknown : Match()

        /** Ответ на этот пакет уже был; новый ничего не меняет. */
        data class Already(val outgoing: GateOutgoing) : Match()

        /** Первый ответ; [outgoing] — уже с ним. */
        data class Answered(val outgoing: GateOutgoing) : Match()
    }

    data class Taken(val outbox: List<GateOutgoing>, val match: Match)

    /** Принять «принял» [ack], пришедший в мой момент [nowMs]. Ищется по паре «кому слал + номер». */
    fun take(outbox: List<GateOutgoing>, ack: GateAck, nowMs: Long): Taken {
        val i = outbox.indexOfFirst { it.to == ack.from && it.number == ack.number }
        if (i < 0) return Taken(outbox, Match.Unknown)
        val o = outbox[i]
        if (o.ack != null) return Taken(outbox, Match.Already(o))
        val answered = o.copy(ack = ack, ackedMs = nowMs)
        return Taken(outbox.toMutableList().also { it[i] = answered }, Match.Answered(answered))
    }

    /** Строка прибора. Имя — из своего знания ([nameOf]); не знаю имени — ключ. */
    fun meterLine(o: GateOutgoing, nowMs: Long, nameOf: (PersonKey) -> String?): String {
        val who = nameOf(o.to)?.takeIf { it.isNotBlank() } ?: o.to.id
        val head = "Отправлено: $who, пакет ${GateWire.numberHex(o.number)} — "
        val tries = "попыток ${o.attempts.size}"
        return when (val s = state(o, nowMs)) {
            is State.Delivered -> head + s.ack.result.words + " через ${spanWords(o.ackedMs!! - o.firstMs)}"
            is State.NotTaken -> head + s.ack.result.words
            is State.Waiting -> head + "нет ответа ${spanWords(nowMs - o.firstMs)}, $tries, следующая через ${spanWords(s.nextMs - nowMs)}"
            State.Resend -> head + "нет ответа ${spanWords(nowMs - o.firstMs)}, $tries, пора повторить"
            State.GaveUp -> head + "не дошло за ${spanWords(giveUpMs)}, $tries"
        }
    }

    /** Строка прибора на «принял», которому не нашлось пакета. */
    fun meterUnknown(ack: GateAck, nameOf: (PersonKey) -> String?): String {
        val who = nameOf(ack.from)?.takeIf { it.isNotBlank() } ?: ack.from.id
        return "«Принял» на пакет ${GateWire.numberHex(ack.number)}: $who — такого пакета я ему не отправлял"
    }
}
