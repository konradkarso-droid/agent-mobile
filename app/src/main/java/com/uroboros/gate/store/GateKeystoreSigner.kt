package com.uroboros.gate.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.uroboros.gate.GateSign
import com.uroboros.gate.GateSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Ключ подписи моего агента — в хранилище ключей Android (`AndroidKeyStore`).
 * Как подпись устроена и зачем, — [GateSign].
 *
 * ЗАКРЫТАЯ ЧАСТЬ ИЗ ТЕЛЕФОНА НЕ ВЫНИМАЕТСЯ — ни приложением, ни выгрузкой.
 * Поэтому двойник с украденным токеном бота подписать не может. Цена: на
 * новом телефоне ключ будет новый. Если старый телефон жив, он подписывает
 * новую карточку прежним ключом ([GateSign.seal], `previous`), и собеседники
 * принимают смену молча; если нет — у каждого собеседника тревога, и его
 * владелец подтверждает новый ключ ([com.uroboros.gate.GateKeys]).
 *
 * БЕЗ ОТПЕЧАТКА ПАЛЬЦА. Ключ, требующий подтверждения человеком, не подписал
 * бы «принял», пока телефон в кармане. Защищает его то, что он не вынимается.
 *
 * ЧЕГО НЕ УМЕЕТ: переустановка приложения стирает ключ — это тоже «новый
 * телефон» для собеседников; от того, кто держит телефон агента
 * разблокированным, не защищает — подписывать он сможет.
 */
class GateKeystoreSigner private constructor(private val entry: KeyStore.PrivateKeyEntry, val createdMs: Long?) : GateSigner {

    override val publicKey: String = GateSign.encodeKey(entry.certificate.publicKey as ECPublicKey)

    override fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
        return GateSign.fromDer(der)
    }

    /** «Подпись: мой ключ — 3fa1… (создан 21.10.2026)». */
    fun meterLine(day: (Long) -> String): String =
        "Подпись: мой ключ — ${publicKey.take(8)}…" + (createdMs?.let { " (создан ${day(it)})" } ?: "")

    companion object {
        const val ALIAS = "uroboros_gate_signing"

        /** Свой ключ: есть — открыть, нет — создать. Обращение к хранилищу может бросить. */
        fun openOrCreate(): GateKeystoreSigner {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
            }
            val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return GateKeystoreSigner(entry, ks.getCreationDate(ALIAS)?.time)
        }
    }
}
