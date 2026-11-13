package com.uroboros.move

import com.uroboros.gate.GateBox
import com.uroboros.gate.GateSign
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/** Что отдаёт старый телефон. Снимок уже снят: [manifest] описывает его. */
interface MoveSource {
    val manifest: MoveManifest
    fun open(entry: MoveManifest.Entry): InputStream

    /** Замереть насовсем и спрятать токен (шаг 3 у [Move]). Повторный вызов — ничего. */
    fun commit(session: String)
}

/** Куда принимает новый телефон. */
interface MoveSink {
    /** Почему не принимать; null — принимать (решает [MoveCheck]). */
    fun refusal(m: MoveManifest): String?

    /** Ключи файлов описи, которые уже лежат здесь и не нужны. */
    fun skip(m: MoveManifest): Set<String>

    /** Пакет этой сессии принят целиком раньше, а «готово» не дошло. */
    fun hasComplete(session: String): Boolean

    /** Начать приём: прежнее недопринятое убрать. */
    fun begin(m: MoveManifest)
    fun create(entry: MoveManifest.Entry): OutputStream

    /** Всё сверено — пакет лежит целиком. */
    fun complete(m: MoveManifest)

    /** «Готово» пришло — применить при следующем запуске процесса. */
    fun ready(session: String)

    /** Убрать недопринятое: передача сорвалась до [complete]. */
    fun abort()
}

data class MoveProgress(val done: Long, val total: Long, val name: String)

sealed class MoveOutcome {
    abstract val line: String

    data class Done(override val line: String) : MoveOutcome()

    /** Код не подошёл — у приёма это попытка из [Move.MAX_TRIES]. */
    data class WrongCode(override val line: String) : MoveOutcome()
    data class Failed(override val line: String) : MoveOutcome()
}

/**
 * Разговор двух телефонов при переезде: знакомство, опись, файлы, «принял»,
 * «готово». Порядок и зачем он такой — у [Move].
 *
 * Обе стороны — над потоками байт: сеть даёт их сокет, проверка — трубу в
 * памяти. Сеть, базы и файлы — за [MoveSource] и [MoveSink].
 *
 * ЧЕГО НЕ УМЕЕТ: докачки нет — обрыв значит всё сначала; размер файла задан
 * описью, и файл, изменившийся после снимка, рвёт передачу.
 *
 * Чистый объект: ни сети, ни Android.
 */
object MoveSession {

    private const val HELLO = 1
    private const val MANIFEST = 2
    private const val ANSWER = 3
    private const val HEAD = 4
    private const val DATA = 5
    private const val END = 6
    private const val DONE = 7
    private const val RECEIVED = 8
    private const val COMMITTED = 9

    private const val OK = 0
    private const val REFUSE = 1
    private const val HAVE = 2

    private const val RAW_MAX = 512
    private const val SEALED_MAX = Move.CHUNK + 1024

    /** Сбой разговора словами для строки хода. */
    private class Broken(msg: String) : Exception(msg)

    private class Wire(input: InputStream, output: OutputStream) {
        private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
        private val dout = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
        lateinit var outSeal: MoveSeal
        lateinit var inSeal: MoveSeal

        fun writeRaw(b: ByteArray) {
            dout.writeInt(b.size)
            dout.write(b)
            dout.flush()
        }

        fun readRaw(max: Int): ByteArray {
            val n = din.readInt()
            if (n < 0 || n > max) throw Broken("сообщение не той длины ($n)")
            return ByteArray(n).also { din.readFully(it) }
        }

        fun send(type: Int, body: ByteArray = ByteArray(0), flush: Boolean = true) {
            val sealed = outSeal.seal(byteArrayOf(type.toByte()) + body)
            dout.writeInt(sealed.size)
            dout.write(sealed)
            if (flush) dout.flush()
        }

        /** null — не открылось (не тот ключ или подмена). */
        fun receiveOrNull(): Pair<Int, ByteArray>? {
            val plain = inSeal.open(readRaw(SEALED_MAX)) ?: return null
            if (plain.isEmpty()) throw Broken("пустое сообщение")
            return plain[0].toInt() to plain.copyOfRange(1, plain.size)
        }

        fun receive(): Pair<Int, ByteArray> = receiveOrNull() ?: throw Broken("сообщение не открылось — подмена или порча в канале")

        fun expect(type: Int, what: String): ByteArray {
            val (t, body) = receive()
            if (t != type) throw Broken("ждал «$what», пришло другое ($t)")
            return body
        }
    }

    private fun point(k: ECPublicKey): ByteArray = GateSign.unb64(GateSign.encodeKey(k))!!

    private fun keyOf(raw: ByteArray): ECPublicKey? =
        if (raw.size == 65) GateSign.decodeKey(GateSign.b64(raw)) else null

    private fun magicTail(h: ByteArray): ByteArray? =
        if (h.size > Move.MAGIC.size && h.copyOfRange(0, Move.MAGIC.size).contentEquals(Move.MAGIC)) h.copyOfRange(Move.MAGIC.size, h.size) else null

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()

    private fun reading(b: ByteArray) = DataInputStream(ByteArrayInputStream(b))

    // --- Старый телефон ---

    fun send(
        input: InputStream,
        output: OutputStream,
        code: String,
        source: MoveSource,
        iterations: Int = Move.ITERATIONS,
        progress: (MoveProgress) -> Unit = {},
    ): MoveOutcome {
        val m = source.manifest
        var committed = false
        val wire = Wire(input, output)
        return try {
            val mine = GateBox.newPair()
            val encS = point(mine.public as ECPublicKey)
            wire.writeRaw(Move.MAGIC + encS)
            val encR = magicTail(wire.readRaw(RAW_MAX)) ?: throw Broken("на этом адресе не приём агента")
            val theirs = keyOf(encR) ?: throw Broken("ключ нового телефона не точка кривой")
            val keys = MoveKeys.derive(GateBox.dh(mine.private as ECPrivateKey, theirs), code, encS, encR, iterations)
            wire.outSeal = MoveSeal(keys.toReceiver)
            wire.inSeal = MoveSeal(keys.toSender)
            wire.send(HELLO)
            val hello = try {
                wire.receiveOrNull()
            } catch (e: EOFException) {
                null
            }
            if (hello == null || hello.first != HELLO) {
                return MoveOutcome.WrongCode("Переезд: новый телефон не принял код — проверь код и адрес")
            }

            wire.send(MANIFEST, m.encode())
            val answer = reading(wire.expect(ANSWER, "ответ на опись"))
            val status = answer.readByte().toInt()
            val reason = answer.readUTF()
            val skip = List(answer.readInt()) { answer.readUTF() }.toSet()
            when (status) {
                REFUSE -> return MoveOutcome.Failed("Переезд: новый телефон отказал — $reason")
                HAVE -> {
                    source.commit(m.session)
                    committed = true
                    wire.send(COMMITTED)
                    return MoveOutcome.Done("Переезд: пакет уже лежал на новом целиком — отправил «готово»")
                }
                OK -> Unit
                else -> throw Broken("непонятный ответ на опись")
            }

            val toSend = m.entries.withIndex().filter { it.value.key !in skip }
            val total = toSend.sumOf { it.value.size }
            var done = 0L
            val buf = ByteArray(Move.CHUNK)
            for ((index, entry) in toSend) {
                wire.send(HEAD, bytes { writeInt(index) }, flush = false)
                val digest = MessageDigest.getInstance("SHA-256")
                var left = entry.size
                source.open(entry).use { inp ->
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw Broken("«${entry.name}» короче описи")
                        digest.update(buf, 0, n)
                        wire.send(DATA, buf.copyOf(n), flush = false)
                        left -= n
                        done += n
                        progress(MoveProgress(done, total, entry.name))
                    }
                    if (inp.read() >= 0) throw Broken("«${entry.name}» длиннее описи")
                }
                wire.send(END, digest.digest())
            }
            wire.send(DONE)

            val received = reading(wire.expect(RECEIVED, "принял"))
            if (!received.readBoolean()) return MoveOutcome.Failed("Переезд: новый телефон не сошёлся — ${received.readUTF()}")
            source.commit(m.session)
            committed = true
            wire.send(COMMITTED)
            MoveOutcome.Done("Переезд: новый телефон принял всё и получил «готово»; здесь агент замер")
        } catch (e: Broken) {
            failedSending(committed, e.message.orEmpty())
        } catch (e: IOException) {
            failedSending(committed, "связь оборвалась (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            failedSending(committed, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
    }

    private fun failedSending(committed: Boolean, why: String) = MoveOutcome.Failed(
        if (committed) "Переезд: здесь агент уже замер, но «готово» до нового не дошло ($why) — повтори «Переехать»"
        else "Переезд сорвался: $why",
    )

    // --- Новый телефон ---

    fun receive(
        input: InputStream,
        output: OutputStream,
        code: String,
        sink: MoveSink,
        iterations: Int = Move.ITERATIONS,
        progress: (MoveProgress) -> Unit = {},
    ): MoveOutcome {
        val wire = Wire(input, output)
        var begun = false
        var complete = false
        return try {
            val encS = magicTail(wire.readRaw(RAW_MAX))
                ?: return MoveOutcome.WrongCode("Приём: подключился не агент")
            val theirs = keyOf(encS) ?: return MoveOutcome.WrongCode("Приём: ключ старого телефона не точка кривой")
            val mine = GateBox.newPair()
            val encR = point(mine.public as ECPublicKey)
            wire.writeRaw(Move.MAGIC + encR)
            val keys = MoveKeys.derive(GateBox.dh(mine.private as ECPrivateKey, theirs), code, encS, encR, iterations)
            wire.inSeal = MoveSeal(keys.toReceiver)
            wire.outSeal = MoveSeal(keys.toSender)
            val hello = wire.receiveOrNull()
            if (hello == null || hello.first != HELLO) return MoveOutcome.WrongCode("Приём: код не подошёл")
            wire.send(HELLO)

            val m = MoveManifest.decode(wire.expect(MANIFEST, "опись")) ?: throw Broken("опись не читается")
            if (m.committed && sink.hasComplete(m.session)) {
                wire.send(ANSWER, bytes { writeByte(HAVE); writeUTF(""); writeInt(0) })
                wire.expect(COMMITTED, "готово")
                sink.ready(m.session)
                return MoveOutcome.Done("Приём: пакет уже лежал целиком, «готово» получено")
            }
            sink.refusal(m)?.let { why ->
                wire.send(ANSWER, bytes { writeByte(REFUSE); writeUTF(why); writeInt(0) })
                return MoveOutcome.Failed("Приём: отказал — $why")
            }
            val skip = sink.skip(m)
            sink.begin(m)
            begun = true
            wire.send(ANSWER, bytes { writeByte(OK); writeUTF(""); writeInt(skip.size); skip.forEach { writeUTF(it) } })

            val expected = m.entries.withIndex().filter { it.value.key !in skip }
            val total = expected.sumOf { it.value.size }
            val got = HashSet<Int>()
            var done = 0L
            var current: Pair<Int, OutputStream>? = null
            var digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var mismatch: String? = null
            loop@ while (true) {
                val (type, body) = wire.receive()
                when (type) {
                    HEAD -> {
                        if (current != null) throw Broken("новый файл до конца прежнего")
                        val index = reading(body).readInt()
                        val entry = m.entries.getOrNull(index) ?: throw Broken("файла $index нет в описи")
                        if (entry.key in skip || index in got) throw Broken("«${entry.name}» не ждал")
                        current = index to sink.create(entry)
                        digest = MessageDigest.getInstance("SHA-256")
                        written = 0L
                    }
                    DATA -> {
                        val (index, out) = current ?: throw Broken("кусок вне файла")
                        val entry = m.entries[index]
                        if (written + body.size > entry.size) throw Broken("«${entry.name}» длиннее описи")
                        out.write(body)
                        digest.update(body)
                        written += body.size
                        done += body.size
                        progress(MoveProgress(done, total, entry.name))
                    }
                    END -> {
                        val (index, out) = current ?: throw Broken("конец вне файла")
                        out.close()
                        current = null
                        val entry = m.entries[index]
                        if (written != entry.size) mismatch = mismatch ?: "«${entry.name}»: пришло $written байт из ${entry.size}"
                        else if (!digest.digest().contentEquals(body)) mismatch = mismatch ?: "«${entry.name}»: сумма не сошлась"
                        got += index
                    }
                    DONE -> break@loop
                    else -> throw Broken("непонятное сообщение ($type)")
                }
            }
            val missing = expected.filter { it.index !in got }.map { it.value.name }
            val why = mismatch ?: missing.takeIf { it.isNotEmpty() }?.let { "не пришло: ${it.joinToString(", ")}" }
            if (why != null) {
                wire.send(RECEIVED, bytes { writeBoolean(false); writeUTF(why) })
                sink.abort()
                begun = false
                return MoveOutcome.Failed("Приём: не сошлось — $why")
            }
            sink.complete(m)
            complete = true
            wire.send(RECEIVED, bytes { writeBoolean(true); writeUTF("") })
            wire.expect(COMMITTED, "готово")
            sink.ready(m.session)
            MoveOutcome.Done("Приём: всё принято и сверено, старый подтвердил «готово»")
        } catch (e: Broken) {
            failedReceiving(sink, begun, complete, e.message.orEmpty())
        } catch (e: IOException) {
            failedReceiving(sink, begun, complete, "связь оборвалась (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            failedReceiving(sink, begun, complete, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
        }
    }

    private fun failedReceiving(sink: MoveSink, begun: Boolean, complete: Boolean, why: String): MoveOutcome {
        if (complete) return MoveOutcome.Failed("Приём: всё принято, но «готово» со старого не пришло ($why) — повтори «Переехать» на старом")
        if (begun) sink.abort()
        return MoveOutcome.Failed("Приём сорвался: $why")
    }
}
