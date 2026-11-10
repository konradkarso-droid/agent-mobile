package com.uroboros.memory

/**
 * Согласие владельца на один вид действий одного компонента — на срок или
 * без срока ([NO_TERM]), с переживанием перезапусков. Сегодня одно: ответы
 * агента в телеграме, и даётся оно без срока.
 *
 * ЗАЧЕМ, ЕСЛИ ЕСТЬ [TaskAuthorization]. Ответ в телеграм весит в [ActionGate]
 * 8,0 при пороге 7,0 (уходит с телефона, необратим, написан моделью) — без
 * разрешения человека ворота его не пустят, и это верно: барьер подгонять под
 * действие нельзя. Разрешение задачи живёт час и только в памяти процесса, а
 * процесс на MIUI выгружается много раз в сутки — после каждой выгрузки агент
 * замолкал бы, пока владелец снова не нажмёт. Решение владельца: разрешать
 * один раз кнопкой, и пусть согласие переживает перезапуски. Срок в семь
 * дней он снял позже: про продление легко забыть, и агент замолкал посреди
 * разговора. Без срока согласие держится, пока его не снимут.
 *
 * ЧЕМ ЭТО НЕ ЯВЛЯЕТСЯ:
 *  - не разрешение задачи: у него нет задачи, только тип и компонент. Слить
 *    два — значит продлить разрешение задачи до недели, а с ним и запуск
 *    сгенерированного кода;
 *  - не снижение веса: в журнале действий остаётся честная восьмёрка, рядом —
 *    чьё согласие её покрыло;
 *  - не глобальное «действия включены»: область — ровно пара тип + компонент
 *    ([covers]); всё прочее по-прежнему решает порог.
 *
 * ГДЕ РОЖДАЕТСЯ — два места, и оба можно перечислить:
 *  - [grantedByOwner] — только обработчик кнопки за замком экрана (PresenceLock);
 *  - [restored] — только чтение сохранённого согласия при подъёме процесса.
 * Обход, как и у [TaskAuthorization], технически возможен, но виден: третье
 * место создания и есть сигнал.
 *
 * СНИМАЕТСЯ кнопкой владельца, сроком (если он есть) и аварийным стопом:
 * стоп проверяется первой строкой [ActionGate.evaluate], выше этой проверки.
 *
 * ЧЕГО БЕЗ СРОКА БОЛЬШЕ НЕТ: забытое согласие само не погаснет. Телефон в
 * чужих руках или бот, о котором забыли, отвечает, пока владелец не нажмёт
 * «Снять» или стоп.
 *
 * Чистый объект: ни Android, ни базы. Хранение — у вызывающего.
 */
class StandingConsent private constructor(
    val type: ActionType,
    /** Компонент, которому разрешено; сравнивается с `requestedBy` запроса. */
    val requestedBy: String,
    /** Кто дал — для журнала, не для решения. */
    val grantedBy: String,
    val grantedAt: Long,
    val expiresAt: Long,
) {
    val id: String get() = "consent-$grantedAt"

    fun isExpired(now: Long): Boolean = now >= expiresAt

    /** Согласие без срока: держится, пока не снято. */
    val openEnded: Boolean get() = expiresAt == NO_TERM

    /** Подходит ли под запрос: тип и компонент совпали, срок не истёк. Любое несовпадение — нет. */
    fun covers(type: ActionType, requestedBy: String, now: Long): Boolean =
        !isExpired(now) && this.type == type && this.requestedBy == requestedBy

    fun describe(): String = "согласие $id: $type для $requestedBy, выдал $grantedBy"

    companion object {
        /**
         * Срок по умолчанию для согласия на срок — 7 дней: объявленное число,
         * не замер. Ответы в телеграме им больше не пользуются ([NO_TERM]).
         */
        const val DEFAULT_TERM_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Конец согласия без срока: момент, который не наступает. */
        const val NO_TERM: Long = Long.MAX_VALUE

        /** Только обработчик кнопки владельца за замком экрана. */
        fun grantedByOwner(
            type: ActionType,
            requestedBy: String,
            grantedBy: String,
            now: Long,
            termMs: Long = DEFAULT_TERM_MS,
        ): StandingConsent = StandingConsent(type, requestedBy, grantedBy, now, if (termMs == NO_TERM) NO_TERM else now + termMs)

        /**
         * Только чтение сохранённого. Истёкшее и несуразное (конец не позже
         * начала, начало в будущем больше чем на сутки — часы сбиты или файл
         * чужой) — null: не согласие.
         */
        fun restored(
            type: ActionType,
            requestedBy: String,
            grantedBy: String,
            grantedAt: Long,
            expiresAt: Long,
            now: Long,
        ): StandingConsent? {
            if (expiresAt <= grantedAt) return null
            if (grantedAt > now + 24L * 60 * 60 * 1000) return null
            if (now >= expiresAt) return null
            return StandingConsent(type, requestedBy, grantedBy, grantedAt, expiresAt)
        }
    }
}
