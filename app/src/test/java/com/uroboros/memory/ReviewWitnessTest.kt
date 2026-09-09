package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Показания прибора очереди: что на пути сохранения двигается и о чём отчёт не
 * имеет права промолчать.
 *
 * Предмет отдельный от SaveEventReviewTest, и слить их нельзя. Там проверяется,
 * с каким битом запись уходит в базу; здесь — что случилось со счётом. Разошлись
 * бы они так: бит поднимается правильно, а прибор его не считает — очередь
 * растёт, показания стоят на нуле, и ноль читается как "дел не было".
 *
 * ЧТО ЗДЕСЬ ЗАКРЕПЛЕНО, кроме самих чисел: три проверки ниже — на молчание.
 * Прибор без укладки, отказ укладки и счёт чистых проверок наблюдаются на
 * устройстве редко или никогда, а ветка, ни разу не сработавшая, неотличима от
 * ненаписанной. Возврат к "молчим и показываем ноль" уронит сборку.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ. Что показания действительно переживают
 * перезапуск процесса: настоящие SharedPreferences здесь не участвуют, укладка
 * подставная. Доказуемо лишь то, что прибор кладёт и поднимает то, что должен;
 * что запись доходит до диска — свойство Android, и проверяется на устройстве.
 *
 * Строки отчёта сверяются по куску смысла, а не целиком: выравнивание столбцов
 * менять можно, обещание — нет.
 */
class ReviewWitnessTest {

    /**
     * Укладка в памяти. Живёт здесь, а не отдельным файлом: ею пользуется один
     * этот тест, и общий подставной класс завёл бы второе место, где придётся
     * помнить про прибор.
     */
    private class FakeStore(
        var stored: ReviewCounts? = null,
        private val failOnSave: Boolean = false
    ) : ReviewWitnessStore {
        override val persistent: Boolean = true
        override fun load(): ReviewCounts? = stored
        override fun save(counts: ReviewCounts) {
            if (failOnSave) throw IllegalStateException("нет места")
            stored = counts
        }
    }

    private fun sticker(content: String) = Sticker(
        id = 0L,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = Layer.GREEN.name
    )

    private fun dao(pool: (String, List<String>) -> List<Sticker>) = FakeStickerDao().apply {
        onInsert = { 42L }
        onGetByTagInLayers = pool
    }

    @Test
    fun `чистое сохранение двигает счёт проверок и не двигает дела`() = runBlocking {
        val witness = ReviewWitness(FakeStore())

        HourglassMemory(dao { _, _ -> emptyList() }, witness)
            .saveEvent(sticker("у паука восемь ног"))

        val counts = witness.counts(now = 5_000L)
        assertEquals(
            "без счёта чистых проверок ноль дел неотличим от неработающего прибора",
            1L, counts.checks
        )
        assertEquals(0L, counts.disputes)
        assertEquals(0L, counts.failures)
    }

    @Test
    fun `противоречие считается как спор`() = runBlocking {
        val existing = sticker("у паука восемь ног").copy(id = 7L)
        val witness = ReviewWitness(FakeStore())

        HourglassMemory(dao { _, _ -> listOf(existing) }, witness)
            .saveEvent(sticker("у паука четыре ноги"))

        val counts = witness.counts(now = 5_000L)
        assertEquals(1L, counts.checks)
        assertEquals(1L, counts.disputes)
        assertEquals(0L, counts.failures)
    }

    @Test
    fun `сбой сравнения считается отдельно от спора`() = runBlocking {
        val witness = ReviewWitness(FakeStore())

        HourglassMemory(
            dao { _, _ -> throw IllegalStateException("база недоступна") },
            witness
        ).saveEvent(sticker("у паука восемь ног"))

        val counts = witness.counts(now = 5_000L)
        assertEquals(1L, counts.checks)
        assertEquals(
            "слитые в одно число, сбой и спор сделали бы мёртвую проверку похожей на всплеск споров",
            0L, counts.disputes
        )
        assertEquals(1L, counts.failures)
    }

    @Test
    fun `отказ укладки не роняет сохранение и виден в отчёте`() = runBlocking {
        val witness = ReviewWitness(FakeStore(failOnSave = true))

        val id = HourglassMemory(dao { _, _ -> emptyList() }, witness)
            .saveEvent(sticker("у паука восемь ног"))

        assertEquals(
            "потерянное показание восстановимо наблюдением, потерянная запись нет",
            42L, id
        )
        assertTrue(
            "прибор, молчащий о собственной поломке, не прибор",
            witness.report(now = 5_000L).contains("под сомнением")
        )
    }

    @Test
    fun `прибор без укладки говорит об этом, а не молчит нулём`() {
        val report = ReviewWitness().report(now = 5_000L)

        assertTrue(
            "счёт, умирающий вместе с процессом, систематически показывает «дел мало» — " +
                "ровно тот вывод, который прибор должен проверять",
            report.contains("не подключена")
        )
    }

    @Test
    fun `момент начала счёта поднимается из укладки, а не заводится заново`() = runBlocking {
        val store = FakeStore(
            stored = ReviewCounts(checks = 5, disputes = 1, failures = 0, startedAt = 1_000L)
        )
        val witness = ReviewWitness(store)

        HourglassMemory(dao { _, _ -> emptyList() }, witness)
            .saveEvent(sticker("у паука восемь ног"))

        val counts = witness.counts(now = 900_000L)
        assertEquals("иначе каждый запуск начинал бы счёт заново", 1_000L, counts.startedAt)
        assertEquals(6L, counts.checks)
        assertEquals(1L, counts.disputes)
    }
}
