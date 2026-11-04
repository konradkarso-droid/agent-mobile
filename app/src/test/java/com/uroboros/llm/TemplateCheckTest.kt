package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCheckTest {

    private val selfNote = "Перед этим у меня был перерыв в жизни: я очнулся в 14:08."
    private val dreamNote = "Этой ночью мне снилось: запись, стихи и температура."

    /** Лента хода «Как ты?» так, как её отдаёт код: строки о себе и о ночи — системными посреди ленты. */
    private val sent = listOf(
        "user" to "Сколько у тебя записей в памяти?",
        "assistant" to "679.",
        "system" to selfNote,
        "system" to dreamNote,
        "user" to "Как ты?",
    )

    /** Запрос так, как его собирает шаблон, оставляющий только первое системное (стену). */
    private val droppedPrompt = "<|im_start|>system\nСТЕНА<|im_end|>\n" +
        "<|im_start|>user\nСколько у тебя записей в памяти?<|im_end|>\n" +
        "<|im_start|>assistant\n679.<|im_end|>\n" +
        "<|im_start|>user\nКак ты?<|im_end|>\n<|im_start|>assistant\n"

    /** Тот же запрос у шаблона, который выводит все сообщения. */
    private val fullPrompt = "<|im_start|>system\nСТЕНА<|im_end|>\n" +
        "<|im_start|>user\nСколько у тебя записей в памяти?<|im_end|>\n" +
        "<|im_start|>assistant\n679.<|im_end|>\n" +
        "<|im_start|>system\n$selfNote<|im_end|>\n" +
        "<|im_start|>system\n$dreamNote<|im_end|>\n" +
        "<|im_start|>user\nКак ты?<|im_end|>\n<|im_start|>assistant\n"

    @Test
    fun `выброшенные системные посреди ленты найдены`() {
        assertEquals(listOf("system" to selfNote, "system" to dreamNote), TemplateCheck.lost(sent, droppedPrompt))
    }

    @Test
    fun `всё дошло — молчит`() {
        assertEquals(emptyList<Pair<String, String>>(), TemplateCheck.lost(sent, fullPrompt))
    }

    @Test
    fun `пробелы по краям не в счёт`() {
        val padded = listOf("user" to "  Сколько у тебя записей в памяти?\n", "assistant" to "   ")
        assertEquals(emptyList<Pair<String, String>>(), TemplateCheck.lost(padded, droppedPrompt))
    }

    @Test
    fun `ищется по порядку — нашедшееся раньше предыдущего не засчитывается`() {
        // Вопрос в запросе один, и стоит он до ответа «679.» и до «Как ты?».
        val twice = sent + ("user" to "Сколько у тебя записей в памяти?")
        assertEquals(
            listOf("system" to selfNote, "system" to dreamNote, "user" to "Сколько у тебя записей в памяти?"),
            TemplateCheck.lost(twice, droppedPrompt),
        )
    }

    @Test
    fun `граница — короткие не сверяются, выброшенное короткое не видно`() {
        // «Как» нашлось бы внутри «Как ты?» и сдвинуло поиск за собой: тогда
        // выброшенным назвалось бы «Как ты?». Поэтому короткие не сверяются
        // вовсе — и выброшенное короткое прибор пропускает (шапка TemplateCheck).
        val short = listOf("user" to "Сколько у тебя записей в памяти?", "system" to "Как", "user" to "Как ты?")
        assertEquals(emptyList<Pair<String, String>>(), TemplateCheck.lost(short, droppedPrompt))
        val dropped = listOf("system" to "Да, понял.", "user" to "Сколько у тебя записей в памяти?")
        assertEquals(emptyList<Pair<String, String>>(), TemplateCheck.lost(dropped, droppedPrompt))
    }

    @Test
    fun `счёт — сверенные, с выброшенным и не сверенные`() {
        var t = TemplateCheck.Tally()
        assertEquals("Шаблон: сверенных запросов с подъёма ещё нет", TemplateCheck.line(t, null))
        t = TemplateCheck.add(t, "14:10", "лента экрана", sent, fullPrompt)
        assertEquals(
            "Шаблон (из файла модели): запросов с подъёма 1, все сообщения дошли до модели",
            TemplateCheck.line(t, "из файла модели"),
        )
        t = TemplateCheck.add(t, "14:16", "лента экрана", sent, droppedPrompt)
        t = TemplateCheck.add(t, "14:20", "чат", sent.takeLast(1), fullPrompt)
        t = TemplateCheck.add(t, "14:21", "чат", sent, "")
        assertEquals(TemplateCheck.Tally(checked = 3, withLoss = 1, unchecked = 1, lastLoss = t.lastLoss), t)
        assertEquals("14:16", t.lastLoss?.clock)
        assertEquals(
            "Шаблон (из файла модели) выбрасывает сообщения: запросов с подъёма 3, с выброшенным 1 · не сверено: 1 · " +
                "последний раз 14:16, лента экрана: из 5 выброшено 2 — " +
                "системное «Перед этим у меня был перерыв в жизни: я…»; " +
                "системное «Этой ночью мне снилось: запись, стихи и…»",
            TemplateCheck.line(t, "из файла модели"),
        )
    }

    @Test
    fun `больше двух выброшенных — названы два и счёт остальных`() {
        val many = (1..4).map { "system" to "служебная строка $it" } + ("user" to "Сколько у тебя записей в памяти?")
        val line = TemplateCheck.line(TemplateCheck.add(TemplateCheck.Tally(), "14:30", "прочее", many, droppedPrompt), null)
        assertTrue(line, line.endsWith("системное «служебная строка 1»; системное «служебная строка 2» и ещё 2"))
    }
}
