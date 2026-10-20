package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSplitTest {

    @Test
    fun `пересказ — подпись до первого двоеточия с пробелом, содержание с пробелом в начале`() {
        assertEquals(
            listOf(RecordSplit.Line("С твоих слов в прошлом разговоре, на днях:", " ты работаешь по субботам.")),
            RecordSplit.lines("С твоих слов в прошлом разговоре, на днях: ты работаешь по субботам."),
        )
    }

    @Test
    fun `цитата — двоеточие внутри кавычек в подпись не уходит`() {
        assertEquals(
            listOf(RecordSplit.Line("Твои слова вчера днём:", " «Запомни: я Иван».")),
            RecordSplit.lines("Твои слова вчера днём: «Запомни: я Иван»."),
        )
    }

    @Test
    fun `шапка портрета без подписи идёт целиком содержанием`() {
        assertEquals(
            listOf(
                RecordSplit.Line("", "О собеседнике я знаю только это:"),
                RecordSplit.Line("С твоих слов на днях:", " ты Иван."),
            ),
            RecordSplit.lines("О собеседнике я знаю только это:\nС твоих слов на днях: ты Иван."),
        )
    }

    @Test
    fun `пустая строка между портретом и записями — не запись`() {
        assertEquals(
            listOf(
                RecordSplit.Line("", "Об этом ты мне говорил:"),
                RecordSplit.Line("Твои слова вчера:", " «Да»."),
            ),
            RecordSplit.lines("Об этом ты мне говорил:\n\nТвои слова вчера: «Да»."),
        )
    }

    @Test
    fun `склеенные подпись и содержание дают строку целиком`() {
        val recall = "Ты — Кэп, мой создатель. О тебе я знаю только это:\n" +
            "С твоих слов в прошлом разговоре, пару недель назад: ты состоишь в клубе исторического фехтования.\n" +
            "Я говорил в прошлом разговоре, сегодня ночью: «Я умею отвечать в разговоре»."
        assertEquals(
            recall.split('\n'),
            RecordSplit.lines(recall).map { it.sig + it.content },
        )
    }

    @Test
    fun `прибор молчит словами, когда записей в ходе не было`() {
        assertEquals("Записи: в этом ходе записей не было", RecordSplit.meterLine(null))
    }

    @Test
    fun `прибор называет причину, когда запрос не режется`() {
        assertEquals(
            "Записи: не режутся — у модели Mamba-слои: готовый кусок в её состояние не вставить",
            RecordSplit.meterLine(RecordSplit.Report(why = "у модели Mamba-слои: готовый кусок в её состояние не вставить")),
        )
    }

    @Test
    fun `прибор — доли свежего при подписи отдельно и с подписью`() {
        assertEquals(
            "Записи: 6 · подписи 92 ток. · содержание 114 ток. · свежими при склейке: " +
                "подпись отдельно 112 ток. (54 %), с подписью 33 ток. (16 %) · " +
                "склейки ещё нет — записи обсчитаны целиком",
            RecordSplit.meterLine(
                RecordSplit.Report(lines = 6, sigTokens = 92, contentTokens = 114, seamTokens = 20, seamWholeTokens = 33),
            ),
        )
    }

    @Test
    fun `прибор не делит на ноль`() {
        assertEquals(
            "Записи: 0 · подписи 0 ток. · содержание 0 ток. · свежими при склейке: " +
                "подпись отдельно 0 ток., с подписью 0 ток. · склейки ещё нет — записи обсчитаны целиком",
            RecordSplit.meterLine(RecordSplit.Report()),
        )
    }
}
