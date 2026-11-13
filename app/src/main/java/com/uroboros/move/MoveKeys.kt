package com.uroboros.move

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ключи сессии переезда и закрытые сообщения. Зачем так — у [Move], «защита
 * канала».
 *
 * Сборка из стандартных частей, своего шифра нет: ECDH P-256 (gate.GateBox.dh),
 * PBKDF2-HMAC-SHA256, HKDF-SHA256 (RFC 5869), AES-256-GCM.
 *
 * Соль растяжки — хэш начала разговора (оба одноразовых ключа): заранее
 * просчитать коды нельзя, на каждую сессию — своя соль.
 *
 * Чистый объект: только java.security и javax.crypto.
 */
object MoveKeys {

    class Pair(val toReceiver: ByteArray, val toSender: ByteArray)

    /**
     * Ключи обоих направлений из общего секрета ECDH [shared], кода [code] и
     * открытых ключей сторон [encS], [encR] в виде для провода.
     */
    fun derive(shared: ByteArray, code: String, encS: ByteArray, encR: ByteArray, iterations: Int = Move.ITERATIONS): Pair {
        val transcript = sha256(Move.MAGIC + encS + encR)
        val stretched = pbkdf2(code, transcript, iterations)
        val prk = hmac(stretched, shared)
        return Pair(
            toReceiver = expand(prk, "uroboros move s->r".toByteArray(Charsets.US_ASCII), 32),
            toSender = expand(prk, "uroboros move r->s".toByteArray(Charsets.US_ASCII), 32),
        )
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun pbkdf2(code: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, iterations, 256))
            .encoded

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    private fun expand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out += t
            i++
        }
        return out.copyOf(len)
    }
}

/**
 * Одно направление закрытых сообщений: AES-256-GCM, значение — номер сообщения
 * в этом направлении. Номера идут подряд с нуля и не повторяются; выброшенное,
 * переставленное или повторённое посредником сообщение не откроется — номер
 * не совпадёт.
 */
class MoveSeal(private val key: ByteArray) {
    private var counter = 0L

    fun seal(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(plain)

    /** null — не открылось: не тот ключ (неверный код), порча, подмена, перестановка. */
    fun open(sealed: ByteArray): ByteArray? = try {
        cipher(Cipher.DECRYPT_MODE).doFinal(sealed)
    } catch (e: javax.crypto.AEADBadTagException) {
        null
    }

    private fun cipher(mode: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
    }
}
