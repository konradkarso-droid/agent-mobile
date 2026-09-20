package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пара из двух отчётов агента не сравнивается на противоречие.
 *
 * ЗАЧЕМ ЭТО ЕСТЬ. Отчёт о прогоне — запись события, а не утверждение о мире.
 * Тексты двух отчётов почти совпадают (тело кода одно), а числа итераций
 * разные, поэтому признак чисел срабатывал исправно и наполнял очередь парами,
 * в которых спорить не о чем. Замер на живой базе 20.09: на пороге 0.40 таких
 * пар было четыре, на 0.45 — две, и ВЕСЬ мусор низких порогов состоял только из
 * них; смешанных пар (отчёт против человека) не нашлось ни одной.
 *
 * ГЛАВНАЯ ПРОВЕРКА ЗДЕСЬ — ТРЕТЬЯ: отчёт против слов человека спорить МОЖЕТ, и
 * запрет на неё не распространяется. Ослабив её до «отчёты не сравниваются
 * вовсе», мы потеряли бы случай, наблюдавшийся живьём: модель пересказала
 * человеку его же словами то, что было отчётом агента.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что то же правило применено у судьи. Там оно
 * вызывается из того же места (RiskTrigger.bothAgentReports), но сам прогон
 * судьи без Android не запускается.
 */
class AgentReportsNotComparedTest {

    private fun record(content: String, source: SourceKind) = Sticker(
        id = 0L,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = Layer.GREEN.name,
        source = source.name,
    )

    private fun dao(pool: List<Sticker>) = FakeStickerDao().apply {
        onInsert = { 42L }
        onGetExpired = { emptyList() }
        onGetByTagInLayers = { _, _ -> pool }
    }

    private val firstRun =
        "[TOTE] Успех за 3 итераций. Итоговый код: fun sumPositive(numbers: List<Int>): Int"
    private val secondRun =
        "[TOTE] Успех за 5 итераций. Итоговый код: fun sumPositive(numbers: List<Int>): Int"

    @Test
    fun `два отчёта о разных прогонах не спорят`() {
        val decision = RiskTrigger.evaluate(
            record(secondRun, SourceKind.AGENT_INFERRED),
            listOf(record(firstRun, SourceKind.AGENT_INFERRED).copy(id = 7L)),
        )
        assertFalse(
            "разные числа итераций — это разные прогоны, а не спор",
            decision.reasons.contains("contradiction"),
        )
    }

    @Test
    fun `сами по себе тексты отчётов правило по-прежнему считает спорными`() {
        // Запрет живёт в паре, а не в тексте: правило, глядя на одни слова,
        // видит расхождение чисел и обязано о нём сказать. Снимает пару тот,
        // кто знает происхождение записей.
        assertTrue(RiskTrigger.contradicts(firstRun, secondRun))
    }

    @Test
    fun `отчёт против слов человека спорить может`() {
        val decision = RiskTrigger.evaluate(
            record("Задача решена за 5 итераций", SourceKind.USER_STATED),
            listOf(record("Задача решена за 3 итераций", SourceKind.AGENT_INFERRED).copy(id = 7L)),
        )
        assertTrue(
            "иначе пропадёт случай, наблюдавшийся живьём",
            decision.reasons.contains("contradiction"),
        )
    }

    @Test
    fun `на показе спора пара двух отчётов не считается даже сравнением`() = runBlocking {
        val other = record(firstRun, SourceKind.AGENT_INFERRED).copy(id = 7L)
        val report = HourglassMemory(dao(listOf(other)))
            .disputesOf(record(secondRun, SourceKind.AGENT_INFERRED).copy(id = 8L))
        assertTrue("противников быть не должно", report.visible.isEmpty() && report.hidden.isEmpty())
        assertTrue(
            "несравниваемая пара завышала бы счёт сравнений, по которому судят о работе правила",
            report.comparisons == 0,
        )
    }
}
