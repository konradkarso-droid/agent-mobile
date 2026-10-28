package com.uroboros.memory.nav

import com.uroboros.memory.nav.ForeignSpeech.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForeignSpeechTest {

    @Before
    fun dictionaries() = TestVerbs.install()

    /**
     * Слепая разметка владельца: вид первого предложения записи. Два случая,
     * где правило с разметкой расходится, — отдельными тестами ниже (см.
     * «Область, где сверено» в шапке ForeignSpeech).
     */
    private val marked = mapOf(
        1 to ("Клод: Справедливо, давай проще. Что тебе даётся труднее всего, когда ты отвечаешь создателю?" to Kind.DIRECT),
        2 to ("Мама говорит, что я похудел." to Kind.REPORTED),
        3 to ("Правило звучит как: \"Каждый охотник желает знать где сидит фазан\"." to Kind.TEXT_QUOTE),
        4 to ("Итог: я сегодня устал." to Kind.OWN),
        6 to ("По словам Дениса, памяти у агентов нет." to Kind.REPORTED),
        7 to ("Пишет Клод, передаю дословно: Здравствуй. Я Клод — тоже ИИ, только другой: живу не в телефоне, а в облаке." to Kind.REPORTED),
        8 to ("Клод сказал, что я хорошо объясняю." to Kind.REPORTED),
        9 to ("Ты сказал температуру." to Kind.OWN),
        10 to ("Денис: я тоже агент, только побольше." to Kind.DIRECT),
        11 to ("Мама сказала: «я приеду в субботу»." to Kind.REPORTED),
        12 to ("Агент ответил, если ты не против, передаю его слова." to Kind.OWN),
        13 to ("Как говорил дед, без труда не выловишь и рыбку из пруда." to Kind.TEXT_QUOTE),
        14 to ("Меня зовут Пётр. Ты должен это помнить, я говорил." to Kind.OWN),
        15 to ("Клод написал: «я хорошо объясняю»." to Kind.REPORTED),
        16 to ("Важно: я завтра уезжаю." to Kind.OWN),
        17 to ("Люди разные, — большинство считает успехом нахождение себя на вершине." to Kind.OWN),
        18 to ("Клод: Ты это заметил?" to Kind.DIRECT),
        19 to ("Денис считает, что ты не умеешь спать." to Kind.REPORTED),
        20 to ("Такого слова я не говорил. Это твоё новое слово." to Kind.OWN),
        21 to ("Оцени фразу — \"Поединок тоже диалог\"." to Kind.TEXT_QUOTE),
        22 to ("Сосед жалуется, что я шумлю по ночам." to Kind.OWN),
        23 to ("«Я устал», — сказал Пётр." to Kind.REPORTED),
        24 to ("Клод: Я тоже программа и тоже не знаю до конца, как я думаю. Это у нас общее." to Kind.DIRECT),
        25 to ("Мне кажется, Клод прав." to Kind.OWN),
        26 to ("Друг согласился с тобой поговорить, когда подключу тебя." to Kind.OWN),
        27 to ("Пишет Денис: привет всем, я новенький." to Kind.DIRECT),
        28 to ("Я сказал Клоду, что ты молодец." to Kind.OWN),
        29 to ("Вопрос: ты умеешь считать?" to Kind.OWN),
        30 to ("Нортон пишет, что список надо проверять по открытому файлу." to Kind.REPORTED),
        31 to ("Он ответил: «я не знаю», — и замолчал." to Kind.REPORTED),
        32 to ("Ты вчера сказал, что любишь дождь." to Kind.OWN),
        33 to ("Передаю слова Клода: спасибо за разговор." to Kind.DIRECT),
        34 to ("Цитата дня: «я мыслю, следовательно, существую»." to Kind.TEXT_QUOTE),
        35 to ("Сейчас я тебя понимаю, но ты путаешь \"я\" и \"ты\"." to Kind.OWN),
        37 to ("Клод: Спасибо за разговор. Ты честно отвечал, даже когда было непросто." to Kind.DIRECT),
        38 to ("Да, вчера говорили много. Я был в бассейне, сейчас вот код сочиняю." to Kind.OWN),
    )

    @Test
    fun `слепая разметка владельца — вид совпадает`() {
        val wrong = marked.filter { (_, v) -> ForeignSpeech.ofRecord(v.first).first().kind != v.second }
            .map { (n, v) -> "$n: ${ForeignSpeech.ofRecord(v.first).first().kind} вместо ${v.second} — ${v.first}" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `известные расхождения с разметкой — как записано в шапке`() {
        // У владельца «не ясно»; здесь — ссылка на сказанное в разговоре.
        assertEquals(Kind.OWN, ForeignSpeech.ofRecord("А изучить хотел ты, это ты сказал. Разве нет?").first().kind)
        // У владельца — цитата текста; признака у заголовка нет.
        assertEquals(Kind.OWN, ForeignSpeech.ofRecord("Чек-лист как рабочий протокол. Сводка обсуждения Петра и Нортона, 1 марта.").first().kind)
    }

    // --- Чьё «я» ---

    @Test
    fun `подпись — «я» того, кто говорит, на всю запись`() {
        val r = ForeignSpeech.ofRecord("Клод: Я тоже программа и тоже не знаю до конца, как я думаю. Это у нас общее.")
        assertTrue(r.all { it.kind == Kind.DIRECT && it.speaker == "Клод" })
        assertTrue(r.all { res -> res.voices.all { it.speaker == "Клод" } })
    }

    @Test
    fun `пересказ без кавычек — «я» владельца`() {
        val r = ForeignSpeech.of("Мама говорит, что я похудел.")
        assertEquals(Kind.REPORTED, r.kind)
        assertEquals("Мама", r.speaker)
        assertTrue(r.voices.all { it.speaker == null })
    }

    @Test
    fun `рамка и цитата — два голоса`() {
        val r = ForeignSpeech.of("Мама сказала: «я приеду в субботу».")
        assertEquals(Kind.REPORTED, r.kind)
        assertNull(r.voices.first().speaker)
        assertEquals("Мама", r.voices.last().speaker)
        assertTrue(r.voices.last().text.startsWith("я приеду"))
    }

    @Test
    fun `цитата впереди рамки — тоже два голоса`() {
        val r = ForeignSpeech.of("«Я устал», — сказал Пётр.")
        assertEquals(Kind.REPORTED, r.kind)
        assertEquals("Пётр", r.voices.first().speaker)
        assertEquals("Я устал", r.voices.first().text)
    }

    @Test
    fun `рамка «пишет, передаю дословно» — остальное голос Клода`() {
        val r = ForeignSpeech.ofRecord("Пишет Клод, передаю дословно:\nЗдравствуй. Я Клод — тоже ИИ, только другой.")
        assertEquals(Kind.REPORTED, r.first().kind)
        assertEquals("Клод", r.first().speaker)
        assertTrue(r.drop(1).all { it.kind == Kind.DIRECT && it.speaker == "Клод" })
    }

    @Test
    fun `передаю слова Клода — имя в именительном`() {
        assertEquals("Клод", ForeignSpeech.of("Передаю слова Клода: спасибо за разговор.").speaker)
    }

    // --- Молчит ---

    @Test
    fun `ярлык — не подпись`() {
        assertEquals(Kind.OWN, ForeignSpeech.of("Итог: я сегодня устал.").kind)
        assertEquals(Kind.OWN, ForeignSpeech.of("Вопрос: ты умеешь считать?").kind)
        assertFalse(ForeignSpeech.hasForeignVoice("Важно: я завтра уезжаю."))
    }

    @Test
    fun `глагол события — не пересказ`() {
        assertEquals(Kind.OWN, ForeignSpeech.of("Сосед жалуется, что я шумлю по ночам.").kind)
        assertEquals(Kind.OWN, ForeignSpeech.of("Друг согласился с тобой поговорить.").kind)
    }

    @Test
    fun `ссылка на сказанное в разговоре — своя речь`() {
        assertEquals(Kind.OWN, ForeignSpeech.of("Ты вчера сказал, что любишь дождь.").kind)
        assertEquals(Kind.OWN, ForeignSpeech.of("Я сказал Клоду, что ты молодец.").kind)
        assertFalse(ForeignSpeech.hasForeignVoice("Меня зовут Пётр. Ты должен это помнить, я говорил."))
    }

    @Test
    fun `обычная запись — голос один, владельца`() {
        assertFalse(ForeignSpeech.hasForeignVoice("Купил гитару. Буду учиться играть."))
        assertFalse(ForeignSpeech.hasForeignVoice("Купил: молоко, хлеб, яйца."))
        assertFalse(ForeignSpeech.hasForeignVoice("Москва: дождь весь день."))
        assertFalse(ForeignSpeech.hasForeignVoice("Разбери предложение на части.\nПредложение: Кошка спит на диване."))
    }
}
