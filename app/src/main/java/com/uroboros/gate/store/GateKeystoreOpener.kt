package com.uroboros.gate.store

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import com.uroboros.gate.GateBox
import com.uroboros.gate.GateOpener
import com.uroboros.gate.GateSign
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ шифрования моего агента: им открываются конверты, закрытые мне
 * ([GateBox]). Отдельный от ключа подписи ([GateKeystoreSigner]) — почему, см.
 * [com.uroboros.gate.GateCard.Field.ENC].
 *
 * ДВА ПУТИ, И КАКОЙ ВЫБРАН — ВИДНО ([where], [meterLine]):
 *  - [Where.KEYSTORE] — ключ в хранилище ключей Android, согласование (ECDH)
 *    делает само хранилище, закрытая часть из него не выходит. Есть только с
 *    Android 12 и не на всяком телефоне: хранилище телефона, обновлённого со
 *    старой версии, может этого не уметь. Поэтому ключ после создания
 *    проверяется пробным согласованием;
 *  - [Where.WRAPPED] — где хранилище не умеет: ключ создаётся программно и
 *    лежит в файле [FILE_NAME], закрытый ключом AES из хранилища (это умеют
 *    все версии, на которых ставится агент). Слабее: на время одного
 *    согласования закрытая часть оказывается в памяти приложения.
 * Однажды выбранный запасной путь не меняется (файл есть — путь он):
 * переключения туда и обратно меняли бы ключ у собеседников при каждом сбое.
 *
 * ИЗ ТЕЛЕФОНА КЛЮЧ НЕ ВЫНИМАЕТСЯ — ни выгрузкой, ни переносом: файл ключа без
 * ключа AES из хранилища не открыть, а в облако и на другое устройство файлы
 * приложения не уходят (`res/xml/data_extraction_rules.xml`). На новом телефоне
 * ключ новый — собеседники узнают его из новой подписанной карточки, без
 * тревоги: ключу шифрования верят по подписи карточки.
 *
 * ЧЕГО НЕ УМЕЕТ: переустановка стирает ключ — конверты, закрытые прежним, не
 * откроются, пока собеседник не получит новую карточку; ключ по сроку не
 * меняется; от того, кто держит телефон разблокированным, не защищает.
 * Обращение к хранилищу может бросить — вызывающий показывает строку отказа.
 */
class GateKeystoreOpener private constructor(
    override val publicKey: String,
    val where: Where,
    val createdMs: Long?,
    private val agreeWith: (ECPublicKey) -> ByteArray,
) : GateOpener {

    enum class Where(val words: String) {
        KEYSTORE("в хранилище ключей"),
        WRAPPED("программный, закрыт ключом хранилища"),
    }

    override fun agree(peer: ECPublicKey): ByteArray = agreeWith(peer)

    /** «Шифрование: мой ключ — 7b2e… (в хранилище ключей, создан 21.10.2026)». */
    fun meterLine(day: (Long) -> String): String =
        "Шифрование: мой ключ — ${publicKey.take(8)}… (${where.words}" + (createdMs?.let { ", создан ${day(it)}" } ?: "") + ")"

    companion object {
        const val ALIAS = "uroboros_gate_encryption"
        const val WRAP_ALIAS = "uroboros_gate_encryption_wrap"

        /** Файл запасного пути: открытый ключ (65 байт), IV AES-GCM (12), закрытый ключ PKCS#8, закрытый AES. */
        const val FILE_NAME = "gate_encryption_key.bin"

        fun openOrCreate(context: Context): GateKeystoreOpener {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                keystore(ks)?.let { return it }
            }
            return wrapped(ks, file, retry = true)
        }

        /**
         * Ключ в хранилище; null — хранилище не умеет. Не сработавший ключ
         * стирается: согласовывать им всё равно нельзя.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        private fun keystore(ks: KeyStore): GateKeystoreOpener? = try {
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .build(),
                    )
                }.generateKeyPair()
            }
            val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            val pub = entry.certificate.publicKey as ECPublicKey
            val agree = { peer: ECPublicKey ->
                KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
                    init(entry.privateKey)
                    doPhase(peer, true)
                    generateSecret()
                }
            }
            check(works(pub, agree)) { "пробное согласование не сошлось" }
            GateKeystoreOpener(GateSign.encodeKey(pub), Where.KEYSTORE, ks.getCreationDate(ALIAS)?.time, agree)
        } catch (e: Exception) {
            try {
                ks.deleteEntry(ALIAS)
            } catch (ignored: Exception) {
            }
            null
        }

        /** Запасной путь. Файл не открылся (ключ AES потерян) — ключ создаётся заново, один раз. */
        private fun wrapped(ks: KeyStore, file: File, retry: Boolean): GateKeystoreOpener {
            val wrap = wrapKey(ks)
            if (!file.exists()) {
                val pair = GateBox.newPair()
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrap) }
                val sealed = c.doFinal(pair.private.encoded)
                val pub = GateSign.unb64(GateSign.encodeKey(pair.public as ECPublicKey))!!
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(pub + c.iv + sealed)
                check(tmp.renameTo(file)) { "файл ключа шифрования не записался" }
            }
            val bytes = file.readBytes()
            val pubWire = GateSign.b64(bytes.copyOfRange(0, minOf(65, bytes.size)))
            val pub = GateSign.decodeKey(pubWire)
            val iv = bytes.copyOfRange(minOf(65, bytes.size), minOf(77, bytes.size))
            val sealed = bytes.copyOfRange(minOf(77, bytes.size), bytes.size)
            val agree = { peer: ECPublicKey ->
                val pkcs8 = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, iv))
                    doFinal(sealed)
                }
                try {
                    GateBox.dh(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey, peer)
                } finally {
                    pkcs8.fill(0)
                }
            }
            val ok = pub != null && try {
                works(pub, agree)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                check(retry) { "ключ шифрования не открывается и после пересоздания" }
                file.delete()
                return wrapped(ks, file, retry = false)
            }
            return GateKeystoreOpener(pubWire, Where.WRAPPED, file.lastModified(), agree)
        }

        private fun wrapKey(ks: KeyStore): SecretKey {
            if (!ks.containsAlias(WRAP_ALIAS)) {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(
                        KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }.generateKey()
            }
            return (ks.getEntry(WRAP_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        /** Пробное согласование с одноразовым программным ключом: обе стороны должны получить одно. */
        private fun works(pub: ECPublicKey, agree: (ECPublicKey) -> ByteArray): Boolean {
            val probe = GateBox.newPair()
            return agree(probe.public as ECPublicKey).contentEquals(GateBox.dh(probe.private as ECPrivateKey, pub))
        }
    }
}
