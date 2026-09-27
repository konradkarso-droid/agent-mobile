package com.uroboros.memory.nav

import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address

/**
 * Зеркало в отборе: на вопрос с адресом «агент» не приходит запись, в «о ком»
 * которой есть говорящий, кроме агента, — запись владельца с его первым лицом
 * в любом предложении, смешанная тоже.
 *
 * ПОЧЕМУ ТАК. Малая модель берёт первое лицо владельца в тексте записи
 * («отдыхаю», «работаю над твоей памятью») и произносит его как свою жизнь.
 * Не лечат ни подписи («Твои слова», «Собеседник говорил»), ни роль сообщения —
 * лечит только то, что такая запись на вопрос об агенте не пришла. Смешанная
 * запись («Работаю над твоей памятью») не идёт цитатой никуда: вернуть её
 * верно модель не может — нужно повернуть два местоимения.
 *
 * Что приходит, как прежде:
 *  - запись владельца о собеседнике без его «я» («Будет тебе новый опыт») —
 *    агент принимает её своей новостью;
 *  - «не ясно» (распознанное с картинки, голое «мы», прошедшее без
 *    местоимения) — сомнение не снимает запись;
 *  - записи агента (выводы, строки о себе).
 *
 * На другие адреса («владелец», «оба», «не определён») фильтр не действует.
 *
 * Одна функция, два места вызова: до раздачи мест круга
 * (HourglassMemory.getContextWithSummary — место достаётся следующему
 * кандидату) и на стыке путей в экране, где приходят дверь сна и ассоциация.
 */
object MirrorFilter {

    /** Проходит ли запись на вопрос с адресом [address]. */
    fun keeps(record: Sticker, address: Address): Boolean {
        if (address != Address.AGENT) return true
        val speaker = Coordinates.speakerOf(record.source) ?: return true
        if (speaker == PersonKey.AGENT) return true
        val about = Coordinates.aboutOf(record.content, speaker)
        return speaker !in about.persons
    }

    /** Прошедшие записи в прежнем порядке и число снятых. */
    fun apply(records: List<Sticker>, address: Address): Pair<List<Sticker>, Int> {
        if (address != Address.AGENT) return records to 0
        val kept = records.filter { keeps(it, address) }
        return kept to (records.size - kept.size)
    }

    /** Строка прибора «Зеркало в отборе:». */
    fun meterLine(address: Address, removed: Int): String = when (address) {
        Address.AGENT -> "адрес — агент · снято записей с чужим «я»: $removed"
        Address.OWNER -> "адрес — владелец"
        Address.BOTH -> "адрес — владелец и агент"
        Address.UNDEFINED -> "адрес не определён — отбор как обычно"
    }
}
