package com.uroboros.gate

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * Тот, кто подписывает: ключ подписи агента. Закрытая часть у него не
 * вынимается (на телефоне — хранилище ключей Android); наружу — только
 * открытая часть и подписи.
 */
interface GateSigner {
    /** Открытый ключ в виде для провода ([GateSign], «вид ключа»). */
    val publicKey: String

    /** Подпись [data] — 64 байта: r и s по 32 (см. [GateSign]). */
    fun sign(data: ByteArray): ByteArray
}

/**
 * Подпись форм врат: ECDSA на кривой P-256 (secp256r1) с SHA-256.
 *
 * ЗАЧЕМ. Кто прислал, знает канал, а канал знает только токен бота: у кого
 * токен, тот и агент для всех. Угонщик аккаунта, создавшего бота, берёт токен
 * и запускает двойника. Ключ подписи от токена не зависит и из телефона агента
 * не вынимается — двойнику подписать нечем.
 *
 * ПОЧЕМУ P-256. Хранилище ключей Android подписывает ею на всех версиях, где
 * ставится агент; Ed25519 там есть только с Android 13. Проверить подпись
 * P-256 умеет любая обычная библиотека (в Python — `cryptography`).
 *
 * ВИД НА ПРОВОДЕ. Открытый ключ — точка кривой без сжатия (65 байт: 0x04, x,
 * y), подпись — r и s по 32 байта подряд; оба — base64url без `=` на конце.
 * Не DER: у DER длина плавает, а здесь подпись — слово шапки фиксированной
 * длины ([SIG_CHARS]).
 *
 * ЧТО ПОДПИСЫВАЕТСЯ — шапка без подписи, перевод строки и тело, байт в байт
 * ([seal]). В шапке есть знак формы и версия: подпись от карточки к пакету не
 * приставить.
 *
 * ЧЕГО НЕ УМЕЕТ: не шифрует — это [GateBox]; что ключ принадлежит
 * именно этому агенту, сама подпись не говорит — это решает [GateKeys];
 * подпись, сделанная до кражи телефона вместе с разблокировкой, от подписи
 * вора не отличается.
 *
 * Чистый объект: ни сети, ни базы, ни Android (только java.security).
 */
object GateSign {

    /** Подпись base64url без `=`: 64 байта → 86 знаков. */
    const val SIG_CHARS = 86

    /** Открытый ключ base64url без `=`: 65 байт → 87 знаков. */
    const val KEY_CHARS = 87

    internal val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** null — не base64url или с `=`. */
    fun unb64(s: String): ByteArray? =
        if (s.isEmpty() || s.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) null
        else try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Открытый ключ Java в вид для провода. */
    fun encodeKey(k: ECPublicKey): String {
        fun fixed(n: BigInteger): ByteArray {
            val b = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - b.size) + b
        }
        return b64(byteArrayOf(4) + fixed(k.w.affineX) + fixed(k.w.affineY))
    }

    /**
     * Ключ с провода; null — не точка кривой P-256 в нужном виде.
     *
     * Лежит ли точка на кривой, проверяется здесь явно, а не оставляется
     * библиотеке: не все реализации `KeyFactory` это делают. Для подписи
     * точка вне кривой — просто неверная подпись, для шифрования ([GateBox]) —
     * способ выведать закрытый ключ получателя.
     */
    fun decodeKey(s: String): ECPublicKey? {
        if (s.length != KEY_CHARS) return null
        val b = unb64(s) ?: return null
        if (b.size != 65 || b[0] != 4.toByte()) return null
        val x = BigInteger(1, b.copyOfRange(1, 33))
        val y = BigInteger(1, b.copyOfRange(33, 65))
        if (!onCurve(x, y)) return null
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
        } catch (e: Exception) {
            null
        }
    }

    /** y² = x³ + ax + b по модулю p, и обе координаты меньше p. У P-256 сомножитель 1: точки кривой — вся группа. */
    private fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        val c = P256.curve
        val p = (c.field as java.security.spec.ECFieldFp).p
        if (x >= p || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.multiply(x).multiply(x).add(c.a.multiply(x)).add(c.b).mod(p)
        return left == right
    }

    /** Подпись [sig] (r и s по 32 байта) под [data] ключом [key] с провода. */
    fun verify(key: String, data: ByteArray, sig: String): Boolean {
        val k = decodeKey(key) ?: return false
        if (sig.length != SIG_CHARS) return false
        val raw = unb64(sig)?.takeIf { it.size == 64 } ?: return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(k)
                update(data)
                verify(toDer(raw))
            }
        } catch (e: Exception) {
            false
        }
    }

    /** r и s по 32 байта → DER, как ждёт java.security. */
    fun toDer(raw: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            var v = b.copyOfRange(i, b.size)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(2, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** DER (так подписывает java.security и хранилище Android) → r и s по 32 байта. */
    fun fromDer(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "не подпись DER" }
        var i = 2
        fun next(): ByteArray {
            require(der[i] == 2.toByte()) { "не подпись DER" }
            val len = der[i + 1].toInt()
            val v = der.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            val t = v.dropWhile { it == 0.toByte() }.toByteArray()
            require(t.size <= 32) { "число длиннее 32 байт" }
            return ByteArray(32 - t.size) + t
        }
        return next() + next()
    }

    /**
     * Подписать форму: [raw] — неподписанная форма ([GateWire.write] и
     * соседи), её версия заменяется подписанной ([GateWire.signedVersion]), в
     * шапку пятым словом ложится подпись. Тело не меняется.
     *
     * [previous] — прежний ключ при его смене, только для карточки: он
     * подписывает те же байты, подпись ложится шестым словом ([GateKeys]).
     */
    fun seal(raw: ByteArray, signer: GateSigner, previous: GateSigner? = null): ByteArray {
        val nl = raw.indexOf('\n'.code.toByte())
        require(nl > 0) { "нет шапки" }
        val parts = String(raw, 0, nl, Charsets.US_ASCII).split(' ')
        require(parts.size == 4) { "шапка уже с подписью или не шапка" }
        val form = GateWire.Form.values().firstOrNull { it.magic == parts[0] } ?: error("не форма врат")
        require(form.signed.isNotEmpty()) { "форма «${form.magic}» не подписывается" }
        require(parts[1].toIntOrNull() == form.unsigned) { "не та версия для подписи" }
        val body = raw.copyOfRange(nl + 1, raw.size)
        val head = "${parts[0]} ${GateWire.signedVersion(form, body)} ${parts[2]} ${parts[3]}"
        require(previous == null || form == GateWire.Form.CARD) { "прежним ключом подписывается только карточка" }
        val data = signedBytes(head, body)
        val sigs = listOfNotNull(signer.sign(data), previous?.sign(data)).joinToString(" ") { b64(it) }
        return "$head $sigs\n".toByteArray(Charsets.US_ASCII) + body
    }

    /** Байты, которые подписаны в форме с шапкой [head] (без подписи) и телом [body]. */
    internal fun signedBytes(head: String, body: ByteArray): ByteArray = (head + "\n").toByteArray(Charsets.US_ASCII) + body
}
