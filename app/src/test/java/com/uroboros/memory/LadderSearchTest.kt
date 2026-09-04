package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Лестница префиксов: о чём поиск СПРАШИВАЕТ базу.
 *
 * Проверяется через публичный getContextWithSummary, а не через приватную
 * searchByWords: Prism — чистый объект, время в путь чтения не входит, а всё
 * остальное отдаёт подставной DAO, так что открывать внутренности ради теста не
 * пришлось.
 *
 * ГРАНИЦА, за которую этот файл не имеет права заходить. Подделка не
 * воспроизводит SQLite (см. KDoc FakeStickerDao). Значит здесь доказуемо только
 * то, какие запросы уходят в базу и что вызывающий делает с ответами. Найдёт ли
 * настоящий LIKE слово с заглавной буквы — свойство SQL, и проверяется оно на
 * устройстве или тестом с базой в памяти. Имена тестов ниже говорят
 * "запрашивает" именно поэтому: тест с именем "находит" был бы зелёным при
 * сломанном запросе.
 *
 * Числа ступеней ниже посчитаны по коду ladder(), а не взяты из головы:
 * список (длина слова, длина минус два, 5, 4), отфильтрованный границами
 * 4..длина, без повторов, по убыванию.
 */
class LadderSearchTest {

    private fun sticker(
        id: Long,
        content: String = "запись про рубанок",
        layer: Layer = Layer.GREEN
    ) = Sticker(
        id = id,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = layer.name
    )

    /** DAO, настроенный на «в базе ничего нет»: путь дойдёт до конца лестницы. */
    private fun emptyDao(
        found: (String, String, Int) -> List<Sticker> = { _, _, _ -> emptyList() }
    ) = FakeStickerDao().apply {
        onGetExpired = { emptyList() }
        onGetRanked = { _, _ -> emptyList() }
        onSearchAnyCase = found
    }

    // --- Ступени ---

    /**
     * Семибуквенное слово даёт три ступени: целиком, минус окончание, четыре
     * буквы. Пятая ступень совпадает со второй и схлопывается.
     */
    @Test
    fun `семибуквенное слово спускается по трём ступеням`() = runBlocking {
        val dao = emptyDao()
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(listOf("рубанка", "рубан", "руба"), dao.searchedPrefixes)
    }

    /**
     * У пятибуквенного слова ступеней две: ниже четырёх букв лестница не идёт,
     * потому что там подстрока совпадает с чем угодно.
     */
    @Test
    fun `лестница не спускается ниже четырёх букв`() = runBlocking {
        val dao = emptyDao()
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "цвета",
            limit = 5
        )
        assertEquals(listOf("цвета", "цвет"), dao.searchedPrefixes)
    }

    // --- Регистр ---

    /**
     * На каждой ступени запрашиваются ОБА варианта написания. Это половина
     * починки регистра, живущая в Kotlin; вторая половина — сам запрос, и она
     * здесь недосягаема.
     */
    @Test
    fun `на каждой ступени запрашиваются оба варианта регистра`() = runBlocking {
        val dao = emptyDao()
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(
            listOf("Рубанка", "Рубан", "Руба"),
            dao.searchCalls.map { it.queryCapitalized }
        )
        assertTrue(dao.searchCalls.all { it.query != it.queryCapitalized })
    }

    // --- Остановка спуска ---

    /**
     * Ступень, набравшая три записи, останавливает спуск по своему слову:
     * слово, нашедшееся точно, не должно тащить за собой случайную родню с
     * размытых ступеней.
     */
    @Test
    fun `спуск останавливается на ступени с тремя находками`() = runBlocking {
        val dao = emptyDao { _, _, _ ->
            listOf(sticker(1), sticker(2), sticker(3))
        }
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(listOf("рубанка"), dao.searchedPrefixes)
    }

    /** Двух находок для остановки мало — спуск идёт до конца лестницы. */
    @Test
    fun `двух находок для остановки спуска мало`() = runBlocking {
        val dao = emptyDao { _, _, _ -> listOf(sticker(1), sticker(2)) }
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(listOf("рубанка", "рубан", "руба"), dao.searchedPrefixes)
    }

    // --- Что отбрасывается до взвешивания ---

    /**
     * Запись из RED в кандидаты не попадает: принципы приезжают своим каналом,
     * и попасть в выдачу дважды они не должны.
     */
    @Test
    fun `запись из RED не становится кандидатом`() = runBlocking {
        val dao = emptyDao { _, _, _ -> listOf(sticker(1, layer = Layer.RED)) }
        val result = HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertTrue(result.stickers.isEmpty())
        assertTrue(result.summary.contains("не нашлось ни одной записи"))
    }

    /**
     * Запись из слоя, не разрешённого для этого запроса, тоже не кандидат.
     * BLUE открывается только словами вроде "старое" или "прошлое".
     */
    @Test
    fun `запись из неразрешённого слоя не становится кандидатом`() = runBlocking {
        val dao = emptyDao { _, _, _ -> listOf(sticker(1, layer = Layer.BLUE)) }
        val result = HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertTrue(result.stickers.isEmpty())
        assertTrue(result.summary.contains("не нашлось ни одной записи"))
    }

    // --- Второй канал ---

    /**
     * Принципы запрашиваются отдельным обращением — по одному слою и своим
     * лимитом, не общим. Два канала намеренно не сливаются в один рейтинг, и
     * тест закрепляет, что запросов действительно два разных.
     */
    @Test
    fun `принципы запрашиваются отдельным каналом`() = runBlocking {
        val dao = emptyDao()
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(1, dao.rankedCalls.size)
        assertEquals(listOf(Layer.RED.name), dao.rankedCalls[0].layers)
        assertEquals(3, dao.rankedCalls[0].limit)
    }

    // --- Итог сходится с выдачей ---

    /**
     * Число в строке итога совпадает с числом реально отданных записей. Это то
     * же самое, что проверяется на устройстве глазами, только здесь оно
     * закреплено.
     */
    @Test
    fun `число в итоге совпадает с числом отданных записей`() = runBlocking {
        val dao = emptyDao { prefix, _, _ ->
            if (prefix == "рубанка") listOf(sticker(1, content = "рубанок с колодкой"))
            else emptyList()
        }
        val result = HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(1, result.stickers.size)
        assertTrue(result.summary.contains("показано 1"))
        assertTrue(result.summary.contains("отсеяно 0"))
    }

    // --- Сама подделка ---

    /**
     * Неподготовленный метод обязан падать, а не возвращать пустоту. Без этого
     * свойства все тесты выше могли бы быть зелёными на пустом месте, поэтому
     * оно проверяется отдельно.
     */
    @Test
    fun `неподготовленный метод подделки падает`() = runBlocking {
        val dao = FakeStickerDao()
        try {
            dao.count()
            fail("ожидалось исключение: count() не подготовлен")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("count"))
        }
    }
}
