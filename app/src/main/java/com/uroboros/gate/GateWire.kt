package com.uroboros.gate

import com.uroboros.memory.SentenceKind
import com.uroboros.memory.SourceKind
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Пакет врат на проводе: шапка одной строкой и тело в JSON.
 *
 * ```
 * trafaret <версия> <длина тела в байтах> <CRC32 тела, 8 знаков hex>\n
 * {"from":{...},"to":{...},"sent":{...},...}
 * ```
 *
 * ПОЧЕМУ ВЕРСИЯ, ДЛИНА И СУММА — В ШАПКЕ, А НЕ В ТЕЛЕ. Тело разбирается только
 * после того, как сошлись все три. Пакет чужой версии не разбирается вовсе:
 * его прорези могут значить другое, и разбор наугад хуже молчания. Пакет
 * короче объявленной длины — оборванный; длиннее или с другой суммой —
 * испорченный. Обрывок, принятый за целое, особенно опасен для сжатых слов:
 * он раскодируется в связный, но чужой текст.
 *
 * Сумма ловит случайную порчу, не подделку (см. [GatePacket], «чего не умеет»).
 *
 * ТЕЛО СТРОГОЕ: каждое поле обязательно, незнакомое поле — отказ. Так пакет
 * не может пронести ни права, ни роль, ни «доверие» — поля для них просто нет,
 * а любое лишнее поле останавливает разбор. Права даёт только код (BIBLE 3);
 * слова «я админ» в [GatePacket.words] — только слова.
 *
 * НОМЕР ПАКЕТА НА ПРОВОДЕ — СТРОКОЙ из 16 знаков hex, а не числом JSON. Номер
 * занимает все 64 бита, а многие разборщики JSON (в JavaScript — любой) держат
 * числа как дробные и точны только до 2^53: два разных номера у них читались бы
 * одним, и второй пакет отсеялся бы как повтор.
 *
 * Карточка агента идёт той же шапкой со своим знаком (см. [readCard]).
 *
 * Здесь проверяется форма. Чья это схема ключа, мне ли пакет, не слишком ли
 * часто пишет отправитель и не повтор ли это, решает [GateIntake].
 */
object GateWire {

    const val VERSION = 2

    private const val MAGIC = "trafaret"

    /** Объявленное число, не подобранное: шапка — четыре коротких слова. */
    private const val MAX_HEADER = 64

    /** Объявленное число, не подобранное. */
    const val MAX_KEY_LENGTH = 128

    sealed class Read {
        data class Ok(val packet: GatePacket) : Read()
        data class Fail(val refusal: GateRefusal, val detail: String) : Read()
    }

    private val BODY_FIELDS = listOf(
        "from", "to", "sent", "place", "said_by", "about", "at", "source", "kind", "topic", "exact", "words",
    )

    /** Новый номер пакета для отправителя (см. [GatePacket.number]). */
    fun newNumber(): Long = random.nextLong()

    private val random = SecureRandom()

    /** Номер так, как он идёт по проводу и в строку прибора: 16 знаков hex. */
    fun numberHex(n: Long): String = java.lang.Long.toHexString(n).padStart(16, '0')

    fun write(p: GatePacket): ByteArray {
        val body = GateJson.write(toJson(p)).toByteArray(Charsets.UTF_8)
        val header = "$MAGIC $VERSION ${body.size} ${crcHex(body)}\n".toByteArray(Charsets.US_ASCII)
        return header + body
    }

    fun read(raw: ByteArray): Read = when (val f = frame(raw, MAGIC, VERSION, GateRefusal.NOT_A_PACKET, "трафарета")) {
        is Frame.Fail -> Read.Fail(f.refusal, f.detail)
        is Frame.Ok -> try {
            Read.Ok(fromJson(f.json))
        } catch (e: Refused) {
            fail(e.refusal, e.detail)
        }
    }

    // --- Карточка агента ---

    /**
     * Карточка — своя форма со своим знаком в шапке, а не поля пакета слов:
     * иначе точное поле «repo» в обычной реплике переписывало бы паспорт.
     * Пакет слов карточкой не читается, карточка — пакетом слов.
     *
     * ```
     * kartochka <версия> <длина тела в байтах> <CRC32 тела>\n
     * {"agent":"tg:bot:111","fields":{"name":"Хоуп","repo":"https://…"}}
     * ```
     *
     * Тело строгое, как у пакета: поля — только из [GateCard.Field], каждое не
     * длиннее своего предела; незнакомое поле — отказ. Что карточка значит и
     * как хранится, — [GateCard] и [GateCards].
     */
    const val CARD_VERSION = 1

    private const val CARD_MAGIC = "kartochka"

    sealed class CardRead {
        data class Ok(val card: GateCard) : CardRead()
        data class Fail(val refusal: GateRefusal, val detail: String) : CardRead()
    }

    fun writeCard(c: GateCard): ByteArray {
        val fields = LinkedHashMap<String, GateJson.Value>()
        for ((f, v) in c.fields) fields[f.wire] = GateJson.Value.Str(v)
        val body = GateJson.write(
            GateJson.Value.Obj(linkedMapOf("agent" to GateJson.Value.Str(c.agent.id), "fields" to GateJson.Value.Obj(fields))),
        ).toByteArray(Charsets.UTF_8)
        return "$CARD_MAGIC $CARD_VERSION ${body.size} ${crcHex(body)}\n".toByteArray(Charsets.US_ASCII) + body
    }

    fun readCard(raw: ByteArray): CardRead = when (val f = frame(raw, CARD_MAGIC, CARD_VERSION, GateRefusal.NOT_A_CARD, "карточки")) {
        is Frame.Fail -> CardRead.Fail(f.refusal, f.detail)
        is Frame.Ok -> try {
            CardRead.Ok(cardFromJson(f.json))
        } catch (e: Refused) {
            CardRead.Fail(e.refusal, e.detail)
        }
    }

    private fun cardFromJson(v: GateJson.Value): GateCard {
        val body = obj(v, "тело")
        exactFields(body, listOf("agent", "fields"), "тело") { if (it == "agent") GateRefusal.NO_ORIGIN else GateRefusal.MALFORMED }
        val agent = key(body["agent"], "agent", GateRefusal.NO_ORIGIN)
        val raw = obj(body.getValue("fields"), "fields")
        val byWire = GateCard.Field.values().associateBy { it.wire }
        val fields = LinkedHashMap<GateCard.Field, String>()
        for ((name, item) in raw) {
            val f = byWire[name] ?: throw Refused(GateRefusal.UNKNOWN_FIELD, "незнакомое поле «$name» в fields")
            val s = str(item, "fields.$name")
            if (s.isBlank()) throw Refused(GateRefusal.MALFORMED, "fields.$name пустое")
            if (s.length > f.maxLength) throw Refused(GateRefusal.MALFORMED, "fields.$name длиннее ${f.maxLength} знаков")
            fields[f] = s
        }
        return GateCard(agent, fields)
    }

    // --- Шапка и целость: общие для пакета и карточки ---

    private sealed class Frame {
        class Ok(val json: GateJson.Value) : Frame()
        class Fail(val refusal: GateRefusal, val detail: String) : Frame()
    }

    /**
     * Шапка, длина, сумма, UTF-8 и JSON — в этом порядке (почему — в начале
     * этого объекта). [notThis] — отказ, когда шапка не той формы; [whose] —
     * чья шапка ждалась, для строки отказа.
     */
    private fun frame(raw: ByteArray, magic: String, expected: Int, notThis: GateRefusal, whose: String): Frame {
        fun no(r: GateRefusal, d: String) = Frame.Fail(r, d)
        val nl = (0 until minOf(raw.size, MAX_HEADER)).firstOrNull { raw[it] == '\n'.code.toByte() }
            ?: return no(notThis, "нет шапки")
        val parts = String(raw, 0, nl, Charsets.US_ASCII).split(' ')
        if (parts.size != 4 || parts[0] != magic) return no(notThis, "шапка не $whose")
        val version = parts[1].toIntOrNull() ?: return no(notThis, "версия не числом")
        if (version != expected) return no(GateRefusal.FOREIGN_VERSION, "версия $version, у меня $expected")
        val length = parts[2].toIntOrNull()?.takeIf { it >= 0 && parts[2].all { c -> c in '0'..'9' } }
            ?: return no(notThis, "длина не числом")
        val crc = parts[3].takeIf { it.length == 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            ?: return no(notThis, "сумма не 8 знаков hex")

        val got = raw.size - nl - 1
        if (got < length) return no(GateRefusal.INCOMPLETE, "пришло $got Б из $length")
        if (got > length) return no(GateRefusal.DAMAGED, "лишние ${got - length} Б после тела")
        val body = raw.copyOfRange(nl + 1, raw.size)
        if (crcHex(body) != crc) return no(GateRefusal.DAMAGED, "сумма не сошлась")

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            return no(GateRefusal.MALFORMED, "тело не UTF-8")
        }
        return try {
            Frame.Ok(GateJson.parse(text))
        } catch (e: GateJson.Malformed) {
            no(GateRefusal.MALFORMED, "JSON: ${e.message}")
        }
    }

    private fun fail(r: GateRefusal, detail: String) = Read.Fail(r, detail)

    private fun crcHex(bytes: ByteArray): String {
        val c = CRC32()
        c.update(bytes)
        return String.format("%08x", c.value)
    }

    // --- Тело ---

    private class Refused(val refusal: GateRefusal, val detail: String) : Exception(detail)

    private fun toJson(p: GatePacket): GateJson.Value {
        fun s(v: String) = GateJson.Value.Str(v)
        return GateJson.Value.Obj(
            linkedMapOf(
                "from" to GateJson.Value.Obj(linkedMapOf("key" to s(p.from.id), "represents" to s(p.represents.id))),
                "to" to GateJson.Value.Obj(linkedMapOf("key" to s(p.to.id))),
                "sent" to GateJson.Value.Obj(linkedMapOf("number" to s(numberHex(p.number)), "ms" to GateJson.Value.Num(p.sentMs))),
                "place" to s(p.place.name),
                "said_by" to s(p.saidBy.id),
                "about" to GateJson.Value.Obj(
                    linkedMapOf(
                        "persons" to GateJson.Value.Arr(p.about.map { s(it.id) }),
                        "unclear" to GateJson.Value.Bool(p.aboutUnclear),
                    ),
                ),
                "at" to GateJson.Value.Obj(linkedMapOf("ms" to GateJson.Value.Num(p.atMs), "zone" to s(p.zone))),
                "source" to s(p.source.name),
                "kind" to s(p.kind.name),
                "topic" to GateJson.Value.Arr(p.topic.map { s(it) }),
                "exact" to GateJson.Value.Obj(LinkedHashMap(p.exact.mapValues { s(it.value) })),
                "words" to s(p.words),
            ),
        )
    }

    private fun fromJson(v: GateJson.Value): GatePacket {
        val body = obj(v, "тело")
        exactFields(body, BODY_FIELDS, "тело") { field ->
            when (field) {
                "from" -> GateRefusal.NO_ORIGIN
                "to", "said_by" -> GateRefusal.NO_KEY
                else -> GateRefusal.MALFORMED
            }
        }

        // Точка отправления — первой: без неё остальное не читается.
        val fromObj = (body["from"] as? GateJson.Value.Obj)?.fields
            ?: throw Refused(GateRefusal.NO_ORIGIN, "from не объект")
        exactFields(fromObj, listOf("key", "represents"), "from") { GateRefusal.NO_ORIGIN }
        val from = key(fromObj["key"], "from.key", GateRefusal.NO_ORIGIN)
        val represents = key(fromObj["represents"], "from.represents", GateRefusal.NO_ORIGIN)

        val toObj = obj(body.getValue("to"), "to")
        exactFields(toObj, listOf("key"), "to") { GateRefusal.NO_KEY }
        val to = key(toObj["key"], "to.key", GateRefusal.NO_KEY)

        val sentObj = obj(body.getValue("sent"), "sent")
        exactFields(sentObj, listOf("number", "ms"), "sent")
        val number = str(sentObj.getValue("number"), "sent.number")
            .takeIf { it.length == 16 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            ?.let { java.lang.Long.parseUnsignedLong(it, 16) }
            ?: throw Refused(GateRefusal.MALFORMED, "sent.number не 16 знаков hex")
        val sentMs = (sentObj.getValue("ms") as? GateJson.Value.Num)?.n?.takeIf { it > 0 }
            ?: throw Refused(GateRefusal.MALFORMED, "sent.ms не положительное целое")

        val place = enumOf(str(body.getValue("place"), "place"), "place") { Place.valueOf(it) }
        if (place == Place.SCREEN) throw Refused(GateRefusal.MALFORMED, "экран через врата не идёт")

        val saidBy = key(body["said_by"], "said_by", GateRefusal.NO_KEY)
        if (saidBy != from && saidBy != represents) {
            throw Refused(GateRefusal.HEARSAY, "сказал ${saidBy.id} — не отправитель и не тот, за кого он говорит")
        }

        val aboutObj = obj(body.getValue("about"), "about")
        exactFields(aboutObj, listOf("persons", "unclear"), "about")
        val about = arr(aboutObj.getValue("persons"), "about.persons")
            .mapIndexed { i, item -> key(item, "about.persons[$i]", GateRefusal.NO_KEY) }
        val unclear = bool(aboutObj.getValue("unclear"), "about.unclear")

        val atObj = obj(body.getValue("at"), "at")
        exactFields(atObj, listOf("ms", "zone"), "at")
        val atMs = (atObj.getValue("ms") as? GateJson.Value.Num)?.n?.takeIf { it > 0 }
            ?: throw Refused(GateRefusal.MALFORMED, "at.ms не положительное целое")
        val zone = str(atObj.getValue("zone"), "at.zone")
        try {
            java.time.ZoneId.of(zone)
        } catch (e: Exception) {
            throw Refused(GateRefusal.MALFORMED, "незнакомый пояс «$zone»")
        }
        // Часы у обоих моментов одни — отправителя; сказанное после отправки — порча.
        if (atMs > sentMs) throw Refused(GateRefusal.MALFORMED, "сказано позже, чем отправлено")

        val source = enumOf(str(body.getValue("source"), "source"), "source") { SourceKind.valueOf(it) }
        val kind = enumOf(str(body.getValue("kind"), "kind"), "kind") { SentenceKind.Kind.valueOf(it) }
        val topic = arr(body.getValue("topic"), "topic").mapIndexed { i, item -> str(item, "topic[$i]") }
        val exact = obj(body.getValue("exact"), "exact").mapValues { (k, item) -> str(item, "exact.$k") }
        val words = str(body.getValue("words"), "words")
        if (words.isBlank()) throw Refused(GateRefusal.MALFORMED, "слова пустые")

        return GatePacket(from, represents, to, number, sentMs, place, saidBy, about, unclear, atMs, zone, source, kind, topic, exact, words)
    }

    /**
     * Ровно эти поля. Лишнее — [GateRefusal.UNKNOWN_FIELD] (проверяется первым:
     * пакет с правами внутри молчит, даже если в нём чего-то не хватает); нет
     * нужного — причина по имени поля из [missing].
     */
    private fun exactFields(
        o: Map<String, GateJson.Value>,
        expected: List<String>,
        where: String,
        missing: (String) -> GateRefusal = { GateRefusal.MALFORMED },
    ) {
        o.keys.firstOrNull { it !in expected }?.let { throw Refused(GateRefusal.UNKNOWN_FIELD, "незнакомое поле «$it» в $where") }
        expected.firstOrNull { it !in o }?.let { throw Refused(missing(it), "нет поля «$it» в $where") }
    }

    private fun obj(v: GateJson.Value, where: String): Map<String, GateJson.Value> =
        (v as? GateJson.Value.Obj)?.fields ?: throw Refused(GateRefusal.MALFORMED, "$where не объект")

    private fun arr(v: GateJson.Value, where: String): List<GateJson.Value> =
        (v as? GateJson.Value.Arr)?.items ?: throw Refused(GateRefusal.MALFORMED, "$where не массив")

    private fun str(v: GateJson.Value, where: String): String =
        (v as? GateJson.Value.Str)?.s ?: throw Refused(GateRefusal.MALFORMED, "$where не строка")

    private fun bool(v: GateJson.Value, where: String): Boolean =
        (v as? GateJson.Value.Bool)?.b ?: throw Refused(GateRefusal.MALFORMED, "$where не да/нет")

    private fun <T> enumOf(name: String, where: String, of: (String) -> T): T =
        try {
            of(name)
        } catch (e: IllegalArgumentException) {
            throw Refused(GateRefusal.MALFORMED, "$where: незнакомое «$name»")
        }

    /**
     * Ключ: «схема:остальное», схема — строчные латинские буквы и цифры,
     * остальное — без пробелов и управляющих знаков, всё вместе не длиннее
     * [MAX_KEY_LENGTH]. Знакома ли схема — решает [GateIntake].
     */
    private fun key(v: GateJson.Value?, where: String, missing: GateRefusal): PersonKey {
        val s = (v as? GateJson.Value.Str)?.s ?: throw Refused(missing, "$where — нет ключа")
        val colon = s.indexOf(':')
        val ok = s.isNotEmpty() && s.length <= MAX_KEY_LENGTH && colon > 0 && colon < s.length - 1 &&
            s.substring(0, colon).all { it in 'a'..'z' || it in '0'..'9' } &&
            s.none { it.isWhitespace() || it.isISOControl() }
        if (!ok) throw Refused(missing, "$where — не ключ: «${s.take(40)}»")
        return PersonKey(s)
    }

    /** Схема ключа — то, что до первого двоеточия. */
    fun schemeOf(key: PersonKey): String = key.id.substringBefore(':')
}
