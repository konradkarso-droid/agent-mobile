package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перепроверка записи перед приёмом её из очереди: три исхода и правило, по
 * которому приём происходит.
 *
 * ЗАЧЕМ ЗАКРЕПЛЯТЬ. Приём необратим, и ниже него никого нет — отсеять лишнее
 * некому. Единственный исход, при котором приёма не происходит, это
 * несостоявшаяся проверка, и на устройстве он не воспроизводится по желанию:
 * базу нельзя уронить по требованию. Ветка, написанная заранее и ни разу не
 * сработавшая, неотличима от ненаписанной, поэтому граница держится здесь.
 *
 * Второй природный рычаг падения, кроме отказа базы, — мусорное значение
 * importance: Importance.valueOf внутри evaluate бросает на нём, тогда как два
 * соседних места деградируют до низкой важности. Здесь он не используется, но
 * попадает в тот же исход.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что бит с записи действительно снялся: снятие живёт на фасаде, а тот
 *    требует Android. Здесь проверяется исход перепроверки и правило приёма,
 *    но не сам UPDATE;
 *  - что самоисключение записи из своего же пула работает. Оно есть внутри
 *    evaluate, но исходом не наблюдается: противоречие требует расхождения в
 *    отрицании или числах, а с собой запись не расходится никогда. Отличить
 *    работающее самоисключение от сломанного этим способом нельзя вовсе.
 */
class RecheckForAcceptTest {

    private fun sticker(content: String, id: Long = 0L) = Sticker(
        id = id,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = Layer.GREEN.name
    )

    /** DAO, у которого готова только выдача горячего пула — больше здесь ничего не нужно. */
    private fun dao(pool: (String, List<String>) -> List<Sticker>) = FakeStickerDao().apply {
        onGetByTagInLayers = pool
    }

    @Test
    fun `спор в горячем пуле называет противника`() = runBlocking {
        val opponent = sticker("у паука восемь ног", id = 7L)
        val memory = HourglassMemory(dao { _, _ -> listOf(opponent) })

        val outcome = memory.recheckForAccept(sticker("у паука четыре ноги", id = 3L))

        assertEquals(
            "без номера противника человеку показывать нечего",
            HourglassMemory.RecheckOutcome.Disputes(7L), outcome
        )
    }

    @Test
    fun `пустой пул — спора нет`() = runBlocking {
        val memory = HourglassMemory(dao { _, _ -> emptyList() })

        val outcome = memory.recheckForAccept(sticker("у паука восемь ног", id = 3L))

        assertEquals(HourglassMemory.RecheckOutcome.Clean, outcome)
    }

    @Test
    fun `упавшая проверка не выдаёт себя за чистоту`() = runBlocking {
        val memory = HourglassMemory(dao { _, _ -> throw IllegalStateException("база недоступна") })

        val outcome = memory.recheckForAccept(sticker("у паука восемь ног", id = 3L))

        assertEquals(
            "иначе сбой сравнения при приёме означал бы то же, что и его успех",
            HourglassMemory.RecheckOutcome.Failed, outcome
        )
    }

    @Test
    fun `приём разрешён при любом исходе, кроме несостоявшейся проверки`() {
        assertFalse(
            "приём необратим, отсеять лишнее ниже некому",
            AcceptCheck.CheckFailed.allowsAccept
        )
        assertTrue(
            "спор приёму не мешает: он повторится завтра, и очередь стала бы неразбираемой",
            AcceptCheck.Disputes(opponentId = 7L, opponentContent = "у паука восемь ног").allowsAccept
        )
        assertTrue(AcceptCheck.Clean.allowsAccept)
    }
}
