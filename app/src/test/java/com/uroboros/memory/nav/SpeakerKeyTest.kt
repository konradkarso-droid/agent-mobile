package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind
import com.uroboros.memory.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кто сказал запись — по ключу из канала (Coordinates.speakerOf с ключом владельца). */
class SpeakerKeyTest {

    private val owner = "tg:user:1"
    private val other = "tg:user:2"
    private val now = 1_000_000_000_000L

    private fun record(id: Long, text: String, speaker: String? = null, at: Long = now) =
        Sticker(id = id, content = text, createdAt = at, source = SourceKind.USER_STATED.name, speaker = speaker)

    @Test
    fun `запись с экрана — по виду записи, как прежде`() {
        assertEquals(PersonKey.OWNER, Coordinates.speakerOf(record(1, "Я химик"), owner))
        assertEquals(PersonKey.OWNER, Coordinates.speakerOf(record(1, "Я химик"), null))
        val agent = Sticker(id = 2, content = "Я подумал", source = SourceKind.AGENT_INFERRED.name)
        assertEquals(PersonKey.AGENT, Coordinates.speakerOf(agent, owner))
    }

    @Test
    fun `ключ владельца из телеграма — владелец`() {
        assertEquals(PersonKey.OWNER, Coordinates.speakerOf(record(1, "Я химик", owner), owner))
    }

    @Test
    fun `другой человек из телеграма — своя персона, не владелец`() {
        assertEquals(PersonKey(other), Coordinates.speakerOf(record(1, "Я химик", other), owner))
    }

    @Test
    fun `владелец не отмечен — его слова из телеграма не числятся владельцем`() {
        assertEquals(PersonKey(owner), Coordinates.speakerOf(record(1, "Я химик", owner), null))
    }

    @Test
    fun `имя другого человека не становится именем владельца`() {
        val records = listOf(
            record(1, "Меня зовут Иван.", at = now),
            record(2, "Меня зовут Пракс.", other, at = now + 1),
        )
        assertEquals("Иван", NameClaim.of(records, ownerKey = owner).found?.name)
        assertEquals("Пракс", NameClaim.of(records, PersonKey(other), ownerKey = owner).found?.name)
    }

    @Test
    fun `портрет владельца без слов другого человека, портрет другого — только его`() {
        val records = listOf(
            record(1, "По профессии я химик."),
            record(2, "Я люблю поезда.", other),
            record(3, "Я состою в клубе фехтования.", owner),
        )
        val mine = Portrait.of(records, ownerKey = owner).passed.map { it.sentence }
        assertTrue(mine.toString(), "По профессии я химик." in mine && "Я состою в клубе фехтования." in mine)
        assertFalse(mine.toString(), "Я люблю поезда." in mine)
        assertEquals(listOf("Я люблю поезда."), Portrait.of(records, PersonKey(other), ownerKey = owner).passed.map { it.sentence })
    }

    @Test
    fun `облако владельца без слов другого человека`() {
        val sources = listOf(
            Clouds.fromRecord(record(1, "Я люблю рубанок"), owner),
            Clouds.fromRecord(record(2, "Я люблю кондуктора", other), owner),
        )
        val stems = Clouds.of(PersonKey.OWNER, sources, now).elements.map { it.form }
        assertTrue(stems.toString(), "рубанок" in stems)
        assertFalse(stems.toString(), "кондуктора" in stems)
        val theirs = Clouds.of(PersonKey(other), sources, now).elements.map { it.form }
        assertTrue(theirs.toString(), "кондуктора" in theirs)
    }

    @Test
    fun `запись без ясного говорящего — ни владелец, ни другой`() {
        val image = Sticker(id = 1, content = "Я на картинке", source = SourceKind.OCR_EXTRACTED.name)
        assertNull(Coordinates.speakerOf(image, owner))
    }
}
