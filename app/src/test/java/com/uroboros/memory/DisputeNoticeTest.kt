package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки [DisputeNotice].
 *
 * ЧТО ЗДЕСЬ ЗАКРЕПЛЯЕТСЯ, КРОМЕ РАБОТЫ МЕХАНИЗМА. Проверки на МОЛЧАНИЕ:
 * непротиворечивые записи, одна запись, пробельная строка, пустая реплика,
 * непереданная реплика, короткое возражение ниже порога схожести. Без них
 * «механизм молчит, потому что ловить нечего» и «механизм молчит, потому что
 * сломан» неразличимы, и случайная починка, начавшая находить расхождения
 * везде, прошла бы зелёной.
 *
 * ТЕКСТЫ ВЗЯТЫ С ЖИВОГО МАТЕРИАЛА, а не выдуманы: пара про рубанок — та самая,
 * на которой наблюдался спор в памяти, пара про Меркурий — та, на которой
 * сверка впервые сработала на устройстве. Правило сравнивает пару только после
 * порога схожести, поэтому пары здесь различаются одним словом или одним
 * числом: придуманные «разные» фразы до сравнения просто не доходят, и тест
 * зеленел бы, ничего не проверяя.
 *
 * ПОЧЕМУ ГРАНИЦА ПОРОГА ПРОВЕРЯЕТСЯ ОТДЕЛЬНЫМ ТЕСТОМ. Пара «запись и реплика»
 * задумана ради живого возражения, но берёт она только возражение, повторяющее
 * запись её же словами. Возражение, сказанное по-человечески, до сравнения не
 * доходит. Это не изъян реализации, а область механизма, и она обязана стоять
 * в проверках: иначе первый же, кто увидит молчание на живой паре, примет
 * область за поломку и «починит» порог, у которого свой замер.
 */
class DisputeNoticeTest {

    private val instrument = "Мой любимый инструмент — рубанок с деревянной колодкой"
    private val instrumentDenied = "Мой любимый инструмент — не рубанок с деревянной колодкой"
    private val instrumentExtended =
        "Мой любимый инструмент — рубанок с деревянной колодкой и стальным ножом"
    private val rate5 = "Ставка по договору 5 процентов годовых"
    private val rate6 = "Ставка по договору 6 процентов годовых"
    private val rate7 = "Ставка по договору 7 процентов годовых"
    private val rate8 = "Ставка по договору 8 процентов годовых"
    private val rate9 = "Ставка по договору 9 процентов годовых"
    private val mercury = "Меркурий светит алым у полудня"
    private val mercuryDenied = "Меркурий не светит алым у полудня"
    private val mercuryDeniedAlive = "да не светит он алым"

    @Test
    fun `пустой список — сверять нечего`() {
        assertEquals(DisputeNotice.Result.NothingToCompare, DisputeNotice.of(emptyList()))
    }

    @Test
    fun `одна запись — сверять нечего`() {
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(instrument)),
        )
    }

    @Test
    fun `пробельная строка за запись не считается`() {
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(instrument, " ")),
        )
    }

    @Test
    fun `молчание — записи без расхождения дают чистый исход`() {
        val result = DisputeNotice.of(listOf(instrument, rate5))
        assertEquals(DisputeNotice.Result.Clean, result)
    }

    @Test
    fun `отрицание названо именем признака`() {
        val result = DisputeNotice.of(listOf(instrument, instrumentDenied))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(1, result.pairsFound)
        assertEquals(1, result.pairsShown)
        assertTrue(result.text.contains("отрицание"))
        // Числа в этой паре не расходятся, и признак числа назваться не должен.
        assertTrue(!result.text.contains("числа разошлись"))
    }

    @Test
    fun `числа названы своим признаком`() {
        val result = DisputeNotice.of(listOf(rate5, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertTrue(result.text.contains("числа разошлись"))
    }

    @Test
    fun `граница механизма стоит последней строкой и один раз`() {
        val result = DisputeNotice.of(listOf(rate5, rate6, rate7))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        val lines = result.text.split("\n")
        assertEquals(DisputeNotice.LIMIT_LINE, lines.last())
        assertEquals(1, lines.count { it == DisputeNotice.LIMIT_LINE })
    }

    @Test
    fun `при обрезке названо, сколько пар осталось за потолком`() {
        // Четыре записи, различающиеся только числом, дают шесть пар, и все
        // шесть расходятся. Названы должны быть три.
        val result = DisputeNotice.of(listOf(rate5, rate6, rate7, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(6, result.pairsFound)
        assertEquals(DisputeNotice.MAX_PAIRS, result.pairsShown)
        assertTrue(result.text.contains("Названы первые 3 расхождения из 6."))
    }

    @Test
    fun `без обрезки о потолке не говорится`() {
        val result = DisputeNotice.of(listOf(rate5, rate9))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertTrue(!result.text.contains("Названы первые"))
    }

    @Test
    fun `длинная запись цитируется началом`() {
        val result = DisputeNotice.of(listOf(instrument, instrumentDenied))
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        // Обе записи длиннее черты, значит обе стоят в тексте обрезанными.
        assertTrue(result.text.contains(instrument.take(DisputeNotice.QUOTE_CHARS) + "…"))
        assertTrue(result.text.contains(instrumentDenied.take(DisputeNotice.QUOTE_CHARS) + "…"))
    }

    @Test
    fun `перенос строки внутри записи не ломает построчность блока`() {
        val result = DisputeNotice.of(
            listOf("Ставка по договору\n5 процентов годовых", rate9),
        )
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        // Одна пара — значит ровно две строки: строка о паре и граница.
        assertEquals(2, result.text.split("\n").size)
    }

    // --- пара «запись и реплика» ---

    @Test
    fun `реплика спорит с записью — расхождение найдено и названо своим предметом`() {
        val result = DisputeNotice.of(listOf(mercury), mercuryDenied)
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(1, result.pairsFound)
        assertTrue(result.text.contains("репликой пользователя"))
        assertTrue(result.text.contains("отрицание"))
        // Предмет пары назван: это не спор записей между собой.
        assertTrue(!result.text.contains("записей между собой"))
    }

    @Test
    fun `одна запись вместе с репликой — уже сравнимая пара`() {
        // Без реплики тот же вызов дал бы NothingToCompare: пары не набиралось.
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(mercury)),
        )
        assertTrue(DisputeNotice.of(listOf(mercury), mercuryDenied) is DisputeNotice.Result.Found)
    }

    @Test
    fun `молчание — реплика сравнена с записью и расхождения нет`() {
        // Схожесть выше порога, то есть пара ДОШЛА до сравнения: исход Clean
        // здесь означает "сравнили и не нашли", а не "не сравнивали".
        val result = DisputeNotice.of(listOf(instrument), instrumentExtended)
        assertEquals(DisputeNotice.Result.Clean, result)
    }

    @Test
    fun `молчание — пробельная реплика стороной не считается`() {
        assertEquals(
            DisputeNotice.Result.NothingToCompare,
            DisputeNotice.of(listOf(instrument), "   "),
        )
    }

    @Test
    fun `молчание — без реплики поведение ровно прежнее`() {
        val withoutSpeech = DisputeNotice.of(listOf(instrument, instrumentDenied))
        assertTrue(withoutSpeech is DisputeNotice.Result.Found)
        withoutSpeech as DisputeNotice.Result.Found
        assertTrue(!withoutSpeech.text.contains("репликой пользователя"))
        assertEquals(1, withoutSpeech.pairsFound)
    }

    @Test
    fun `молчание — живое возражение до сравнения не доходит`() {
        // Область механизма, а не изъян: схожесть этой пары около трети при
        // пороге в половину. Берётся возражение, повторяющее запись её же
        // словами; сказанное по-человечески не берётся.
        assertEquals(
            DisputeNotice.Result.Clean,
            DisputeNotice.of(listOf(mercury), mercuryDeniedAlive),
        )
    }

    @Test
    fun `пары с репликой вытесняют пары записей из-под потолка`() {
        // Четыре записи дают шесть споров между собой, реплика спорит с каждой
        // из четырёх — всего десять. Потолок общий, и названы должны быть три
        // пары с репликой, иначе частый случай не доехал бы до модели.
        val result = DisputeNotice.of(listOf(rate5, rate6, rate7, rate9), rate8)
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertEquals(10, result.pairsFound)
        assertEquals(DisputeNotice.MAX_PAIRS, result.pairsShown)
        assertEquals(3, result.text.split("\n").count { it.contains("репликой пользователя") })
        assertTrue(!result.text.contains("записей между собой"))
    }

    @Test
    fun `текст реплики в блоке не цитируется`() {
        // Реплика стоит в том же сообщении целиком. Цитата здесь дала бы
        // одному утверждению два вхождения, а два вхождения читаются как два
        // свидетельства.
        val result = DisputeNotice.of(listOf(mercury), mercuryDenied)
        assertTrue(result is DisputeNotice.Result.Found)
        result as DisputeNotice.Result.Found
        assertTrue(result.text.contains(mercury))
        assertTrue(!result.text.contains(mercuryDenied))
    }
}
