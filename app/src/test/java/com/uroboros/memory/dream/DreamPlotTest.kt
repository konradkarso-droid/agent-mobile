package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сюжет вокруг записи-центра: из чего он собирается и чего в него не берут.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: обычай, подпись, имя агента и омоним
 * темой не становятся; запись без ядра сюжета не даёт; соседи по времени из
 * другого чата и голые отклики в разговор центра не входят. Каждая закрепляет
 * склейку, найденную на живой памяти.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что сюжеты читаются историей. Это видно только
 * глазами на живой памяти.
 */
class DreamPlotTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private val words = DreamPlot.Words.of(TestVerbs.lemmas, listOf("Александр", "@AlexAI_agent"))!!

    private fun rec(id: Long, text: String, at: Long = id * hour, chat: String? = null) =
        Sticker(id = id, content = text, createdAt = at, chat = chat)

    // --- Слова-темы ---

    @Test
    fun `тема — имя и предмет в начальной форме`() {
        assertEquals(setOf("клод", "работа"), words.of("Продолжаем с Клодом работу, сегодня насыщенный день."))
        assertTrue("сне, сна и сон — одна тема", "сон" in words.of("Ты видел это во сне.") && "сон" in words.of("После сна всё уляжется."))
    }

    @Test
    fun `молчит — обычай не даёт темы`() {
        // «Доброй ночи» связывало записи «ночью».
        assertFalse("ночь" in words.of("Давай. Доброй ночи."))
        assertFalse("ночь" in words.of("Время покажет. Ладно, доброй тебе ночи."))
    }

    @Test
    fun `молчит — подпись и имя агента не тема`() {
        assertEquals(setOf("стих"), words.of("Nastya: Привет. Я пишу стихи."))
        assertEquals(setOf("переезд"), words.of("Александр, расскажи про переезд"))
        assertFalse("alexai" in words.of("@AlexAI_agent расскажи про переезд"))
    }

    @Test
    fun `молчит — омоним и не только существительное не тема`() {
        // «теста» — и тест, и тесто: сюжет склеил бы чужое.
        assertEquals(setOf("скамейка"), words.of("Ком теста сидит под скамейкой."))
        assertFalse("быль" in words.of("Ночью тут был? Были новости?"))
    }

    @Test
    fun `без словаря слов-тем нет`() {
        assertNull(DreamPlot.Words.of(null, listOf("Александр")))
    }

    // --- Сюжет ---

    /** Центр (4) и три записи о Клоде в другие дни. */
    private fun claude() = listOf(
        rec(1, "Продолжаем с Клодом работу, сегодня насыщенный день."),
        rec(2, "Кодом занимается другой агент - Клод."),
        rec(3, "Я передавал тебе слова Клода."),
        rec(4, "Разобрались с Клодом, почему ты не весь работал."),
    )

    @Test
    fun `сюжет собирается вокруг центра одной темой, по времени`() {
        val result = DreamPlot.weave(claude(), words)
        assertEquals(DreamPlot.Plot(listOf(1L, 2L, 3L, 4L), "клод"), result.plots.first())
    }

    @Test
    fun `молчит — ядро только в одной записи вне разговора`() {
        val two = listOf(
            rec(1, "Продолжаем с Клодом работу."),
            rec(2, "Разобрались с Клодом наконец."),
        )
        val result = DreamPlot.weave(two, words)
        assertTrue(result.plots.isEmpty())
        assertEquals(0, result.centers)
    }

    @Test
    fun `молчит — частое слово не ядро`() {
        val many = (1L..DreamPlot.THEME_CEILING + 1L).map { rec(it, "Лопата стоит в углу номер $it.") }
        assertTrue("слово в каждой записи — фон, а не тема", DreamPlot.weave(many, words).plots.none { it.core == "лопата" })
    }

    @Test
    fun `разговор центра — из того же чата и со словами-темами`() {
        val t = 50 * hour
        val records = claude() + listOf(
            rec(10, "Он пишет мне код уже месяц.", at = t, chat = "tg:chat:1"),
            rec(11, "Клод сегодня в ударе, починили память.", at = t + minute, chat = "tg:chat:1"),
            rec(12, "да", at = t + 2 * minute, chat = "tg:chat:1"),
            rec(13, "Погода сегодня пасмурная.", at = t + 3 * minute, chat = "tg:chat:2"),
        )
        val plot = DreamPlot.weave(records, words).plots.first { 11L in it.recordIds }
        assertTrue("сосед того же чата со словом-темой — в разговоре: $plot", 10L in plot.recordIds)
        assertFalse("голый отклик — не разговор", 12L in plot.recordIds)
        assertFalse("сосед из другого чата — не разговор", 13L in plot.recordIds)
    }

    @Test
    fun `ядро — то, чьи записи сильнее сходятся с центром`() {
        val records = listOf(
            // «лопата» — в двух записях, и больше ничем они с центром не сходятся.
            rec(1, "Лопата сломалась."),
            rec(2, "Купил новую лопату."),
            // «бетон» — в двух записях, и в обеих ещё «мешалка».
            rec(3, "Бетон застыл в мешалке."),
            rec(4, "Мешалка снова гоняет бетон."),
            rec(5, "Лопатой мешал бетон, пока мешалка стояла."),
        )
        val plot = DreamPlot.weave(records, words).plots.first()
        assertEquals("бетон", plot.core)
        assertEquals(listOf(3L, 4L, 5L), plot.recordIds)
    }

    @Test
    fun `сюжет не длиннее потолка`() {
        val records = (1L..15L).map { rec(it, "Клод прислал письмо номер $it.") }
        val plot = DreamPlot.weave(records, words).plots.first()
        assertEquals(DreamWeaver.MAX_PLOT_RECORDS, plot.recordIds.size)
        assertTrue("эхо — сперва более новые при равной силе", 15L in plot.recordIds && 6L in plot.recordIds && 5L !in plot.recordIds)
    }
}
