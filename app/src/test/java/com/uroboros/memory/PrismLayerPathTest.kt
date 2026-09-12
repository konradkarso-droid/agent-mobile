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
 * ВХОД. RED — единственный слой без срока, и попавшая туда запись едет в
 * каждый ответ постоянным каналом. Значит дверь в него должна быть узкой, и
 * проверки на молчание ниже закрепляют её ширину: обиходные слова, внутри
 * которых лежит "принцип", полномочий принципа не дают. Случайная "починка"
 * обратно на подстроку сломает их заметно.
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

    // --- Дверь в RED ---

    @Test
    fun `объявленный принцип попадает в RED и не получает срока`() {
        val (layer, interval) = Prism.classify(sticker("Мой принцип: не врать"))
        assertEquals(Layer.RED, layer)
        assertNull("у принципа не должно быть срока — он не остывает", interval)
    }

    @Test
    fun `множественное число тоже объявляет принцип`() {
        val (layer, _) = Prism.classify(sticker("Принципы работы с памятью"))
        assertEquals(Layer.RED, layer)
    }

    @Test
    fun `регистр и дефис границе слова не мешают`() {
        val (layer, _) = Prism.classify(sticker("Принцип-кирпич, а не закон"))
        assertEquals(Layer.RED, layer)
    }

    @Test
    fun `тег identity ведёт в RED независимо от текста`() {
        val (layer, interval) = Prism.classify(sticker("Меня зовут Тест", tag = "identity"))
        assertEquals(Layer.RED, layer)
        assertNull(interval)
    }

    // --- Молчание двери: обиходные слова полномочий не дают ---

    @Test
    fun `оборот в принципе принципом не объявляет`() {
        val (layer, interval) = Prism.classify(sticker("В принципе, да, можно и так"))
        assertTrue("оборот \"в принципе\" не должен попадать в RED", layer != Layer.RED)
        assertNotNull("запись вне RED обязана иметь срок и остывать", interval)
    }

    @Test
    fun `наречие принципиально принципом не объявляет`() {
        val (layer, _) = Prism.classify(sticker("Принципиально не буду это делать"))
        assertTrue(layer != Layer.RED)
    }

    @Test
    fun `беспринципный принципом не объявляет`() {
        val (layer, _) = Prism.classify(sticker("Беспринципный человек мне не нравится"))
        assertTrue(layer != Layer.RED)
    }

    @Test
    fun `форма принципе исключена намеренно`() {
        // Цена сужения, названная в KDoc набора форм: редкий оборот
        // "записано в принципе номер три" в RED не попадёт.
        val (layer, _) = Prism.classify(sticker("Записано в принципе номер три"))
        assertTrue(layer != Layer.RED)
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
