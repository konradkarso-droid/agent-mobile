package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/**
 * Часы пары агентов: на сколько часы собеседника расходятся с моими.
 *
 * ЗАЧЕМ. «Устарел», «из будущего» и «когда сказано» у пакета — по часам
 * отправителя ([GatePacket.sentMs], [GatePacket.atMs]). Разойдутся часы на
 * сутки — пакеты молча пойдут в отказ; на несколько часов — «вчера» у модели
 * станет «сегодня». Без замера этого не видно.
 *
 * ЧЕТЫРЕ МОМЕНТА, как сверяет часы NTP: t1 — попытка ушла (мои часы, хранит
 * [GateOutgoing]), t2 — пакет пришёл и t3 — ушёл ответ (часы собеседника, в
 * «принял»: [GateAck.gotMs], [GateAck.sentMs]), t4 — ответ пришёл ко мне
 * ([GateOutgoing.ackedMs]). Расхождение — ((t2 − t1) + (t3 − t4)) / 2, задержка
 * туда-обратно — (t4 − t1) − (t3 − t2). Расчёт верен, если путь туда и обратно
 * занял поровну; не поровну — ошибка не больше половины задержки, она и
 * пишется разбросом («±»).
 *
 * ЗАМЕР БЕРЁТСЯ НЕ ВСЕГДА — только когда он что-то значит:
 *  - попытка была одна. Повторов несколько — неизвестно, на какую пришёл
 *    ответ, и t1 не определить;
 *  - задержка туда-обратно не больше [MAX_ROUND_TRIP_MS]. Телефон, выключенный
 *    на ночь, даёт задержку в часы, и разброс выходит больше самого
 *    расхождения. Такой замер выбрасывается, а не усредняется;
 *  - задержка не отрицательная: иначе чьи-то часы перевели между моментами.
 *
 * ТОЛЬКО ПОКАЗЫВАЕТ. Моменты в пакетах не исправляются: исправление по
 * неточному замеру само сдвинуло бы смысл «когда сказано».
 *
 * Объявленные числа, не подобранные: [MAX_ROUND_TRIP_MS] — две минуты, разброс
 * тогда не больше минуты, а расхождение, которое что-то портит, — часы и сутки;
 * [SELF_TOLERANCE_MS] — минута против секундной точности времени сервера.
 *
 * ЧЕГО НЕ УМЕЕТ: замеры не копит и не хранит — один замер на один ответ;
 * какое расхождение терпимо, не решает; часы никому не сообщает.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateClock {

    const val MAX_ROUND_TRIP_MS = 2 * 60_000L
    const val SELF_TOLERANCE_MS = 60_000L

    /** Часы собеседника минус мои: [offsetMs] больше нуля — его спешат; ±[errorMs]. */
    data class Sample(val offsetMs: Long, val errorMs: Long)

    sealed class Reading {
        data class Taken(val sample: Sample) : Reading()
        data class Dropped(val why: String) : Reading()
    }

    /** Замер по отправленному пакету с ответом; без ответа — нечего мерить. */
    fun of(o: GateOutgoing): Reading {
        val ack = o.ack ?: return Reading.Dropped("ответа нет")
        val t4 = o.ackedMs ?: return Reading.Dropped("ответа нет")
        if (o.attempts.size != 1) return Reading.Dropped("попыток ${o.attempts.size} — не знаю, на какую ответ")
        val t1 = o.attempts.single()
        val t2 = ack.gotMs
        val t3 = ack.sentMs
        val roundTrip = (t4 - t1) - (t3 - t2)
        if (roundTrip < 0) return Reading.Dropped("моменты не сходятся — часы перевели на ходу")
        if (roundTrip > MAX_ROUND_TRIP_MS) return Reading.Dropped("ответ шёл ${spanWords(roundTrip)}")
        return Reading.Taken(Sample(((t2 - t1) + (t3 - t4)) / 2, roundTrip / 2))
    }

    /** Строка прибора. Имя — из своего знания ([nameOf]); не знаю имени — ключ. */
    fun meterLine(peer: PersonKey, r: Reading, nameOf: (PersonKey) -> String?): String {
        val who = nameOf(peer)?.takeIf { it.isNotBlank() } ?: peer.id
        return when (r) {
            is Reading.Dropped -> "Часы: $who — замер выброшен: ${r.why}"
            is Reading.Taken -> {
                val s = r.sample
                val spread = "±${spanWords(s.errorMs)}"
                val off = kotlin.math.abs(s.offsetMs)
                when {
                    off <= s.errorMs -> "Часы: $who — сходятся с моими ($spread)"
                    s.offsetMs > 0 -> "Часы: $who — спешат на ~${spanWords(off)} ($spread)"
                    else -> "Часы: $who — отстают на ~${spanWords(off)} ($spread)"
                }
            }
        }
    }

    /**
     * Свои часы по свидетелю — серверу канала, у которого время на каждом
     * сообщении ([serverMs]). Проверка в одну сторону: сообщение не может прийти
     * раньше, чем сервер его принял, поэтому сервер «впереди» моего «сейчас»
     * больше чем на [SELF_TOLERANCE_MS] значит, что мои часы отстают. Обратное
     * ничего не значит: телефон мог быть выключен, и сообщение ждало. null —
     * расхождения не видно. [witness] — как назвать сервер в строке.
     */
    fun selfCheck(serverMs: Long, nowMs: Long, witness: String): String? {
        val ahead = serverMs - nowMs
        if (ahead <= SELF_TOLERANCE_MS) return null
        return "Часы: мои отстают от $witness на ~${spanWords(ahead)}"
    }
}
