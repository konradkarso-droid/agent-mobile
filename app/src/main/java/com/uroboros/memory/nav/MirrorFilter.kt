package com.uroboros.memory.nav

import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address

/**
 * Зеркало в отборе. Два правила.
 *
 * 1. СМЕШАННАЯ ЗАПИСЬ КАК ЕСТЬ НЕ ПРИХОДИТ НИ НА КАКОЙ АДРЕС. Смешанная — где
 *    есть и первое, и второе лицо того, кто сказал («Работаю над твоей
 *    памятью», «Сегодня отдыхаю. Вечером займусь твоими настройками»). Вернуть
 *    её верно модель не может: нужно повернуть два местоимения сразу, и малая
 *    модель их переставляет («моя задача — работа над твоей памятью»). Это не
 *    зависит от адреса: на вопросе о владельце она путает так же, как на
 *    вопросе об агенте. «Мы с тобой» без отдельных «я» и «ты» смешанной не
 *    считается — оно одинаково из обоих ртов, поворачивать нечего.
 *
 *    Исключение — смешанная запись ВЛАДЕЛЬЦА на адрес не «агент», когда
 *    доступен разворот (передана таблица глаголов): поворачивает код
 *    ([Retelling]), модель получает пересказ. Проходит, если после разворота
 *    осталось хоть одно предложение; запись только из вопросов и просьб
 *    снимается. Смешанная запись агента снимается всегда: её не разворачивают.
 *
 * 2. НА ВОПРОС С АДРЕСОМ «АГЕНТ» не приходит и запись, в «о ком» которой есть
 *    говорящий, кроме агента, — запись владельца с его первым лицом в любом
 *    предложении. Малая модель берёт первое лицо владельца в тексте записи
 *    («отдыхаю») и произносит его как свою жизнь. Не лечат ни подписи («Твои
 *    слова», «Собеседник говорил»), ни роль сообщения — лечит только то, что
 *    такая запись на вопрос об агенте не пришла. На вопрос о владельце запись
 *    с одним его «я» приходит: там модель её не путает.
 *
 * Что приходит, как прежде:
 *  - запись владельца о собеседнике без его «я» («Будет тебе новый опыт») —
 *    агент принимает её своей новостью;
 *  - «не ясно» по говорящему (распознанное с картинки) — сомнение не снимает
 *    запись;
 *  - записи агента без «ты» (выводы, строки о себе).
 *
 * Чего не умеет: смешанная фраза в самой реплике владельца сюда не попадает —
 * реплика не запись, её модель получает как есть. Лицо читается по форме
 * (PersonForm), с его ошибками.
 *
 * Одна функция, два места вызова: до раздачи мест круга
 * (HourglassMemory.getContextWithSummary — место достаётся следующему
 * кандидату) и на стыке путей в экране, где приходят дверь сна и ассоциация.
 */
object MirrorFilter {

    /**
     * Проходит ли запись на вопрос с адресом [address]. [retell] — таблица
     * разворота, если он доступен; null — недоступен, смешанные снимаются все.
     */
    fun keeps(record: Sticker, address: Address, retell: RetellTable? = null): Boolean {
        val speaker = Coordinates.speakerOf(record.source) ?: return true
        if (isMixed(record.content)) return retold(record, address, retell) != null
        if (address != Address.AGENT) return true
        if (speaker == PersonKey.AGENT) return true
        val about = Coordinates.aboutOf(record.content, speaker)
        return speaker !in about.persons
    }

    /**
     * Развёрнутый текст записи, если она идёт модели пересказом, — исключение
     * из правила 1; иначе null. Одно место решения: по нему и зеркало
     * пропускает запись, и экран строит строку для модели.
     */
    fun retold(record: Sticker, address: Address, retell: RetellTable?): String? {
        if (retell == null || address == Address.AGENT) return null
        if (Coordinates.speakerOf(record.source) != PersonKey.OWNER) return null
        if (!isMixed(record.content)) return null
        return Retelling.retell(record.content, retell).text
    }

    /** Есть ли в тексте и первое, и второе лицо — правило 1 в описании объекта. */
    fun isMixed(text: String): Boolean {
        val form = PersonForm.of(text)
        return form.sentences.any { PersonForm.Person.FIRST in it.persons } &&
            form.sentences.any { PersonForm.Person.SECOND in it.persons }
    }

    /** Прошедшие записи в прежнем порядке и число снятых. */
    fun apply(records: List<Sticker>, address: Address, retell: RetellTable? = null): Pair<List<Sticker>, Int> {
        val kept = records.filter { keeps(it, address, retell) }
        return kept to (records.size - kept.size)
    }

    /** Прежняя строка прибора — пока экран не перешёл на строку с разворотом; уходит следующим коммитом. */
    fun meterLine(address: Address, removed: Int): String = when (address) {
        Address.AGENT -> "адрес — агент · снято записей с чужим «я»: $removed"
        Address.OWNER -> "адрес — владелец · снято смешанных: $removed"
        Address.BOTH -> "адрес — владелец и агент · снято смешанных: $removed"
        Address.UNDEFINED -> "адрес не определён · снято смешанных: $removed"
    }

    /**
     * Строка прибора «Зеркало в отборе:». Печатается всегда, и при нулях.
     * [retold] — сколько записей реально ушло модели развёрнутыми в этот ход;
     * [retellState] — состояние таблицы: пока она не загружена, вместо числа
     * развёрнутых печатается причина.
     */
    fun meterLine(address: Address, removed: Int, retold: Int, retellState: RetellHolder.State): String {
        val retellPart = when (retellState) {
            is RetellHolder.State.Ready -> " · развёрнуто: $retold"
            RetellHolder.State.Loading -> " · разворот: таблица загружается"
            is RetellHolder.State.Failed -> " · разворот: таблица не загрузилась: ${retellState.reason}"
        }
        return when (address) {
            Address.AGENT -> "адрес — агент · снято записей с чужим «я»: $removed"
            Address.OWNER -> "адрес — владелец · снято смешанных: $removed$retellPart"
            Address.BOTH -> "адрес — владелец и агент · снято смешанных: $removed$retellPart"
            Address.UNDEFINED -> "адрес не определён · снято смешанных: $removed$retellPart"
        }
    }
}
