package com.uroboros.memory.nav

import com.uroboros.memory.nav.AddressSorter.Answer
import com.uroboros.memory.nav.AddressSorter.Guess
import com.uroboros.memory.nav.AddressSorter.Memory
import com.uroboros.memory.nav.AddressSorter.Message
import com.uroboros.memory.nav.AddressSorter.Prior
import com.uroboros.memory.nav.AddressSorter.Ring
import com.uroboros.memory.nav.AddressSorter.Self
import com.uroboros.memory.nav.AddressSorter.Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Люди, ники и имя агента — выдуманы. */
class AddressSorterTest {

    private val agent = PersonKey("tg:user:100")
    private val denis = PersonKey("tg:user:200")
    private val olga = PersonKey("tg:user:300")
    private val self = Self(agent, usernames = setOf("astra_agent"), names = listOf("Астра", "Астры", "Астре", "Астру", "Астрой", "Астрочка"))

    private fun group(text: String, from: PersonKey = denis, replyTo: PersonKey? = null, mentions: List<String> = emptyList(),
                      textMentions: List<PersonKey> = emptyList(), commands: List<String> = emptyList()) =
        Message(Place.GROUP, from, text, replyTo, mentions, textMentions, commands)

    private fun ring(m: Message, prior: Prior? = null, who: Self = self): Ring = AddressSorter.sort(m, who, prior).ring

    // --- Канал: к агенту ---

    @Test
    fun `личка и экран — к агенту, что бы ни было в тексте`() {
        assertEquals(Ring.TO_AGENT, ring(Message(Place.DIRECT, denis, "Кто едет в Казань?")))
        assertEquals(Ring.TO_AGENT, ring(Message(Place.SCREEN, PersonKey.OWNER, "Сегодня дождь.")))
    }

    @Test
    fun `ответ на сообщение агента — к агенту`() {
        val r = AddressSorter.sort(group("Не согласен.", replyTo = agent), self)
        assertEquals(Ring.TO_AGENT, r.ring)
        assertEquals(listOf(Sign.REPLY_TO_ME), r.channel)
    }

    @Test
    fun `упоминание ника — к агенту, без разницы к регистру и собаке`() {
        assertEquals(Ring.TO_AGENT, ring(group("@Astra_Agent что скажешь?", mentions = listOf("@Astra_Agent"))))
        assertEquals(Ring.TO_AGENT, ring(group("глянь", mentions = listOf("astra_agent"))))
        assertEquals(Ring.TO_AGENT, ring(group("Астра посмотри", textMentions = listOf(agent))))
    }

    @Test
    fun `упоминание сильнее ответа другому — канал решает`() {
        assertEquals(Ring.TO_AGENT, ring(group("согласен с ним?", replyTo = olga, mentions = listOf("astra_agent"))))
    }

    @Test
    fun `догадки по тексту записываются, даже когда решил канал`() {
        val r = AddressSorter.sort(group("Астра права.", replyTo = agent), self)
        assertEquals(Ring.TO_AGENT, r.ring)
        assertTrue(Guess.NAME_MENTION in r.text)
    }

    // --- Канал: другому ---

    @Test
    fun `ответ другому без имени агента — общее`() {
        assertEquals(Ring.GENERAL, ring(group("А ты что думаешь?", replyTo = olga)))
    }

    @Test
    fun `ответ другому, но имя агента в обращении — сомнительно`() {
        val r = AddressSorter.sort(group("Астра, а ты как считаешь?", replyTo = olga), self)
        assertEquals(Ring.DOUBTFUL, r.ring)
        assertTrue(r.reason, r.reason.contains("ответ другому"))
    }

    @Test
    fun `ответ другому с именем агента в рассказе — об агенте`() {
        assertEquals(Ring.ABOUT_AGENT, ring(group("Да, Астра вчера так и сказала.", replyTo = olga)))
    }

    @Test
    fun `команда чужому боту — общее, с ником агента — к агенту`() {
        assertEquals(Ring.GENERAL, ring(group("/start@weather_bot", commands = listOf("/start@weather_bot"))))
        assertEquals(Ring.GENERAL, ring(group("/start", commands = listOf("/start"))))
        assertEquals(Ring.TO_AGENT, ring(group("/help@astra_agent", commands = listOf("/help@astra_agent"))))
    }

    // --- Имя в тексте ---

    @Test
    fun `имя в обращении — к агенту`() {
        for (t in listOf("Астра, как дела?", "Привет, Астра!", "Астра?", "Астра ты где", "Эй Астра глянь сюда",
            "Скажи, Астра, сколько времени.", "Ну что, Астра?", "Астрочка, привет.")) {
            assertEquals(t, Ring.TO_AGENT, ring(group(t)))
        }
    }

    @Test
    fun `имя в рассказе — об агенте`() {
        for (t in listOf("Астра права.", "Я думаю, Астра права.", "Астре это не понравится.", "Спроси у Астры потом.",
            "Вчера Астра сказала смешное.")) {
            assertEquals(t, Ring.ABOUT_AGENT, ring(group(t)))
        }
    }

    @Test
    fun `имя — только целым словом`() {
        assertEquals(Ring.GENERAL, ring(group("Еду в Астрахань.")))
        assertEquals(Ring.GENERAL, ring(group("Астральные проекции — чушь.")))
    }

    // --- По ходу разговора ---

    private val agentToDenis = Prior(agent, null, denis)
    private val agentToOlga = Prior(agent, null, olga)

    @Test
    fun `«ты» сразу после ответа агента этому человеку — к агенту`() {
        assertEquals(Ring.TO_AGENT, ring(group("А ты сам что думаешь?"), agentToDenis))
        assertEquals(Ring.TO_AGENT, ring(group("Расскажи подробнее."), agentToDenis))
    }

    @Test
    fun `без «ты» по ходу разговора — сомнительно`() {
        assertEquals(Ring.DOUBTFUL, ring(group("Спасибо, понятно."), agentToDenis))
    }

    @Test
    fun `продолжение того же человека к агенту — к агенту`() {
        assertEquals(Ring.TO_AGENT, ring(group("И ещё: что ты помнишь про вчера?"), Prior(denis, Ring.TO_AGENT, null)))
    }

    @Test
    fun `«ты» сразу после ответа агента другому — сомнительно`() {
        assertEquals(Ring.DOUBTFUL, ring(group("А ты откуда знаешь?"), agentToOlga))
    }

    // --- Молчание: общее ---

    @Test
    fun `болтовня группы — общее`() {
        for (t in listOf("Кто едет на конференцию в Казань?", "Сегодня короткий день.", "Ты видел вчерашний матч?",
            "Скиньте ссылку на статью.", "Мой кот опять спит на клавиатуре.")) {
            assertEquals(t, Ring.GENERAL, ring(group(t)))
            assertEquals(t, Ring.GENERAL, ring(group(t), Prior(olga, Ring.GENERAL, null)))
        }
    }

    @Test
    fun `«ты» после чужого сообщения — общее`() {
        assertEquals(Ring.GENERAL, ring(group("А ты что думаешь?"), Prior(olga, Ring.GENERAL, null)))
    }

    @Test
    fun `без объявленных имён по имени не сортируется`() {
        val noNames = self.copy(names = emptyList())
        assertEquals(Ring.GENERAL, ring(group("Астра, как дела?"), who = noNames))
        assertEquals(Ring.GENERAL, ring(group("Астра права."), who = noNames))
    }

    @Test
    fun `своё сообщение агента — общее`() {
        assertEquals(Ring.GENERAL, ring(group("Астра, это я.", from = agent)))
        assertEquals(Ring.GENERAL, ring(Message(Place.DIRECT, agent, "Привет")))
    }

    // --- Куда идёт кольцо ---

    @Test
    fun `судье — только к агенту`() {
        assertTrue(AddressSorter.judged(Ring.TO_AGENT))
        assertFalse(AddressSorter.judged(Ring.ABOUT_AGENT))
        assertFalse(AddressSorter.judged(Ring.DOUBTFUL))
        assertFalse(AddressSorter.judged(Ring.GENERAL))
    }

    @Test
    fun `модели — всё, кроме общего`() {
        assertTrue(AddressSorter.modelReads(Ring.TO_AGENT))
        assertTrue(AddressSorter.modelReads(Ring.ABOUT_AGENT))
        assertTrue(AddressSorter.modelReads(Ring.DOUBTFUL))
        assertFalse(AddressSorter.modelReads(Ring.GENERAL))
    }

    @Test
    fun `ответ и память по кольцу`() {
        assertEquals(Answer.ANSWER, AddressSorter.answer(Ring.TO_AGENT))
        assertEquals(Answer.ASK, AddressSorter.answer(Ring.DOUBTFUL))
        assertEquals(Answer.SILENT, AddressSorter.answer(Ring.ABOUT_AGENT))
        assertEquals(Answer.SILENT, AddressSorter.answer(Ring.GENERAL))
        assertEquals(Memory.TOLD_ME, AddressSorter.memory(Ring.TO_AGENT))
        assertEquals(Memory.TOLD_ABOUT_ME, AddressSorter.memory(Ring.ABOUT_AGENT))
        assertEquals(Memory.DOUBTFUL, AddressSorter.memory(Ring.DOUBTFUL))
        assertEquals(Memory.NONE, AddressSorter.memory(Ring.GENERAL))
    }

    // --- Прибор ---

    @Test
    fun `строка прибора — счёт по кольцам, прочитанное и переспросы`() {
        var t = AddressSorter.Tally()
        assertEquals("Обращение (General): сообщений не было", t.meterLine("General", self.names))
        t += AddressSorter.sort(group("Астра, привет!"), self)
        t += AddressSorter.sort(group("Кто едет в Казань?"), self)
        t += AddressSorter.sort(group("Спасибо."), self, agentToDenis)
        t = t.read().read().asked()
        assertEquals(
            "Обращение (General): к агенту 1 · про агента 0 · сомнительно 1 (переспросов 1) · общее 1 · прочитано моделью 2 из 3",
            t.meterLine("General", self.names),
        )
    }

    @Test
    fun `строка прибора говорит, что имён нет`() {
        val line = AddressSorter.Tally().meterLine("General", emptyList())
        assertTrue(line, line.endsWith("имён агента нет — по имени не сортирую"))
    }
}
