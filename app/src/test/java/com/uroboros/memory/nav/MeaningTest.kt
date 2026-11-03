package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.Sticker
import com.uroboros.memory.normalizeExact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прибор «смысл» ([Meaning]) на настоящих файлах приложения — векторах и
 * словаре начальных форм. Что находится без общих слов, когда прибор молчит
 * и почему, и какие записи в пул не попадают.
 */
class MeaningTest {

    private val vectors = TestVerbs.vectors
    private val dictionary = TestVerbs.lemmas

    private var nextId = 1L
    private fun rec(content: String, layer: Layer = Layer.GREEN, pending: Boolean = false, rejected: Long? = null) =
        Sticker(id = nextId++, content = content, layer = layer.name, reviewPending = pending, rejectedAt = rejected)

    /**
     * Записи «шума» — чтобы веса слов и общая составляющая считались не по
     * двум записям. Рубанок оставлен нарочно: на малом наборе он второй и к
     * зубу, и к клинку, — тест проверяет, что уместное его обгоняет.
     */
    private val background = listOf(
        "Чёрный чай заваривают кипятком",
        "Мнемоническое правило помогает запомнить все цвета радуги.",
        "Алеет солнце на закате.",
        "Всегда носи с собой полотенце.",
        "Мой любимый инструмент — рубанок с железной колодкой",
        "Сейчас поеду гулять, последние тёплые дни в этом году.",
        "Там есть чат разработчиков и ИИ-агентов. Хотел бы с ними пообщаться?",
        "Сегодня мы просто болтаем. За окном дождь, темно, вечер. Торопиться некуда.",
        "Купил новую клавиатуру для компьютера.",
        "Читаю книгу про далёкие планеты и звёзды.",
    ).map { rec(it) }

    private fun probe(question: String, pool: List<Sticker>, all: List<Sticker> = background + pool) =
        Meaning.probe(question, all, pool, vectors, dictionary)

    // --- Что находится ---

    @Test
    fun `находит запись без общих слов с репликой`() {
        val dentist = rec("Вчера был у стоматолога, поставили пломбу.")
        val r = probe("Коронка на зубе отвалилась, поеду чинить.", background + dentist)
        r as Meaning.Result.Best
        assertEquals(dentist.id, r.record.id)
    }

    @Test
    fun `близкие существительные дают надбавку и видны в строке`() {
        val sword = rec("Купил себе новую шпагу для тренировок.")
        val r = probe("Предпочитаю лёгкие клинки.", background + sword) as Meaning.Result.Best
        assertEquals(sword.id, r.record.id)
        assertEquals("клинок" to "шпага", r.nouns)
        assertTrue(Meaning.line(r, 2, 5).contains("общее: клинок → шпага"))
    }

    @Test
    fun `слово короче четырёх букв в спектр не входит — как у поиска по словам`() {
        // «меч» отсеивается правилом значимых слов (memory.allMeaningfulWords), и
        // запись из одного короткого существительного спектра не имеет.
        val sword = rec("Меч")
        assertEquals(Meaning.Result.NoSpectra(1), probe("Предпочитаю лёгкие клинки.", listOf(sword)))
    }

    // --- Когда молчит ---

    @Test
    fun `реплика без значимых слов — нечем искать, а не «ничего не нашёл»`() {
        val r = probe("Да?", background)
        assertTrue(r is Meaning.Result.NoWords)
        assertTrue(Meaning.line(r, 3, 1).contains("в реплике нет слов с вектором"))
    }

    @Test
    fun `пустой пул — искать не в чем`() {
        val r = probe("Коронка на зубе отвалилась.", emptyList())
        assertEquals(Meaning.Result.EmptyPool, r)
        assertTrue(Meaning.line(r, 0, 1).contains("искать не в чем"))
    }

    @Test
    fun `пул без слов с вектором — сказано числом записей`() {
        val latin = rec("ROYGBIV")
        val r = probe("Коронка на зубе отвалилась.", listOf(latin), all = background + latin)
        assertEquals(Meaning.Result.NoSpectra(1), r)
    }

    @Test
    fun `лучшее ниже порога печатается как ниже порога`() {
        val r = Meaning.Result.Best(rec("Чёрный чай заваривают кипятком"), 0.1, null, 0, 8)
        assertFalse(r.found)
        val line = Meaning.line(r, 5, 3)
        assertTrue(line, line.contains("лучшее ниже порога"))
        assertFalse(line.contains("нашёл бы"))
    }

    @Test
    fun `нет мест — строка говорит, что окно молчало бы`() {
        val r = Meaning.Result.Best(rec("Я был у стоматолога."), 0.6, null, 1, 3)
        assertTrue(Meaning.line(r, 0, 3).contains("мест нет — окно молчало бы"))
        assertTrue(Meaning.line(r, 2, 3).contains("нашёл бы «Я был у стоматолога.»"))
    }

    @Test
    fun `нет векторов или словаря — так и сказано`() {
        assertTrue(Meaning.offLine(vectorsLoaded = false, dictionaryLoaded = true).contains("векторы смысла не загружены"))
        assertTrue(Meaning.offLine(vectorsLoaded = true, dictionaryLoaded = false).contains("словарь начальных форм не загружен"))
    }

    // --- Заборы пула: те же, что у окон круга ---

    private fun eligible(r: Sticker, excluded: List<String> = emptyList(), taken: Set<Long> = emptySet()) =
        Meaning.eligible(r, excluded.toSet(), excluded.mapTo(HashSet()) { normalizeExact(it) }, taken, Coordinates.Address.UNDEFINED, null)

    @Test
    fun `обычная запись проходит`() {
        assertTrue(eligible(rec("Я был у стоматолога.")))
    }

    @Test
    fun `красный, на проверке, отвергнутые и усаженные не проходят`() {
        assertFalse(eligible(rec("Я агент на телефоне.", layer = Layer.RED)))
        assertFalse(eligible(rec("Я был у стоматолога.", pending = true)))
        assertFalse(eligible(rec("Я был у стоматолога.", pending = true, rejected = 5L)))
        val seated = rec("Я был у стоматолога.")
        assertFalse(eligible(seated, taken = setOf(seated.id)))
    }

    @Test
    fun `реплика из ленты не проходит и в другой форме`() {
        assertFalse(eligible(rec("Коронка отвалилась."), excluded = listOf("Коронка отвалилась.")))
        assertFalse(eligible(rec("коронка отвалилась"), excluded = listOf("Коронка отвалилась.")))
    }

    @Test
    fun `одни вопросы и обычаи не проходят`() {
        assertFalse(eligible(rec("Что тебе снилось?")))
        assertFalse(eligible(rec("Доброй ночи.")))
    }

    @Test
    fun `холод проходит — смысл ищет во всех слоях, кроме красного`() {
        assertTrue(eligible(rec("Я был у стоматолога.", layer = Layer.BLUE)))
        assertTrue(eligible(rec("Я был у стоматолога.", layer = Layer.PURPLE)))
    }

    @Test
    fun `слово без вектора в спектр не входит`() {
        assertNull(vectors.vector("roygbiv"))
    }

    // --- Весь прибор одним вызовом (как его зовёт экран) ---

    @Test
    fun `прибор целиком — реплика ленты не находит саму себя, а уместное находит`() {
        val dentist = rec("Вчера был у стоматолога, поставили пломбу.")
        val same = rec("Коронка на зубе отвалилась, поеду чинить.")
        val all = background + dentist + same
        val line = Meaning.meter(
            "Коронка на зубе отвалилась, поеду чинить.", all, taken = emptySet(), free = 3,
            excludedTexts = listOf("Коронка на зубе отвалилась, поеду чинить."),
            address = Coordinates.Address.UNDEFINED, retell = null, vectors = vectors, dictionary = dictionary,
        )
        assertTrue(line, line.contains("№${dentist.id}"))
        assertFalse(line, line.contains("№${same.id}"))
        assertTrue(line, line.startsWith("Смысл (модели не подаётся): "))
    }

    @Test
    fun `прибор целиком — усаженное кругом не берётся`() {
        val dentist = rec("Вчера был у стоматолога, поставили пломбу.")
        val line = Meaning.meter(
            "Коронка на зубе отвалилась.", background + dentist, taken = setOf(dentist.id), free = 1,
            excludedTexts = emptyList(), address = Coordinates.Address.UNDEFINED, retell = null,
            vectors = vectors, dictionary = dictionary,
        )
        assertFalse(line, line.contains("№${dentist.id}"))
    }

    @Test
    fun `прибор целиком без векторов — молчит словами, не падает`() {
        val line = Meaning.meter(
            "Коронка на зубе отвалилась.", background, emptySet(), 5, emptyList(),
            Coordinates.Address.UNDEFINED, null, vectors = null, dictionary = dictionary,
        )
        assertTrue(line, line.contains("векторы смысла не загружены"))
    }
}
