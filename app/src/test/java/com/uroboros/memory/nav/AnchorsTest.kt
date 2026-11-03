package com.uroboros.memory.nav

import com.uroboros.memory.nav.Anchors.Anchor
import com.uroboros.memory.nav.Anchors.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Якоря вопроса ([Anchors]) на настоящем словаре приложения (TestVerbs.lemmas):
 * что становится якорем и с какой приставкой — и что не становится.
 */
class AnchorsTest {

    private val dictionary = TestVerbs.lemmas

    private fun of(query: String) = Anchors.of(query, dictionary)

    // --- Что становится якорем ---

    @Test
    fun `латинское слово — имя, и без словаря тоже`() {
        val expected = listOf(Anchor("praxis", "praxis", Kind.NAME))
        assertEquals(expected, Anchors.of("Где работает Praxis, помнишь?", null))
        assertEquals(expected, of("Где работает Praxis, помнишь?"))
    }

    @Test
    fun `имя из словаря ищется по основе, в любом месте предложения`() {
        assertTrue(Anchor("клодом", "клод", Kind.NAME) in of("А что помнишь про беседу с Клодом?"))
        assertEquals(listOf(Anchor("клод", "клод", Kind.NAME)), of("Клод и Клод"))
    }

    @Test
    fun `незнакомое слово с заглавной не в начале — имя без падежного окончания`() {
        assertEquals(listOf(Anchor("пракса", "пракс", Kind.NAME)), of("Расскажи про Пракса."))
    }

    @Test
    fun `предмет ищется по основе лексемы — беглая гласная не теряет форм`() {
        assertEquals(listOf(Anchor("рубанком", "рубан", Kind.THING)), of("Что случилось с моим рубанком?"))
        assertEquals(listOf(Anchor("рубанок", "рубан", Kind.THING)), of("Где мой рубанок?"))
    }

    @Test
    fun `в длинной реплике якоря — имена и предмет, а не слова-связки`() {
        val anchors = of(
            "Да, как раз про Клода и Praxis. Твои первые разговоры с другими. Именно после попытки их " +
                "вспомнить, мы выяснили, что у тебя в памяти туман.",
        )
        assertEquals(listOf("клода", "praxis", "туман"), anchors.map { it.word })
        assertEquals(listOf(Kind.NAME, Kind.NAME, Kind.THING), anchors.map { it.kind })
    }

    // --- Что не становится: проверки на молчание ---

    @Test
    fun `без словаря кириллица не якорь`() {
        assertEquals(emptyList<Anchor>(), Anchors.of("Спроси Клода про рубанок", null))
    }

    @Test
    fun `разговор о самом разговоре — без якорей`() {
        assertEquals(emptyList<Anchor>(), of("Помнишь наш разговор о памяти?"))
    }

    @Test
    fun `незнакомое слово в начале предложения — не якорь`() {
        assertEquals(emptyList<Anchor>(), of("Пракс сказал так."))
    }

    @Test
    fun `слово, которое бывает и не существительным, — не предмет`() {
        // «стекло» — и глагол «стечь»; «после» — и предлог; «первые» — и прилагательное.
        assertEquals(emptyList<Anchor>(), of("Где лежит стекло?"))
        assertEquals(emptyList<Anchor>(), of("Что было после?"))
        assertEquals(listOf("шаги"), of("Это первые шаги").map { it.word })
    }

    @Test
    fun `глагол, прилагательное, короткая латиница — не якоря`() {
        assertEquals(emptyList<Anchor>(), of("Чем я увлекаюсь?"))
        assertEquals(emptyList<Anchor>(), of("Как быстро он бежал? Холодный и тёплый."))
        assertEquals(emptyList<Anchor>(), Anchors.of("Что такое AI?", null))
    }

    // --- Совпадение с записью ---

    @Test
    fun `приставка совпадает только с начала слова`() {
        val rubanok = Anchor("рубанком", "рубан", Kind.THING)
        assertTrue(Anchors.hits(rubanok, "рубанок затупился"))
        assertTrue(Anchors.hits(rubanok, "точил рубанка нож"))
        assertFalse(Anchors.hits(rubanok, "перерубанный"))
    }

    @Test
    fun `короткое слово совпадает только целым словом`() {
        val dreams = of("Что я говорил про сны и воспоминания?").single { it.word == "сны" }
        assertEquals("сны", dreams.prefix)
        assertTrue(Anchors.hits(dreams, "мне снятся сны про лес"))
        assertTrue(Anchors.hits(dreams, "сны"))
        assertFalse("«сны» в «ясны»", Anchors.hits(dreams, "всё ясны"))
        assertFalse("«сны» в «сныть»", Anchors.hits(dreams, "сныть в саду"))
    }

    // --- Строка круга ---

    @Test
    fun `строка круга называет якоря, а без них — причину`() {
        val anchors = of("А что помнишь про беседу с Клодом?")
        val line = Anchors.meterPart(anchors, found = 4, seated = 2, dictionaryLoaded = true, cold = Anchors.ColdPart(3, 1))
        assertEquals("якоря (беседу, клодом — имя) — нашли 4, мест 2 · в холоде нашли 3, мест 1", line)
        // Пустой холод сказан словами, а не нулём: «искал — пусто» и «искать не в чем» — разное.
        assertEquals(
            "якоря (беседу, клодом — имя) — нашли 4, мест 2 · в холоде: слой пуст",
            Anchors.meterPart(anchors, found = 4, seated = 2, dictionaryLoaded = true, cold = null),
        )
        assertEquals("якоря: нет", Anchors.meterPart(emptyList(), 0, 0, dictionaryLoaded = true))
        assertTrue(Anchors.meterPart(emptyList(), 0, 0, dictionaryLoaded = false).contains("словарь не загружен"))
    }
}
