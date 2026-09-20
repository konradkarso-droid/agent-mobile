package com.uroboros.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Догоняющее остывание: слой записи зависит от времени, а не от того, как часто
 * звали уборку. Почему срок отсчитывается от старого срока — в KDoc
 * Prism.catchUp.
 *
 * Три части. Правила (Prism.catchUp) проверяются как чистая функция. Их
 * применение (HourglassMemory.migrateExpiredAt) — через подставной DAO: что на
 * каждую просроченную запись уходит ровно одна запись слоя с результатом правил
 * и что больше ничего не трогается. И читатели слоя — что уборка идёт у них
 * раньше чтения.
 *
 * ГЛАВНЫЕ ЗДЕСЬ — ТРИ ПРОВЕРКИ:
 *  - `один вызов после паузы равен частым вызовам`: закрепляет, что пауза в
 *    вызовах не удлиняет жизнь записи. «Починка» срока обратно на отсчёт от
 *    момента вызова ломает её заметно;
 *  - `срок следующего слоя считается от старого срока`: то же на одной ступени;
 *  - проверка на молчание в применении: уборка не создаёт записей, не
 *    засчитывает обращений и не трогает бит спора.
 *
 * ЧЕГО ЭТОТ ФАЙЛ НЕ ДОКАЗЫВАЕТ:
 *  - что настоящий StickerDao.getExpired отбирает именно просроченные строки,
 *    а updateLayer пишет только слой и срок. Подделка отвечает то, что положил
 *    тест; SQL проверяется на устройстве снимком памяти до и после;
 *  - что уборку зовёт ночной судья: JudgeRun держит движок модели и здесь
 *    не собирается. Зовут ли её сохранение и показ спора — проверено
 *    ниже; появится новый читатель слоя — проверка ему добавляется сюда же.
 */
class CatchUpCoolingTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    // Точка отсчёта взята круглой и далёкой от нуля: срок — абсолютный момент,
    // и ноль здесь маскировал бы ошибку «отсчёт от нуля вместо старого срока».
    private val t0 = 1_000_000L * day

    // --- Правила: Prism.catchUp ---

    @Test
    fun `срок не наступил — запись остаётся где была`() {
        val (layer, expiry) = Prism.catchUp(Layer.YELLOW, t0 + hour, now = t0)
        assertEquals(Layer.YELLOW, layer)
        assertEquals(t0 + hour, expiry)
    }

    @Test
    fun `срок ровно сейчас — уже истёк, как в отборе базы`() {
        val (layer, _) = Prism.catchUp(Layer.ORANGE, t0, now = t0)
        assertEquals(
            "граница та же, что у getExpired (<=): иначе запись отбиралась бы и не двигалась",
            Layer.YELLOW, layer
        )
    }

    @Test
    fun `срок следующего слоя считается от старого срока`() {
        val (layer, expiry) = Prism.catchUp(Layer.ORANGE, t0, now = t0 + hour)
        assertEquals(Layer.YELLOW, layer)
        assertEquals(
            "выход из YELLOW — через 7 дней после выхода из ORANGE, а не после вызова",
            t0 + 7 * day, expiry
        )
    }

    @Test
    fun `после 40 дней паузы запись проходит несколько ступеней`() {
        // ORANGE вышел в t0, YELLOW в t0+7д, GREEN в t0+37д, BLUE — через год после.
        val (layer, expiry) = Prism.catchUp(Layer.ORANGE, t0, now = t0 + 40 * day)
        assertEquals(Layer.BLUE, layer)
        assertEquals(t0 + 37 * day + 365 * day, expiry)
    }

    @Test
    fun `один вызов после паузы равен частым вызовам`() {
        val end = t0 + 40 * day
        var layer = Layer.ORANGE
        var expiry: Long? = t0
        var now = t0
        while (now <= end) {
            val step = Prism.catchUp(layer, expiry, now)
            layer = step.first
            expiry = step.second
            now += hour
        }
        assertEquals(
            "частота вызовов не должна менять итог",
            Prism.catchUp(Layer.ORANGE, t0, end), layer to expiry
        )
    }

    @Test
    fun `очень долгая пауза кончается в PURPLE без срока, а не бесконечным циклом`() {
        val (layer, expiry) = Prism.catchUp(Layer.ORANGE, t0, now = t0 + 10 * 365 * day)
        assertEquals(Layer.PURPLE, layer)
        assertNull("в конце спектра срока нет — запись лежит, а не удаляется", expiry)
    }

    @Test
    fun `записи без срока не двигаются`() {
        for (layer in listOf(Layer.RED, Layer.PURPLE)) {
            val result = Prism.catchUp(layer, null, now = t0 + 10 * 365 * day)
            assertEquals("$layer без срока остаётся на месте", layer to null, result)
        }
    }

    // --- Применение: HourglassMemory.migrateExpiredAt ---

    @Test
    fun `на каждую просроченную запись одна запись слоя с итогом правил`() = runBlocking {
        val now = t0 + 40 * day
        val a = Sticker(id = 1, content = "а", layer = Layer.ORANGE.name, expiryTime = t0)
        val b = Sticker(id = 2, content = "б", layer = Layer.GREEN.name, expiryTime = now - hour)
        val dao = FakeStickerDao().apply { onGetExpired = { listOf(a, b) } }

        HourglassMemory(dao).migrateExpiredAt(now)

        val (aLayer, aExpiry) = Prism.catchUp(Layer.ORANGE, t0, now)
        val (bLayer, bExpiry) = Prism.catchUp(Layer.GREEN, now - hour, now)
        assertEquals(
            listOf(
                FakeStickerDao.LayerUpdate(1, aLayer.name, aExpiry),
                FakeStickerDao.LayerUpdate(2, bLayer.name, bExpiry)
            ),
            dao.layerUpdates
        )
    }

    @Test
    fun `уборка ничего, кроме слоя и срока, не трогает`() = runBlocking {
        val sticker = Sticker(id = 5, content = "запись", layer = Layer.YELLOW.name, expiryTime = t0)
        val dao = FakeStickerDao().apply { onGetExpired = { listOf(sticker) } }

        HourglassMemory(dao).migrateExpiredAt(t0 + day)

        // Остальные запросы подделка выполняет молча, а незаготовленные падают с
        // именем метода — поэтому лишнее чтение тоже уронило бы тест само.
        assertTrue("новых записей уборка не создаёт", dao.inserted.isEmpty())
        assertTrue("остывание — не обращение", dao.touchedAccess.isEmpty())
        assertTrue("отметка пользы не ставится", dao.touchedUserMatch.isEmpty())
        assertTrue("бит спора уборка не трогает", dao.reviewPendingSet.isEmpty())
    }

    @Test
    fun `нечего убирать — ничего не пишется`() = runBlocking {
        val dao = FakeStickerDao().apply { onGetExpired = { emptyList() } }
        HourglassMemory(dao).migrateExpiredAt(t0)
        assertTrue(dao.layerUpdates.isEmpty())
    }

    // --- Читатели слоя убирают перед чтением ---
    //
    // Проверяется порядок запросов, а не только их наличие: уборка после чтения
    // пула выглядела бы так же, как её отсутствие.

    private fun readerDao(calls: MutableList<String>) = FakeStickerDao().apply {
        onInsert = { 42L }
        onGetExpired = { calls += "уборка"; emptyList() }
        onGetByTagInLayers = { _, _ -> calls += "пул"; emptyList() }
    }

    @Test
    fun `сохранение убирает до того, как берёт горячий пул`() = runBlocking {
        val calls = mutableListOf<String>()
        HourglassMemory(readerDao(calls)).saveEventChecked(Sticker(content = "у паука восемь ног"))
        assertEquals(listOf("уборка", "пул"), calls)
    }

    @Test
    fun `сбой уборки при сохранении — сбой пула, запись уходит в очередь`() = runBlocking {
        val dao = FakeStickerDao().apply {
            onInsert = { 42L }
            onGetExpired = { throw IllegalStateException("база недоступна") }
            onGetByTagInLayers = { _, _ -> emptyList() }
        }
        HourglassMemory(dao).saveEventChecked(Sticker(content = "у паука восемь ног"))
        assertTrue(
            "пул, снятый без уборки, — не тот пул; молча судить по нему нельзя",
            dao.inserted.single().reviewPending
        )
    }

    @Test
    fun `показ спора убирает до того, как делит противников по слою`() = runBlocking {
        val calls = mutableListOf<String>()
        HourglassMemory(readerDao(calls)).disputesOf(Sticker(id = 3, content = "у паука восемь ног"))
        assertEquals("уборка", calls.first())
        assertTrue("дальше идёт чтение по слоям", calls.drop(1).all { it == "пул" })
    }
}
