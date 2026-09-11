package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы скрытия записи по решению человека.
 *
 * ЗАЧЕМ ОНИ ЕСТЬ. Механизм маленький — один запрос в try/catch, — но стоит он
 * на краю, который весь остальной путь охраняет: правило противоречия отвечает
 * «спорят или нет» и о сторонах не знает ничего, поэтому выбирать сторону не
 * вправе ни один механизм. Дописать сюда проверку «скрывать, только если и
 * правда спорит» выглядит заботой о безопасности и молча превращает исполнение
 * чужого решения в собственное. Проза в KDoc от такой правки не ломается, тест
 * ломается.
 *
 * ГЛАВНЫЙ ЗДЕСЬ — ТЕСТ НА МОЛЧАНИЕ, тот, что перечисляет НЕсделанные запросы.
 * Остальные три закрепляют исход и направление промаха.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что бит в настоящей базе действительно поднимается. Подделка не
 *    воспроизводит SQLite и отвечает ровно то, что положил тест. Это
 *    проверяется на устройстве, и было проверено 11.09: три записи ушли в
 *    очередь и появились в списке;
 *  - что скрывать надо именно эти записи. Кого скрывать, решает человек в
 *    диалоге, и решение сюда приходит готовым списком;
 *  - что скрытая запись перестанет попадать в выдачу. Это свойство запросов
 *    пути чтения, которые начинаются с reviewPending = 0, а не этого метода.
 */
class HideForReviewTest {

    private fun dao() = FakeStickerDao().apply {
        onSetReviewPending = { }
    }

    @Test
    fun `бит поднимается у названной записи и ровно один раз`() = runBlocking {
        val dao = dao()

        val done = HourglassMemory(dao).hideForReview(7L)

        assertTrue("успешный запрос обязан кончиться истиной", done)
        assertEquals(
            "запрос идёт по названному id, без подстановок и без повторов",
            listOf(7L), dao.reviewPendingSet
        )
    }

    @Test
    fun `скрытие ничего не проверяет — других запросов не делает`() = runBlocking {
        val dao = dao()

        HourglassMemory(dao).hideForReview(7L)

        // Перечислены те запросы, которые подделка выполняет молча. Всё
        // остальное в ней падает с именем метода — в том числе горячий пул,
        // поэтому попытка что-нибудь сверить перед скрытием уронила бы этот
        // тест сама, без единого утверждения.
        assertTrue("новых записей скрытие не создаёт", dao.inserted.isEmpty())
        assertTrue("обращение лежащей записи не засчитывается", dao.touchedAccess.isEmpty())
        assertTrue("отметка пользы не ставится", dao.touchedUserMatch.isEmpty())
        assertTrue("слой не трогается: скрытая запись остывает как обычно", dao.layerUpdates.isEmpty())
    }

    @Test
    fun `сбой базы возвращает ложь, а не летит наружу`() = runBlocking {
        val dao = FakeStickerDao().apply {
            onSetReviewPending = { throw IllegalStateException("база не ответила") }
        }

        val done = HourglassMemory(dao).hideForReview(7L)

        assertFalse(
            "несработавшее скрытие обязано отличаться от сработавшего: иначе человек " +
                "уйдёт уверенным, что запись под разбором, а она в выдаче",
            done
        )
        assertEquals(
            "обращение всё равно состоялось — упал ответ, а не вызов",
            listOf(7L), dao.reviewPendingSet
        )
    }

    @Test
    fun `несколько записей скрываются по одной и обходятся все`() = runBlocking {
        val dao = dao()
        val memory = HourglassMemory(dao)

        for (id in listOf(3L, 5L, 8L)) memory.hideForReview(id)

        assertEquals(
            "приём одной записи из грозди выталкивает нескольких противников сразу; " +
                "обойти надо всех, а не первого",
            listOf(3L, 5L, 8L), dao.reviewPendingSet
        )
    }
}
