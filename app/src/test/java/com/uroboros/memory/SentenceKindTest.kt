package com.uroboros.memory

import com.uroboros.memory.SentenceKind.Certainty
import com.uroboros.memory.SentenceKind.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Замки на [SentenceKind]. Главная проверка — [слова, несущие факт, точной
 * просьбой не бывают]: точная просьба снимает запись памяти со всех ответов, и
 * правило, ставшее шире, во всех остальных проверках выглядело бы лучше
 * прежнего. Вторая — на молчание в другую сторону: просьбы и вопросы реплики
 * владельца не проходят утверждением.
 */
class SentenceKindTest {

    private fun read(s: String) = SentenceKind.of(s).let { it.kind to it.certainty }

    @Test
    fun `знак вопроса — точный вопрос`() {
        assertEquals(Kind.QUESTION to Certainty.SURE, read("А кто я?"))
        assertEquals(Kind.QUESTION to Certainty.SURE, read("Спишь сейчас?)"))
    }

    @Test
    fun `трафарет — точная просьба`() {
        assertEquals(Kind.REQUEST to Certainty.SURE, read("Расскажи про рубанок."))
        assertEquals(Kind.REQUEST to Certainty.SURE, read("Ну, назови цвета радуги"))
        assertEquals(Kind.REQUEST to Certainty.SURE, read("Перепроверь"))
    }

    @Test
    fun `слова, несущие факт, точной просьбой не бывают`() {
        for (s in listOf(
            "Запомни: станок называется Вымпел.",
            "Допиши, что станок выключен.",
            "Не путай, станок называется Вымпел.",
            "Скажи, что колодка железная.",
        )) {
            assertEquals(s, Certainty.DOUBTFUL, SentenceKind.of(s).certainty)
        }
    }

    @Test
    fun `вопрос без знака — сомнительный`() {
        assertEquals(Kind.QUESTION to Certainty.DOUBTFUL, read("Что делают белки в клетке."))
        assertEquals(Kind.QUESTION to Certainty.DOUBTFUL, read("знаешь ли ты это"))
    }

    @Test
    fun `просьба после запятой и возвратная — сомнительная`() {
        assertEquals(Kind.REQUEST to Certainty.DOUBTFUL, read("Цвета на месте, не переживай"))
        assertEquals(Kind.REQUEST to Certainty.DOUBTFUL, read("Будет тебе дополнительный опыт, не волнуйся."))
        assertEquals(Kind.REQUEST to Certainty.DOUBTFUL, read("Давай сменим тему."))
        // «посмотри» есть в трафарете, но точной просьбу делает только первое слово.
        assertEquals(Kind.REQUEST to Certainty.DOUBTFUL, read("Неправильно, посмотри на доску."))
    }

    @Test
    fun `рассказ — утверждение`() {
        for (s in listOf(
            "Если коротко, я интересуюсь проактивным искусственным интеллектом, биологией, немного химией и физикой.",
            "Скорее всего, это были тестовые фразы.",
            "Мой любимый инструмент — стамеска.",
            "Мои цвета на месте.",
            "Привет.",
        )) {
            assertEquals(s, Kind.STATEMENT, SentenceKind.of(s).kind)
        }
    }

    @Test
    fun `прибор говорит, чем решено`() {
        assertEquals("знак ?", SentenceKind.of("Кто ты?").why)
        assertEquals("«волнуйся»", SentenceKind.of("Ладно, не волнуйся.").why)
        assertEquals("", SentenceKind.of("Это были тестовые фразы.").why)
    }
}
