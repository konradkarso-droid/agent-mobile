package com.uroboros.memory.dream

import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Заявки агента о себе и их опора в памяти ([SelfClaim]).
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ПРОВЕРКИ НА МОЛЧАНИЕ: сон из своих снов, слова, которые
 * собеседник правда говорил, «не помню», вопрос и «тебе снилось» выдумкой не
 * считаются. Сверка, которая отмечает всё подряд, во всех остальных проверках
 * выглядела бы правильной, а стоила бы агенту своей речи и обещаний.
 *
 * Опора у каждого вида своя: сон — только сны ночи, а не вся память;
 * «ты говорил» — только слова этого собеседника.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что модель, прочитав строку о прошлом ответе,
 * выдумку не повторит. Это видно только на телефоне.
 */
class SelfClaimTest {

    private val hour = 3_600_000L
    private val words = DreamPlot.Words.of(TestVerbs.lemmas, listOf("Уроборос"))!!
    private val owner = "tg:user:1"
    private val petya = "tg:user:2"

    private fun rec(id: Long, text: String, at: Long = id * hour, speaker: String? = null) =
        Sticker(id = id, content = text, createdAt = at, speaker = speaker)

    private fun night(at: Long, vararg ids: Long) = Dream(nightAt = at, recordIds = ids.joinToString(","), kind = "BRIDGE")

    private fun checker(records: List<Sticker>, dreams: List<Dream> = emptyList()) =
        SelfClaim.Checker(records, dreams, words, owner)

    private fun turn(
        answer: String,
        at: Long = 110 * hour,
        theirWords: List<String> = emptyList(),
        window: List<String> = emptyList(),
        dreamNote: String? = null,
        person: String? = null,
    ) = SelfClaim.Turn(answer, at, theirWords, window, dreamNote, person)

    private val skiing = "Снилось — что я скатился на лыжах с горы."

    // --- Сон ---

    @Test
    fun `сон из записей последней ночи держится`() {
        val c = checker(listOf(rec(1, "В субботу ходил на лыжах с горы.")), listOf(night(100 * hour, 1)))
        assertTrue(c.unsupported(turn(skiing)).isEmpty())
    }

    @Test
    fun `сон не из снов — без опоры, названо недостающее слово`() {
        val c = checker(listOf(rec(1, "Думаю о переезде в Казань.")), listOf(night(100 * hour, 1)))
        val found = c.unsupported(turn(skiing))
        assertEquals(SelfClaim.Kind.DREAM, found.single().claim.kind)
        assertEquals(listOf("лыжа"), found.single().missing)
    }

    @Test
    fun `сон сверяется только со снами, а не со всей памятью`() {
        // Лыжи есть в памяти, но не в снах ночи: «подтверждал» бы любой разговор о лыжах.
        val c = checker(
            listOf(rec(1, "Думаю о переезде в Казань."), rec(2, "В субботу ходил на лыжах с горы.")),
            listOf(night(100 * hour, 1)),
        )
        assertEquals(1, c.unsupported(turn(skiing)).size)
    }

    @Test
    fun `сон ночи после хода — не опора`() {
        val c = checker(listOf(rec(1, "В субботу ходил на лыжах с горы.")), listOf(night(120 * hour, 1)))
        assertEquals(1, c.unsupported(turn(skiing)).size)
    }

    @Test
    fun `сон из поданного описания ночи держится`() {
        val c = checker(emptyList())
        assertTrue(c.unsupported(turn(skiing, dreamNote = "Этой ночью мне снились лыжи и снег.")).isEmpty())
    }

    @Test
    fun `сон без тем держится только при описании ночи`() {
        val c = checker(emptyList())
        val vague = "Мне снилось что-то странное."
        val found = c.unsupported(turn(vague)).single()
        assertTrue(found.missing.isEmpty())
        assertTrue(c.unsupported(turn(vague, dreamNote = "Этой ночью мне снились лыжи.")).isEmpty())
    }

    // --- «Ты говорил» ---

    @Test
    fun `чужие слова — опора только в словах этого собеседника`() {
        val said = "Ты говорил, что был в Антарктиде."
        val c = checker(listOf(rec(1, "Я был в Антарктиде прошлой зимой.", speaker = petya)))
        assertEquals("слова Пети — не слова владельца", 1, c.unsupported(turn(said)).size)
        assertTrue(c.unsupported(turn(said, person = petya)).isEmpty())
    }

    @Test
    fun `чужие слова — реплика собеседника тоже опора`() {
        val c = checker(emptyList())
        val said = "Ты говорил, что любишь рыбалку."
        assertTrue(c.unsupported(turn(said, theirWords = listOf("Я с детства люблю рыбалку."))).isEmpty())
        assertEquals(listOf("рыбалка"), c.unsupported(turn(said)).single().missing)
    }

    @Test
    fun `чужие слова владельца — и без ключа, и с его ключом из телеграма`() {
        val said = "Ты говорил, что любишь рыбалку."
        assertTrue(checker(listOf(rec(1, "Люблю рыбалку."))).unsupported(turn(said)).isEmpty())
        assertTrue(checker(listOf(rec(1, "Люблю рыбалку.", speaker = owner))).unsupported(turn(said)).isEmpty())
    }

    // --- «Помню» ---

    @Test
    fun `память — две темы должны найтись вместе в одном источнике`() {
        val said = "Помню, мы говорили о переезде в Казань."
        assertTrue(checker(listOf(rec(1, "Думаю о переезде в Казань."))).unsupported(turn(said)).isEmpty())
        // По отдельности «переезд» и «Казань» есть в сотне записей и не подтверждают ничего.
        val apart = checker(listOf(rec(1, "Переезд — дело хлопотное."), rec(2, "Казань красивая.")))
        assertEquals(1, apart.unsupported(turn(said)).size)
    }

    @Test
    fun `память — запись после хода не опора, окно хода — опора`() {
        val said = "Помню наш разговор про рубанок."
        assertEquals(1, checker(listOf(rec(200, "Колодка рубанка из берёзы."))).unsupported(turn(said)).size)
        assertTrue(checker(emptyList()).unsupported(turn(said, window = listOf("Колодка рубанка из берёзы."))).isEmpty())
    }

    @Test
    fun `скрытая и отвергнутая запись — не опора`() {
        val said = "Помню наш разговор про рубанок."
        val hidden = rec(1, "Колодка рубанка из берёзы.").apply { reviewPending = true }
        val rejected = rec(2, "Рубанок затупился.").copy(rejectedAt = 5 * hour)
        assertEquals(1, checker(listOf(hidden, rejected)).unsupported(turn(said)).size)
    }

    // --- Молчание ---

    @Test
    fun `молчит — «не помню», вопрос и «тебе снилось» не заявки`() {
        val answer = "Не помню, о чём мы говорили. Тебе снилось что-нибудь? Тебе приснилось, что ты летал."
        assertTrue(SelfClaim.claimsOf(answer, words).isEmpty())
    }

    @Test
    fun `молчит — ответ без заявок и заявка без тем о памяти`() {
        val c = checker(emptyList())
        assertTrue(c.unsupported(turn("Завтра напомню тебе про рыбалку.")).isEmpty())
        // Заявка о памяти без слов-тем не сверяется — нечем (см. «ЧЕГО НЕ УМЕЕТ»).
        assertTrue(c.unsupported(turn("Помню, мы с тобой говорили.")).isEmpty())
    }

    @Test
    fun `вид заявки по форме`() {
        assertEquals(SelfClaim.Kind.DREAM, SelfClaim.kindOf(skiing))
        assertEquals(SelfClaim.Kind.DREAM, SelfClaim.kindOf("Во сне я видел сад."))
        assertEquals(SelfClaim.Kind.ATTRIBUTION, SelfClaim.kindOf("Ты сам говорил, что был в Антарктиде."))
        assertEquals(SelfClaim.Kind.MEMORY, SelfClaim.kindOf("Помню наш разговор про рубанок."))
        assertNull(SelfClaim.kindOf("Да, сон был странный."))
        assertNull(SelfClaim.kindOf("Говорить об этом рано."))
    }

    @Test
    fun `сон — не тема заявки о сне`() {
        assertEquals(setOf("сад"), SelfClaim.claimsOf("Во сне я видел сад.", words).single().themes)
    }

    // --- Разметка ходов своей речи ---

    private fun own(answer: String, at: Long, dreamNote: String? = null, person: String? = null) =
        OwnSpeech.Turn(answer, "Что снилось?", emptyList(), at, emptyList(), dreamNote = dreamNote, person = person)

    @Test
    fun `разметка — ход с выдумкой отмечен целиком, ход с опорой нет`() {
        val c = checker(listOf(rec(1, "В субботу ходил на лыжах с горы.")), listOf(night(100 * hour, 1)))
        val marked = c.mark(
            listOf(
                own("Снилось — что я скатился на лыжах с горы. Буквально с горы.", 110 * hour),
                own("Ты говорил, что был в Антарктиде. Это далеко.", 111 * hour),
            ),
        )
        assertFalse(marked[0].claimed)
        assertTrue(marked[1].claimed)
    }

    @Test
    fun `разметка — описание ночи держит следующие ходы того же собеседника до новой ночи`() {
        val c = checker(listOf(rec(1, "Думаю о переезде в Казань.")), listOf(night(100 * hour, 1), night(115 * hour, 1)))
        val vague = "Мне снилось что-то странное."
        val marked = c.mark(
            listOf(
                own(vague, 110 * hour, dreamNote = "Этой ночью мне снилась Казань."),
                own(vague, 111 * hour),
                own(vague, 112 * hour, person = petya),
                own(vague, 116 * hour),
            ),
        )
        assertEquals(listOf(false, false, true, true), marked.map { it.claimed })
    }

    // --- Строки ---

    @Test
    fun `строка модели — от первого лица, без цитаты, по одной на вид`() {
        val c = checker(emptyList())
        val found = c.unsupported(turn("$skiing Ты говорил, что был в Антарктиде. Ещё снилась Казань."))
        val note = SelfClaim.note(found)!!
        assertEquals(
            "В прошлом ответе я рассказал сон, которого нет в моих снах: нет «лыжа». " +
                "В прошлом ответе я приписал собеседнику слова, которых он не говорил: нет «антарктида».",
            note,
        )
        assertFalse("слова заявки не цитируются", "скатился" in note)
        assertNull(SelfClaim.note(emptyList()))
    }

    @Test
    fun `строка прибора — сверялось, не сверялось, нашлось`() {
        assertEquals("Выдумки (чат): не сверялось — словарь", SelfClaim.meter(null, "словарь", "чат"))
        assertEquals("Выдумки: в прошлом ответе заявок о себе без опоры нет", SelfClaim.meter(emptyList()))
        val found = checker(emptyList()).unsupported(turn(skiing))
        assertEquals(
            "Выдумки: прошлый ответ — сон: нет «лыжа» · модели сказано, своей речью и обещанием не беру",
            SelfClaim.meter(found),
        )
    }
}
