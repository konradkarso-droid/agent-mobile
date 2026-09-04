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
 * ТА ЖЕ ГРАНИЦА, и вдвойне, про счётчик скрытых. searchHiddenAnyCase обязан
 * искать теми же двумя условиями LIKE, что и searchAnyCase, иначе видимое и
 * скрытое считаются по разным множествам. Здесь оба ответа приходят от РАЗНЫХ
 * лямбд теста, поэтому расхождение самих запросов этот файл не увидит НИКОГДА —
 * равенство держится только соседством в StickerDao.kt. Доказуемо здесь ровно
 * то, что вызывающий спрашивает оба на тех же ступенях и теми же префиксами, и
 * что делает с ответами.
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

    private fun hiddenRow(id: Long, layer: Layer = Layer.GREEN) =
        HiddenRow(id = id, layer = layer.name)

    /**
     * DAO, настроенный на «в базе ничего нет»: путь дойдёт до конца лестницы.
     *
     * Оба канала задаются ИМЕНОВАННЫМИ аргументами, и это не стиль. Хвостовая
     * лямбда в Kotlin достаётся ПОСЛЕДНЕМУ параметру, так что прежняя запись
     * emptyDao { ... } после появления второго канала означала бы уже не то, что
     * означала раньше. Имена убирают вопрос совсем, а заодно говорят в каждом
     * тесте, какой из двух поисков он подменяет.
     */
    private fun emptyDao(
        found: (String, String, Int) -> List<Sticker> = { _, _, _ -> emptyList() },
        hidden: (String, String, Int) -> List<HiddenRow> = { _, _, _ -> emptyList() }
    ) = FakeStickerDao().apply {
        onGetExpired = { emptyList() }
        onGetRanked = { _, _ -> emptyList() }
        onSearchAnyCase = found
        onSearchHiddenAnyCase = hidden
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
        val dao = emptyDao(found = { _, _, _ ->
            listOf(sticker(1), sticker(2), sticker(3))
        })
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
        val dao = emptyDao(found = { _, _, _ -> listOf(sticker(1), sticker(2)) })
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
        val dao = emptyDao(found = { _, _, _ -> listOf(sticker(1, layer = Layer.RED)) })
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
        val dao = emptyDao(found = { _, _, _ -> listOf(sticker(1, layer = Layer.BLUE)) })
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
        val dao = emptyDao(found = { prefix, _, _ ->
            if (prefix == "рубанка") listOf(sticker(1, content = "рубанок с колодкой"))
            else emptyList()
        })
        val result = HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(1, result.stickers.size)
        assertTrue(result.summary.contains("показано 1"))
        assertTrue(result.summary.contains("отсеяно 0"))
    }

    // --- Карантин: второй запрос на тех же ступенях ---

    /**
     * Зеркальный запрос уходит ровно тогда же и ровно с теми же аргументами, что
     * и видимый. Сравниваются целиком списки обращений: это разом закрепляет
     * совпадение префиксов, обоих вариантов регистра, лимита и числа ступеней.
     * Разойдись они — два числа в итоге считали бы разные множества.
     */
    @Test
    fun `скрытые спрашиваются на тех же ступенях, что и видимые`() = runBlocking {
        val dao = emptyDao()
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(dao.searchCalls, dao.hiddenSearchCalls)
        assertTrue(dao.hiddenSearchCalls.isNotEmpty())
    }

    /**
     * Находки в карантине НЕ останавливают спуск. Иначе запись, убранная из
     * памяти, меняла бы то, что находится для всех остальных, — карантин
     * перестал бы быть просто вычитанием.
     *
     * Здесь скрытых сразу три, то есть с запасом больше порога остановки, а
     * видимых нет ни одной: лестница обязана пройти все три ступени.
     */
    @Test
    fun `скрытые не останавливают спуск по лестнице`() = runBlocking {
        val dao = emptyDao(hidden = { _, _, _ ->
            listOf(hiddenRow(1), hiddenRow(2), hiddenRow(3))
        })
        HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertEquals(listOf("рубанка", "рубан", "руба"), dao.searchedPrefixes)
    }

    /**
     * Одна и та же скрытая запись, найденная двумя словами и на нескольких
     * ступенях, считается ОДИН раз. Потому счётчик и собран множеством id, а не
     * сложением: иначе число мерило бы количество совпадений, а обещает
     * количество записей.
     */
    @Test
    fun `скрытая запись считается один раз при нескольких совпадениях`() = runBlocking {
        val dao = emptyDao(hidden = { _, _, _ -> listOf(hiddenRow(1)) })
        val result = HourglassMemory(dao).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка колодка",
            limit = 5
        )
        assertTrue(result.summary.contains("скрыто карантином 1"))
    }

    /**
     * Скрытые фильтруются по слоям теми же двумя правилами, что и видимые.
     * Разойдись фильтры — запись из архива попала бы в счёт, а такая же видимая
     * нет, и два числа мерили бы разные множества.
     */
    @Test
    fun `скрытая запись из RED и неразрешённого слоя не считается`() = runBlocking {
        val red = emptyDao(hidden = { _, _, _ -> listOf(hiddenRow(1, layer = Layer.RED)) })
        val redResult = HourglassMemory(red).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertTrue(redResult.summary.contains("скрыто карантином 0"))

        val blue = emptyDao(hidden = { _, _, _ -> listOf(hiddenRow(1, layer = Layer.BLUE)) })
        val blueResult = HourglassMemory(blue).getContextWithSummary(
            purpose = RetrievalPurpose.BROWSING,
            query = "рубанка",
            limit = 5
        )
        assertTrue(blueResult.summary.contains("скрыто карантином 0"))
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
