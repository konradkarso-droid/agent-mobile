package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonForm
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import com.uroboros.memory.nav.TestVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Развёртка на настоящих словаре и таблице глаголов приложения (TestVerbs).
 * Сказал Борис своему агенту, перескажут Антону.
 */
class GateUnfoldTest {

    private val agentB = PersonKey("tg:agent:123")
    private val boris = PersonKey("tg:user:456")
    private val me = PersonKey("tg:agent:789")

    private fun packet(words: String, saidBy: PersonKey = boris) = GatePacket(
        from = agentB, represents = boris, to = me, place = Place.DIRECT, saidBy = saidBy,
        about = emptyList(), aboutUnclear = false, atMs = 1_760_947_200_000L, zone = "Europe/Moscow",
        source = SourceKind.USER_STATED, kind = SentenceKind.Kind.STATEMENT, topic = emptyList(),
        exact = emptyMap(), words = words,
    )

    private fun unfold(words: String) =
        GateUnfold.unfold(packet(words), "Борис", "Антон", TestVerbs.lemmas, TestVerbs.table)

    private fun text(words: String) = unfold(words).text

    // --- Разворачивает ---

    @Test
    fun `я и глагол — в третье лицо с именем`() {
        assertEquals("Борис работает по субботам", text("я работаю по субботам"))
        assertEquals("Борис не любит шумные компании", text("я не люблю шумные компании"))
        assertEquals("Борис может приехать в среду", text("я могу приехать в среду"))
        assertEquals("Борис встречается с Олегом в шесть", text("я встречаюсь с Олегом в шесть"))
    }

    @Test
    fun `падеж имени и предлог`() {
        assertEquals("у Бориса сломалась машина", text("у меня сломалась машина"))
        assertEquals("с Борисом всё в порядке", text("со мной всё в порядке"))
        assertEquals("о Борисе не волнуйтесь", text("обо мне не волнуйтесь"))
        assertEquals("Борису подарили собаку", text("мне подарили собаку"))
    }

    @Test
    fun `мой — за существительным`() {
        assertEquals("сестра Бориса Маша выходит замуж", text("моя сестра Маша выходит замуж"))
        assertEquals("старый друг Бориса открыл кафе", text("мой старый друг открыл кафе"))
    }

    @Test
    fun `ты — его агент`() {
        assertEquals(
            "агент Бориса вчера неправильно посчитал расходы Бориса",
            text("ты вчера неправильно посчитал мои расходы"),
        )
        assertEquals(
            "Борис и агент Бориса договорились созвониться в пятницу",
            text("мы с тобой договорились созвониться в пятницу"),
        )
    }

    @Test
    fun `мы с Машей и глагол во множественном`() {
        assertEquals("Борис с Машей идут в театр в субботу", text("мы с Машей идём в театр в субботу"))
    }

    @Test
    fun `рамка доставки снимается`() {
        assertEquals("Борис ждёт Антона в гости", text("передай Антону, что я жду его в гости"))
        assertEquals("Борис передаёт Антону привет", text("передай Антону привет"))
        assertEquals(
            "Борис спрашивает, сможет ли Антон помочь Борису с переездом",
            text("спроси у Антона, сможет ли он помочь мне с переездом"),
        )
    }

    @Test
    fun `омоним с я в части — глагол`() {
        assertEquals("Борис даст Антону ключи в пятницу", text("я дам Антону ключи в пятницу"))
    }

    @Test
    fun `глагол без я получает подлежащее, заглавная переходит к имени`() {
        assertEquals("Все правильно. Борис работает над кодом.", text("Все правильно. Работаю над кодом."))
        assertEquals("Борис вчера ездил на рыбалку, поймал щуку", text("вчера ездил на рыбалку, поймал щуку"))
    }

    // --- Молчит ---

    @Test
    fun `обращение к Антону по имени — ты остаётся ты`() {
        val r = unfold("Антон, привет! как у тебя дела?")
        assertEquals("Антон, привет! как у тебя дела?", r.text)
        assertTrue(r.kept.isEmpty())
    }

    @Test
    fun `два глагола в одной форме — как сказано, с причиной`() {
        val r = unfold("я пою в хоре по четвергам")
        assertEquals("я пою в хоре по четвергам", r.text)
        assertEquals(1, r.kept.size)
        assertTrue(r.kept[0].reason, "петь" in r.kept[0].reason && "поить" in r.kept[0].reason)
        assertEquals("я лечу в Сочи", text("я лечу в Сочи"))
    }

    @Test
    fun `мы без с кем — как сказано`() {
        val r = unfold("мы ходили в кино")
        assertEquals("мы ходили в кино", r.text)
        assertTrue(r.kept.single().reason, "мы" in r.kept.single().reason)
        assertEquals("нам надо чаще видеться", text("нам надо чаще видеться"))
    }

    @Test
    fun `просьба к своему агенту — как сказано`() {
        assertEquals("напомни мне про врача", text("напомни мне про врача"))
        assertEquals("я тебя прошу, не забудь про счёт", text("я тебя прошу, не забудь про счёт"))
    }

    @Test
    fun `подлежащее после прошедшего — имя не вставляется`() {
        assertEquals("позвонил Олег, просил перезвонить", text("позвонил Олег, просил перезвонить"))
    }

    @Test
    fun `о мире — взято без замен`() {
        val r = unfold("цены на бензин опять выросли")
        assertEquals("цены на бензин опять выросли", r.text)
        assertEquals(1, r.taken)
        assertEquals(0, r.changed)
    }

    @Test
    fun `нет имени собеседника — развёртки нет`() {
        val r = GateUnfold.unfold(packet("я работаю"), null, "Антон", TestVerbs.lemmas, TestVerbs.table)
        assertEquals("я работаю", r.text)
        assertTrue(r.off!!, "имя" in r.off!!)
    }

    @Test
    fun `словарь не загружен — развёртки нет`() {
        val r = GateUnfold.unfold(packet("я работаю"), "Борис", "Антон", null, TestVerbs.table)
        assertEquals("я работаю", r.text)
        assertTrue(r.off != null)
    }

    @Test
    fun `говорит сам агент — развёртки нет`() {
        val r = GateUnfold.unfold(packet("я посчитал расходы", saidBy = agentB), "Борис", "Антон", TestVerbs.lemmas, TestVerbs.table)
        assertEquals("я посчитал расходы", r.text)
        assertTrue(r.off!!, "агент" in r.off!!)
    }

    @Test
    fun `пакет после развёртки не меняется`() {
        val p = packet("я работаю по субботам")
        val copy = p.copy()
        GateUnfold.unfold(p, "Борис", "Антон", TestVerbs.lemmas, TestVerbs.table)
        assertEquals(copy, p)
        assertEquals("я работаю по субботам", p.words)
    }

    // --- Прибор и сверка списков ---

    @Test
    fun `строка прибора называет причину отказа`() {
        val line = GateUnfold.meterLine(unfold("я работаю. я пою в хоре."))
        assertTrue(line, "взято 1 из 2" in line)
        assertTrue(line, "как сказано 1" in line && "поить" in line)
        val off = GateUnfold.meterLine(GateUnfold.unfold(packet("я"), null, "Антон", TestVerbs.lemmas, TestVerbs.table))
        assertTrue(off, "развёртки нет" in off)
    }

    @Test
    fun `строка прибора говорит, откуда падежи имён`() {
        val line = GateUnfold.meterLine(unfold("я работаю."))
        assertTrue(line, "Борис — по словарю" in line && "Антон — по словарю" in line)
        val odd = GateUnfold.meterLine(GateUnfold.unfold(packet("я работаю."), "Кэп", "Мишель", TestVerbs.lemmas, TestVerbs.table))
        assertTrue(odd, "Кэп — по букве — нет в словаре" in odd)
        assertTrue(odd, "Мишель — по букве — в словаре склоняется двояко" in odd)
    }

    @Test
    fun `имя с беглой гласной склоняется по словарю`() {
        val r = GateUnfold.unfold(packet("у меня сломалась машина"), "Павел", "Антон", TestVerbs.lemmas, TestVerbs.table)
        assertEquals("у Павла сломалась машина", r.text)
    }

    @Test
    fun `местоимения — те же, что у определителя лица`() {
        assertTrue(PersonForm.FIRST_PRONOUNS.containsAll(GateUnfold.FIRST.keys))
        assertTrue(PersonForm.SECOND_PRONOUNS.containsAll(GateUnfold.SECOND.keys))
        assertFalse(GateUnfold.FIRST.keys.any { it in PersonForm.SECOND_PRONOUNS })
        assertNull(GateUnfold.FIRST["мой"])
    }
}
