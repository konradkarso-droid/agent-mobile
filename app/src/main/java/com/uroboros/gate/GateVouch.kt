package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Знакомство: мой владелец знает агента [agent] — подтвердил его сам.
 *
 * Не доверенность ([GateProxy]): доверенность говорит «за кого», знакомство —
 * только «этого агента мы знаем». Запиши знакомство доверенностью «за себя» —
 * и [GateWho] для агента без доверенности показал бы «доверенность — на
 * другого: он сам», что неправда.
 *
 * Запись не переписывают: конец знакомства — момент, когда владелец его снял
 * ([revokedMs]); новое знакомство — новая запись.
 */
data class GateAcquaintance(
    val agent: PersonKey,
    /** Когда мой владелец подтвердил; null — ещё не подтвердил. */
    val confirmedMs: Long?,
    /** Когда снял; null — не снимал. */
    val revokedMs: Long?,
)

/** «[voucher] ручается за [agent] перед [to]»; [withdraw] — поручительство снимается. */
data class GateVouch(
    val voucher: PersonKey,
    val agent: PersonKey,
    val to: PersonKey,
    val withdraw: Boolean,
)

/**
 * Поручительство: кто ручается за агента и знает ли поручителя мой владелец.
 *
 * Образ — клиентела в Риме: патрон ручается за клиента своим именем, поэтому за
 * кого попало не ручаются. Здесь у поручителя своё имя — знакомство с моим
 * владельцем.
 *
 * ПОРУЧИТЕЛЬСТВО НИЧЕГО НЕ РЕШАЕТ. Итог — строка для моего владельца:
 * «знакомых поручителей 2, нужно 2 — достаточно». «Достаточно» ничего не
 * включает само: решает владелец, подтверждая знакомство. «За человека» не
 * даёт никакое поручительство — только доверенность, подтверждённая моим
 * владельцем ([GateWho]); иначе цепочка знакомых давала бы чужому агенту
 * говорить за Бориса. Весов нет: числа доверия сейчас некому читать и не на
 * чем подобрать.
 *
 * В СЧЁТ ИДЁТ ТОЛЬКО ПОРУЧИТЕЛЬ, КОТОРОГО ЗНАЕТ МОЙ ВЛАДЕЛЕЦ: знакомство
 * действует ([GateAcquaintance]) или у поручителя действует доверенность
 * ([GateProxy]) — её тоже подтвердил мой владелец. Длина цепочки поэтому — один
 * шаг, и числом он не задаётся — так устроен счёт: за кого поручился знакомый, тот сам поручителем не становится
 * — патрон отвечает за своего клиента, а не за клиентов клиента. Отсюда и
 * защита от подделки массой: сто незнакомых агентов, ручающихся друг за
 * друга, — ноль знакомых поручителей.
 *
 * НУЖНО ДВОЕ ([MIN_VOUCHERS]): один знакомый может ошибиться или быть угнан.
 *
 * ЧТО НОВЕЕ — ТО, ЧТО ПРИНЯТО ПОЗЖЕ. У пары «поручитель → агент» действует
 * последнее принятое: поручился, снял, поручился снова — поручается.
 * Знакомство и доверенность проверяются на моё «сейчас», как в [GateWho].
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - ничего не хранит: поручительства, знакомства и доверенности подаются на
 *    вход готовыми, таблиц и экрана нет;
 *  - ответственности поручителя нет: отметки «этот агент подвёл» нет, её
 *    ставит владелец, а экрана нет;
 *  - двух агентов одного человека считает двумя поручителями;
 *  - правдивость поручительства не проверяет: знакомый поручитель тоже может
 *    ошибиться.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateVouches {

    /** Объявленное число, не подобранное (решение владельца). */
    const val MIN_VOUCHERS = 2

    /** Принятое поручительство и мой момент приёма. */
    data class Record(val vouch: GateVouch, val receivedMs: Long)

    /** Почему поручитель не в счёт. */
    enum class Why(val words: String) {
        UNKNOWN("его вы не подтверждали"),
        PENDING("знакомство с ним ждёт вашего подтверждения"),
        REVOKED("знакомство с ним вы сняли"),
        SELF("ручается сам за себя"),
    }

    /** Знакомый поручитель; [sinceMs] — с какого момента мой владелец его знает. */
    data class Counted(val voucher: PersonKey, val sinceMs: Long)

    data class NotCounted(val voucher: PersonKey, val why: Why, val atMs: Long?)

    /**
     * [directSince] — с какого момента мой владелец знает самого агента (null —
     * не знает); [counted] и [notCounted] —
     * нынешние поручительства (снятые не показываются).
     */
    data class Result(
        val agent: PersonKey,
        val directSince: Long?,
        val counted: List<Counted>,
        val notCounted: List<NotCounted>,
    ) {
        val enough: Boolean get() = counted.size >= MIN_VOUCHERS
    }

    fun of(
        agent: PersonKey,
        records: List<Record>,
        acquaintances: List<GateAcquaintance>,
        proxies: List<GateProxy>,
        nowMs: Long,
    ): Result {
        val latest = records
            .filter { it.vouch.agent == agent }
            .groupBy { it.vouch.voucher }
            .mapValues { (_, rs) -> rs.maxByOrNull { it.receivedMs }!! }
            .values
            .filter { !it.vouch.withdraw }
            .sortedBy { it.receivedMs }

        val counted = mutableListOf<Counted>()
        val notCounted = mutableListOf<NotCounted>()
        for (r in latest) {
            val v = r.vouch.voucher
            if (v == agent) {
                notCounted += NotCounted(v, Why.SELF, null)
                continue
            }
            val since = knownSince(v, acquaintances, proxies, nowMs)
            if (since != null) counted += Counted(v, since) else notCounted += whyNot(v, acquaintances, nowMs)
        }
        return Result(agent, knownSince(agent, acquaintances, proxies, nowMs), counted, notCounted)
    }

    /** С какого момента мой владелец знает [k] на [nowMs]; null — не знает. */
    fun knownSince(k: PersonKey, acquaintances: List<GateAcquaintance>, proxies: List<GateProxy>, nowMs: Long): Long? {
        val byAcq = acquaintances.filter { it.agent == k && active(it, nowMs) }.mapNotNull { it.confirmedMs }
        val byProxy = proxies.filter { it.agent == k && GateWho.why(it, nowMs) == null }.mapNotNull { it.confirmedMs }
        return (byAcq + byProxy).minOrNull()
    }

    private fun active(a: GateAcquaintance, nowMs: Long): Boolean {
        val c = a.confirmedMs ?: return false
        if (nowMs < c) return false
        return a.revokedMs == null || nowMs < a.revokedMs
    }

    private fun whyNot(v: PersonKey, acquaintances: List<GateAcquaintance>, nowMs: Long): NotCounted {
        val mine = acquaintances.filter { it.agent == v }
        mine.firstOrNull { it.confirmedMs == null }?.let { return NotCounted(v, Why.PENDING, null) }
        mine.filter { it.revokedMs != null && nowMs >= it.revokedMs }.maxByOrNull { it.revokedMs!! }
            ?.let { return NotCounted(v, Why.REVOKED, it.revokedMs) }
        return NotCounted(v, Why.UNKNOWN, null)
    }

    /**
     * Строка прибора. Имена — из своего знания ([nameOf]); не знаю имени — ключ.
     * Даты — в поясе получателя [zone].
     */
    fun meterLine(r: Result, nameOf: (PersonKey) -> String?, zone: ZoneId): String {
        fun name(k: PersonKey) = nameOf(k)?.takeIf { it.isNotBlank() } ?: k.id
        val day = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        fun date(ms: Long) = day.format(Instant.ofEpochMilli(ms))

        val head = "Поручительство: ${name(r.agent)} — " +
            (r.directSince?.let { "знаком вам напрямую, с ${date(it)}; " } ?: "")
        if (r.counted.isEmpty() && r.notCounted.isEmpty()) return head + "никто не ручается"
        val count = "знакомых поручителей ${r.counted.size}, нужно $MIN_VOUCHERS — " + if (r.enough) "достаточно" else "мало"
        val yes = r.counted.joinToString(", ") { "${name(it.voucher)} (вы подтвердили ${date(it.sinceMs)})" }
        val no = r.notCounted.joinToString(", ") { n ->
            "${name(n.voucher)} — ${n.why.words}" + (n.atMs?.let { " ${date(it)}" } ?: "")
        }
        return buildString {
            append(head)
            append(count)
            if (yes.isNotEmpty()) append(": ").append(yes)
            if (no.isNotEmpty()) append("; не в счёт: ").append(no)
        }
    }
}
