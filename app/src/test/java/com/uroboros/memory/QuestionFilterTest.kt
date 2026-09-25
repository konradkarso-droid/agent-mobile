package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вопросы не попадают в отбор для ответа на пути [RetrievalPurpose.ANSWERING_USER].
 *
 * ОТКУДА ВЗЯЛАСЬ ЗАДАЧА. На живой базе 20.09 из 66 записей 23 оказались
 * только вопросами. На повторный вопрос отбор находил прежние формулировки
 * того же вопроса: пять из пяти проверенных запросов вернули вопросы вместо
 * фактов. Вопрос в отборе занимал место сведений, не неся их.
 *
 * ЗАЧЕМ ВОПРОСЫ ОСТАЮТСЯ В ПАМЯТИ. «Спрашивал трижды про имя» — это сигнал:
 * будущая мотивация агента может читать число безответных вопросов. Терять
 * этот факт нельзя; отсев работает только на пути ответа.
 *
 * ЧЕГО ФАЙЛ НЕ ПРОВЕРЯЕТ. Подделка DAO воспроизводит SQL-поиск приблизительно
 * (см. [LadderSearchTest]): тест доказывает, что вопрос отсеивается по признаку
 * [RiskTrigger.isOnlyQuestions], а не то, что SQL его обязательно найдёт.
 * Поведение на устройстве проверяется строкой «отсеяно вопросов: N» на экране.
 */
class QuestionFilterTest {

    private fun sticker(id: Long, content: String) = Sticker(
        id = id,
        content = content,
        createdAt = id * 1_000L,
        lastAccessedAt = id * 1_000L,
        layer = Layer.GREEN.name,
    )

    private fun memory(vararg records: Sticker): HourglassMemory {
        val dao = FakeStickerDao().apply {
            onGetExpired = { emptyList() }
            onGetRanked = { layers, limit ->
                records.filter { it.layer in layers }.sortedByDescending { it.createdAt }.take(limit)
            }
            onSearchAnyCase = { q, qCap, limit, _ ->
                records.filter { !it.reviewPending && (it.content.contains(q) || it.content.contains(qCap)) }
                    .sortedByDescending { it.createdAt }.take(limit)
            }
            onSearchHiddenAnyCase = { _, _, _, _ -> emptyList() }
            onCountInLayer = { layer -> records.count { it.layer == layer } }
            // Стены нет: правило подсказки работает по прошлому ответу, как прежде.
            onIdentityWall = { emptyList() }
        }
        return HourglassMemory(dao)
    }

    @Test
    fun `вопрос не попадает в отбор для ответа`() = runBlocking {
        val q = sticker(1, "Меня как зовут, скажи?")
        val f = sticker(2, "Зовут меня Олег")
        val result = memory(q, f).getContextFor(RetrievalPurpose.ANSWERING_USER, "зовут", 5)
        assertEquals("вопрос должен быть отсеян", listOf(f.id), result.map { it.id })
    }

    @Test
    fun `вопрос виден при просмотре памяти`() = runBlocking {
        val q = sticker(1, "Меня как зовут, скажи?")
        val result = memory(q).getContextFor(RetrievalPurpose.BROWSING, "зовут", 5)
        assertEquals(listOf(q.id), result.map { it.id })
    }

    @Test
    fun `когда все найденные — вопросы, ответ пустой`() = runBlocking {
        val q1 = sticker(1, "Меня как зовут, скажи?")
        val q2 = sticker(2, "Какого вообще рода числа?")
        val result = memory(q1, q2).getContextFor(RetrievalPurpose.ANSWERING_USER, "зовут", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `счётчик отсеянных идёт в ContextResult`() = runBlocking {
        val q = sticker(1, "Меня как зовут, скажи?")
        val f = sticker(2, "Зовут меня Олег")
        val result = memory(q, f).getContextWithSummary(RetrievalPurpose.ANSWERING_USER, "зовут", 5)
        assertTrue("отсеяно > 0", result.questionsFiltered > 0)
        assertEquals("факт прошёл", listOf(f.id), result.stickers.map { it.id })
    }

    @Test
    fun `смешанная запись вопрос-плюс-факт проходит целиком`() = runBlocking {
        // «Что такое рубанок? Это инструмент для строгания.» — вопрос и ответ
        // в одной записи; isOnlyQuestions = false, запись остаётся.
        val mixed = sticker(1, "Что такое рубанок? Это инструмент для строгания.")
        val result = memory(mixed).getContextFor(RetrievalPurpose.ANSWERING_USER, "рубанок", 5)
        assertEquals(listOf(mixed.id), result.map { it.id })
    }
}
