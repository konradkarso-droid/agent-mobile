package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Тот, кто открывает конверты, закрытые мне: мой ключ шифрования. Закрытая
 * часть у него (на телефоне — хранилище ключей Android); наружу — только
 * открытая часть и итог согласования.
 */
interface GateOpener {
    /** Открытый ключ в виде для провода ([GateSign], «вид ключа»). */
    val publicKey: String

    /**
     * Согласование ECDH моего закрытого ключа с [peer]: координата x общей
     * точки, 32 байта. [peer] уже проверен — точка кривой P-256 ([GateSign.decodeKey]).
     */
    fun agree(peer: ECPublicKey): ByteArray
}

/** Ключ шифрования в памяти процесса — для проверок и для запасного пути на телефоне. */
class GateKeyPairOpener(private val pair: KeyPair) : GateOpener {
    override val publicKey: String = GateSign.encodeKey(pair.public as ECPublicKey)
    override fun agree(peer: ECPublicKey): ByteArray = GateBox.dh(pair.private as ECPrivateKey, peer)
}

/**
 * Конверт врат: подписанная форма целиком, закрытая открытым ключом
 * получателя. Прочитать её может только получатель — ни канал, ни тот, кто
 * держит канал посередине.
 *
 * ШИФР — НЕ СВОЙ, А СТАНДАРТ HPKE (RFC 9180), режим base, набор
 * DHKEM(P-256, HKDF-SHA256) + HKDF-SHA256 + AES-128-GCM. Тот же набор есть в
 * CryptoKit у Apple и в сторонних библиотеках на Python, и у него есть
 * контрольные примеры в самом RFC (приложение A.3) — по ним проверен этот код.
 * Кривая та же, что у подписи: P-256 умеет хранилище ключей Android.
 *
 * ЧТО ЗАКРЫВАЕТСЯ — форма с шапкой и подписью, байт в байт: сначала подпись,
 * потом конверт. Канал видит только знак «konvert», размер, кто кому и когда.
 * Подпись поверх конверта показала бы ему, кто пишет; шифрование одного тела
 * оставило бы видным, какая форма идёт.
 *
 * КАРТОЧКА В КОНВЕРТ НЕ КЛАДЁТСЯ: она одна для всех по замыслу и сама везёт
 * ключи — закрыть её, пока ключи не известны, нечем.
 *
 * РАСШИФРОВАНО — НЕ ЗНАЧИТ «ОТ НЕГО». В режиме base закрыть мне конверт может
 * любой, у кого мой открытый ключ. Кто прислал, решают канал и подпись внутри
 * — все проверки [GateIntake] идут после расшифровки как обычно.
 *
 * ТОЧКА НА КРИВОЙ. Одноразовый ключ отправителя проверяется до согласования
 * ([GateSign.decodeKey]): точка вне кривой, подобранная нарочно, выдавала бы
 * по кусочку мой закрытый ключ (invalid curve attack).
 *
 * ЧЕГО НЕ УМЕЕТ: не прячет, кто кому пишет, когда и сколько — длина видна,
 * выравнивания нет; ключ получателя сам по сроку не меняется — утечёт однажды,
 * и всё прошлое, что сохранил канал, читается; подлинности не даёт (см. выше).
 *
 * Чистый объект: ни сети, ни базы, ни Android (только java.security и javax.crypto).
 */
object GateBox {

    /** Строка `info` HPKE: конверт этого протокола не раскроется как чужой и наоборот. */
    val INFO: ByteArray = "uroboros gate konvert 1".toByteArray(Charsets.US_ASCII)

    private const val KEM_ID = 0x0010
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0001
    private const val NK = 16
    private const val NN = 12
    private const val TAG_BYTES = 16

    /** Меньше метки AES-GCM закрытое быть не может. */
    const val MIN_BOX_BYTES = TAG_BYTES

    private val KEM_SUITE = "KEM".ascii() + i2osp(KEM_ID, 2)
    private val HPKE_SUITE = "HPKE".ascii() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)

    /** Конверт на проводе: каким ключом получателя закрыт ([key]), одноразовый ключ ([enc]), закрытое ([box]). */
    class Konvert(val key: String, val enc: String, val box: ByteArray)

    /**
     * Закрыть форму [form] ключом получателя [to] (вид для провода). Каждый раз
     * — новый одноразовый ключ: повтор того же пакета на проводе другой.
     */
    fun close(form: ByteArray, to: String): ByteArray {
        val kind = GateWire.formOf(form)
        require(kind != null && kind != GateWire.Form.CARD && kind != GateWire.Form.KONVERT) { "в конверт — только пакет, поручительство и «принял»" }
        val pkR = requireNotNull(GateSign.decodeKey(to)) { "не ключ P-256" }
        val (enc, box) = seal(pkR, INFO, ByteArray(0), form, newPair())
        return GateWire.writeKonvert(Konvert(to, GateSign.b64(enc), box))
    }

    /**
     * Открыть конверт [k] ключом [opener]. null — не открылся: закрыт не этим
     * ключом, одноразовый ключ не точка кривой, испорчен или подделан. Первые
     * два случая [GateIntake] называет сам, до вызова; здесь они — на случай
     * другого вызывающего.
     */
    fun open(opener: GateOpener, k: Konvert): ByteArray? {
        if (k.key != opener.publicKey) return null
        val enc = GateSign.unb64(k.enc) ?: return null
        return open(opener, enc, INFO, ByteArray(0), k.box)
    }

    /** Ключ шифрования агента [agent] из его нынешней карточки; null — не известен, писать открыто. */
    fun encKeyOf(readings: List<GateCards.Reading>, agent: PersonKey): String? =
        GateCards.currentOf(readings, agent)?.card?.fields?.get(GateCard.Field.ENC)

    // --- Строки прибора ---

    /** После отправки: null — ушло закрытым, строки не надо. */
    fun sentLine(who: String, closed: Boolean): String? =
        if (closed) null else "Шифрование: $who — ключ не известен, отправлено открытым"

    /**
     * После приёма: [closed] — пришло в конверте; [keyKnown] — у отправителя
     * известен ключ шифрования. Открытое от того, чей ключ не известен, — как
     * было до шифрования, строки нет.
     */
    fun receivedLine(who: String, closed: Boolean, keyKnown: Boolean): String? = when {
        closed -> "Шифрование: $who — пришло закрытым, расшифровано"
        keyKnown -> "Шифрование: $who — пришло открытым, хотя ключ шифрования известен"
        else -> null
    }

    // --- HPKE (RFC 9180), режим base, одно письмо на контекст ---

    /** Закрыть: одноразовый ключ [ephemeral] — снаружи только для контрольных примеров. */
    internal fun seal(pkR: ECPublicKey, info: ByteArray, aad: ByteArray, pt: ByteArray, ephemeral: KeyPair): Pair<ByteArray, ByteArray> {
        val enc = point(ephemeral.public as ECPublicKey)
        val dh = dh(ephemeral.private as ECPrivateKey, pkR)
        val shared = extractAndExpand(dh, enc + point(pkR))
        val (key, nonce) = keySchedule(shared, info)
        return enc to aead(Cipher.ENCRYPT_MODE, key, nonce, aad, pt)
    }

    /** Открыть; null — метка не сошлась (не тот ключ, порча, подделка). */
    internal fun open(opener: GateOpener, enc: ByteArray, info: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray? {
        if (ct.size < TAG_BYTES) return null
        val pkE = GateSign.decodeKey(GateSign.b64(enc)) ?: return null
        val pkRm = GateSign.unb64(opener.publicKey) ?: return null
        val shared = extractAndExpand(opener.agree(pkE), enc + pkRm)
        val (key, nonce) = keySchedule(shared, info)
        return try {
            aead(Cipher.DECRYPT_MODE, key, nonce, aad, ct)
        } catch (e: javax.crypto.AEADBadTagException) {
            null
        }
    }

    /** Общий секрет KEM (RFC 9180, 4.1) — снаружи только для контрольных примеров. */
    internal fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
        return labeledExpand(KEM_SUITE, prk, "shared_secret", kemContext, 32)
    }

    /** Ключ и одноразовое значение AES-GCM (RFC 9180, 5.1), режим base, без psk. */
    internal fun keySchedule(shared: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
        val pskIdHash = labeledExtract(HPKE_SUITE, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, shared, "secret", ByteArray(0))
        return labeledExpand(HPKE_SUITE, secret, "key", context, NK) to labeledExpand(HPKE_SUITE, secret, "base_nonce", context, NN)
    }

    /**
     * Одно письмо на контекст: значение — base_nonce, номер письма 0. Повтора
     * значения нет: на каждое письмо — свой одноразовый ключ и свой контекст.
     */
    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
            doFinal(data)
        }

    /** ECDH: координата x общей точки, 32 байта. */
    fun dh(priv: ECPrivateKey, pub: ECPublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(priv)
        doPhase(pub, true)
        generateSecret()
    }

    /** Новая пара P-256 программно — одноразовый ключ отправителя. */
    fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Закрытый ключ P-256 из 32 байт — для контрольных примеров и образца в описании. */
    fun privateKey(raw: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), GateSign.P256)) as ECPrivateKey

    private fun point(k: ECPublicKey): ByteArray = GateSign.unb64(GateSign.encodeKey(k))!!

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hmac(salt, "HPKE-v1".ascii() + suite + label.ascii() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, len: Int): ByteArray =
        expand(prk, i2osp(len, 2) + "HPKE-v1".ascii() + suite + label.ascii() + info, len)

    /**
     * HMAC-SHA256. Пустой ключ HMAC дополняет нулями до блока — то же, что 32
     * нулевых байта; Java пустого ключа не принимает.
     */
    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }

    /** HKDF-Expand (RFC 5869). */
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

    private fun i2osp(n: Int, len: Int): ByteArray = ByteArray(len) { ((n shr (8 * (len - 1 - it))) and 0xff).toByte() }

    private fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)
}
