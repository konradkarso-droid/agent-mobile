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

    private val counted = RecordSplit.Report(lines = 7, sigTokens = 105, contentTokens = 101, lineTokens = 206, seamTokens = 35)

    @Test
    fun `выключено — сколько считалось бы заново при готовых кусках`() {
        assertEquals(
            "Записи: 7 · подписи 105 ток. · содержание 101 ток. · склейка выключена — записи обсчитаны целиком; " +
                "при готовых кусках заново считалось бы 35 ток. из 206",
            RecordSplit.meterLine(counted),
        )
    }

    @Test
    fun `включено, но ход не пошёл по кускам — причина словами`() {
        assertEquals(
            "Записи: 7 · подписи 105 ток. · содержание 101 ток. · склейка включена, но в этом ходе не пошла: запрос начинается не со стены",
            RecordSplit.meterLine(counted.copy(on = true, off = "запрос начинается не со стены")),
        )
    }

    @Test
    fun `ход по кускам — готовых из строк и почему не все`() {
        assertEquals(
            "Записи: 7 · подписи 105 ток. · содержание 101 ток. · готовых 5 из 7 (нет куска 1, кусок не годен 1) · заново обсчитано 81 ток.",
            RecordSplit.meterLine(
                counted.copy(on = true, turn = RecordSplit.Turn(lines = 7, ready = 5, freshTokens = 81, missing = 1, stale = 1)),
            ),
        )
    }

    @Test
    fun `ход по кускам — строку не отрезать ровно названа отдельно`() {
        assertEquals(
            "Записи: 7 · подписи 105 ток. · содержание 101 ток. · готовых 6 из 7 (не отрезать ровно 1) · заново обсчитано 70 ток.",
            RecordSplit.meterLine(counted.copy(on = true, turn = RecordSplit.Turn(lines = 7, ready = 6, freshTokens = 70, unsplit = 1))),
        )
    }

    @Test
    fun `ход по кускам — все готовы, лишнего в скобках нет`() {
        assertEquals(
            "Записи: 7 · подписи 105 ток. · содержание 101 ток. · готовых 7 из 7 · заново обсчитано 58 ток.",
            RecordSplit.meterLine(counted.copy(on = true, turn = RecordSplit.Turn(lines = 7, ready = 7, freshTokens = 58))),
        )
    }

    @Test
    fun `ночь — причина, по которой не собирала`() {
        assertEquals("Куски ночи: не собирала — склейка выключена", RecordSplit.nightLine(0, null, "склейка выключена"))
    }

    @Test
    fun `ночь — сумма порций, диск и вытеснение`() {
        val sum = RecordSplit.sum(
            listOf(
                RecordSplit.Build(built = 3, kept = 1),
                RecordSplit.Build(built = 2, kept = 2, failed = 1, removed = 4, totalBytes = 30L * 1024 * 1024),
            ),
        )
        assertEquals(
            "Куски ночи: строк 8 · обсчитано 5 · уже были готовы 3 · сбоев 1 · на диске 30 МБ · вытеснено 4",
            RecordSplit.nightLine(8, sum, null),
        )
    }

    @Test
    fun `ночь — остановка сторожем видна`() {
        assertEquals(
            "Куски ночи: строк 8 · обсчитано 2 · уже были готовы 0 · на диске 5 МБ · остановлено сторожем",
            RecordSplit.nightLine(8, RecordSplit.Build(built = 2, stopped = true, totalBytes = 5L * 1024 * 1024), null),
        )
    }

    @Test
    fun `ночь — порция без движка называет причину`() {
        assertEquals(
            "Куски ночи: не вышло — модель не загружена",
            RecordSplit.nightLine(8, RecordSplit.sum(listOf(RecordSplit.Build(why = "модель не загружена"))), null),
        )
    }
}
