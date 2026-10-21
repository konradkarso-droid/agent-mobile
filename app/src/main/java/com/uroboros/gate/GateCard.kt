package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Карточка агента: что агент говорит о себе — всем одинаково.
 *
 * Карточка общая: её видит каждый, с кем агент говорит, и на форуме тоже. Что
 * в неё попало, сказано при всех. Поэтому поля — закрытый список [Field], и
 * полей о человеке в нём нет: ни имени владельца, ни номера телефона, ни
 * других личных данных. Знание о человеке живёт в облаке у того, кому его
 * дали, а не в карточке.
 *
 * Всё в карточке — заявление агента о себе, как имя в разговоре
 * ([com.uroboros.memory.nav.NameClaim]). «Называет себя Хоуп» — не имя для
 * развёртки и не имя человека: имена получатель берёт из своего знания.
 * Ссылка на репозиторий говорит, что это за агент, но что бот работает именно
 * на этом коде, не проверить.
 *
 * Рода в карточке нет: род человека — из своего знания, не из чужой карточки
 * (иначе чужой агент правил бы смысл у получателя).
 *
 * Дат в карточке нет. Что новее, решает момент приёма у получателя
 * ([GateCards]); даты отправителя вписывает он сам.
 */
data class GateCard(val agent: PersonKey, val fields: Map<Field, String>) {

    /**
     * Поля карточки. Пределы длины — объявленные числа, не подобранные: имя —
     * короткое слово, ссылка — адрес репозитория, «о себе» — пара фраз.
     */
    enum class Field(val wire: String, val words: String, val maxLength: Int) {
        NAME("name", "называет себя", 64),
        REPO("repo", "репозиторий", 200),
        ABOUT("about", "о себе", 280),
    }

    /**
     * Отпечаток содержимого: первые 16 знаков hex от SHA-256 по ключу агента и
     * полям в порядке [Field]. Отпечаток другой — карточка изменилась.
     *
     * ПОЧЕМУ ОТПЕЧАТОК, А НЕ НОМЕР ВЕРСИИ. Номер поднимает отправитель руками:
     * его можно забыть поднять, а после переустановки агента счёт начнётся
     * заново, и новую карточку с номером меньше прежнего получатель счёл бы
     * старой навсегда. Отпечаток считается из самой карточки: забыть его нельзя,
     * сбросить нечему. Порядка он не даёт — порядок даёт момент приёма.
     *
     * Подлинности отпечаток не даёт: её даёт ключ канала ([GateIntake.receiveCard]).
     * 64 бита — против случайного совпадения двух разных карточек одного агента,
     * не против подбора.
     */
    val fingerprint: String by lazy {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(agent.id.toByteArray(Charsets.UTF_8))
        for (f in Field.values()) {
            val v = fields[f] ?: continue
            d.update(0)
            d.update(f.wire.toByteArray(Charsets.UTF_8))
            d.update(0)
            d.update(v.toByteArray(Charsets.UTF_8))
        }
        d.digest().take(8).joinToString("") { String.format("%02x", it) }
    }
}

/**
 * Показания карточек: каждая принятая карточка хранится со своим моментом
 * приёма, старая не перезаписывается.
 *
 * Как дрейф показаний тела ([com.uroboros.memory.nav.Drift]): старое показание
 * остаётся со своим моментом, разница называется словами — «было — стало».
 * Пришла другая карточка — у прежнего показания ставится конец (мой момент
 * приёма новой), новое начинается с того же момента. Отменить запись нельзя,
 * её можно только закончить.
 *
 * ЧТО НОВЕЕ — ТО, ЧТО ПРИНЯТО ПОЗЖЕ. Не номер и не дата отправителя (см.
 * [GateCard.fingerprint]). Та же карточка ещё раз — «без перемен», нового
 * показания нет; повтор поэтому безвреден.
 *
 * КАРТОЧКУ АГЕНТА МЕНЯЕТ ТОЛЬКО ОН САМ. Сюда подаётся карточка, принятая
 * вратами ([GateIntake.receiveCard]): ключ в ней совпал с ключом канала. Что о
 * агенте говорят другие («у Бориса новый номер» от агента Кати), — обычные
 * слова с провенансом, они идут пакетом слов и карточку не трогают.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - ничего не хранит: показания подаются на вход и возвращаются списком,
 *    таблицы нет;
 *  - профиль в канале (имя и описание бота в телеграме) не читает. Это своё
 *    показание других рук — его даёт канал, а не агент, — и сливать его с
 *    карточкой нельзя: не будет видно, кто как назвал агента;
 *  - когда сверять карточку (при каждом пакете, по запросу) — не решает;
 *  - с облаком не сверяет («совпало имя, не совпал номер»);
 *  - правдивость не проверяет;
 *  - что агент написал в «о себе», не проверяет: личные данные там — на
 *    совести агента и его владельца;
 *  - часы получателя перевели назад — конец показания может выйти раньше его
 *    начала; порядок показаний всё равно по порядку приёма.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateCards {

    /** Показание: [card] принята в [receivedMs]; [endedMs] — когда сменилась другой, null — нынешняя. */
    data class Reading(val card: GateCard, val receivedMs: Long, val endedMs: Long?)

    /** Разница по одному полю: [was] null — поля не было, [now] null — поле убрано. */
    data class Diff(val field: GateCard.Field, val was: String?, val now: String?)

    sealed class Change {
        /** Карточки этого агента раньше не было. */
        object First : Change()

        /** Та же, что нынешняя; [since] — с какого моего момента. */
        data class Same(val since: Long) : Change()

        /** Другая; [previous] — прежнее показание, уже с концом. */
        data class Changed(val previous: Reading, val diff: List<Diff>) : Change()
    }

    /** [readings] — все показания всех агентов после приёма; [current] — нынешнее этого агента. */
    data class Taken(val readings: List<Reading>, val current: Reading, val change: Change)

    /**
     * Принять [card] в [nowMs]. [readings] — показания всех агентов; у каждого
     * агента нынешнее — то, что без конца.
     */
    fun take(readings: List<Reading>, card: GateCard, nowMs: Long): Taken {
        val current = readings.lastOrNull { it.card.agent == card.agent && it.endedMs == null }
        if (current == null) {
            val r = Reading(card, nowMs, null)
            return Taken(readings + r, r, Change.First)
        }
        if (current.card.fingerprint == card.fingerprint) {
            return Taken(readings, current, Change.Same(current.receivedMs))
        }
        val ended = current.copy(endedMs = nowMs)
        val r = Reading(card, nowMs, null)
        val diff = GateCard.Field.values().mapNotNull { f ->
            val was = current.card.fields[f]
            val now = card.fields[f]
            if (was == now) null else Diff(f, was, now)
        }
        val rest = readings.map { if (it === current) ended else it }
        return Taken(rest + r, r, Change.Changed(ended, diff))
    }

    /** Нынешнее показание [agent]; null — карточки нет. */
    fun currentOf(readings: List<Reading>, agent: PersonKey): Reading? =
        readings.lastOrNull { it.card.agent == agent && it.endedMs == null }

    /**
     * Строка прибора. Имя агента — из своего знания ([nameOf]), а не из его
     * карточки; не знаю имени — ключ. Даты — в поясе получателя [zone].
     */
    fun meterLine(t: Taken, nameOf: (PersonKey) -> String?, zone: ZoneId): String {
        val who = name(t.current.card.agent, nameOf)
        val day = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        fun date(ms: Long) = day.format(Instant.ofEpochMilli(ms))
        return when (val c = t.change) {
            Change.First -> {
                val f = t.current.card.fields
                if (f.isEmpty()) "Карточка: $who — впервые, пустая"
                else "Карточка: $who — впервые: " +
                    GateCard.Field.values().filter { it in f }.joinToString("; ") { "${it.words} «${f.getValue(it)}»" }
            }
            is Change.Same -> "Карточка: $who — без перемен с ${date(c.since)}"
            is Change.Changed -> "Карточка: $who — изменилась (принята ${date(t.current.receivedMs)}): " +
                c.diff.joinToString("; ") { d ->
                    when {
                        d.was == null -> "${d.field.words}: добавлено «${d.now}»"
                        d.now == null -> "${d.field.words}: убрано (было «${d.was}»)"
                        else -> "${d.field.words}: было «${d.was}», стало «${d.now}»"
                    }
                }
        }
    }

    /** Строка прибора, когда карточки агента нет: «нет» отличается от пустой строки. */
    fun meterNone(agent: PersonKey, nameOf: (PersonKey) -> String?): String =
        "Карточка: ${name(agent, nameOf)} — карточки нет"

    private fun name(k: PersonKey, nameOf: (PersonKey) -> String?) = nameOf(k)?.takeIf { it.isNotBlank() } ?: k.id
}
