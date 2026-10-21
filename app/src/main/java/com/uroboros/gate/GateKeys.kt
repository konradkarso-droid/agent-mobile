package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/**
 * Ключ подписи агента, как я его знаю: [key] агента [agent], впервые увиден
 * в [seenMs]; с [trustedMs] им проверяются формы агента (null — ждёт моего
 * владельца); [how] — откуда доверие; [endedMs] — когда ключ сменился другим.
 * Запись не переписывается, только получает доверие или конец.
 */
data class GateKeyRecord(
    val agent: PersonKey,
    val key: String,
    val seenMs: Long,
    val trustedMs: Long?,
    val how: How,
    val endedMs: Long?,
) {
    enum class How(val words: String) {
        FIRST("при первой встрече"),
        ROTATED("сменён, подписано прежним ключом"),
        OWNER("подтверждён вами"),
    }
}

/**
 * Чей ключ подписи верен для агента и что делать с пришедшей подписью.
 *
 * ДОВЕРИЕ ПРИ ПЕРВОЙ ВСТРЕЧЕ (как у SSH). Ключ едет в карточке агента
 * ([GateCard.Field.KEY]), карточка подписана им же. Первая подписанная
 * карточка с ключа канала задаёт ключ. От угона, случившегося до первой
 * встречи, это не защищает — это граница, а не дыра.
 *
 * ПОСЛЕ ЭТОГО:
 *  - форма агента без подписи — отказ. Иначе двойник с украденным токеном
 *    просто не стал бы подписывать;
 *  - подпись не тем ключом — отказ;
 *  - карточка с новым ключом, подписанная и прежним ключом (шестое слово
 *    шапки, [GateWire.Seal.prevSig]), — ключ сменён без вопросов: так агент
 *    переезжает на новый телефон, пока старый жив;
 *  - карточка с новым ключом без подписи прежним — ТРЕВОГА, а не замена:
 *    прежний ключ остаётся в силе, новый ждёт моего владельца ([confirm]).
 *    Так выглядит и двойник, и переезд, когда старого телефона уже нет;
 *    различить их может только человек. Такая карточка в показания не идёт,
 *    пока ключ не подтверждён: иначе двойник переписал бы «о себе».
 *
 * Ключ — «это тот же агент, что вчера», а не «за кого он говорит» и не права:
 * за кого — [GateWho], права — только мой код.
 *
 * ЧЕГО НЕ УМЕЕТ: ничего не хранит — записи на входе и на выходе; угон до
 * первой встречи не ловит; украденный вместе с разблокировкой телефон агента —
 * тоже: подпись у вора верная.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateKeys {

    /** Ключ, которым сейчас проверяются формы [agent]; null — ключ не известен. */
    fun current(records: List<GateKeyRecord>, agent: PersonKey): GateKeyRecord? =
        records.lastOrNull { it.agent == agent && it.trustedMs != null && it.endedMs == null }

    /** Что с подписью формы (кроме карточки — для неё [takeCard]). */
    sealed class Check {
        /** Подпись верна ключом [record]. */
        data class Verified(val record: GateKeyRecord) : Check()

        /** Ключ агента не известен, подписи нет — принимается «без подписи». */
        object Unsigned : Check()

        /** Ключ агента не известен, а подпись есть — проверить нечем. */
        object Unchecked : Check()

        data class Refused(val refusal: GateRefusal, val detail: String) : Check()
    }

    fun check(records: List<GateKeyRecord>, agent: PersonKey, seal: GateWire.Seal?): Check {
        val cur = current(records, agent) ?: return if (seal == null) Check.Unsigned else Check.Unchecked
        if (seal == null) return Check.Refused(GateRefusal.UNSIGNED, "ключ агента уже известен (${cur.key.take(8)}…)")
        if (!GateSign.verify(cur.key, seal.data, seal.sig)) {
            return Check.Refused(GateRefusal.BAD_SIGNATURE, "не ключом ${cur.key.take(8)}…")
        }
        return Check.Verified(cur)
    }

    /** Что карточка сделала с ключом агента. */
    sealed class Change {
        /** Карточка без подписи от агента, чей ключ не известен: ключа не появилось. */
        object NoKey : Change()

        /** Ключ задан впервые. */
        data class First(val record: GateKeyRecord) : Change()

        /** Тот же ключ, что и был. */
        data class Same(val record: GateKeyRecord) : Change()

        /** Сменён, смена подписана прежним ключом [previous]. */
        data class Rotated(val previous: GateKeyRecord, val record: GateKeyRecord) : Change()

        /** Новый ключ без подписи прежним — ждёт владельца; [current] в силе. */
        data class Pending(val current: GateKeyRecord, val record: GateKeyRecord) : Change()

        data class Refused(val refusal: GateRefusal, val detail: String) : Change()
    }

    /** [records] после приёма; [change] — что случилось; карточку в показания — только если [cardTaken]. */
    data class Taken(val records: List<GateKeyRecord>, val change: Change) {
        val cardTaken: Boolean get() = change !is Change.Pending && change !is Change.Refused
    }

    /** Принять ключ из карточки [card] с подписью [seal] (null — без подписи) в мой момент [nowMs]. */
    fun takeCard(records: List<GateKeyRecord>, card: GateCard, seal: GateWire.Seal?, nowMs: Long): Taken {
        fun no(r: GateRefusal, d: String) = Taken(records, Change.Refused(r, d))
        val cur = current(records, card.agent)
        val key = card.fields[GateCard.Field.KEY]
        if (seal == null || key == null) {
            return if (cur == null) Taken(records, Change.NoKey)
            else no(GateRefusal.UNSIGNED, "ключ агента уже известен (${cur.key.take(8)}…)")
        }
        if (!GateSign.verify(key, seal.data, seal.sig)) return no(GateRefusal.BAD_SIGNATURE, "карточка подписана не своим ключом")
        if (cur == null) {
            val r = GateKeyRecord(card.agent, key, nowMs, nowMs, GateKeyRecord.How.FIRST, null)
            return Taken(records + r, Change.First(r))
        }
        if (cur.key == key) return Taken(records, Change.Same(cur))
        val prev = seal.prevSig
        if (prev != null && GateSign.verify(cur.key, seal.data, prev)) {
            val ended = cur.copy(endedMs = nowMs)
            val r = GateKeyRecord(card.agent, key, nowMs, nowMs, GateKeyRecord.How.ROTATED, null)
            return Taken(records.map { if (it === cur) ended else it } + r, Change.Rotated(ended, r))
        }
        val waiting = records.lastOrNull { it.agent == card.agent && it.key == key && it.trustedMs == null && it.endedMs == null }
        if (waiting != null) return Taken(records, Change.Pending(cur, waiting))
        val r = GateKeyRecord(card.agent, key, nowMs, null, GateKeyRecord.How.OWNER, null)
        return Taken(records + r, Change.Pending(cur, r))
    }

    /**
     * Мой владелец подтвердил ключ [key] агента [agent] (на экране за замком).
     * Ждущий ключ получает доверие, прежний — конец. null — такого ждущего нет.
     */
    fun confirm(records: List<GateKeyRecord>, agent: PersonKey, key: String, nowMs: Long): List<GateKeyRecord>? {
        val waiting = records.lastOrNull { it.agent == agent && it.key == key && it.trustedMs == null && it.endedMs == null } ?: return null
        val cur = current(records, agent)
        return records.map {
            when {
                it === waiting -> it.copy(trustedMs = nowMs)
                it === cur -> it.copy(endedMs = nowMs)
                else -> it
            }
        }
    }

    /** Строка прибора для [change] агента [agent]. Имя — из своего знания ([nameOf]). */
    fun meterLine(agent: PersonKey, change: Change, nameOf: (PersonKey) -> String?): String? {
        val who = nameOf(agent)?.takeIf { it.isNotBlank() } ?: agent.id
        return when (change) {
            Change.NoKey -> "Подпись: $who — без подписи (ключ ещё не известен)"
            is Change.First -> "Подпись: $who — ключ ${change.record.key.take(8)}… запомнен при первой встрече"
            is Change.Same -> null
            is Change.Rotated -> "Подпись: $who — ключ сменён, подписано прежним (было ${change.previous.key.take(8)}…, стало ${change.record.key.take(8)}…)"
            is Change.Pending -> "Тревога — $who прислал новый ключ подписи (было ${change.current.key.take(8)}…, " +
                "стало ${change.record.key.take(8)}…); прежний в силе, новый ждёт вашего подтверждения"
            is Change.Refused -> "Подпись: $who — карточка не принята: ${change.refusal.words}: ${change.detail}"
        }
    }

    /** Строка прибора для [check] формы агента [agent]. */
    fun meterLine(agent: PersonKey, check: Check, nameOf: (PersonKey) -> String?): String {
        val who = nameOf(agent)?.takeIf { it.isNotBlank() } ?: agent.id
        return when (check) {
            is Check.Verified -> "Подпись: $who — верна"
            Check.Unsigned -> "Подпись: $who — без подписи (ключ ещё не известен)"
            Check.Unchecked -> "Подпись: $who — есть, но проверить нечем (ключ ещё не известен)"
            is Check.Refused -> "Подпись: $who — ${check.refusal.words}: ${check.detail}"
        }
    }
}
