package com.uroboros.move

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MoveSessionTest {

    private val code = "4821773106"
    private val fast = 1000

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private val files = linkedMapOf(
        MoveManifest.Entry(MoveManifest.Kind.DB, "", "uroboros_memory.db", 0) to bytes(70_000, 1),
        MoveManifest.Entry(MoveManifest.Kind.PREFS, "", "prefs", 0) to bytes(300, 2),
        MoveManifest.Entry(MoveManifest.Kind.TOKEN, "", "token", 0) to "123:abc".toByteArray(),
        MoveManifest.Entry(MoveManifest.Kind.MODEL, "", "falcon.gguf", 0) to bytes(Move.CHUNK * 2 + 17, 3),
        MoveManifest.Entry(MoveManifest.Kind.MODEL, "ночь", "night.gguf", 0) to bytes(5000, 4),
    ).entries.associate { (e, b) -> e.copy(size = b.size.toLong()) to b }

    private fun manifest(session: String = "s1", committed: Boolean = false) = MoveManifest(
        session, committed, listOf(MoveManifest.Db("uroboros_memory.db", "память", 33)),
        mapOf("uroboros_memory.db/stickers" to 804L), files.keys.toList(), "falcon.gguf",
    )

    private class Source(
        override val manifest: MoveManifest,
        val content: Map<MoveManifest.Entry, ByteArray>,
        val failCommit: Boolean = false,
        val short: Boolean = false,
    ) : MoveSource {
        val commits = mutableListOf<String>()
        override fun open(entry: MoveManifest.Entry): InputStream {
            val b = content.getValue(entry)
            return ByteArrayInputStream(if (short) b.copyOf(b.size - 1) else b)
        }
        override fun commit(session: String) {
            if (failCommit) throw IOException("диск")
            commits += session
        }
    }

    private class Sink(
        val refuse: String? = null,
        val skipKeys: Set<String> = emptySet(),
        val completeSession: String? = null,
    ) : MoveSink {
        val written = LinkedHashMap<String, ByteArrayOutputStream>()
        var begun = false
        var completed: String? = null
        var readySession: String? = null
        var aborted = false
        override fun refusal(m: MoveManifest) = refuse
        override fun skip(m: MoveManifest) = skipKeys
        override fun hasComplete(session: String) = session == completeSession
        override fun begin(m: MoveManifest) { begun = true }
        override fun create(entry: MoveManifest.Entry): OutputStream = ByteArrayOutputStream().also { written[entry.key] = it }
        override fun complete(m: MoveManifest) { completed = m.session }
        override fun ready(session: String) { readySession = session }
        override fun abort() { aborted = true }
    }

    /** Два конца трубы; [tamper] портит поток от старого к новому. */
    private fun run(
        source: Source,
        sink: Sink,
        sendCode: String = code,
        tamper: ((ByteArray, Int) -> Unit)? = null,
    ): Pair<MoveOutcome, MoveOutcome> {
        val sToR = PipedInputStream(1 shl 20)
        val sOut = PipedOutputStream(sToR)
        val rToS = PipedInputStream(1 shl 20)
        val rOut = PipedOutputStream(rToS)
        val rIn: InputStream = if (tamper == null) sToR else object : InputStream() {
            var pos = 0
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = sToR.read(b, off, len)
                if (n > 0) {
                    val chunk = b.copyOfRange(off, off + n)
                    tamper(chunk, pos)
                    chunk.copyInto(b, off)
                    pos += n
                }
                return n
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val s = pool.submit(Callable {
            try { MoveSession.send(rToS, sOut, sendCode, source, fast) } finally { sOut.close() }
        })
        val r = pool.submit(Callable {
            try { MoveSession.receive(rIn, rOut, code, sink, fast) } finally { rOut.close(); sToR.close() }
        })
        val out = s.get(30, TimeUnit.SECONDS) to r.get(30, TimeUnit.SECONDS)
        pool.shutdownNow()
        return out
    }

    @Test
    fun `переезд целиком — все файлы байт в байт, «принял», затем «готово»`() {
        val source = Source(manifest(), files)
        val sink = Sink()
        val (s, r) = run(source, sink)
        assertTrue(s.line, s is MoveOutcome.Done)
        assertTrue(r.line, r is MoveOutcome.Done)
        assertEquals(listOf("s1"), source.commits)
        assertEquals("s1", sink.completed)
        assertEquals("s1", sink.readySession)
        assertFalse(sink.aborted)
        for ((e, b) in files) assertArrayEquals(e.name, b, sink.written.getValue(e.key).toByteArray())
    }

    @Test
    fun `неверный код — ни описи, ни замирания, ни приёма`() {
        val source = Source(manifest(), files)
        val sink = Sink()
        val (s, r) = run(source, sink, sendCode = "0000000000")
        assertTrue(s.line, s is MoveOutcome.WrongCode)
        assertTrue(r.line, r is MoveOutcome.WrongCode)
        assertTrue(source.commits.isEmpty())
        assertFalse(sink.begun)
    }

    @Test
    fun `отказ нового — причина доходит до старого, старый не замирает`() {
        val source = Source(manifest(), files)
        val sink = Sink(refuse = "здесь уже есть память (3 строк)")
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Failed && s.line.contains("здесь уже есть память"))
        assertTrue(r is MoveOutcome.Failed)
        assertTrue(source.commits.isEmpty())
        assertFalse(sink.begun)
    }

    @Test
    fun `модель, что уже лежит, не едет`() {
        val model = files.keys.first { it.name == "falcon.gguf" }
        val source = Source(manifest(), files)
        val sink = Sink(skipKeys = setOf(model.key))
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Done && r is MoveOutcome.Done)
        assertFalse(sink.written.containsKey(model.key))
        assertEquals(files.size - 1, sink.written.size)
    }

    @Test
    fun `файл короче описи — передача рвётся, недопринятое убрано, старый не замер`() {
        val source = Source(manifest(), files, short = true)
        val sink = Sink()
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Failed)
        assertTrue(r is MoveOutcome.Failed)
        assertTrue(source.commits.isEmpty())
        assertTrue(sink.aborted)
        assertEquals(null, sink.readySession)
    }

    @Test
    fun `подмена байта в канале — сообщение не открывается, приём убран, замирания нет`() {
        val source = Source(manifest(), files)
        val sink = Sink()
        // Байт далеко за знакомством — внутри куска модели.
        val (s, r) = run(source, sink, tamper = { chunk, pos ->
            val at = 200_000 - pos
            if (at in chunk.indices) chunk[at] = (chunk[at].toInt() xor 1).toByte()
        })
        assertTrue(r.line, r is MoveOutcome.Failed)
        assertTrue(s is MoveOutcome.Failed)
        assertTrue(sink.aborted)
        assertTrue(source.commits.isEmpty())
    }

    @Test
    fun `«готово» не дошло — у нового пакет остаётся целым, не применяется`() {
        val source = Source(manifest(), files, failCommit = true)
        val sink = Sink()
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Failed)
        assertTrue(r is MoveOutcome.Failed && r.line.contains("повтори"))
        assertEquals("s1", sink.completed)
        assertFalse(sink.aborted)
        assertEquals(null, sink.readySession)
    }

    @Test
    fun `повтор с замершего старого — пакет уже лежит, едет только «готово»`() {
        val source = Source(manifest(committed = true), files)
        val sink = Sink(completeSession = "s1")
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Done && r is MoveOutcome.Done)
        assertTrue(sink.written.isEmpty())
        assertFalse(sink.begun)
        assertEquals("s1", sink.readySession)
        assertEquals(listOf("s1"), source.commits)
    }

    @Test
    fun `повтор с замершего старого без пакета у нового — едет весь пакет заново`() {
        val source = Source(manifest(committed = true), files)
        val sink = Sink(completeSession = null)
        val (s, r) = run(source, sink)
        assertTrue(s is MoveOutcome.Done && r is MoveOutcome.Done)
        assertEquals(files.size, sink.written.size)
    }
}
