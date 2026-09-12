package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы ступени отсева повторов: что она съедает, что называет и что
 * пропускает дальше.
 *
 * ЗАЧЕМ ОНИ ЕСТЬ. Мерки — короткие правила, которые меняются от любой соседней
 * правки, а промах у них невосстановим и невидим одновременно: съеденной записи
 * нет ни в базе, ни в очереди, ни в отчёте, и узнать о ней неоткуда. На
 * устройстве проверяется, что ступень жива; здесь — где проходит её граница.
 * В коде ступени записано решение расширять нормализацию только с замером —
 * это и есть замер, иначе мерять было бы нечем.
 *
 * ЧТО ПРОВЕРЯЕТСЯ ЧЕРЕЗ ФАСАД, А НЕ ПОШТУЧНО. Тесты зовут saveEventChecked и
 * смотрят на исход, а не на normalizeExact/normalizeLoose порознь. Так они
 * проверяют то, что ступень делает на самом деле, и не требуют открывать
 * внутренности ради теста. Цена названа честно: при поломке видно "исход не
 * тот", а не "сломалась узкая мерка", и сузить причину придётся глазами.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что пул в жизни содержит то же, что здесь кладёт тест. Пул приходит от
 *    подделки, а на устройстве его выбирает запрос по тегу и горячим слоям.
 *    Близнец, чей двойник остыл, и близнец под другим тегом не ловятся ни там,
 *    ни здесь — это форма ступени, а не то, что проверяется;
 *  - что отметка обращения у лежащей записи действительно продлевает ей жизнь.
 *    Подделка запоминает вызов и ничего не меняет; здесь доказуем факт
 *    обращения, а не его последствие;
 *  - что съеденная запись не потерялась бы, будь мерка шире. Обратное
 *    невыразимо в принципе: тест ловит только те случаи, которые в нём
 *    записаны;
 *  - куда ошибается сохранение, когда пул не достался вовсе. Это соседний
 *    файл, [SaveEventReviewTest], и повторять его здесь нечего.
 */
class SaveEventDedupTest {

    private fun sticker(content: String) = Sticker(
        id = 0L,
        content = content,
        createdAt = 1_000L,
        lastAccessedAt = 1_000L,
        layer = Layer.GREEN.name
    )

    /** Лежащая запись пула. id ненулевой: по нулевому findTwin принял бы её за самого кандидата. */
    private fun lying(content: String, id: Long = 7L, createdAt: Long = 500L) =
        sticker(content).copy(id = id, createdAt = createdAt)

    private fun dao(pool: List<Sticker>) = FakeStickerDao().apply {
        onInsert = { 42L }
        onGetByTagInLayers = { _, _ -> pool }
    }

    private suspend fun save(pool: List<Sticker>, text: String): Pair<HourglassMemory.SaveOutcome, FakeStickerDao> {
        val dao = dao(pool)
        return HourglassMemory(dao).saveEventChecked(sticker(text)) to dao
    }

    @Test
    fun `дословный повтор не вставляется и продлевает жизнь лежащей`() = runBlocking {
        val existing = lying("Алеет солнце на закате.", id = 7L, createdAt = 500L)

        val (outcome, dao) = save(listOf(existing), "алеет солнце на закате")

        assertTrue(
            "повтор обязан кончиться исходом Duplicate: человеку показывают текст и время лежащей",
            outcome is HourglassMemory.SaveOutcome.Duplicate
        )
        outcome as HourglassMemory.SaveOutcome.Duplicate
        assertEquals(7L, outcome.existingId)
        assertEquals(500L, outcome.existingCreatedAt)
        assertTrue("вставки быть не должно — это и есть отсев", dao.inserted.isEmpty())
        assertEquals(
            "у лежащей засчитано обращение, иначе повторённая мысль остынет так, будто о ней не вспоминали",
            listOf(7L), dao.touchedAccess
        )
    }

    @Test
    fun `узкая мерка съедает только оформление ввода`() = runBlocking {
        // Каждая пара — то, чем два ввода одной фразы могут различаться, не
        // различаясь по смыслу. Список короткий намеренно: он же и есть
        // объявленная граница мерки.
        val pairs = listOf(
            "регистр" to ("Алеет солнце" to "алеет солнце"),
            "ё против е" to ("всё сходится" to "все сходится"),
            "лишние пробелы" to ("алеет  солнце" to "алеет солнце"),
            "концевая точка" to ("алеет солнце." to "алеет солнце"),
            "концевое тире" to ("алеет солнце \u2014" to "алеет солнце")
        )

        for ((name, texts) in pairs) {
            val (lyingText, incoming) = texts
            val (outcome, dao) = save(listOf(lying(lyingText)), incoming)

            assertTrue(
                "$name: пара должна считаться дословным повтором, а исход вышел $outcome",
                outcome is HourglassMemory.SaveOutcome.Duplicate
            )
            assertTrue("$name: съеденная запись не вставляется", dao.inserted.isEmpty())
        }
    }

    @Test
    fun `различие только в знаках внутри текста сохраняется и называется`() = runBlocking {
        val (outcome, dao) = save(listOf(lying("ставка 7.5")), "ставка 7,5")

        assertTrue(
            "различие могло быть значащим, поэтому запись сохраняется, а не съедается",
            outcome is HourglassMemory.SaveOutcome.SavedNearDuplicate
        )
        outcome as HourglassMemory.SaveOutcome.SavedNearDuplicate
        assertEquals(42L, outcome.id)
        assertEquals(7L, outcome.similarToId)
        assertEquals(1, dao.inserted.size)
        assertFalse(
            "почти-повтор в очередь НЕ ставится: спорить тут не о чем, а разбор стоит человеку нажатия",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `различие в цифрах не съедается ни одной меркой`() = runBlocking {
        val (outcome, dao) = save(listOf(lying("у паука 8 ног")), "у паука 6 ног")

        assertTrue(
            "цифры остаются на месте в обеих мерках — иначе спор о числах съелся бы как оформление",
            outcome is HourglassMemory.SaveOutcome.Saved
        )
        assertFalse(
            "такая пара принадлежит проверке на противоречие, но в очередь она не уходит: " +
                "обе стороны остаются видимыми, а о расхождении говорится при подаче в модель",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `порядок слов значим — переставленные слова не считаются повтором`() = runBlocking {
        val (outcome, _) = save(listOf(lying("у паука восемь ног")), "восемь ног у паука")

        assertTrue(
            "обе мерки сравнивают строки целиком; перестановка даёт разные строки",
            outcome is HourglassMemory.SaveOutcome.Saved
        )
    }

    @Test
    fun `пересказ другими словами не берётся вовсе`() = runBlocking {
        val (outcome, dao) = save(listOf(lying("у паука восемь ног")), "пауки имеют четыре ноги")

        assertTrue(
            "ни мерки, ни порог схожести такую пару не ловят — этот класс доходит до человека всегда",
            outcome is HourglassMemory.SaveOutcome.Saved
        )
        assertFalse(
            "бит отсюда ставит только несостоявшееся сравнение, а оно состоялось; " +
                "нашло оно что-нибудь или нет, по базе не видно ни в том, ни в другом случае",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `текст из одних знаков не совпадает с другим таким же`() = runBlocking {
        val (outcome, dao) = save(listOf(lying("...")), "!!!")

        assertTrue(
            "обе мерки дают пустую строку, а по пустой мерке совпало бы что угодно другое такое же",
            outcome is HourglassMemory.SaveOutcome.Saved
        )
        assertEquals(1, dao.inserted.size)
    }
}
