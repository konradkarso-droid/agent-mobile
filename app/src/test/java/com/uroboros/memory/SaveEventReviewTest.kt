package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Куда ошибается сохранение записи: путь проверки на противоречие.
 *
 * У бита отсюда ОДИН вход — несостоявшееся сравнение. Найденный спор его не
 * ставит: правило решает, состоялась ли проверка, а годится ли запись в
 * выдачу, решает человек нажатием. Обе стороны противоречия остаются в горячей
 * памяти и едут в модель вместе; сказанное о их расхождении собирается на пути
 * ввода и в базе следа не оставляет.
 *
 * Сбой сравнения — редкость, и на живом устройстве эта ветка может не
 * сработать за всё время работы. Ветка, написанная заранее и ни разу не
 * наблюдавшаяся, неотличима от ненаписанной, поэтому граница закрепляется
 * здесь: возврат к прежнему поведению — хоть "не смогли сравнить, сохраняем
 * как проверенное", хоть "нашли спор, прячем" — уронит сборку, а не пройдёт
 * молча.
 *
 * ЧЕМ РОНЯЕТСЯ СРАВНЕНИЕ. Не подделкой RiskTrigger, а отказом базы на выдаче
 * горячего пула — ровно тем сбоем, ради которого try там и стоит. Второй
 * природный рычаг рядом: мусорное значение importance роняет Importance.valueOf
 * внутри evaluate. Здесь он не используется, потому что первый ближе к тому,
 * что действительно может случиться на устройстве.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ. Что запись, скрытая битом, действительно
 * пропадает из выдачи — это свойство SQL (`reviewPending = 0` в трёх запросах
 * пути чтения), и подставной DAO его не воспроизводит. Здесь доказуемо только
 * то, с каким битом запись уходит в базу. Обратное — что запись со спором
 * действительно доезжает до модели — тоже не здесь: это путь ввода.
 */
class SaveEventReviewTest {

    private fun sticker(content: String) = Sticker(
        id = 0L,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = Layer.GREEN.name
    )

    /** DAO, у которого готова только вставка; пул задаётся отдельно в каждом тесте. */
    private fun dao(pool: (String, List<String>) -> List<Sticker>) = FakeStickerDao().apply {
        onInsert = { 42L }
        onGetByTagInLayers = pool
    }

    @Test
    fun `сбой сравнения прячет запись, а не пропускает её`() = runBlocking {
        val dao = dao { _, _ -> throw IllegalStateException("база недоступна") }

        val outcome = HourglassMemory(dao).saveEventChecked(sticker("у паука восемь ног"))

        assertTrue(
            "тот же отказ пула читают два механизма, и направления у них разные: " +
                "проверка ужесточает исход, отсев повторов молчит и сохраняет",
            outcome is HourglassMemory.SaveOutcome.Saved
        )
        assertEquals(42L, (outcome as HourglassMemory.SaveOutcome.Saved).id)
        assertEquals(1, dao.inserted.size)
        assertTrue(
            "сравнение не состоялось — запись обязана уйти в очередь, а не в выдачу",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `сравнение прошло и спора нет — бит опущен`() = runBlocking {
        val dao = dao { _, _ -> emptyList() }

        HourglassMemory(dao).saveEventChecked(sticker("у паука восемь ног"))

        assertFalse(
            "иначе в очередь уходило бы всё подряд, и разбирать её стало бы нечем",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `противоречие в горячем пуле бита НЕ поднимает`() = runBlocking {
        val existing = sticker("у паука восемь ног").copy(id = 7L)
        val dao = dao { _, _ -> listOf(existing) }

        HourglassMemory(dao).saveEventChecked(sticker("у паука четыре ноги"))

        assertFalse(
            "спор — состоявшаяся проверка с известным результатом, и прятать по нему " +
                "нечего: пряча вторую сторону здесь, механизм убирал её РАНЬШЕ отбора, " +
                "и до сверки поданных записей не доживала ни одна свежая пара",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `бит, поднятый вызывающим, не сбрасывается ни на чистой проверке`() = runBlocking {
        val dao = dao { _, _ -> emptyList() }
        val incoming = sticker("у паука восемь ног").apply { reviewPending = true }

        HourglassMemory(dao).saveEventChecked(incoming)

        assertTrue(
            "наш вердикт может бит только добавить: пришедший с ним текст уже кем-то " +
                "отложен, и снять отметку молча значило бы отменить чужое решение",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `бит, поднятый вызывающим, не сбрасывается и при найденном споре`() = runBlocking {
        val existing = sticker("у паука восемь ног").copy(id = 7L)
        val dao = dao { _, _ -> listOf(existing) }
        val incoming = sticker("у паука четыре ноги").apply { reviewPending = true }

        HourglassMemory(dao).saveEventChecked(incoming)

        assertTrue(
            "спор бит не ставит, но и не снимает: это отдельная проверка, потому что " +
                "путь со спором ходит по другой ветке, чем чистый",
            dao.inserted.single().reviewPending
        )
    }
}
