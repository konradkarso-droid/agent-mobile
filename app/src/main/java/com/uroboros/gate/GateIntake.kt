package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/** Почему пакет врат не принят — словами для прибора («Врата: не принят — …»). */
enum class GateRefusal(val words: String) {
    TOO_BIG("больше предела размера"),
    NOT_A_PACKET("не пакет трафарета"),
    NOT_A_CARD("не карточка"),
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
}

/**
 * Приём пакета врат: принять или не принять, и если нет — почему.
 *
 * Порядок проверок — от дешёвого к дорогому, и пакет молчит на первой же
 * несошедшейся: размер → форма и целость ([GateWire.read]) → схемы ключей →
 * предел потока → отправитель → адресат → момент отправки → повтор.
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
 * ПРЕДЕЛ ПОТОКА считается по ключу канала у пакетов, прошедших форму, — и тех,
 * что адресованы не мне или вписали чужого отправителя: заваливать можно и
 * ими. По ключу из тела считать нельзя: меняя его в каждом пакете, предел
 * обошёл бы любой.
 * Пакет, у которого форма не сошлась, отправителя не имеет; от потока таких
 * защищает только предел размера. Отброшенное сверх предела не копится и не
 * откладывается — его не было.
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
 * КАРТОЧКА АГЕНТА ([receiveCard]) идёт через те же врата: размер → форма →
 * схемы ключей → предел потока → отправитель. Счёт потока у карточек и пакетов
 * одного ключа общий: заваливать можно и тем, и другим. Адресата, момента и
 * номера у карточки нет: она одна для всех, что новее — решает момент приёма
 * ([GateCards]), а повтор той же карточки ничего не меняет.
 *
 * ЧЕГО НЕ УМЕЕТ: подлинность отправителя — не больше, чем у канала: ключу
 * канала верит как есть (см. [GatePacket]); за кого отправитель говорит, не
 * решает — это [GateWho];
 * оборванный пакет не складывает в буфер до прихода остатка; в память ничего
 * не кладёт; «принял» не отвечает — это дело канала. Счётчик потока и номера
 * принятых пакетов живут в памяти процесса: после перезапуска счётчик
 * начинается с нуля, а повтор пакета, принятого до перезапуска, пройдёт ещё
 * раз. Хранить номера между перезапусками может только таблица, а её нет.
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
        data class Accepted(val packet: GatePacket) : Verdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : Verdict() {
            /** Строка для прибора. */
            val words: String get() = "не принят — ${refusal.words}: $detail"
        }
    }

    sealed class CardVerdict {
        data class Accepted(val card: GateCard) : CardVerdict()
        data class Refused(val refusal: GateRefusal, val detail: String) : CardVerdict() {
            /** Строка для прибора. */
            val words: String get() = "Карточка: не принята — ${refusal.words}: $detail"
        }
    }

    /** Ключ отправителя → моменты принятых к счёту пакетов и карточек в окне [Limits.windowMs]. */
    private val recent = HashMap<String, ArrayDeque<Long>>()

    /** Ключ отправителя → номер принятого пакета → момент его отправки. */
    private val accepted = HashMap<String, HashMap<Long, Long>>()

    /** [sender] — ключ того, от кого пакет пришёл, по словам канала (см. «отправитель» выше). */
    @Synchronized
    fun receive(raw: ByteArray, sender: PersonKey, nowMs: Long): Verdict {
        if (raw.size > limits.maxBytes) {
            return Verdict.Refused(GateRefusal.TOO_BIG, "${raw.size} Б, предел ${limits.maxBytes}")
        }
        val packet = when (val r = GateWire.read(raw)) {
            is GateWire.Read.Fail -> return Verdict.Refused(r.refusal, r.detail)
            is GateWire.Read.Ok -> r.packet
        }

        val keys = listOf(sender, packet.from, packet.represents, packet.to, packet.saidBy) + packet.about
        keys.firstOrNull { GateWire.schemeOf(it) !in knownSchemes }?.let {
            return Verdict.Refused(GateRefusal.UNKNOWN_SCHEME, "«${GateWire.schemeOf(it)}» в ${it.id}")
        }

        if (!countWithinLimit(sender.id, nowMs)) {
            return Verdict.Refused(
                GateRefusal.FLOOD,
                "от ${sender.id} больше ${limits.perWindow} за ${limits.windowMs / 60_000} мин",
            )
        }

        if (packet.from != sender) {
            return Verdict.Refused(GateRefusal.NOT_SENDER, "в пакете ${packet.from.id}, канал говорит ${sender.id}")
        }

        if (packet.to != me) return Verdict.Refused(GateRefusal.NOT_FOR_ME, "кому ${packet.to.id}, я ${me.id}")

        val age = nowMs - packet.sentMs
        if (age >= limits.repeatWindowMs) {
            return Verdict.Refused(GateRefusal.STALE, "отправлен ${span(age)} назад, срок ${span(limits.repeatWindowMs)}")
        }
        if (-age > limits.futureMs) {
            return Verdict.Refused(GateRefusal.FROM_FUTURE, "отправлен на ${span(-age)} позже моего «сейчас»")
        }

        forgetOld(nowMs)
        val numbers = accepted.getOrPut(packet.from.id) { HashMap() }
        if (packet.number in numbers) {
            return Verdict.Refused(
                GateRefusal.REPEAT,
                "номер ${GateWire.numberHex(packet.number)} от ${packet.from.id} уже принят",
            )
        }
        numbers[packet.number] = packet.sentMs
        return Verdict.Accepted(packet)
    }

    /** [sender] — ключ того, от кого карточка пришла, по словам канала. Карточка агента — только с его ключа. */
    @Synchronized
    fun receiveCard(raw: ByteArray, sender: PersonKey, nowMs: Long): CardVerdict {
        if (raw.size > limits.maxBytes) {
            return CardVerdict.Refused(GateRefusal.TOO_BIG, "${raw.size} Б, предел ${limits.maxBytes}")
        }
        val card = when (val r = GateWire.readCard(raw)) {
            is GateWire.CardRead.Fail -> return CardVerdict.Refused(r.refusal, r.detail)
            is GateWire.CardRead.Ok -> r.card
        }
        listOf(sender, card.agent).firstOrNull { GateWire.schemeOf(it) !in knownSchemes }?.let {
            return CardVerdict.Refused(GateRefusal.UNKNOWN_SCHEME, "«${GateWire.schemeOf(it)}» в ${it.id}")
        }
        if (!countWithinLimit(sender.id, nowMs)) {
            return CardVerdict.Refused(
                GateRefusal.FLOOD,
                "от ${sender.id} больше ${limits.perWindow} за ${limits.windowMs / 60_000} мин",
            )
        }
        if (card.agent != sender) {
            return CardVerdict.Refused(GateRefusal.NOT_SENDER, "в карточке ${card.agent.id}, канал говорит ${sender.id}")
        }
        return CardVerdict.Accepted(card)
    }

    /** Забыть номера, отправленные [Limits.repeatWindowMs] назад и раньше: такой пакет теперь отказывается как «устарел». */
    private fun forgetOld(nowMs: Long) {
        val senders = accepted.entries.iterator()
        while (senders.hasNext()) {
            val numbers = senders.next().value
            numbers.values.removeIf { sentMs -> nowMs - sentMs >= limits.repeatWindowMs }
            if (numbers.isEmpty()) senders.remove()
        }
    }

    private fun span(ms: Long): String = when {
        ms >= DAY_MS -> "${ms / DAY_MS} сут"
        ms >= 3_600_000L -> "${ms / 3_600_000L} ч"
        else -> "${ms / 60_000L} мин"
    }

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

private const val DAY_MS = 24 * 60 * 60 * 1000L
