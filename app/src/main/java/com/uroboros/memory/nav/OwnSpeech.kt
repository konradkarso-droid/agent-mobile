package com.uroboros.memory.nav

import com.uroboros.llm.EchoCheck
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
 * Фильтр стоит в [said], поэтому действует и на облако агента: слова агента
 * о владельце не набирают вес облаку агента.
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
    )

    /** Предложение своей речи и время хода, где оно сказано. */
    data class Said(val sentence: String, val at: Long?)

    /** Предложения своей речи по ходам, в порядке ходов. */
    fun said(turns: List<Turn>): List<Said> = turns.flatMap { turn ->
        EchoCheck.ownSentences(turn.answer, turn.question, turn.records)
            .filterNot { PersonForm.of(it).aboutAddressee }
            .map { Said(it, turn.at) }
    }

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
