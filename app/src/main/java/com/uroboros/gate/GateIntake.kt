package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/** Почему пакет врат не принят — словами для прибора («Врата: не принят — …»). */
enum class GateRefusal(val words: String) {
    TOO_BIG("больше предела размера"),
    NOT_A_PACKET("не пакет трафарета"),
    NOT_A_CARD("не карточка"),
    NOT_A_VOUCH("не поручительство"),
    NOT_AN_ACK("не ответ «принял»"),
    FOREIGN_VERSION("чужая версия трафарета"),
    INCOMPLETE("оборван"),
    DAMAGED("испорчен"),
    MALFORMED("неверная форма"),
    UNKNOWN_FIELD("незнакомое поле"),
    NO_ORIGIN("нет точки отправления"),
    NO_KEY("нет ключа"),
    HEARSAY("с третьих рук"),
    UNKNOWN_SCHEME("незнакомый канал ключа"),
    FLOOD("сверх предела потока"),
    NOT_SENDER("отправитель не тот, что у канала"),
    NOT_FOR_ME("не мне"),
    STALE("устарел"),
    FROM_FUTURE("из будущего"),
    REPEAT("повтор"),
    UNSIGNED("без подписи, а ключ агента известен"),
    BAD_SIGNATURE("подпись не та"),
}

/**
 * Приём пакета врат: принять или не принять, и если нет — почему.
 *
 * Порядок проверок — от дешёвого к дорогому, и пакет молчит на первой же
 * несошедшейся: размер → схема ключа канала → предел потока → форма и целость
 * ([GateWire.read]) → схемы ключей в теле → отправитель → подпись → адресат →
 * момент отправки → повтор.
 *
 * ОТПРАВИТЕЛЬ — ОТ КАНАЛА, А НЕ ИЗ ПАКЕТА. Ключ в теле ([GatePacket.from])
 * вписывает сам отправитель, и вписать он может чей угодно — например, ключ
 * настоящего агента Бориса, чтобы ему нашлась чужая доверенность ([GateWho]).
 * Кто прислал на самом деле, знает только канал (в телеграме — номер аккаунта,
 * с которого пришло сообщение); его ключ передаёт [receive] тот, кто
 * подключает канал. Не совпал с ключом в теле — отказ. После этой проверки
 * [GatePacket.from] значит то же, что ключ канала, и дальше ему можно верить
 * как каналу — не больше.
 *
 * СХЕМЫ КЛЮЧЕЙ. Знакомы только схемы из [knownSchemes]. Ключ незнакомого
 * канала не принимается, а не «как-нибудь»: подлинность ключа даёт канал, и
 * канал, которого агент не знает, ничего не подтверждает. Схема экрана
 * («screen:») через врата не идёт: экран — не канал между агентами.
 *
 * ПРЕДЕЛ ПОТОКА считается по ключу канала ДО РАЗБОРА ФОРМЫ: ключ даёт канал, а
 * не пакет, поэтому он известен и у пакета, который не читается. Заваливать
 * можно чем угодно — мусором, пакетами не мне, пакетами с чужим отправителем,
 * — и всё это идёт в счёт. По ключу из тела считать нельзя: меняя его в каждом
 * пакете, предел обошёл бы любой. Отброшенное сверх предела не копится и не
 * откладывается — его не было. Пакет больше предела размера в счёт не идёт:
 * он отбрасывается раньше, не читаясь.
 *
 * ПОВТОРЫ. Пакет может потеряться в пути: канал держит недоставленное
 * ограниченное время (Telegram — сутки), телефон получателя бывает выключен.
 * Поэтому отправитель шлёт пакет снова, пока не услышит «принял», и до
 * получателя один пакет может дойти дважды. Повтор узнаётся по паре «ключ
 * отправителя + номер» ([GatePacket.number]): номера принятых пакетов
 * помнятся [Limits.repeatWindowMs] от момента отправки ([GatePacket.sentMs]).
 * Чтобы забытый номер не прошёл заново, пакет, отправленный раньше этого
 * срока, отказывается как «устарел», а отправленный позже моего «сейчас»
 * больше чем на [Limits.futureMs] — как «из будущего»: иначе его номер
 * помнился бы сколько угодно долго. Отказ «повтор» значит «уже принят», а не
 * ошибку отправителя: на повтор тоже надо ответить «принял», иначе
 * отправитель будет слать его снова.
 *
 * Номер — случайное число, а не счётчик: счётчик отправитель должен хранить,
 * и после переустановки он начнётся с нуля — тогда все его новые пакеты
 * получатель молча отсеял бы как повторы. Случайному числу хранить нечего.
 * Запоминаются номера только принятых пакетов: отказанный (сверх предела
 * потока, не мне, устаревший) номера не занимает и может прийти снова.
 *
 * КАРТОЧКА АГЕНТА ([receiveCard]) идёт через те же врата: размер → схема ключа
 * канала → предел потока → форма → схемы ключей → отправитель. Счёт потока у
 * всех форм одного ключа общий: заваливать можно любой. Адресата, момента и
 * номера у карточки нет: она одна для всех, что новее — решает момент приёма
 * ([GateCards]), а повтор той же карточки ничего не меняет.
 *
 * ПОРУЧИТЕЛЬСТВО ([receiveVouch]) — так же, как карточка, и ещё адресат:
 * поручительство даётся тому, кому его решили показать. Поручитель — только
 * отправитель по каналу.
 *
 * ПОДПИСЬ проверяется сразу после отправителя: подпись — его. Ключ агента —
 * из [keys] (что он значит и откуда берётся, — [GateKeys]). Ключ известен —
 * форма без подписи или с чужой подписью отказывается, и «принял» на неё не
 * отвечается: прислать её мог двойник с украденным токеном. Ключ не известен
 * — форма идёт «без подписи». Карточка проверяется своим же ключом;
 * смену ключа решает [GateKeys.takeCard], а не врата.
 *
 * «ПРИНЯЛ» ([receiveAck]) — так же, как поручительство: отвечает только тот,
 * кому пакет слали, и только мне. Что «принял» значит и на какие отказы он
 * отвечается, — [GateAck].
 *
 * ЧЕГО НЕ УМЕЕТ: подлинность отправителя — не больше, чем у канала: ключу
 * канала верит как есть (см. [GatePacket]); за кого отправитель говорит, не
 * решает — это [GateWho];
 * оборванный пакет не складывает в буфер до прихода остатка; в память ничего
 * не кладёт; «принял» сам не отправляет — его собирает [GateAcks.answer], а
 * шлёт канал. Счётчик потока живёт в памяти процесса: после перезапуска он
 * начинается с нуля (окно — минуты, а перезапустить агента снаружи нельзя).
 * Номера принятых пакетов — в [GateNumbers]: переживают перезапуск, только
 * если хранилище — база (`gate.store`); по умолчанию — память процесса, и
 * тогда повтор пакета, принятого до перезапуска, пройдёт ещё раз.
 * Если часы получателя перевели назад после того, как номер забыт, повтор
 * тоже пройдёт. Номеров от одного ключа помнится не больше, чем пропускает
 * предел потока за [Limits.repeatWindowMs] + [Limits.futureMs]; число ключей
 * ограничивает только канал.
 *
 * @param me мой ключ: пакет с другим [GatePacket.to] — «не мне».
 */
class GateIntake(
    private val me: PersonKey,
    private val limits: Limits = Limits.DECLARED,
    private val knownSchemes: Set<String> = setOf("tg"),
    private val numbers: GateNumbers = GateNumbers.InMemory(),
    private val keys: () -> List<GateKeyRecord> = { emptyList() },
) {

    data class Limits(
        val maxBytes: Int,
        val perWindow: Int,
        val windowMs: Long,
        val repeatWindowMs: Long,
        val futureMs: Long,
    ) {
        companion object {
            /**
             * Объявленные числа, не подобранные: 8 КБ на пакет, 20 пакетов за 10
             * минут от одного ключа; номера помнятся 7 суток — с запасом против
             * суток, что канал держит недоставленное, на выключенный телефон
             * отправителя; часы отправителя могут спешить на сутки.
             */
            val DECLARED = Limits(
                maxBytes = 8 * 1024,
                perWindow = 20,
                windowMs = 10 * 60_000L,
                repeatWindowMs = 7 * DAY_MS,
                futureMs = DAY_MS,
            )
        }
    }

    sealed class Verdict {
        /** [sign] — что с подписью: верна или «без подписи» ([GateKeys.Check]). */
        data class Accepted(val packet: GatePacket, val sign: GateKeys.Check = GateKeys.Check.Unsigned) : Verdict()
        /**
         * [packet] — пакет, который прочитан и прислан тем, кто в нём назван
         * отправителем, но не принят (не мне, устарел, из будущего, повтор): на
         * такой отказ можно ответить ([GateAcks.answer]). Для остальных отказов —
         * null: отвечать некому или нечего.
         */
        data class Refused(val refusal: GateRefusal, val detail: String, val packet: GatePacket? = null) : Verdict() {
            /** Строка для прибора. */
            val words: String get() = "не принят — ${refusal.words}: $detail"
        }
    }

    sealed class CardVerdict {
        /** [seal] — подпись карточки; ключ из неё принимает [GateKeys.takeCard]. */
        data class Accepted(val card: GateCard, val seal: GateWire.Seal? = null) : CardVerdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : CardVerdict() {
            /** Строка для прибора. */
            val words: String get() = "Карточка: не принята — ${refusal.words}: $detail"
        }
    }

    sealed class VouchVerdict {
        data class Accepted(val vouch: GateVouch, val sign: GateKeys.Check = GateKeys.Check.Unsigned) : VouchVerdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : VouchVerdict() {
            /** Строка для прибора. */
            val words: String get() = "Поручительство: не принято — ${refusal.words}: $detail"
        }
    }

    sealed class AckVerdict {
        data class Accepted(val ack: GateAck, val sign: GateKeys.Check = GateKeys.Check.Unsigned) : AckVerdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : AckVerdict() {
            /** Строка для прибора. */
            val words: String get() = "«Принял»: не принят — ${refusal.words}: $detail"
        }
    }

    /** Ключ отправителя → моменты засчитанных сообщений всех форм в окне [Limits.windowMs]. */
    private val recent = HashMap<String, ArrayDeque<Long>>()


    /** [sender] — ключ того, от кого пакет пришёл, по словам канала (см. «отправитель» выше). */
    @Synchronized
    fun receive(raw: ByteArray, sender: PersonKey, nowMs: Long): Verdict {
        entry(raw, sender, nowMs)?.let { return Verdict.Refused(it.first, it.second) }
        val read = when (val r = GateWire.read(raw)) {
            is GateWire.Read.Fail -> return Verdict.Refused(r.refusal, r.detail)
            is GateWire.Read.Ok -> r
        }
        val packet = read.packet

        val keys = listOf(packet.from, packet.represents, packet.to, packet.saidBy) + packet.about
        unknownScheme(keys)?.let { return Verdict.Refused(GateRefusal.UNKNOWN_SCHEME, it) }

        if (packet.from != sender) {
            return Verdict.Refused(GateRefusal.NOT_SENDER, "в пакете ${packet.from.id}, канал говорит ${sender.id}")
        }
        val sign = GateKeys.check(keys(), sender, read.seal)
        if (sign is GateKeys.Check.Refused) return Verdict.Refused(sign.refusal, sign.detail)

        if (packet.to != me) return Verdict.Refused(GateRefusal.NOT_FOR_ME, "кому ${packet.to.id}, я ${me.id}", packet)

        val age = nowMs - packet.sentMs
        if (age >= limits.repeatWindowMs) {
            return Verdict.Refused(GateRefusal.STALE, "отправлен ${spanWords(age)} назад, срок ${spanWords(limits.repeatWindowMs)}", packet)
        }
        if (-age > limits.futureMs) {
            return Verdict.Refused(GateRefusal.FROM_FUTURE, "отправлен на ${spanWords(-age)} позже моего «сейчас»", packet)
        }

        numbers.forgetSentUpTo(nowMs - limits.repeatWindowMs)
        if (numbers.contains(packet.from.id, packet.number)) {
            return Verdict.Refused(
                GateRefusal.REPEAT,
                "номер ${GateWire.numberHex(packet.number)} от ${packet.from.id} уже принят",
                packet,
            )
        }
        numbers.put(packet.from.id, packet.number, packet.sentMs)
        return Verdict.Accepted(packet, sign)
    }

    /** [sender] — ключ того, от кого карточка пришла, по словам канала. Карточка агента — только с его ключа. */
    @Synchronized
    fun receiveCard(raw: ByteArray, sender: PersonKey, nowMs: Long): CardVerdict {
        entry(raw, sender, nowMs)?.let { return CardVerdict.Refused(it.first, it.second) }
        val read = when (val r = GateWire.readCard(raw)) {
            is GateWire.CardRead.Fail -> return CardVerdict.Refused(r.refusal, r.detail)
            is GateWire.CardRead.Ok -> r
        }
        val card = read.card
        unknownScheme(listOf(card.agent))?.let { return CardVerdict.Refused(GateRefusal.UNKNOWN_SCHEME, it) }
        if (card.agent != sender) {
            return CardVerdict.Refused(GateRefusal.NOT_SENDER, "в карточке ${card.agent.id}, канал говорит ${sender.id}")
        }
        val seal = read.seal
        if (seal == null) {
            GateKeys.current(keys(), sender)?.let {
                return CardVerdict.Refused(GateRefusal.UNSIGNED, "ключ агента уже известен (${it.key.take(8)}…)")
            }
        } else if (!GateSign.verify(card.fields.getValue(GateCard.Field.KEY), seal.data, seal.sig)) {
            return CardVerdict.Refused(GateRefusal.BAD_SIGNATURE, "карточка подписана не своим ключом")
        }
        return CardVerdict.Accepted(card, seal)
    }

    /** [sender] — ключ того, от кого поручительство пришло, по словам канала. Поручитель — только он. */
    @Synchronized
    fun receiveVouch(raw: ByteArray, sender: PersonKey, nowMs: Long): VouchVerdict {
        entry(raw, sender, nowMs)?.let { return VouchVerdict.Refused(it.first, it.second) }
        val read = when (val r = GateWire.readVouch(raw)) {
            is GateWire.VouchRead.Fail -> return VouchVerdict.Refused(r.refusal, r.detail)
            is GateWire.VouchRead.Ok -> r
        }
        val vouch = read.vouch
        unknownScheme(listOf(vouch.voucher, vouch.agent, vouch.to))?.let {
            return VouchVerdict.Refused(GateRefusal.UNKNOWN_SCHEME, it)
        }
        if (vouch.voucher != sender) {
            return VouchVerdict.Refused(GateRefusal.NOT_SENDER, "поручитель ${vouch.voucher.id}, канал говорит ${sender.id}")
        }
        val sign = GateKeys.check(keys(), sender, read.seal)
        if (sign is GateKeys.Check.Refused) return VouchVerdict.Refused(sign.refusal, sign.detail)
        if (vouch.to != me) return VouchVerdict.Refused(GateRefusal.NOT_FOR_ME, "кому ${vouch.to.id}, я ${me.id}")
        return VouchVerdict.Accepted(vouch, sign)
    }

    /** [sender] — ключ того, от кого «принял» пришёл, по словам канала. Отвечает только он. */
    @Synchronized
    fun receiveAck(raw: ByteArray, sender: PersonKey, nowMs: Long): AckVerdict {
        entry(raw, sender, nowMs)?.let { return AckVerdict.Refused(it.first, it.second) }
        val read = when (val r = GateWire.readAck(raw)) {
            is GateWire.AckRead.Fail -> return AckVerdict.Refused(r.refusal, r.detail)
            is GateWire.AckRead.Ok -> r
        }
        val ack = read.ack
        unknownScheme(listOf(ack.from, ack.to))?.let { return AckVerdict.Refused(GateRefusal.UNKNOWN_SCHEME, it) }
        if (ack.from != sender) {
            return AckVerdict.Refused(GateRefusal.NOT_SENDER, "отвечает ${ack.from.id}, канал говорит ${sender.id}")
        }
        val sign = GateKeys.check(keys(), sender, read.seal)
        if (sign is GateKeys.Check.Refused) return AckVerdict.Refused(sign.refusal, sign.detail)
        if (ack.to != me) return AckVerdict.Refused(GateRefusal.NOT_FOR_ME, "кому ${ack.to.id}, я ${me.id}")
        return AckVerdict.Accepted(ack, sign)
    }

    /**
     * Общий вход всех форм, до разбора: размер → схема ключа канала → предел
     * потока. null — пропустить к разбору; иначе причина и подробность отказа.
     */
    private fun entry(raw: ByteArray, sender: PersonKey, nowMs: Long): Pair<GateRefusal, String>? {
        if (raw.size > limits.maxBytes) return GateRefusal.TOO_BIG to "${raw.size} Б, предел ${limits.maxBytes}"
        unknownScheme(listOf(sender))?.let { return GateRefusal.UNKNOWN_SCHEME to it }
        if (!countWithinLimit(sender.id, nowMs)) {
            return GateRefusal.FLOOD to "от ${sender.id} больше ${limits.perWindow} за ${limits.windowMs / 60_000} мин"
        }
        return null
    }

    /** Подробность отказа «незнакомый канал ключа» для первого ключа незнакомой схемы; null — все знакомы. */
    private fun unknownScheme(keys: List<PersonKey>): String? =
        keys.firstOrNull { GateWire.schemeOf(it) !in knownSchemes }?.let { "«${GateWire.schemeOf(it)}» в ${it.id}" }

    /**
     * Засчитать пакет отправителя. false — предел окна уже набран: этот пакет
     * не засчитывается и отбрасывается. Моменты из будущего (часы перевели
     * назад) остаются в окне — сомнение в сторону предела, а не пропуска.
     */
    private fun countWithinLimit(sender: String, nowMs: Long): Boolean {
        val it = recent.entries.iterator()
        while (it.hasNext()) {
            val q = it.next().value
            while (q.isNotEmpty() && nowMs - q.first() >= limits.windowMs) q.removeFirst()
            if (q.isEmpty()) it.remove()
        }
        val q = recent.getOrPut(sender) { ArrayDeque() }
        if (q.size >= limits.perWindow) return false
        q.addLast(nowMs)
        return true
    }
}

/**
 * Номера принятых пакетов: пара «ключ отправителя + номер» и момент отправки.
 * Зачем помнить — [GateIntake], «повторы». Забывать можно номер, отправленный
 * [GateIntake.Limits.repeatWindowMs] назад и раньше: такой пакет врата и так
 * отказывают как «устарел».
 */
interface GateNumbers {
    fun contains(sender: String, number: Long): Boolean
    fun put(sender: String, number: Long, sentMs: Long)

    /** Забыть номера пакетов, отправленных в [ms] и раньше. */
    fun forgetSentUpTo(ms: Long)

    /** В памяти процесса: после перезапуска пусто. */
    class InMemory : GateNumbers {
        private val bySender = HashMap<String, HashMap<Long, Long>>()

        override fun contains(sender: String, number: Long) = bySender[sender]?.containsKey(number) == true

        override fun put(sender: String, number: Long, sentMs: Long) {
            bySender.getOrPut(sender) { HashMap() }[number] = sentMs
        }

        override fun forgetSentUpTo(ms: Long) {
            val it = bySender.values.iterator()
            while (it.hasNext()) {
                val m = it.next()
                m.values.removeIf { sentMs -> sentMs <= ms }
                if (m.isEmpty()) it.remove()
            }
        }
    }
}

internal const val DAY_MS = 24 * 60 * 60 * 1000L

/** Длительность словами для строк прибора врат, с округлением вниз: «8 сут», «3 ч», «4 мин», «20 с». */
internal fun spanWords(ms: Long): String = when {
    ms >= DAY_MS -> "${ms / DAY_MS} сут"
    ms >= 3_600_000L -> "${ms / 3_600_000L} ч"
    ms >= 60_000L -> "${ms / 60_000L} мин"
    else -> "${ms / 1000L} с"
}
