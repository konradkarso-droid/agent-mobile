package com.uroboros.will

import com.uroboros.safety.SafetyZone
import com.uroboros.safety.SafetyZoneSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка калибровки item 7a/8a (2026-08-24) — та самая, которую три живых прогона
 * на устройстве так и не смогли проверить: модель каждый раз решала задачу за
 * 2-3 итерации, повторов не случалось, и ось 3 ни разу не исполнялась.
 *
 * Смысл этих тестов именно в детерминированности. Живой прогон стохастичен: он
 * показывает, что цикл РАБОТАЕТ на конкретном везении, но не показывает, что
 * пороги достижимы в принципе. Здесь StepTest подставной, поэтому проверяется
 * ровно арифметика порогов, без модели, без Termux и без устройства.
 *
 * ВАЖНАЯ ПОПРАВКА к комментарию в EnergyBudget.applyRepeatDamage. Там сказано:
 * "10 + 10 + 20 + 30 = 70, на третьем повторе остаётся 30%". Это верно только
 * если ПЕРВАЯ (ещё не повторная) неудача пришла с usefulProgress=false, то есть
 * стоила MEDIUM=10. Реальная кодинг-задача так себя не ведёт: на ветке
 * CompileFailure в KotlinCodingTask usefulProgress захардкожен в true, значит
 * первая неудача стоит LIGHT=0, и до срабатывания порога тратится 0+10+20+30=60,
 * а остаётся 40%. Оба варианта закреплены тестами ниже, чтобы цифра больше не
 * зависела от того, кто её по памяти пересказывает.
 */
class ToteEngineCalibrationTest {

    /** Подставной источник зоны: физическая защита есть, но всегда в комфорте. */
    private class FakeZoneSource(zone: SafetyZone = SafetyZone.COMFORT) : SafetyZoneSource {
        override val zone: StateFlow<SafetyZone> = MutableStateFlow(zone)
    }

    private fun engineFor(
        test: StepTest<Int>,
        budget: EnergyBudget
    ) = ToteEngine(
        test = test,
        operate = { state, _ -> state + 1 },
        watchdog = FakeZoneSource(),
        energyBudget = budget
    )

    @Test
    fun `three identical failures trip the stuck threshold, not the energy floor`() = runBlocking {
        val budget = EnergyBudget()
        val alwaysSameFailure = StepTest<Int> {
            StepOutcome(
                success = false,
                usefulProgress = true,
                signature = "unresolved reference: tota",
                detail = "неважно"
            )
        }

        val result = engineFor(alwaysSameFailure, budget).run(0)

        assertTrue("ожидалась эвакуация, получено: $result", result is ToteResult.Evacuated)
        val evacuated = result as ToteResult.Evacuated
        assertTrue(
            "причина должна быть застревание, а не энергия: ${evacuated.reason}",
            evacuated.reason.startsWith("застряли")
        )
        // Итерация 1 задаёт подпись (повтором ещё не считается), повторы — на 2, 3 и 4.
        assertEquals(4, evacuated.iterations)
        // Проверка на молчание для строки, которую видит пользователь: она обязана
        // называть число одинаковых неудач, а не число повторов. Эти числа отличаются
        // на единицу, расхождение не роняет ничего и однажды уже прожило незамеченным.
        assertTrue(
            "строка должна сообщать 4 одинаковых неудачи: ${evacuated.reason}",
            evacuated.reason.contains("×4")
        )
        // 100 - LIGHT(0) - 10 - 20 - 30
        assertEquals(40, budget.energy.value)
    }

    @Test
    fun `useless repeated failures cost the first step too`() = runBlocking {
        val budget = EnergyBudget()
        val uselessSameFailure = StepTest<Int> {
            StepOutcome(
                success = false,
                usefulProgress = false,
                signature = "unresolved reference: tota",
                detail = "неважно"
            )
        }

        val result = engineFor(uselessSameFailure, budget).run(0)

        assertTrue(result is ToteResult.Evacuated)
        assertEquals(4, (result as ToteResult.Evacuated).iterations)
        // 100 - MEDIUM(10) - 10 - 20 - 30 — тот самый случай из комментария в EnergyBudget.
        assertEquals(30, budget.energy.value)
    }

    /**
     * Прямое следствие LIGHT=0: ошибка компиляции с usefulProgress=true не стоит
     * энергии вообще. Значит для кодинг-задачи энергетический выход фактически
     * отключён, и stuckThreshold остался ЕДИНСТВЕННЫМ мягким барьером — а если
     * подписи каждый раз разные, цикл честно доходит до жёсткого потолка.
     * Тест фиксирует это как осознанное поведение, а не как случайность.
     */
    @Test
    fun `distinct failures never trip stuck detection and cost no energy`() = runBlocking {
        val budget = EnergyBudget()
        var counter = 0
        val alwaysDifferentFailure = StepTest<Int> {
            counter++
            StepOutcome(
                success = false,
                usefulProgress = true,
                signature = "ошибка номер $counter",
                detail = "неважно"
            )
        }

        val result = engineFor(alwaysDifferentFailure, budget).run(0)

        assertTrue("ожидался жёсткий стоп, получено: $result", result is ToteResult.HardStopped)
        val stopped = result as ToteResult.HardStopped
        assertEquals(10, stopped.iterations)
        // Десять разных подписей при десяти итерациях — противоположность хождению
        // по кругу. Пара с тестом чередования ниже: одна и та же строка на экране
        // должна различать эти два случая.
        assertEquals(10, stopped.distinctFailures)
        assertEquals(100, budget.energy.value)
    }

    /**
     * Чередование двух ошибок (A, B, A, B). Защита от залипания сравнивает подпись
     * только с непосредственно предыдущей, поэтому повтором это не считается ни разу,
     * и цикл честно доходит до потолка итераций. Поведение осознанное, барьера под
     * него нет намеренно: живого чередования пока не наблюдалось, а детектор
     * промахивался бы в сторону обрыва сходящегося прогона.
     *
     * Тест закрепляет две вещи сразу: что чередование доходит до жёсткого стопа, и
     * что прибор его отличает — разных подписей две при десяти итерациях. Пока это
     * число доезжает до экрана, хождение по кругу можно заметить глазами; если
     * однажды оно перестанет считаться, здесь станет красно.
     */
    @Test
    fun `alternating failures reach the ceiling and are visible as few distinct errors`() = runBlocking {
        val budget = EnergyBudget()
        var counter = 0
        val alternating = StepTest<Int> {
            counter++
            StepOutcome(
                success = false,
                usefulProgress = true,
                signature = if (counter % 2 == 0) "ошибка A" else "ошибка B",
                detail = "неважно"
            )
        }

        val result = engineFor(alternating, budget).run(0)

        assertTrue("ожидался жёсткий стоп, получено: $result", result is ToteResult.HardStopped)
        val stopped = result as ToteResult.HardStopped
        assertEquals(10, stopped.iterations)
        assertEquals(2, stopped.distinctFailures)
        assertEquals(100, budget.energy.value)
    }

    /** Энергетический выход должен оставаться достижимым там, где шаги бесполезны. */
    @Test
    fun `useless distinct failures drain energy into evacuation`() = runBlocking {
        val budget = EnergyBudget()
        var counter = 0
        val uselessDifferentFailure = StepTest<Int> {
            counter++
            StepOutcome(
                success = false,
                usefulProgress = false,
                signature = "ошибка номер $counter",
                detail = "неважно"
            )
        }

        val result = engineFor(uselessDifferentFailure, budget).run(0)

        assertTrue(result is ToteResult.Evacuated)
        val evacuated = result as ToteResult.Evacuated
        assertTrue(
            "причина должна быть энергия: ${evacuated.reason}",
            evacuated.reason.startsWith("энергия исчерпана")
        )
        // 9 шагов по MEDIUM(10) -> 10%, это ниже порога зоны EVACUATION (20%).
        assertEquals(9, evacuated.iterations)
        assertEquals(10, budget.energy.value)
    }

    @Test
    fun `useful success exits immediately without spending energy`() = runBlocking {
        val budget = EnergyBudget()
        val immediateSuccess = StepTest<Int> {
            StepOutcome(success = true, usefulProgress = true, signature = "OK", detail = "")
        }

        val result = engineFor(immediateSuccess, budget).run(0)

        assertTrue(result is ToteResult.Success)
        assertEquals(1, (result as ToteResult.Success).iterations)
        assertEquals(100, budget.energy.value)
    }

    /**
     * Reward-hacking guard (решение 2026-08-16): компиляция прошла, но структурная
     * проверка сказала "прогресса нет" — такой шаг НЕ завершает цикл. Здесь он
     * вдобавок каждый раз даёт одну и ту же подпись "OK", то есть выглядит как
     * застревание, и именно застреванием и должен закончиться.
     */
    @Test
    fun `success without useful progress does not end the loop`() = runBlocking {
        val budget = EnergyBudget()
        val hollowSuccess = StepTest<Int> {
            StepOutcome(success = true, usefulProgress = false, signature = "OK", detail = "")
        }

        val result = engineFor(hollowSuccess, budget).run(0)

        assertTrue("цикл не должен был засчитать успех: $result", result is ToteResult.Evacuated)
        assertTrue((result as ToteResult.Evacuated).reason.startsWith("застряли"))
    }
}
