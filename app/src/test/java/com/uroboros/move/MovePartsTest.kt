package com.uroboros.move

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MovePartsTest {

    // --- Код и адрес ---

    @Test
    fun `код — десять цифр, пробелы и дефисы при вводе не важны`() {
        val c = MoveCode.newCode()
        assertEquals(10, c.length)
        assertEquals(c, MoveCode.parse(MoveCode.format(c)))
        assertEquals("4821773106", MoveCode.parse(" 4821-7731 06 "))
        assertNull(MoveCode.parse("482177310"))
        assertNull(MoveCode.parse("48217731О6"))
        assertEquals("4821 7731 06", MoveCode.format("4821773106"))
    }

    @Test
    fun `адрес — только частная сеть`() {
        assertEquals("192.168.43.23", MoveCode.parseAddress(" 192.168.43.23 "))
        assertEquals("10.0.0.5", MoveCode.parseAddress("10.0.0.5"))
        assertEquals("172.20.1.1", MoveCode.parseAddress("172.20.1.1"))
        assertNull(MoveCode.parseAddress("8.8.8.8"))
        assertNull(MoveCode.parseAddress("192.168.43"))
        assertNull(MoveCode.parseAddress("192.168.43.256"))
        assertNull(MoveCode.parseAddress("192.168..1"))
        assertEquals("192.168.43.", MoveCode.prefixOf("192.168.43.1"))
    }

    // --- Ключи ---

    @Test
    fun `ключи сходятся у обеих сторон и расходятся при другом коде`() {
        val shared = ByteArray(32) { it.toByte() }
        val s = ByteArray(65) { 1 }
        val r = ByteArray(65) { 2 }
        val a = MoveKeys.derive(shared, "1234567890", s, r, 1000)
        val b = MoveKeys.derive(shared, "1234567890", s, r, 1000)
        val c = MoveKeys.derive(shared, "1234567891", s, r, 1000)
        assertArrayEquals(a.toReceiver, b.toReceiver)
        assertArrayEquals(a.toSender, b.toSender)
        assertFalse(a.toReceiver.contentEquals(a.toSender))
        assertFalse(a.toReceiver.contentEquals(c.toReceiver))
    }

    @Test
    fun `закрытые сообщения открываются только по порядку`() {
        val key = ByteArray(32) { 7 }
        val out = MoveSeal(key)
        val one = out.seal("раз".toByteArray())
        val two = out.seal("два".toByteArray())
        val inOrder = MoveSeal(key)
        assertEquals("раз", String(inOrder.open(one)!!))
        assertEquals("два", String(inOrder.open(two)!!))
        val swapped = MoveSeal(key)
        assertNull(swapped.open(two))
    }

    // --- Опись ---

    @Test
    fun `опись — туда и обратно, мусор не читается`() {
        val m = MoveManifest(
            "s", true, listOf(MoveManifest.Db("a.db", "память", 33)), mapOf("a.db/t" to 5L),
            listOf(MoveManifest.Entry(MoveManifest.Kind.MODEL, "ночь", "n.gguf", 42)), "m.gguf",
        )
        assertEquals(m, MoveManifest.decode(m.encode()))
        assertNull(MoveManifest.decode(byteArrayOf(1, 2, 3)))
        assertNull(MoveManifest.decode(m.encode() + 0))
    }

    // --- Настройки ---

    @Test
    fun `настройки — все типы туда и обратно`() {
        val files: MovePrefsFiles = mapOf(
            "uroboros_chat" to mapOf("owner_key" to "tg:user:1", "n" to 3, "at" to 5L, "f" to 1.5f, "b" to true, "names" to setOf("Саша", "Alex")),
            "empty" to emptyMap(),
        )
        assertEquals(files, MovePrefs.decode(MovePrefs.encode(files)))
        assertNull(MovePrefs.decode(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun `привязанное к телефону не едет, остальное едет целиком`() {
        val files: MovePrefsFiles = mapOf(
            "uroboros_prefs" to mapOf("model_folder_uri" to "content://a", "last_model_uri" to "content://b", "layer_repair_done" to true),
            "uroboros_memory_pressure" to mapOf("x" to 1),
            "agent_life" to mapOf("starts" to 500),
        )
        val p = MovePrefs.portable(files, setOf("uroboros_memory_pressure"), mapOf("uroboros_prefs" to setOf("model_folder_uri", "last_model_uri")))
        assertEquals(setOf("uroboros_prefs", "agent_life"), p.keys)
        assertEquals(mapOf("layer_repair_done" to true), p["uroboros_prefs"])
        assertEquals(mapOf("starts" to 500), p["agent_life"])
    }

    // --- Решения нового ---

    private val model = MoveManifest.Entry(MoveManifest.Kind.MODEL, "", "falcon.gguf", 4_000_000_000L)
    private val m = MoveManifest(
        "s", false, listOf(MoveManifest.Db("uroboros_memory.db", "память", 33)), emptyMap(),
        listOf(MoveManifest.Entry(MoveManifest.Kind.DB, "", "uroboros_memory.db", 3_000_000), model), "falcon.gguf",
    )
    private val clean = MoveCheck.Here(mapOf("uroboros_memory.db" to 33), 0, false, true, 100L * 1024 * 1024 * 1024, emptyMap())

    @Test
    fun `чистая установка той же сборки принимает`() {
        assertNull(MoveCheck.refusal(m, clean))
        assertNull(MoveCheck.refusal(m, clean.copy(versions = mapOf("uroboros_memory.db" to 34))))
    }

    @Test
    fun `отказы нового телефона`() {
        assertTrue(MoveCheck.refusal(m, clean.copy(memoryRows = 3))!!.contains("уже есть память"))
        assertTrue(MoveCheck.refusal(m, clean.copy(tokenHere = true))!!.contains("токен"))
        assertTrue(MoveCheck.refusal(m, clean.copy(versions = mapOf("uroboros_memory.db" to 32)))!!.contains("v33 > v32"))
        assertTrue(MoveCheck.refusal(m, clean.copy(versions = emptyMap()))!!.contains("не знакомы"))
        assertTrue(MoveCheck.refusal(m, clean.copy(modelFolderChosen = false))!!.contains("папка моделей"))
        assertTrue(MoveCheck.refusal(m, clean.copy(freeBytes = 1_000_000_000L))!!.contains("не хватит места"))
    }

    @Test
    fun `модель того же имени и размера не едет — и папка для неё не нужна`() {
        val here = clean.copy(modelsHere = mapOf(model.key to model.size), modelFolderChosen = false, freeBytes = 1_000_000_000L)
        assertEquals(setOf(model.key), MoveCheck.skip(m, here))
        assertNull(MoveCheck.refusal(m, here))
        assertEquals(emptySet<String>(), MoveCheck.skip(m, clean.copy(modelsHere = mapOf(model.key to 1L))))
    }

    @Test
    fun `сверка строк — сошлось только при совпадении всех таблиц`() {
        val e = mapOf("a/t" to 804L, "b/u" to 43L)
        assertTrue(MoveCheck.countsLine(e, e).endsWith("— сошлось"))
        val bad = MoveCheck.countsLine(e, mapOf("a/t" to 803L, "b/u" to 43L))
        assertTrue(bad, bad.startsWith("строки не сошлись") && bad.contains("a/t 804 → 803"))
    }

    // --- Применение ---

    private fun tmp(): File = Files.createTempDirectory("move").toFile()

    private fun staged(dir: File, session: String = "s1", ready: String? = "s1"): MoveManifest {
        val mf = MoveManifest(session, false, listOf(MoveManifest.Db("m.db", "память", 33)), mapOf("m.db/stickers" to 2L), emptyList(), "")
        File(dir, MoveApply.DB_DIR).mkdirs()
        File(File(dir, MoveApply.DB_DIR), "m.db").writeText("новая")
        File(File(dir, MoveApply.DB_DIR), "m.db-wal").writeText("новый журнал")
        File(dir, MoveApply.MANIFEST).writeBytes(mf.encode())
        File(dir, MoveApply.PREFS).writeBytes(MovePrefs.encode(mapOf("agent_life" to mapOf("starts" to 9))))
        File(dir, MoveApply.TOKEN).writeText("закрыт")
        File(dir, MoveApply.LAST_MODEL_URI).writeText("content://f/falcon.gguf")
        File(dir, MoveApply.COMPLETE).writeText(session)
        ready?.let { File(dir, MoveApply.READY).writeText(it) }
        return mf
    }

    private class Hands(val dbDir: File, val rows: Long = 2) : MoveApply.Hands {
        var prefs: MovePrefsFiles? = null
        var lastModel: String? = null
        var token: String? = null
        override fun dbFile(fileName: String) = File(dbDir, fileName)
        override fun writePrefs(files: MovePrefsFiles, lastModelUri: String?) { prefs = files; lastModel = lastModelUri }
        override fun installToken(sealed: File) { token = sealed.readText() }
        override fun count(fileName: String) = mapOf("$fileName/stickers" to rows)
    }

    @Test
    fun `применение — базы на месте, чужие журналы убраны, настройки и токен, сверка, папка приёма стёрта`() {
        val staging = tmp()
        val dbs = tmp()
        staged(staging)
        File(dbs, "m.db").writeText("старая пустая")
        File(dbs, "m.db-shm").writeText("чужой индекс")
        val hands = Hands(dbs)
        val r = MoveApply.apply(staging, hands)
        assertTrue(r.toString(), r is MoveApply.Result.Applied && (r as MoveApply.Result.Applied).line.endsWith("сошлось"))
        assertEquals("новая", File(dbs, "m.db").readText())
        assertEquals("новый журнал", File(dbs, "m.db-wal").readText())
        assertFalse(File(dbs, "m.db-shm").exists())
        assertEquals(mapOf("agent_life" to mapOf("starts" to 9)), hands.prefs)
        assertEquals("content://f/falcon.gguf", hands.lastModel)
        assertEquals("закрыт", hands.token)
        assertFalse(staging.exists())
    }

    @Test
    fun `без «готово» — не применяется ничего`() {
        val staging = tmp()
        val dbs = tmp()
        staged(staging, ready = null)
        File(dbs, "m.db").writeText("старая")
        assertEquals(MoveApply.Result.None, MoveApply.apply(staging, Hands(dbs)))
        assertEquals("старая", File(dbs, "m.db").readText())
        assertTrue(staging.exists())
    }

    @Test
    fun `«готово» другой сессии — не применяется, пакет остаётся`() {
        val staging = tmp()
        val dbs = tmp()
        staged(staging, session = "s2", ready = "s1")
        File(dbs, "m.db").writeText("старая")
        val r = MoveApply.apply(staging, Hands(dbs))
        assertTrue(r is MoveApply.Result.Failed)
        assertEquals("старая", File(dbs, "m.db").readText())
        assertNotNull(MoveApply.completeSession(staging))
    }

    @Test
    fun `строки не сошлись — применено, но строка говорит об этом`() {
        val staging = tmp()
        staged(staging)
        val r = MoveApply.apply(staging, Hands(tmp(), rows = 1))
        assertTrue(r is MoveApply.Result.Applied && (r as MoveApply.Result.Applied).line.startsWith("строки не сошлись"))
    }
}
