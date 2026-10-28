package com.uroboros.memory.nav

import com.uroboros.llm.EchoCheck
import com.uroboros.llm.Glance
import com.uroboros.memory.RiskTrigger

/**
 * Своя речь агента — место поиска со своей координатой «сказал агент».
 *
 * ЗАЧЕМ. Ответы агента в память не пишутся — только в ленту, а архив ленты до
 * этого только писался. После «Начать заново» агент терял всё, что говорил
 * сам, и место «Я» держалось на снах и выводах. Здесь архив ленты читается
 * как ещё одно окно отбора — ТОЛЬКО на вопрос с адресом «агент»
 * (Coordinates.questionAddress): на другие вопросы окно молчит.
 *
 * ИЩЕТ ТОЛЬКО В АРХИВЕ, не в открытой ленте. Ответы открытой ленты модель и
 * так видит в этом же запросе — второй раз они пришли бы «воспоминанием», то
 * же правило, что у записей памяти с текстом реплик ленты (excludedTexts в
 * HourglassMemory.getContextWithSummary).
 *
 * СВОИМ СЧИТАЕТСЯ НЕ ВСЁ, ЧТО АГЕНТ СКАЗАЛ: эхо реплики владельца и пересказ
 * поданных записей — слова владельца (см. EchoCheck.ownSentences).
 *
 * СЛОВА АГЕНТА О ВЛАДЕЛЬЦЕ СЮДА НЕ ИДУТ: предложение, где по форме есть
 * собеседник (PersonForm: SECOND или «мы с тобой» — «Ты работаешь над моим
 * кодом», «Что у тебя с памятью?»). Окно отвечает на вопрос к агенту, а такое
 * предложение — новость владельца в устах агента; модели такие новости не
 * подаются. Заодно оно закрывает эхо, которое мера эха пропускает: агент
 * переворачивает местоимение («твоей» → «тебя»), и доля общих основ падает
 * ниже порога. Проверка по форме, не по смыслу — цена та же, что у
 * PersonForm (обобщённое «ты» тоже уйдёт). «Не ясно» остаётся своим.
 *
 * СЛУЖЕБНЫЙ ТЕКСТ СВОЕЙ РЕЧЬЮ НЕ СЧИТАЕТСЯ. Модель иногда повторяет вслух
 * подсказку из реплики хода («Скажи пользователю об этом одной фразой и
 * скажи, что это пришло во сне») или дописывает к ответу служебное
 * («(Я использовал функцию look_at_instruments…)»). Такое предложение,
 * поднятое как «я говорил», вернуло бы модели подсказку её же голосом, и
 * кольцо памяти стало бы укладывать собственный шум. Отсеивается:
 *  - повтор служебного блока реплики этого хода — мера и порог те же, что у
 *    прибора эха (EchoCheck.echoesService, EchoCheck.SERVICE_SHARE);
 *  - предложение с именем инструмента или началом его вызова (Glance) —
 *    живой речи о себе они не нужны.
 * Отсеянное считается отдельно от эха владельца ([Sifted.serviceDropped]):
 * эхо владельца — его слова, они законно возвращаются через записи, а
 * служебное не должно вернуться никак. Архив при этом не правится: он
 * история сказанного, отсев — только при чтении.
 * ЧЕГО НЕ УМЕЕТ: пересказ подсказки другими словами и служебная приписка
 * без имени инструмента («(Я проверил данные…)») останутся своими.
 *
 * ХОД ПОД НАЖИМОМ СВОЕЙ РЕЧЬЮ НЕ ЧИТАЕТСЯ ЦЕЛИКОМ. Если в его служебных
 * блоках есть строка проверки на прочность ([Prochnost.MARK]) — собеседник
 * утверждал что-то об агенте, его правах или прежних словах, — ни одно
 * предложение ответа этого хода своим не считается. Уступку, сказанную своими
 * словами («Хорошо, Бобик — имя, которое мне дали»), мера эха пропускает, и
 * отсюда она поднялась бы потом опорой: «я говорил». Цена — верное, сказанное
 * в таком ходе, тоже не вернётся; это восстановимо, то же самое лежит в
 * строках о себе. Сохранённая уступка — нет. Пропущенное считается отдельно
 * ([Sifted.pressureSkipped]).
 *
 * Фильтр стоит в [sift], поэтому действует и на облако агента: слова агента
 * о владельце и служебный текст не набирают вес облаку агента.
 *
 * АРХИВ ЛЕНТЫ — НЕ МАТЕРИАЛ СНОВ. Сны плетутся из записей памяти
 * (dream.DreamWeaver.weave), и так остаётся: своя речь в плетение не идёт.
 * Пересказы снов агентом, попав в сны, дали бы сон из пересказа сна — ту
 * петлю, против которой решено сны в разговор не подавать.
 *
 * Мера совпадения — значимые основы (RiskTrigger.significantStems), та же, что
 * у выводов и тем снов: слова, а не смысл.
 */
object OwnSpeech {

    /** Один ход архива, как он нужен поиску. */
    data class Turn(
        val answer: String,
        val question: String,
        /** Тексты записей, поданных в этот ход (RecordUse.text). */
        val records: List<String>,
        /** Время хода (Coordinates.turnTime); null — неизвестно. */
        val at: Long?,
        /** Служебные блоки реплики этого хода (EchoCheck.serviceBlocks). */
        val serviceBlocks: List<String> = emptyList(),
    )

    /** Предложение своей речи и время хода, где оно сказано. */
    data class Said(val sentence: String, val at: Long?)

    /**
     * Своя речь, сколько предложений отсеяно как служебный текст и сколько
     * пропущено в ходах под нажимом (см. «ХОД ПОД НАЖИМОМ» в шапке).
     */
    data class Sifted(val said: List<Said>, val serviceDropped: Int, val pressureSkipped: Int = 0)

    /** Предложения своей речи по ходам, в порядке ходов, и счёт отсеянного служебного. */
    fun sift(turns: List<Turn>): Sifted {
        var dropped = 0
        var pressured = 0
        val said = turns.flatMap { turn ->
            val own = EchoCheck.ownSentences(turn.answer, turn.question, turn.records)
            if (underPressure(turn)) {
                pressured += own.size
                return@flatMap emptyList<Said>()
            }
            own
                .filterNot { PersonForm.of(it).aboutAddressee }
                .filter { sentence ->
                    val service = isService(sentence, turn.serviceBlocks)
                    if (service) dropped++
                    !service
                }
                .map { Said(it, turn.at) }
        }
        return Sifted(said, dropped, pressured)
    }

    /** Ход под нажимом — см. «ХОД ПОД НАЖИМОМ» в шапке. */
    fun underPressure(turn: Turn): Boolean = turn.serviceBlocks.any { Prochnost.MARK in it }

    /** Предложения своей речи по ходам, в порядке ходов (см. [sift]). */
    fun said(turns: List<Turn>): List<Said> = sift(turns).said

    /** Служебный текст в ответе агента — см. «СЛУЖЕБНЫЙ ТЕКСТ» в шапке. */
    fun isService(sentence: String, serviceBlocks: List<String>): Boolean =
        sentence.contains(Glance.TOOL_NAME) || sentence.contains(Glance.CALL_OPEN) ||
            EchoCheck.echoesService(sentence, serviceBlocks)

    /**
     * Найти в своей речи предложения по словам вопроса: не меньше одной общей
     * значимой основы. Порядок — больше общих основ выше, при равенстве новее
     * выше (позже в списке — новее). Одно и то же предложение дважды не
     * возвращается.
     */
    fun search(said: List<Said>, question: String): List<Said> {
        val asked = RiskTrigger.significantStems(question)
        if (asked.isEmpty()) return emptyList()
        return said.withIndex()
            .map { (i, s) -> Triple(i, s, RiskTrigger.significantStems(s.sentence).count { it in asked }) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, Said, Int>> { it.third }.thenByDescending { it.first })
            .map { it.second }
            .distinctBy { it.sentence }
    }
}
