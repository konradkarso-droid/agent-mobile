package com.uroboros.gate

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.nav.OwnSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ChatOwnSpeechTest {

    private val owner = "tg:user:1"
    private val petya = "tg:user:2"
    private val masha = "tg:user:3"
    private val ownerChat = "tg:chat:1"
    private val petyaChat = "tg:chat:2"
    private val group = "tg:chat:-100"

    private fun said(chat: String, who: String, name: String, text: String, at: Long, direct: Boolean = true) =
        ChatPromises.Line(chat, false, who, name, text, at, null, direct)

    private fun agent(chat: String, to: String?, text: String, at: Long, direct: Boolean = true, records: List<String> = emptyList()) =
        ChatPromises.Line(chat, true, "tg:user:9", "", text, at, to, direct, records)

    /** Три места: личка владельца, личка Пети, группа, где агент отвечал Пете и Маше. */
    private val lines = listOf(
        said(ownerChat, owner, "Иван", "Какой чай заварить?", 10),
        agent(ownerChat, owner, "Зелёный чай бодрит мягче чёрного.", 11),
        said(petyaChat, petya, "Пётр", "Что почитать про рубанок?", 20),
        agent(petyaChat, petya, "Рубанок точат на камне под постоянным углом.", 21),
        said(group, petya, "Пётр", "А стамеску чем править?", 30, direct = false),
        agent(group, petya, "Стамеску правят на коже с пастой.", 31, direct = false),
        said(group, masha, "Маша", "Какая порода для ложек?", 32, direct = false),
        agent(group, masha, "Липа режется легче берёзы.", 33, direct = false),
    )

    private fun answers(viewer: ChatOwnSpeech.Viewer) =
        ChatOwnSpeech.turns(lines, viewer, owner, emptyList()).map { it.answer }

    // --- Граница ---

    @Test
    fun `экран и личка владельца видят все ответы агента`() {
        assertEquals(4, answers(ChatOwnSpeech.Viewer(scope = null)).size)
        assertEquals(4, answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.OWNER, ownerChat, owner)).size)
    }

    @Test
    fun `другой человек видит только ответы ему — в личке и в группе`() {
        assertEquals(
            listOf("Рубанок точат на камне под постоянным углом.", "Стамеску правят на коже с пастой."),
            answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, petya)),
        )
    }

    @Test
    fun `сказанное владельцу и другому наедине третьему не видно никогда`() {
        val masha = answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, "tg:chat:3", masha))
        assertEquals(listOf("Липа режется легче берёзы."), masha)
        assertFalse(masha.any { "чай" in it || "Рубанок" in it })
    }

    @Test
    fun `группа видит только сказанное в ней — даже когда спрашивает владелец`() {
        assertEquals(
            listOf("Стамеску правят на коже с пастой.", "Липа режется легче берёзы."),
            answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.GROUP, group, owner)),
        )
    }

    @Test
    fun `без ключа собеседника и без ключа чата узкая граница молчит`() {
        assertTrue(answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, "")).isEmpty())
        assertTrue(answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, null)).isEmpty())
        assertTrue(answers(ChatOwnSpeech.Viewer(ChatRecall.Scope.GROUP, "", owner)).isEmpty())
    }

    @Test
    fun `ответ без «кому» ходом не считается и чужому не виден`() {
        val orphan = listOf(agent(petyaChat, null, "Рубанок лежит на верстаке.", 5))
        assertTrue(ChatOwnSpeech.turns(orphan, ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, petya), owner, emptyList()).isEmpty())
        assertTrue(ChatOwnSpeech.turns(orphan, ChatOwnSpeech.Viewer(scope = null), owner, emptyList()).isEmpty())
    }

    // --- Что модель и так видит ---

    @Test
    fun `ответ из окна этого чата воспоминанием не подаётся, тот же текст в другом чате — подаётся`() {
        val text = "Рубанок точат на камне под постоянным углом."
        val viewer = ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, petya, shown = setOf(text))
        assertEquals(listOf("Стамеску правят на коже с пастой."), answers(viewer))
        val ownerView = ChatOwnSpeech.Viewer(ChatRecall.Scope.OWNER, ownerChat, owner, shown = setOf(text))
        assertTrue(text in answers(ownerView))
    }

    // --- Место ---

    private fun whereOf(viewer: ChatOwnSpeech.Viewer, answer: String) =
        ChatOwnSpeech.turns(lines, viewer, owner, emptyList()).first { it.answer == answer }.where

    @Test
    fun `экрану сказано, где и кому — с ролью владельца и именем собеседника`() {
        val screen = ChatOwnSpeech.Viewer(scope = null)
        assertEquals("в телеграме я сказал собеседнику (мой владелец Иван)", whereOf(screen, "Зелёный чай бодрит мягче чёрного."))
        assertEquals("в телеграме я сказал собеседнику (Пётр)", whereOf(screen, "Рубанок точат на камне под постоянным углом."))
        assertEquals("в группе телеграма я сказал собеседнику (Маша)", whereOf(screen, "Липа режется легче берёзы."))
    }

    @Test
    fun `в самом чате — «в этом чате», человеку о группе — «тебе в группе»`() {
        val petyaView = ChatOwnSpeech.Viewer(ChatRecall.Scope.PERSON, petyaChat, petya)
        assertEquals(ChatOwnSpeech.IN_THIS_CHAT, whereOf(petyaView, "Рубанок точат на камне под постоянным углом."))
        assertEquals(ChatOwnSpeech.TO_YOU_IN_GROUP, whereOf(petyaView, "Стамеску правят на коже с пастой."))
        val ownerView = ChatOwnSpeech.Viewer(ChatRecall.Scope.OWNER, ownerChat, owner)
        assertEquals(ChatOwnSpeech.IN_THIS_CHAT, whereOf(ownerView, "Зелёный чай бодрит мягче чёрного."))
        assertEquals("в телеграме я сказал собеседнику (Пётр)", whereOf(ownerView, "Рубанок точат на камне под постоянным углом."))
    }

    @Test
    fun `владелец не отмечен — роль не выдумывается`() {
        val turns = ChatOwnSpeech.turns(lines, ChatOwnSpeech.Viewer(scope = null), null, emptyList())
        assertEquals("в телеграме я сказал собеседнику (Иван)", turns.first { "чай" in it.answer }.where)
    }

    @Test
    fun `ходы экрана для чата помечаются местом`() {
        val screen = ChatOwnSpeech.screenTurns(listOf(OwnSpeech.Turn("Ответ.", "Вопрос?", emptyList(), 1L)))
        assertEquals(ChatOwnSpeech.ON_SCREEN, screen.single().where)
    }

    // --- Своя речь из ходов чата ---

    @Test
    fun `место доходит до найденного предложения и до подписи`() {
        val turns = ChatOwnSpeech.turns(lines, ChatOwnSpeech.Viewer(scope = null), owner, emptyList())
        val found = OwnSpeech.search(OwnSpeech.sift(turns).said, "рубанок")
        assertEquals("в телеграме я сказал собеседнику (Пётр)", found.single().where)
        val label = ProvenanceLabels.ownSpeechForModel(
            found.single().sentence, 0L, 3_600_000L, ZoneId.of("UTC"), where = found.single().where,
        )
        assertTrue(label, label.contains(" в телеграме я сказал собеседнику (Пётр): «"))
        assertTrue(label, label.first().isUpperCase())
        assertFalse(label, label.contains("в прошлом разговоре") || label.contains("Я говорил"))
    }

    @Test
    fun `пересказ поданной записи своей речью не становится`() {
        val record = "Иван работает сторожем на складе и любит рыбалку."
        fun found(records: List<String>): List<OwnSpeech.Said> {
            val chat = listOf(
                said(ownerChat, owner, "Иван", "Кем я работаю?", 10),
                agent(ownerChat, owner, "Иван работает сторожем на складе и любит рыбалку.", 11, records = records),
            )
            return OwnSpeech.sift(ChatOwnSpeech.turns(chat, ChatOwnSpeech.Viewer(scope = null), owner, emptyList())).said
        }
        assertTrue("без записей хода пересказ читается своей речью", found(emptyList()).isNotEmpty())
        assertTrue("с записями хода — эхо, не своя речь", found(listOf(record)).isEmpty())
    }

    // --- Записи хода в строке ленты ---

    @Test
    fun `записи хода пакуются и читаются обратно`() {
        val records = listOf("Первая запись.\nСо второй строкой.", "Вторая запись.")
        assertEquals(records, ChatOwnSpeech.unpack(ChatOwnSpeech.pack(records)))
        assertNull(ChatOwnSpeech.pack(emptyList()))
        assertNull(ChatOwnSpeech.pack(listOf(" ")))
        assertTrue(ChatOwnSpeech.unpack(null).isEmpty())
    }

    // --- Прибор ---

    @Test
    fun `прибор различает «не искал», «не прочиталось» и счёт`() {
        assertEquals("своя речь: не ищу — вопрос не ко мне и вспомнить не просят", ChatOwnSpeech.meter(false, null, 0, 0, 0, 0))
        assertEquals("своя речь: не прочиталась — IOException", ChatOwnSpeech.meter(true, "IOException", 0, 0, 0, 0))
        assertEquals("своя речь: дозволено 803 (из телеграма 63), к вопросу 4, подано 1", ChatOwnSpeech.meter(true, null, 803, 63, 4, 1))
        assertEquals("своей речи 63, к вопросу 2", ChatOwnSpeech.screenMeter(null, 63, 2))
        assertEquals("не прочиталось (IOException), ищу без него", ChatOwnSpeech.screenMeter("IOException", 0, 0))
    }
}
