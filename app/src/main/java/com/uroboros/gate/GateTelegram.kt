package com.uroboros.gate

import com.uroboros.memory.nav.PersonKey

/**
 * Сообщение телеграма — то, что канал отдаёт вратам: кто прислал (ключ канала),
 * в каком чате, исправлено ли, время сервера и файл, если он есть. Поля —
 * из сообщения Bot API: `chat.id`, `chat.type == "private"`, `from.id`,
 * `sender_chat.id`, пришло ли оно как `edited_message`, `date` (секунды
 * сервера), содержимое `document`.
 */
class TgMessage(
    val chatId: Long,
    val privateChat: Boolean,
    val fromId: Long?,
    val senderChatId: Long?,
    val edited: Boolean,
    val dateSec: Long,
    val file: ByteArray?,
) {
    /** Время сервера телеграма — свидетель для своих часов ([GateClock.selfCheck]). */
    val serverMs: Long get() = dateSec * 1000
}

/**
 * Телеграм → врата: какие сообщения идут во врата и с каким ключом канала.
 *
 * КЛЮЧ. Любой аккаунт телеграма — человек или бот — `tg:user:<номер>`, группа —
 * `tg:chat:<номер>`. Номер у телеграма постоянный, у людей и ботов он из одного
 * ряда, поэтому вид ключа по «бот или нет» не нужен: бот ли это — показание
 * канала, агент ли — моё знание (агентом бывает и обычный аккаунт). Ключ
 * «по виду» дал бы одному аккаунту два ключа при первой ошибке, и доверенность
 * к нему «не нашлась» бы. Имя и `@username` — не ключ: ник меняют, и
 * освободившийся занимает другой. Ключи для телеграма делаются только здесь.
 *
 * ВО ВРАТА ИДЁТ ТОЛЬКО ПАКЕТ — файл, у которого шапка одной из форм врат
 * ([GateWire.formOf]), присланный в личку и от своего имени. Почему файлом, а
 * не текстом: текст в телеграме ограничен 4096 знаками, а пакет до 8 КБ
 * кириллицей в него не влезет; файл приходит байт в байт.
 *
 * НЕ ИДЁТ ВО ВРАТА:
 *  - правка уже отправленного: принятый пакет не меняется — правка переписала
 *    бы то, на что уже ответили «принял»;
 *  - сообщение от имени чата (анонимный админ, канал): телеграм ставит тогда
 *    отправителем один общий служебный аккаунт на всех анонимов во всех
 *    группах — по нему все они стали бы одним ключом, с одним потоком и одними
 *    доверенностями. Агент от имени чата пакеты не шлёт;
 *  - сообщение без отправителя;
 *  - сообщение не в личке: пакеты ходят только в личке бот ↔ бот. В группе их
 *    видели бы все, а поручительство, например, раскрывает круг знакомств;
 *  - не файл или файл без шапки врат — это не пакет; обычные сообщения людей —
 *    не дело врат.
 *
 * ПЕРЕСЛАННЫЙ пакет здесь не отсеивается: отправитель у него — пересылающий, а
 * в пакете назван другой, и врата откажут «отправитель не тот» ([GateIntake]).
 *
 * ЧЕГО НЕ УМЕЕТ: в сеть не ходит и файлы не скачивает — получает готовое;
 * пакеты в группе не принимает; подлинность ключа — не больше, чем у токена
 * бота: двойника с тем же токеном отсюда не видно.
 *
 * Чистый объект: ни сети, ни базы, ни Android.
 */
object GateTelegram {

    /** Как сервер называется в строках прибора ([GateClock.selfCheck]). */
    const val SERVER = "сервера телеграма"

    fun userKey(id: Long) = PersonKey("tg:user:$id")

    fun chatKey(id: Long) = PersonKey("tg:chat:$id")

    /** Номер аккаунта из ключа [userKey]; null — ключ не того вида. В личке с ботом номер чата — он же. */
    fun userId(key: PersonKey): Long? = key.id.removePrefix("tg:user:").takeIf { key.id.startsWith("tg:user:") }?.toLongOrNull()

    sealed class Incoming {
        /** Нести во врата: в приём формы [form], с ключом канала [sender]. */
        class Gate(val sender: PersonKey, val form: GateWire.Form, val raw: ByteArray) : Incoming()

        /** Не во врата; [why] — для строки прибора. */
        data class NotGate(val why: String) : Incoming() {
            val words: String get() = "Не пакет: $why"
        }
    }

    /**
     * Скачивать ли файл размером [fileSize] байт: больше предела размера врат
     * — не скачивать, всё равно не примут. Размер у телеграма есть в сообщении
     * до скачивания; нет размера — скачивать (врата проверят сами).
     */
    fun worthFetching(fileSize: Long?, limits: GateIntake.Limits = GateIntake.Limits.DECLARED): Boolean =
        fileSize == null || fileSize <= limits.maxBytes

    fun incoming(m: TgMessage): Incoming {
        if (m.edited) return Incoming.NotGate("правка уже отправленного — во врата не идёт")
        if (m.senderChatId != null) return Incoming.NotGate("от имени чата ${chatKey(m.senderChatId).id} — не принимаю")
        val from = m.fromId ?: return Incoming.NotGate("нет отправителя")
        if (!m.privateChat) return Incoming.NotGate("не в личке (${chatKey(m.chatId).id}) — пакеты только в личке")
        val raw = m.file ?: return Incoming.NotGate("не файл")
        val form = GateWire.formOf(raw) ?: return Incoming.NotGate("файл без шапки врат")
        return Incoming.Gate(userKey(from), form, raw)
    }
}
