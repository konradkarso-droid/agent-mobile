package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Доверенность: «агент [agent] говорит за [principal]» — человека или чат.
 *
 * Действует, только когда её подтвердил МОЙ владелец ([confirmedMs]). Что
 * человек сам подтвердил своего агента в своём канале (служебная строка «Борис
 * добавил бота», строка в профиле), — свидетельство для владельца, а не
 * подтверждение: в чаты добавляют и чужих ботов, а раскрытие тому, кто говорит
 * «за Бориса», необратимо. Поля для свидетельства здесь нет: его форма зависит
 * от канала, а канала ещё нет.
 *
 * Доверенность говорит «за кого», а не «что можно у меня»: права в моём агенте
 * даёт только мой код.
 *
 * Запись не переписывают: конец доверенности — момент отзыва ([revokedMs]) или
 * срок ([untilMs]); новая доверенность — новая запись.
 */
data class GateProxy(
    val agent: PersonKey,
    val principal: PersonKey,
    val kind: Kind,
    /** Когда мой владелец подтвердил; null — ещё не подтвердил. С этого момента и действует. */
    val confirmedMs: Long?,
    /**
     * До какого момента; null — срок не указан, и доверенность действует до
     * отзыва. Сознательно: доверенность у агента кончается обычно не по
     * дате, а отзывом — бота удалили из чата, человек сказал «больше не мой
     * агент». Цена — забытая доверенность сама не кончается; виден только её
     * возраст в строке прибора.
     */
    val untilMs: Long?,
    /** Когда отозвана; null — не отзывалась. */
    val revokedMs: Long?,
) {
    /** За кого: человек или чат. Решает запись, а не вид ключа: вид ключа задаёт канал. */
    enum class Kind { PERSON, CHAT }
}

/**
 * «Кто это»: за кого говорит агент, приславший пакет, — по моим записям
 * доверенностей, а не по его словам.
 *
 * ЗАЧЕМ. [GatePacket.represents] — заявление, как имя в разговоре. Поверь ему —
 * и незнакомый агент, написав «я агент Бориса, Борис говорит: я в отпуске»,
 * вложит слова в рот Борису. Поэтому «за кого» верно, только если у меня есть
 * действующая доверенность. Развёртка с именем ([GateUnfold]) принимает этот
 * итог на вход и без «за человека» не включается.
 *
 * ПАКЕТ НЕ ОТВЕРГАЕТСЯ из-за того, что доверенности нет: слова идут как
 * сказаны, от незнакомого агента. Иначе знакомство не начнётся. Отказ один —
 * «с третьих рук»: агент говорит за чат, а сказавшим назван сам чат. Чат не
 * говорит; переписать сказавшего на агента значило бы подделать, кто сказал.
 * Этот отказ значит «не поверил», а не «не дошло»: пакет уже прошёл врата
 * ([GateIntake]), и отправителю его повторять незачем.
 *
 * СРОК — НА МОЁ «СЕЙЧАС», момент приёма. Не на «когда сказано»
 * ([GatePacket.atMs]) и не на «когда отправлено» ([GatePacket.sentMs]): оба
 * вписывает отправитель, и угонщик агента проставил бы момент до отзыва.
 * Слова, сказанные месяц назад, при доверенности со вчерашнего дня проходят:
 * доверенность — о том, говорит ли агент за Бориса сейчас.
 *
 * ТРЕВОГА, А НЕ ЗАМОРОЗКА. У ключа из канала (`tg:` — номер аккаунта) смены не
 * бывает. «Новый ключ Бориса» — это другой аккаунт, который говорит, что он
 * агент Бориса, когда у Бориса уже есть подтверждённый агент. Новому «за
 * Бориса» не даётся, пока владелец не подтвердит; старый агент по-прежнему «за
 * Бориса»: подменяют не его. Двух подтверждённых агентов у одного человека
 * (телефон и компьютер) иметь можно — тревоги нет. За чат тревоги нет вовсе:
 * модераторов у чата бывает несколько.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - не знакомит и ничего не запоминает: доверенности и известные ключи
 *    подаются на вход готовыми, таблиц и экрана подтверждения нет. «Впервые»
 *    говорит, что ключ надо запомнить, — запомнить пока некому;
 *  - облако не сверяет («совпало имя, не совпал номер») — только записи;
 *  - пакет, отправленный до конца срока, а принятый после, идёт без «за кого»;
 *  - «мой преемник — вот этот» не умеет: подписей в телеграме нет;
 *  - правдивость не проверяет: подтверждённый агент Бориса тоже может
 *    ошибаться и врать;
 *  - ключу отправителя верит, как его проверили врата, — не больше, чем каналу.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateWho {

    /** Почему «за кого» не подтверждено. */
    enum class Why(val words: String) {
        NO_PROXY("доверенности нет"),
        PENDING("доверенность ждёт подтверждения владельца"),
        NOT_STARTED("доверенность ещё не действует"),
        EXPIRED("доверенность истекла"),
        REVOKED("доверенность отозвана"),
        OTHER_PRINCIPAL("доверенность — за другого"),
    }

    sealed class Who {
        /** Агент говорит сам за себя ([GatePacket.represents] равен [GatePacket.from]). */
        object Self : Who()

        /** За человека, по действующей доверенности. */
        data class ForPerson(val proxy: GateProxy) : Who()

        /** За чат, по действующей доверенности; сказавший — сам агент. */
        data class ForChat(val proxy: GateProxy) : Who()

        /**
         * Говорит, что за [claimed], но не подтверждено: [why], запись — [proxy]
         * (null — записи нет). [rivals] — подтверждённые агенты того же человека
         * с другими ключами; не пусто — тревога.
         */
        data class Unconfirmed(
            val claimed: PersonKey,
            val why: Why,
            val proxy: GateProxy?,
            val rivals: List<PersonKey>,
        ) : Who() {
            val alarm: Boolean get() = rivals.isNotEmpty()
        }

        /** Не поверил пакету вовсе — пока только «с третьих рук». */
        data class Refused(val refusal: GateRefusal, val detail: String) : Who()
    }

    /** [firstSeen] — ключа отправителя нет среди известных: знакомство, ключ запомнить. */
    data class Result(val from: PersonKey, val who: Who, val firstSeen: Boolean)

    /**
     * [packet] — принятый вратами ([GateIntake]); [proxies] — мои записи
     * доверенностей; [known] — ключи агентов, что уже встречались.
     */
    fun of(packet: GatePacket, proxies: List<GateProxy>, known: Set<PersonKey>, nowMs: Long): Result {
        val firstSeen = packet.from !in known
        fun result(who: Who) = Result(packet.from, who, firstSeen)

        if (packet.represents == packet.from) return result(Who.Self)

        val isChat = proxies.any { it.principal == packet.represents && it.kind == GateProxy.Kind.CHAT }
        if (isChat && packet.saidBy == packet.represents) {
            return result(Who.Refused(GateRefusal.HEARSAY, "сказавшим назван чат ${packet.represents.id} — чат не говорит"))
        }

        val mine = proxies.filter { it.agent == packet.from && it.principal == packet.represents }
        mine.firstOrNull { why(it, nowMs) == null }?.let {
            return result(if (it.kind == GateProxy.Kind.CHAT) Who.ForChat(it) else Who.ForPerson(it))
        }

        val rivals = proxies
            .filter {
                it.principal == packet.represents && it.agent != packet.from &&
                    it.kind == GateProxy.Kind.PERSON && why(it, nowMs) == null
            }
            .map { it.agent }
            .distinct()

        // Записей несколько — причина первой по порядку: чего ждут от владельца,
        // потом что случилось, потом обычный конец.
        val order = listOf(Why.PENDING, Why.REVOKED, Why.EXPIRED, Why.NOT_STARTED)
        val own = mine.map { it to why(it, nowMs)!! }.minByOrNull { order.indexOf(it.second) }
        if (own != null) return result(Who.Unconfirmed(packet.represents, own.second, own.first, rivals))

        val elsewhere = proxies.firstOrNull { it.agent == packet.from && why(it, nowMs) == null }
        return result(
            if (elsewhere != null) Who.Unconfirmed(packet.represents, Why.OTHER_PRINCIPAL, elsewhere, rivals)
            else Who.Unconfirmed(packet.represents, Why.NO_PROXY, null, rivals),
        )
    }

    /** Почему запись не действует на [nowMs]; null — действует. */
    fun why(p: GateProxy, nowMs: Long): Why? {
        val confirmed = p.confirmedMs ?: return Why.PENDING
        if (p.revokedMs != null && nowMs >= p.revokedMs) return Why.REVOKED
        if (nowMs < confirmed) return Why.NOT_STARTED
        if (p.untilMs != null && nowMs >= p.untilMs) return Why.EXPIRED
        return null
    }

    /**
     * Строка прибора. Имена — из своего знания ([nameOf]); не знаю имени — ключ.
     * Даты — в поясе получателя [zone].
     */
    fun meterLine(r: Result, nameOf: (PersonKey) -> String?, zone: ZoneId): String {
        fun name(k: PersonKey) = nameOf(k)?.takeIf { it.isNotBlank() } ?: k.id
        val day = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        fun date(ms: Long) = day.format(Instant.ofEpochMilli(ms))
        fun term(p: GateProxy) =
            "подтверждена ${date(p.confirmedMs!!)}, " +
                if (p.untilMs == null) "без срока, до отзыва" else "до ${date(p.untilMs)}"

        val line = when (val w = r.who) {
            Who.Self -> "Врата: ${name(r.from)} — агент сам за себя"
            is Who.ForPerson -> "Врата: за кого — ${name(w.proxy.principal)} (доверенность ${term(w.proxy)})"
            is Who.ForChat -> "Врата: за чат — ${name(w.proxy.principal)} (доверенность ${term(w.proxy)})"
            is Who.Unconfirmed -> {
                val detail = when (w.why) {
                    Why.EXPIRED -> "${w.why.words} ${date(w.proxy!!.untilMs!!)}"
                    Why.REVOKED -> "${w.why.words} ${date(w.proxy!!.revokedMs!!)}"
                    Why.NOT_STARTED -> "${w.why.words}, с ${date(w.proxy!!.confirmedMs!!)}"
                    Why.OTHER_PRINCIPAL -> "доверенность — на другого: ${name(w.proxy!!.principal)}"
                    else -> w.why.words
                }
                val head = if (w.alarm) "Врата: тревога — " else "Врата: за кого — не подтверждено: "
                val tail = if (w.alarm) {
                    "; у этого человека уже есть подтверждённый агент: ${w.rivals.joinToString(", ") { name(it) }}"
                } else ""
                "${head}заявлено: ${name(w.claimed)}; $detail$tail"
            }
            is Who.Refused -> "Врата: не поверил — ${w.refusal.words}: ${w.detail}"
        }
        return if (r.firstSeen) "$line · ключ ${r.from.id} впервые" else line
    }
}
