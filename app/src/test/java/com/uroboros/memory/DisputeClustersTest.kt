package com.uroboros.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Границы группировки споров в дела.
 *
 * ЗАЧЕМ ОНИ ЕСТЬ. Ошибка здесь не падает и не видна в логе: слипшиеся дела и
 * рассыпавшиеся выглядят на экране одинаково правдоподобно, а проверить их
 * можно только сличая цитаты — ровно то, от чего группировка избавляет. Значит
 * единственный сторож у неё — этот файл.
 *
 * САМЫЙ ВАЖНЫЙ ЗДЕСЬ — ТЕСТ ПРО ДВЕ НЕСВЯЗАННЫЕ ПАРЫ. На живой очереди из трёх
 * записей «слепить всё в одно дело» выглядит правильным ответом, и отличить
 * такую поломку от работы можно только на данных, где дел заведомо два.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что записи в деле действительно об одном предмете. Рёбра приходят готовыми
 *    из отчётов, а их считает правило противоречия — грубое намеренно;
 *  - что дело показано целиком. Остывшие противники в отчёты не попадают, а
 *    упор в потолок списка считается числом, и проверяется здесь только то, что
 *    число это не молчит;
 *  - что экран покажет группы верно. Здесь только сборка.
 */
class DisputeClustersTest {

    private fun sticker(id: Long, hidden: Boolean = false) =
        Sticker(id = id, content = "запись $id", reviewPending = hidden)

    private fun report(
        visible: List<Sticker> = emptyList(),
        hidden: List<Sticker> = emptyList(),
        failed: Boolean = false,
    ) = HourglassMemory.DisputeReport(
        visible = visible,
        hidden = hidden,
        cooled = emptyList(),
        comparisons = 1,
        failed = failed,
    )

    @Test
    fun `несколько скрытых записей вокруг одной видимой — это одно дело`() {
        // Случай, ради которого всё и строится: проверка стоит на входе, поэтому
        // бит подняли троим пришедшим позже, а первая запись осталась видимой.
        // Между собой эти трое по правилу могут не спорить вовсе.
        val visible = sticker(7L)
        val queue = listOf(sticker(101L, hidden = true), sticker(102L, hidden = true), sticker(103L, hidden = true))
        val reports = queue.map { report(visible = listOf(visible)) }

        val clusters = clusterDisputes(queue, reports)

        assertEquals("дело здесь одно, а не три", 1, clusters.size)
        assertEquals(
            "в деле все три записи очереди",
            listOf(101L, 102L, 103L), clusters[0].queueMembers.map { it.id }
        )
        assertEquals(
            "видимая сторона названа один раз, а не по разу на каждого спорящего",
            listOf(7L), clusters[0].visibleOpponents.map { it.id }
        )
    }

    @Test
    fun `две несвязанные пары не слипаются в одно дело`() {
        val first = sticker(8L)
        val second = sticker(9L)
        val queue = listOf(
            sticker(1L, hidden = true), sticker(2L, hidden = true),
            sticker(3L, hidden = true), sticker(4L, hidden = true),
        )
        val reports = listOf(
            report(visible = listOf(first)), report(visible = listOf(first)),
            report(visible = listOf(second)), report(visible = listOf(second)),
        )

        val clusters = clusterDisputes(queue, reports)

        assertEquals("дел два", 2, clusters.size)
        assertEquals(listOf(1L, 2L), clusters[0].queueMembers.map { it.id })
        assertEquals(listOf(3L, 4L), clusters[1].queueMembers.map { it.id })
        assertEquals(listOf(8L), clusters[0].visibleOpponents.map { it.id })
        assertEquals(listOf(9L), clusters[1].visibleOpponents.map { it.id })
    }

    @Test
    fun `запись без споров остаётся своим делом и не пропадает`() {
        // Пустой отчёт при живом механизме — законный исход: бит могли поднять
        // два слабых признака, а противник мог остыть. С экрана такая запись
        // исчезнуть не должна.
        val queue = listOf(sticker(1L, hidden = true))

        val clusters = clusterDisputes(queue, listOf(report()))

        assertEquals(1, clusters.size)
        assertEquals(listOf(1L), clusters[0].queueMembers.map { it.id })
        assertTrue("убирать нечего: видимой стороны нет", clusters[0].visibleOpponents.isEmpty())
        assertEquals("проверка состоялась", 0, clusters[0].failedMembers)
    }

    @Test
    fun `запись с несостоявшейся проверкой попадает в дело через отчёт соседа`() {
        // У неё самой рёбер нет: отчёт пуст, и молчание это не значит ничего.
        // Правило противоречия симметрично, поэтому связь видна со стороны
        // соседа — иначе такая запись выпала бы из своего же дела.
        val queue = listOf(sticker(1L, hidden = true), sticker(2L, hidden = true))
        val reports = listOf(
            report(failed = true),
            report(hidden = listOf(queue[0])),
        )

        val clusters = clusterDisputes(queue, reports)

        assertEquals("дело одно", 1, clusters.size)
        assertEquals(listOf(1L, 2L), clusters[0].queueMembers.map { it.id })
        assertEquals(
            "неполнота дела обязана быть названа числом, а не молчанием",
            1, clusters[0].failedMembers
        )
    }

    @Test
    fun `скрытый противник вне показанной части очереди считается, но в дело не входит`() {
        // Список обрезан потолком. О такой записи известно только то, что она
        // существует: своего отчёта у неё нет, её собственные споры неизвестны.
        val beyondLimit = sticker(50L, hidden = true)
        val queue = listOf(sticker(1L, hidden = true))

        val clusters = clusterDisputes(queue, listOf(report(hidden = listOf(beyondLimit))))

        assertEquals(1, clusters.size)
        assertEquals(
            "членом дела она не становится — разбирать её здесь нечем",
            listOf(1L), clusters[0].queueMembers.map { it.id }
        )
        assertEquals(
            "но и промолчать о ней нельзя: дело показано не целиком",
            1, clusters[0].hiddenOutsideList
        )
    }

    @Test
    fun `пустая очередь даёт пустой список, а не дело из ничего`() {
        assertTrue(clusterDisputes(emptyList(), emptyList()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `списки разной длины отвергаются сразу`() {
        // Отчёт под тем же номером, что и запись, — единственное, на чём держится
        // сборка. Разъехавшись, списки дали бы правдоподобные, но чужие дела.
        clusterDisputes(listOf(sticker(1L, hidden = true)), emptyList())
    }
}
