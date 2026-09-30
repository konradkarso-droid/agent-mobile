package com.uroboros.memory.nav

import com.uroboros.memory.Layer
import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.Coordinates.Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitTest {

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
    fun `ищется только на адрес владелец и оба`() {
        assertTrue(Portrait.searched(Address.OWNER))
        assertTrue(Portrait.searched(Address.BOTH))
        assertFalse(Portrait.searched(Address.AGENT))
        assertFalse(Portrait.searched(Address.UNDEFINED))
        assertTrue(Portrait.meterLine(Address.AGENT, null, fed = false).endsWith("не ищется"))
    }

    @Test
    fun `строка прибора называет каждую причину снятия`() {
        val result = Portrait.of(
            listOf(
                record(1, "Я работаю над твоей памятью."),
                record(2, "Что я говорил?"),
                record(3, "Скоро ложусь."),
                record(4, "Я работаю по субботам."),
            ),
        )
        val line = Portrait.meterLine(Address.OWNER, result, fed = false)
        assertTrue(line, line.contains("обращение к агенту 1"))
        assertTrue(line, line.contains("вопрос/просьба/«мы» 1"))
        assertTrue(line, line.contains("без «я/мой» 1"))
        assertTrue(line, line.contains("в портрет 1"))
        assertTrue(line, line.contains("только прибор"))
    }
}
