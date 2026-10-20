package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/** Почему пакет врат не принят — словами для прибора («Врата: не принят — …»). */
enum class GateRefusal(val words: String) {
    TOO_BIG("больше предела размера"),
    NOT_A_PACKET("не пакет трафарета"),
    FOREIGN_VERSION("чужая версия трафарета"),
    INCOMPLETE("оборван"),
    DAMAGED("испорчен"),
    MALFORMED("неверная форма"),
    UNKNOWN_FIELD("незнакомое поле"),
    NO_ORIGIN("нет точки отправления"),
    NO_KEY("нет ключа"),
    HEARSAY("с третьих рук"),
    UNKNOWN_SCHEME("незнакомый канал ключа"),
    FLOOD("сверх предела потока"),
    NOT_FOR_ME("не мне"),
}

/**
 * Приём пакета врат: принять или не принять, и если нет — почему.
 *
 * Порядок проверок — от дешёвого к дорогому, и пакет молчит на первой же
 * несошедшейся: размер → форма и целость ([GateWire.read]) → схемы ключей →
 * предел потока → адресат.
 *
 * СХЕМЫ КЛЮЧЕЙ. Знакомы только схемы из [knownSchemes]. Ключ незнакомого
 * канала не принимается, а не «как-нибудь»: подлинность ключа даёт канал, и
 * канал, которого агент не знает, ничего не подтверждает. Схема экрана
 * («screen:») через врата не идёт: экран — не канал между агентами.
 *
 * ПРЕДЕЛ ПОТОКА считается по ключу отправителя ([GatePacket.from]) у пакетов,
 * прошедших форму, — и тех, что адресованы не мне: заваливать можно и ими.
 * Пакет, у которого форма не сошлась, отправителя не имеет; от потока таких
 * защищает только предел размера. Отброшенное сверх предела не копится и не
 * откладывается — его не было.
 *
 * ЧЕГО НЕ УМЕЕТ: подлинность отправителя не проверяет (см. [GatePacket]);
 * оборванный пакет не складывает в буфер до прихода остатка; в память ничего
 * не кладёт. Счётчик потока живёт в памяти процесса и после перезапуска
 * начинается с нуля.
 *
 * @param me мой ключ: пакет с другим [GatePacket.to] — «не мне».
 */
class GateIntake(
    private val me: PersonKey,
    private val limits: Limits = Limits.DECLARED,
    private val knownSchemes: Set<String> = setOf("tg"),
) {

    data class Limits(val maxBytes: Int, val perWindow: Int, val windowMs: Long) {
        companion object {
            /** Объявленные числа, не подобранные: 8 КБ на пакет, 20 пакетов за 10 минут от одного ключа. */
            val DECLARED = Limits(maxBytes = 8 * 1024, perWindow = 20, windowMs = 10 * 60_000L)
        }
    }

    sealed class Verdict {
        data class Accepted(val packet: GatePacket) : Verdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : Verdict() {
            /** Строка для прибора. */
            val words: String get() = "не принят — ${refusal.words}: $detail"
        }
    }

    /** Ключ отправителя → моменты принятых к счёту пакетов в окне [Limits.windowMs]. */
    private val recent = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun receive(raw: ByteArray, nowMs: Long): Verdict {
        if (raw.size > limits.maxBytes) {
            return Verdict.Refused(GateRefusal.TOO_BIG, "${raw.size} Б, предел ${limits.maxBytes}")
        }
        val packet = when (val r = GateWire.read(raw)) {
            is GateWire.Read.Fail -> return Verdict.Refused(r.refusal, r.detail)
            is GateWire.Read.Ok -> r.packet
        }

        val keys = listOf(packet.from, packet.represents, packet.to, packet.saidBy) + packet.about
        keys.firstOrNull { GateWire.schemeOf(it) !in knownSchemes }?.let {
            return Verdict.Refused(GateRefusal.UNKNOWN_SCHEME, "«${GateWire.schemeOf(it)}» в ${it.id}")
        }

        if (!countWithinLimit(packet.from.id, nowMs)) {
            return Verdict.Refused(
                GateRefusal.FLOOD,
                "от ${packet.from.id} больше ${limits.perWindow} за ${limits.windowMs / 60_000} мин",
            )
        }

        if (packet.to != me) return Verdict.Refused(GateRefusal.NOT_FOR_ME, "кому ${packet.to.id}, я ${me.id}")
        return Verdict.Accepted(packet)
    }

    /**
     * Засчитать пакет отправителя. false — предел окна уже набран: этот пакет
     * не засчитывается и отбрасывается. Моменты из будущего (часы перевели
     * назад) остаются в окне — сомнение в сторону предела, а не пропуска.
     */
    private fun countWithinLimit(sender: String, nowMs: Long): Boolean {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && nowMs - q.first() >= limits.windowMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
        val q = recent.getOrPut(sender) { ArrayDeque() }
        if (q.size >= limits.perWindow) return false
        q.addLast(nowMs)
        return true
    }
}
