package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitTest {

    /** Таблица глаголов — как на телефоне (см. TestVerbs). */
    @Before
    fun verbs() = TestVerbs.install()

    private fun record(
        id: Long,
        text: String,
        at: Long = id,
        source: SourceKind = SourceKind.USER_STATED,
        layer: Layer = Layer.GREEN,
    ) = Sticker(id = id, content = text, createdAt = at, source = source.name, layer = layer.name, accessCount = 3)

    private fun passed(vararg texts: String): List<String> =
        Portrait.of(texts.mapIndexed { i, t -> record(i + 1L, t) }).passed.map { it.sentence }

    @Test
    fun `берёт утверждение о себе с местоимением первого лица`() {
        assertEquals(listOf("Я работаю по субботам"), passed("Я работаю по субботам"))
        assertEquals(
            listOf("Мой любимый инструмент — рубанок с железной колодкой"),
            passed("Мой любимый инструмент — рубанок с железной колодкой"),
        )
        assertEquals(listOf("Чувство предвкушения для меня важно."), passed("Чувство предвкушения для меня важно."))
    }

    // Проверки на молчание: каждая закрепляет границу, которую случайная
    // «починка» сломала бы заметно.

    @Test
    fun `граница - обращение к агенту о нём самом не берётся`() {
        assertTrue(passed("Я работаю над твоей памятью.").isEmpty())
        assertTrue(passed("Хотя ты пока что даже не уверен, что я знаю своё строение.").isEmpty())
        assertTrue(passed("Мы с тобой давно разговариваем, я помню.").isEmpty())
    }

    @Test
    fun `вопрос и просьба не берутся`() {
        assertTrue(passed("Что я говорил про полотенце?").isEmpty())
        assertTrue(passed("Расскажи, что я люблю.").isEmpty())
    }

    @Test
    fun `без местоимения первого лица не берётся - реплики момента и безличное`() {
        assertTrue(passed("Да, скоро ложусь").isEmpty())
        assertTrue(passed("Надеюсь, всё получится.").isEmpty())
        assertTrue(passed("Хочу обсуждать погоду").isEmpty())
        assertTrue(passed("Исходя из сказанного, цель - научить агента здравому смыслу.").isEmpty())
    }

    @Test
    fun `чужие записи не берутся`() {
        val agent = record(1, "Я думаю, что радуга красивая", source = SourceKind.AGENT_INFERRED)
        val picture = record(2, "Я люблю чай", source = SourceKind.OCR_EXTRACTED)
        val rejected = record(3, "Я живу в горах").copy(rejectedAt = 5L)
        val pending = record(4, "Я живу у моря").copy(reviewPending = true)
        assertTrue(Portrait.of(listOf(agent, picture, rejected, pending)).passed.isEmpty())
    }

    @Test
    fun `три свежие записи по предложению, потом по касаниям, не больше двух с записи и шести всего`() {
        val old = record(1, "Я держу рубанок. Я работаю по субботам. Я фехтую.", at = 1).copy(userMatchCount = 5)
        val warm = record(2, "Я был у врача.", at = 2).copy(userMatchCount = 1)
        val fresh = (10L..14L).map { record(it, "Я новость $it. Я ещё новость $it.", at = it) }
        val chosen = Portrait.of(listOf(old, warm) + fresh).chosen.map { it.sentence }
        assertEquals(
            listOf(
                "Я новость 14.", "Я новость 13.", "Я новость 12.",
                "Я держу рубанок.", "Я работаю по субботам.",
                "Я был у врача.",
            ),
            chosen,
        )
        assertEquals(Portrait.LIMIT, chosen.size)
    }

    @Test
    fun `архив виден и помечен`() {
        val cold = record(1, "Я держу пчёл", layer = Layer.PURPLE)
        val line = Portrait.of(listOf(cold)).chosen.single()
        assertTrue(line.fromArchive)
    }

    @Test
    fun `портрет не трогает счёт обращений и слой`() {
        val r = record(1, "Я работаю по субботам", layer = Layer.BLUE)
        Portrait.of(listOf(r))
        assertEquals(3, r.accessCount)
        assertEquals(Layer.BLUE.name, r.layer)
    }

    @Test
    fun `вопрос о собеседнике - вопрос с адресом владелец или оба и первым лицом`() {
        assertTrue(Portrait.asksAbout("Что знаешь обо мне?"))
        assertTrue(Portrait.asksAbout("Кто я?"))
        assertTrue(Portrait.asksAbout("Ну вот, доделал. Как ты? Помнишь кто я и кто ты?"))
        assertTrue(Portrait.asksAbout("Какая ручка у моего рубанка?"))
    }

    @Test
    fun `не вопрос о собеседнике - утверждение, вопрос агенту, безличный вопрос`() {
        assertFalse(Portrait.asksAbout("Я - Админ"))
        assertFalse(Portrait.asksAbout("Я работаю по субботам."))
        assertFalse(Portrait.asksAbout("Как ты себя чувствуешь?"))
        assertFalse(Portrait.asksAbout("Какая сейчас температура?"))
        assertFalse(Portrait.asksAbout("Доброй ночи"))
    }

    @Test
    fun `строка прибора называет каждую причину снятия`() {
        val result = Portrait.of(
            listOf(
                record(1, "Я работаю над твоей памятью."),
                record(2, "Что я говорил?"),
                record(3, "Скоро ложусь."),
                record(4, "Я работаю по субботам."),
                record(5, "Я сижу.\nМне видно.\nЯ молчу.\nМне стыдно."),
            ),
        )
        val line = Portrait.meterLine(
            Portrait.Reason.QUESTION, result, null, fed = false, whyNotFed = "таблица разворота не готова",
        )
        assertTrue(line, line.contains("столбиком 4"))
        assertTrue(line, line.contains("обращение к агенту 1"))
        assertTrue(line, line.contains("вопрос/просьба/«мы» 1"))
        assertTrue(line, line.contains("без «я/мой» 1"))
        assertTrue(line, line.contains("в портрет 1"))
        assertTrue(line, line.contains("модели не подано — таблица разворота не готова"))
        assertTrue(Portrait.meterLine(Portrait.Reason.QUESTION, result, null, fed = true).contains("подано модели"))
    }

    @Test
    fun `сообщение с записями - портрет под подписью, пустая строка, остальное`() {
        val p1 = "С твоих слов: ты работаешь по субботам."
        val g1 = "Твои слова: «Всегда носи с собой полотенце.»."
        assertEquals(
            "${Portrait.HEADER}\n$p1\n\n$g1",
            Portrait.recall(listOf(p1, g1), setOf(p1), nothing = false),
        )
    }

    @Test
    fun `имя задано - подпись с именем вместо общей`() {
        val p1 = "С твоих слов: ты работаешь по субботам."
        assertEquals(
            "Ты — Кэп, мой создатель. О тебе я знаю только это:\n$p1",
            Portrait.recall(listOf(p1), setOf(p1), nothing = false, name = "Кэп"),
        )
    }

    @Test
    fun `имя задано, а строк портрета нет - имя не подаётся`() {
        val g1 = "Твои слова: «Всегда носи с собой полотенце.»."
        assertEquals(g1, Portrait.recall(listOf(g1), emptySet(), nothing = false, name = "Кэп"))
        assertEquals(Portrait.NOTHING, Portrait.recall(emptyList(), emptySet(), nothing = true, name = "Кэп"))
    }

    @Test
    fun `сообщение без новых строк портрета - без подписи`() {
        val g1 = "Твои слова: «Всегда носи с собой полотенце.»."
        assertEquals(g1, Portrait.recall(listOf(g1), setOf("уже в ленте"), nothing = false))
        assertEquals(null, Portrait.recall(emptyList(), emptySet(), nothing = false))
    }

    @Test
    fun `пустой портрет - строка ничего не знаю`() {
        assertEquals(Portrait.NOTHING, Portrait.recall(emptyList(), emptySet(), nothing = true))
    }

    @Test
    fun `запись столбиком в четыре строки и больше не берётся - стих`() {
        val verse = "Молча сижу под окошком темницы;\nСинее небо отсюда мне видно:\n" +
            "В небе играют всё вольные птицы;\nГлядя на них, мне и больно и стыдно."
        assertTrue(passed(verse).isEmpty())
        assertTrue(passed("Я дыханье ветров на лугу,\n\nЯ алмазный покров на снегу,\nЯ на солнце созревшая рожь,\nЯ осенний тихий дождь.").isEmpty())
    }

    // Проверка на молчание обратная: короткий ответ в несколько строк остаётся.
    @Test
    fun `запись в три строки и пустые строки между ними - берётся`() {
        assertEquals(
            listOf("Я работаю по субботам.", "Мой клуб — фехтование."),
            passed("Я работаю по субботам.\n\nМой клуб — фехтование.\n\nВот так."),
        )
    }

    @Test
    fun `местоимение в предложном падеже - о моём`() {
        assertEquals(listOf("В моём клубе фехтуют."), passed("В моём клубе фехтуют."))
    }

    // --- Тема реплики (Portrait.topic) — на настоящем словаре приложения.

    @Test
    fun `тема - редкое существительное реплики находит строку портрета`() {
        val records = listOf(
            record(1, "Мой любимый инструмент — рубанок с железной колодкой."),
            record(2, "Я работаю по субботам."),
        )
        val topic = topicOf(records, "У рубанка не деревянная колодка.")
        assertEquals(listOf("Мой любимый инструмент — рубанок с железной колодкой."), topic.lines.map { it.sentence })
        assertEquals(setOf("рубанок", "колодка"), topic.words.toSet())
        assertEquals(Portrait.Reason.TOPIC, Portrait.reasonOf(asks = false, topic = topic))
        val line = Portrait.meterLine(
            Portrait.Reason.TOPIC, Portrait.of(records), topic, fed = true,
        )
        assertTrue(line, line.startsWith("тема: рубанок, колодка → строк 1"))
    }

    @Test
    fun `тема - служебное слово с разбором существительного не тема`() {
        // В словаре «по» — ещё и «ПО», программа; «с» — ещё и сокращение.
        val records = listOf(record(1, "Я работаю по субботам."), record(2, "Мой рубанок с колодкой."))
        val topic = topicOf(records, "Перепроверь по памяти, с чего начали")
        assertFalse(topic.nouns.toString(), "по" in topic.nouns)
        assertFalse(topic.nouns.toString(), "с" in topic.nouns)
        assertTrue(topic.lines.isEmpty())
        assertEquals(Portrait.Reason.NONE, Portrait.reasonOf(asks = false, topic = topic))
    }

    @Test
    fun `тема - частое слово не тема`() {
        val records = listOf(
            record(1, "Я пишу код."),
            record(2, "Мой код растёт."),
            record(3, "Я правлю код по вечерам."),
        )
        val topic = topicOf(records, "Твой код хорош")
        // Омоним «кода» (музыкальная) приходит вместе с «код»: словарь отдаёт все разборы.
        assertTrue(topic.nouns.toString(), "код" in topic.nouns)
        assertTrue(topic.rare.isEmpty())
        assertTrue(topic.lines.isEmpty())
        val line = Portrait.meterLine(Portrait.Reason.NONE, Portrait.of(records), topic, fed = false)
        assertTrue(line, line.contains("не редкие"))
    }

    @Test
    fun `тема - реплика без существительных и незнакомые слова молчат`() {
        val records = listOf(record(1, "Мой любимый инструмент — рубанок."))
        val topic = topicOf(records, "Да, хорошо, дебажу")
        assertTrue(topic.nouns.isEmpty())
        assertTrue(topic.lines.isEmpty())
        val line = Portrait.meterLine(Portrait.Reason.NONE, Portrait.of(records), topic, fed = false)
        assertTrue(line, line.contains("существительных в реплике нет"))
    }

    @Test
    fun `тема - редкое слово есть, а строки портрета с ним нет`() {
        // «рубанок» есть в одной записи, но она обращена к агенту и в портрет не прошла.
        val records = listOf(record(1, "Я купил тебе рубанок."), record(2, "Я работаю по субботам."))
        val topic = topicOf(records, "Где рубанок?")
        assertEquals(listOf("рубанок"), topic.rare)
        assertTrue(topic.lines.isEmpty())
    }

    @Test
    fun `тема - не больше двух строк, свежие первыми`() {
        val records = listOf(
            record(1, "Мой рубанок старый."),
            record(2, "Я точу рубанок."),
        )
        val topic = topicOf(records, "А рубанок?")
        assertEquals(listOf("Я точу рубанок.", "Мой рубанок старый."), topic.lines.map { it.sentence })
        assertTrue(topic.lines.size <= Portrait.TOPIC_LIMIT)
    }

    @Test
    fun `вопрос о собеседнике важнее темы`() {
        val topic = topicOf(listOf(record(1, "Мой любимый инструмент — рубанок.")), "Рубанок")
        assertEquals(Portrait.Reason.QUESTION, Portrait.reasonOf(asks = true, topic = topic))
        assertEquals(Portrait.Reason.NONE, Portrait.reasonOf(asks = false, topic = null))
    }

    @Test
    fun `без повода строки не идут, по теме - только строки темы`() {
        val records = listOf(record(1, "Мой любимый инструмент — рубанок."), record(2, "Я работаю по субботам."))
        val result = Portrait.of(records)
        val topic = topicOf(records, "Рубанок")
        assertTrue(Portrait.fedLines(Portrait.Reason.NONE, result, topic).isEmpty())
        assertEquals(topic.lines, Portrait.fedLines(Portrait.Reason.TOPIC, result, topic))
        assertEquals(result.chosen, Portrait.fedLines(Portrait.Reason.QUESTION, result, topic))
    }

    @Test
    fun `повтор текущей реплики в портрет не берётся`() {
        val records = listOf(record(1, "Я работаю по субботам."), record(2, "Мой рубанок старый."))
        val result = Portrait.of(records, current = "я работаю по субботам")
        assertEquals(listOf("Мой рубанок старый."), result.passed.map { it.sentence })
        assertEquals(1, result.repeats)
        assertEquals(0, Portrait.of(records, current = "Я не работаю по субботам").repeats)
    }

    @Test
    fun `по теме - своя подпись, без имени и без строки ничего не знаю`() {
        val p1 = "С твоих слов: твой любимый инструмент — рубанок."
        val g1 = "Твои слова: «Всегда носи с собой полотенце.»."
        assertEquals(
            "${Portrait.TOPIC_HEADER}\n$p1\n\n$g1",
            Portrait.recall(listOf(p1, g1), setOf(p1), nothing = false, name = "Кэп", topic = true),
        )
        assertEquals(g1, Portrait.recall(listOf(g1), emptySet(), nothing = true, topic = true))
    }

    @Test
    fun `словарь не загружен - строка прибора говорит, что по теме не ищется`() {
        val result = Portrait.of(listOf(record(1, "Я работаю по субботам.")))
        val line = Portrait.meterLine(
            Portrait.Reason.NONE, result, null, fed = false, topicOff = "словарь начальных форм не загружен",
        )
        assertTrue(line, line.contains("по теме не ищу — словарь начальных форм не загружен"))
        assertTrue(line, line.endsWith("портрет не подан"))
    }

    private fun topicOf(records: List<Sticker>, reply: String): Portrait.Topic =
        Portrait.topic(Portrait.of(records, current = reply), records, reply, dictionary)

    companion object {
        /** Словарь приложения (`app/src/main/assets/lemma_forms.txt`), один на все тесты. */
        private val dictionary: LemmaDictionary by lazy {
            val file = listOf("src/main/assets/lemma_forms.txt", "app/src/main/assets/lemma_forms.txt")
                .map { java.io.File(it) }.first { it.exists() }
            file.bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
        }
    }

    // --- Чужая речь (проверка 0) ---

    @Test
    fun `«я» во вставленной реплике — не собеседника`() {
        assertTrue(passed("Клод: я тоже программа. Я учусь у людей.").isEmpty())
        val r = Portrait.of(listOf(record(1, "Клод: я тоже программа. Я учусь у людей."), record(2, "Я работаю по субботам")))
        assertEquals(2, r.foreign)
        assertEquals(listOf("Я работаю по субботам"), r.passed.map { it.sentence })
    }

    @Test
    fun `своё рядом с чужим в одной записи — остаётся`() {
        assertEquals(listOf("Я сегодня устал."), passed("Я сегодня устал. Клод сказал, что я не программист."))
    }

    @Test
    fun `ссылка на сказанное в разговоре — своё, чужой речью не снимается`() {
        assertEquals(0, Portrait.of(listOf(record(1, "Ты вчера сказал, что я гений."))).foreign)
    }

    @Test
    fun `пересказ о собеседнике — заявка другого, в портрет не идёт`() {
        // «Я» здесь собеседника, но сказала это мама (граница —
        // ForeignSpeech.ownSentences: мерка — вид, а не голос).
        val r = Portrait.of(listOf(record(1, "Мама говорит, что я похудел.")))
        assertEquals(1, r.foreign)
        assertTrue(r.passed.isEmpty())
    }
}
