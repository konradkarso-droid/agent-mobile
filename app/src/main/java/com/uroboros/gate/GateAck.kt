package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/**
 * «Принял»: получатель [from] отвечает отправителю [to] на пакет слов с номером
 * [number] — пакет дошёл, и вот что с ним стало ([result]).
 *
 * «ПРИНЯЛ» ЗНАЧИТ «ДОШЛО», А НЕ «ПОВЕРИЛ». Отвечается и на пакет, которому
 * получатель потом не поверил ([GateWho]: «с третьих рук», доверенности нет):
 * «кто это» — дело получателя, а отправителю надо знать одно — слать ли ещё.
 * Без ответа отправитель повторял бы пакет до конца срока.
 *
 * НА КАКИЕ ОТКАЗЫ ВРАТ ОТВЕЧАЕТСЯ. На те, где пакет прочитан и прислан тем, кто
 * в нём назван отправителем: «повтор» (уже принят — отправитель мог не услышать
 * первого ответа), «не мне», «устарел», «из будущего». Последние два говорят
 * отправителю, что с его часами или очередью что-то не так, — повторять такой
 * пакет бесполезно. На остальные отказы не отвечается: пакет не прочитан (номера
 * нет), отправитель в пакете не тот, что у канала (отвечать некому), или
 * сверх предела потока (ответ сам стал бы потоком).
 *
 * НА «ПРИНЯЛ» НЕ ОТВЕЧАЮТ. Ответ на ответ — первая же петля двух агентов. Это
 * задано устройством: [GateAcks.answer] берёт только итог приёма пакета слов,
 * а у приёма «принял» ([GateIntake.receiveAck]) свой итог, который туда не
 * подаётся. На карточку и поручительство «принял» тоже нет: у них нет номера.
 *
 * ЧАСЫ. [gotMs] и [sentMs] — по часам отвечающего: когда пакет пришёл и когда
 * ушёл ответ. Вместе с двумя моментами отправителя они дают расхождение часов
 * пары ([GateClock]).
 */
data class GateAck(
    val from: PersonKey,
    val to: PersonKey,
    val number: Long,
    val result: Result,
    val gotMs: Long,
    val sentMs: Long,
) {
    /**
     * Что стало с пакетом. [delivered] — дошёл и взят (сейчас или раньше): больше
     * не слать. Иначе — дошёл, но не взят, и повторять тоже незачем.
     */
    enum class Result(val wire: String, val words: String, val delivered: Boolean) {
        ACCEPTED("accepted", "принят", true),
        REPEAT("repeat", "уже был принят", true),
        NOT_FOR_ME("not_for_me", "не принят: не мне", false),
        STALE("stale", "не принят: устарел", false),
        FROM_FUTURE("from_future", "не принят: из будущего", false),
    }
}

object GateAcks {

    /**
     * Ответ на итог приёма пакета слов; null — на этот итог не отвечается (см.
     * [GateAck], «на какие отказы»). [me] — мой ключ, [gotMs] — когда пакет
     * пришёл, [sentMs] — когда уходит ответ; оба по моим часам.
     */
    fun answer(verdict: GateIntake.Verdict, me: PersonKey, gotMs: Long, sentMs: Long): GateAck? {
        val (packet, result) = when (verdict) {
            is GateIntake.Verdict.Accepted -> verdict.packet to GateAck.Result.ACCEPTED
            is GateIntake.Verdict.Refused -> {
                val p = verdict.packet ?: return null
                val r = when (verdict.refusal) {
                    GateRefusal.REPEAT -> GateAck.Result.REPEAT
                    GateRefusal.NOT_FOR_ME -> GateAck.Result.NOT_FOR_ME
                    GateRefusal.STALE -> GateAck.Result.STALE
                    GateRefusal.FROM_FUTURE -> GateAck.Result.FROM_FUTURE
                    else -> return null
                }
                p to r
            }
        }
        // Часы перевели назад между приходом и ответом — ответ «раньше прихода»
        // получатель счёл бы порчей ([GateWire.readAck]) и ждал бы дальше.
        // Поэтому ответ не раньше прихода; часы, переведённые на ходу, замер
        // пары и так портят — это видно по его разбросу ([GateClock]).
        return GateAck(from = me, to = packet.from, number = packet.number, result = result, gotMs = gotMs, sentMs = maxOf(sentMs, gotMs))
    }
}
