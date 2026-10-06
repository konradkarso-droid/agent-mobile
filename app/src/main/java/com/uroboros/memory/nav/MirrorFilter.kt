package com.uroboros.memory.nav

import com.uroboros.memory.RiskTrigger
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address

/**
 * Зеркало в отборе. Четыре правила; граница идёт первой.
 *
 * 0. ГРАНИЦА НА ВОПРОС О СОБЕСЕДНИКЕ (адрес «владелец» или «владелец и
 *    агент»). Из записи владельца не берутся предложения, где он обращается к
 *    агенту: есть второе лицо («ты», «тебя», «твой», глагол на «-ешь»,
 *    [addressesAgent]). «У тебя есть сны», «Ты не знаешь цвета радуги» — это
 *    владелец об агенте, а не о себе; поданные на вопрос о владельце, они
 *    уходят в ответ его фактами о себе или путаются с ними. Остальные
 *    предложения записи идут как есть (цитатой); не осталось ни одного или
 *    остались одни вопросы («Хорошо, что помнишь сны. А какие цвета?» →
 *    «А какие цвета?») — запись снята: вопрос ничего о собеседнике не
 *    говорит (RiskTrigger.isOnlyQuestions, та же мерка, что у отсева
 *    вопросов в отборе). Смешанная запись после границы уже не смешанная, и
 *    правило 1 к ней не применяется.
 *    Чего граница не снимает: просьбы без местоимения («Всегда носи с собой
 *    полотенце») — повелительное тоже обращение, но в таком виде владелец
 *    передаёт и чужие слова, и советы, и их снятие убирает ответ на вопрос
 *    «что я говорил про …»; «мы с тобой» — оно о обоих, и на вопрос «помнишь
 *    меня?» это как раз то, что нужно. Глагол второго лица, которого
 *    PersonForm не узнал, границу проходит.
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
 * 3. НА ВОПРОС С АДРЕСОМ «ВЛАДЕЛЕЦ» не приходит запись агента о самом агенте —
 *    его «я» без «ты» владельцу («Я подумал, что…», строки о себе). Отбор
 *    находит её по общим словам, а на вопрос «что знаешь обо мне?» модель
 *    подаёт её фактом о собеседнике или заполняет ею ответ вместо него. Запись
 *    агента о владельце («ты») и о мире приходит. Адрес «владелец и агент»
 *    правило не трогает: там агент — половина вопроса. Записи владельца это
 *    правило не касается: его обращения к агенту снимает граница (правило 0).
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
    fun keeps(record: Sticker, address: Address, retell: RetellTable? = null): Boolean =
        shown(record, address, retell) != null

    /** Текст записи, как он идёт модели: [retold] — пересказом, иначе цитатой. */
    data class Shown(val text: String, val retold: Boolean)

    /**
     * Как запись идёт модели на вопрос с адресом [address]; null — снята.
     * Одно место решения для отбора (снять или нет) и для экрана (что подать):
     * граница (правило 0), затем агент о себе на вопрос о владельце (правило
     * 3), затем смешанная (правило 1), затем чужое «я» на вопрос к агенту
     * (правило 2).
     */
    fun shown(record: Sticker, address: Address, retell: RetellTable? = null): Shown? {
        val speaker = Coordinates.speakerOf(record.source) ?: return Shown(record.content, retold = false)
        if (speaker == PersonKey.OWNER && (address == Address.OWNER || address == Address.BOTH)) {
            return bounded(record.content)?.let { Shown(it, retold = false) }
        }
        if (speaker == PersonKey.AGENT && address == Address.OWNER && aboutAgentOnly(record.content)) return null
        if (isMixed(record.content)) return retold(record, address, retell)?.let { Shown(it, retold = true) }
        if (address != Address.AGENT || speaker == PersonKey.AGENT) return Shown(record.content, retold = false)
        val about = Coordinates.aboutOf(record.content, speaker)
        return if (speaker in about.persons) null else Shown(record.content, retold = false)
    }

    /**
     * Предложение обращено к агенту — граница (правило 0): второе лицо по
     * форме. Одно место признака; портрет собеседника (Portrait) берёт его же.
     */
    fun addressesAgent(sentence: PersonForm.SentenceForm): Boolean =
        PersonForm.Person.SECOND in sentence.persons

    /**
     * Текст без предложений, обращённых к агенту; null — не осталось ни
     * одного или остались одни вопросы. Ничего не снято — текст возвращается
     * как был, с исходной разбивкой строк.
     */
    fun bounded(text: String): String? {
        val sentences = PersonForm.of(text).sentences
        val kept = sentences.filterNot { addressesAgent(it) }
        val out = when {
            kept.isEmpty() -> return null
            kept.size == sentences.size -> text
            else -> kept.joinToString(" ") { it.sentence }
        }
        return if (RiskTrigger.isOnlyQuestions(out)) null else out
    }

    /**
     * Развёрнутый текст записи, если она идёт модели пересказом, — исключение
     * из правила 1; иначе null. Вызывается из [shown], после границы: на
     * адресах «владелец» и «оба» смешанной записи владельца сюда не доходит.
     */
    fun retold(record: Sticker, address: Address, retell: RetellTable?): String? {
        if (retell == null || address == Address.AGENT) return null
        if (Coordinates.speakerOf(record.source) != PersonKey.OWNER) return null
        if (!isMixed(record.content)) return null
        return Retelling.retell(record.content, retell).text
    }

    /** Запись агента только о нём самом — правило 3 в описании объекта. */
    fun aboutAgentOnly(text: String): Boolean {
        val about = Coordinates.aboutOf(text, PersonKey.AGENT)
        return PersonKey.AGENT in about.persons && PersonKey.OWNER !in about.persons
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

    /**
     * Строка прибора «Зеркало в отборе:». Печатается всегда, и при нулях.
     * [retold] — сколько записей реально ушло модели развёрнутыми в этот ход;
     * [retellState] — состояние таблицы: пока она не загружена, вместо числа
     * развёрнутых печатается причина.
     */
    fun meterLine(address: Address, removed: Int, retold: Int, retellState: RetellHolder.State): String {
        val retellPart = when {
            retellState is RetellHolder.State.Ready && !RetellHolder.MIXED -> " · разворот смешанных: выключен"
            retellState is RetellHolder.State.Ready -> " · развёрнуто: $retold"
            retellState is RetellHolder.State.Loading -> " · разворот: таблица загружается"
            retellState is RetellHolder.State.Failed -> " · разворот: таблица не загрузилась: ${retellState.reason}"
            // Остаётся выключенная таблица (RetellHolder.ENABLED, состояние Off).
            else -> " · разворот: выключен"
        }
        return when (address) {
            Address.AGENT -> "адрес — агент · снято записей с чужим «я»: $removed"
            // На этих адресах снимает граница (правило 0), смешанных после неё нет.
            Address.OWNER -> "адрес — владелец · снято границей (обращения к агенту) и записей агента о себе: $removed"
            Address.BOTH -> "адрес — владелец и агент · снято границей (обращения к агенту): $removed"
            Address.UNDEFINED -> "адрес не определён · снято смешанных: $removed$retellPart"
        }
    }
}
