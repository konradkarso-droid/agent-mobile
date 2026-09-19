package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Путь записи по слоям: вход в RED и спуск до конца спектра.
 *
 * Две половины, обе про одно — что запись не пропадает и не застревает.
 *
 * ВХОД. RED — единственный слой без срока: попавшая туда запись не остынет
 * никогда. Дверь в него — только явная метка, текст записи на вход не влияет.
 * Проверки на молчание ниже закрепляют это: ни объявление принципа словами, ни
 * вопрос о принципах, ни обиходные обороты в RED не ведут. Случайная "починка"
 * двери обратно на слово сломает их заметно. Почему дверь такая — в KDoc
 * Prism.classify.
 *
 * СПУСК. Остывание отбирает записи по expiryTime, поэтому конец спектра
 * выглядит как отсутствие срока: запись перестаёт попадать в отбор и лежит
 * дальше. Проверяется, что лестница доходит до PURPLE, что PURPLE неподвижен
 * и что срок там исчезает — то есть спуск кончается остановкой, а не
 * удалением.
 *
 * Чего этот тест НЕ доказывает: что HourglassMemory.migrateExpired
 * действительно зовёт colderLayer/newInterval и что StickerDao.updateLayer
 * записывает слой в строку. Обе эти половины ходят в базу, и проверяются они
 * не здесь. Тест говорит только про правила спектра, не про их применение.
 */
class PrismLayerPathTest {

    private fun sticker(content: String, tag: String = "general") =
        Sticker(content = content, tag = tag)

    // --- Дверь в RED: только метка ---

    @Test
    fun `метка identity ведёт в RED и не даёт срока`() {
        val (layer, interval) = Prism.classify(sticker("Меня зовут Тест", tag = Prism.IDENTITY_TAG))
        assertEquals(Layer.RED, layer)
        assertNull("у записи в RED не должно быть срока — она не остывает", interval)
    }

    @Test
    fun `метка ведёт в RED при любом тексте`() {
        val (layer, _) = Prism.classify(sticker("Задача на сегодня", tag = Prism.IDENTITY_TAG))
        assertEquals("метка сильнее ветки ORANGE по слову «задача»", Layer.RED, layer)
    }

    // --- Молчание двери: слова в тексте в RED не ведут ---

    @Test
    fun `объявление принципа словами в RED не ведёт`() {
        val (layer, interval) = Prism.classify(sticker("Мой принцип: не врать"))
        assertTrue("слово «принцип» не даёт записи вечности", layer != Layer.RED)
        assertNotNull("запись вне RED обязана иметь срок и остывать", interval)
    }

    @Test
    fun `вопрос о принципах в RED не ведёт`() {
        // Живой случай, ради которого дверь закрыта: автозапись сохраняет
        // каждую реплику, и вопрос по слову стал бы вечной записью.
        val (layer, _) = Prism.classify(sticker("Какие у тебя принципы?"))
        assertTrue(layer != Layer.RED)
    }

    @Test
    fun `обиходные обороты в RED не ведут`() {
        for (text in listOf(
            "В принципе, да, можно и так",
            "Принципиально не буду это делать",
            "Беспринципный человек мне не нравится",
            "Принцип-кирпич, а не закон"
        )) {
            val (layer, _) = Prism.classify(sticker(text))
            assertTrue("«$text» не должен попадать в RED", layer != Layer.RED)
        }
    }

    @Test
    fun `похожая на метку, но другая метка в RED не ведёт`() {
        for (tag in listOf("Identity", "identity ", "general")) {
            val (layer, _) = Prism.classify(sticker("Меня зовут Тест", tag = tag))
            assertTrue("метка «$tag» не должна вести в RED", layer != Layer.RED)
        }
    }

    // --- Спуск по спектру ---

    @Test
    fun `каждый слой остывает в следующий`() {
        assertEquals(Layer.ORANGE, Prism.colderLayer(Layer.RED))
        assertEquals(Layer.YELLOW, Prism.colderLayer(Layer.ORANGE))
        assertEquals(Layer.GREEN, Prism.colderLayer(Layer.YELLOW))
        assertEquals(Layer.BLUE, Prism.colderLayer(Layer.GREEN))
        assertEquals(Layer.PURPLE, Prism.colderLayer(Layer.BLUE))
    }

    @Test
    fun `PURPLE — конец спектра и неподвижен`() {
        assertEquals(Layer.PURPLE, Prism.colderLayer(Layer.PURPLE))
    }

    @Test
    fun `спуск от самого тёплого доходит до архива за пять ступеней`() {
        var layer = Layer.RED
        repeat(5) { layer = Prism.colderLayer(layer) }
        assertEquals(Layer.PURPLE, layer)
    }

    @Test
    fun `спуск кончается остановкой, а не исчезновением`() {
        // Срока у архива нет, значит migrateExpired (отбор по expiryTime)
        // больше никогда не выберет эту запись. Она лежит, а не удаляется.
        assertNull(Prism.newInterval(Layer.PURPLE))
    }

    @Test
    fun `срок есть у всех слоёв между двумя концами спектра`() {
        for (layer in listOf(Layer.ORANGE, Layer.YELLOW, Layer.GREEN, Layer.BLUE)) {
            assertNotNull("слой $layer обязан остывать дальше", Prism.newInterval(layer))
        }
    }

    @Test
    fun `без срока живут только два конца спектра`() {
        assertNull(Prism.newInterval(Layer.RED))
        assertNull(Prism.newInterval(Layer.PURPLE))
    }
}
