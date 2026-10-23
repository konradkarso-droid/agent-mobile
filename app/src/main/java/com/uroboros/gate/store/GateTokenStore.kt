package com.uroboros.gate.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Токен бота агента — весь бот: у кого токен, тот и агент для канала
 * ([com.uroboros.gate.GateSign], «зачем»). Поэтому:
 *  - лежит в файле [FILE_NAME] во внутреннем хранилище приложения, закрытый
 *    AES-ключом из хранилища ключей Android ([WRAP_ALIAS]); ключ из телефона не
 *    вынимается, и файл без него не прочитать;
 *  - не лежит ни в одной базе — поэтому не попадает в выгрузку
 *    ([com.uroboros.memory.DatabaseExporter] кладёт только базы); в облако и на
 *    другое устройство файлы приложения не уходят
 *    (`res/xml/data_extraction_rules.xml`);
 *  - не идёт ни в модель, ни в память, ни в строки прибора; текст ошибок сети
 *    чистится от него ([com.uroboros.gate.GateBotApi.scrub]).
 *
 * Вводит его только мой владелец на экране врат за замком
 * ([com.uroboros.gate.link.GateActivity]).
 *
 * ЧЕГО НЕ УМЕЕТ: переустановка приложения стирает ключ хранилища — токен
 * придётся ввести снова (бот при этом прежний: токен живёт у @BotFather); от
 * того, кто держит телефон агента разблокированным с открытым замком, не
 * защищает. Обращение к хранилищу может бросить — вызывающий показывает строку.
 */
object GateTokenStore {

    const val WRAP_ALIAS = "uroboros_gate_tgtoken_wrap"

    /** Файл: IV AES-GCM (12 байт), затем закрытый токен. */
    const val FILE_NAME = "gate_tg_token.bin"

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size <= 12) return null
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
        return String(plain, Charsets.UTF_8)
    }

    fun write(context: Context, token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.doFinal(token.toByteArray(Charsets.UTF_8))
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(c.iv + sealed)
        check(tmp.renameTo(file)) { "файл токена не записался" }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
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
}
