package com.uroboros.gate

import com.uroboros.memory.nav.OwnSpeech

/**
 * Своя речь агента из чатов телеграма — кому какие его ответы можно
 * вспомнить и какими словами сказано, где это было. Чистая логика.
 *
 * ЗАЧЕМ. Агент один, а отвечает на экране и в чатах. Без этого объекта экран
 * не знал, что агент говорил в телеграме, а телеграм — что он говорил на
 * экране и в прошлых разговорах того же чата: на «ты же сам говорил…» модель
 * в каждом месте отвечала по своей половине.
 *
 * ЧТО ТАКОЕ СВОЯ РЕЧЬ — правило одно с экраном (memory.nav.OwnSpeech): отсев
 * эха, слов о собеседнике, служебного, ходов под нажимом, поиск по вопросу.
 * Здесь — только то, чем чат отличается:
 *  - ХОД и НАЖИМ — как у обещаний (ChatPromises.turnsOf);
 *  - ЗАПИСИ ХОДА. Чтобы пересказ поданной записи не стал «своими словами»,
 *    у ответа агента в ленте чатов лежат тексты поданных записей
 *    ([pack], llm.ChatLine.recall);
 *  - ГРАНИЦА ([visible]) — та же, что у записей памяти (ChatRecall.Scope):
 *    экран и личка владельца видят все ответы агента; другой человек —
 *    только ответы агента ему, в личке и в группах; группа — только
 *    сказанное агентом в ней, даже владельцу. Ответ, сказанный другому
 *    человеку наедине, до третьего не дойдёт никаким вопросом;
 *  - МЕСТО ([Turn.where][OwnSpeech.Turn.where]) — где и кому сказано, словами
 *    для подписи: модель должна отличать сказанное здесь от сказанного в
 *    другом месте другому человеку.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - всё, чего не умеет OwnSpeech: смысла не знает, слова агента о
 *    собеседнике («ты…») не берёт — из ответов человеку так выпадает то, что
 *    агент говорил о нём самом;
 *  - выдумку от правды не отличает: сказанное агентом «Она ответила.»
 *    вернётся цитатой с подписью «Я говорил…» — подпись правдива, а сказанное
 *    могло быть неправдой;
 *  - у ответов, данных до появления поля записей хода, записей нет — их
 *    пересказ записи читается своей речью;
 *  - адресат — тот, кому агент отвечал; в группе говорят и всем сразу;
 *  - читает последние [READ_LINES] строк ленты чатов — что раньше, не видит;
 *  - сны и облако агента этой речью не питаются (как и речью экрана — см.
 *    OwnSpeech, «АРХИВ ЛЕНТЫ — НЕ МАТЕРИАЛ СНОВ»).
 */
object ChatOwnSpeech {

    /**
     * Сколько последних строк ленты чатов читается за один поиск. Объявленное
     * число, не подобранное: на порядок больше нынешней ленты; цена — одно
     * чтение на ход с вопросом к агенту.
     */
    const val READ_LINES = 3000

    /** Разделитель записей в [pack]: знак, которого в тексте записи не бывает. */
    private const val SEPARATOR = '\u001E'

    /** Тексты записей хода — одной строкой для llm.ChatLine.recall; null — записей нет. */
    fun pack(records: List<String>): String? =
        records.map { it.replace(SEPARATOR, ' ') }.filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())

    /** Обратно к [pack]. */
    fun unpack(recall: String?): List<String> =
        recall?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    /**
     * Кто смотрит. [scope] null — экран телефона; иначе чат телеграма:
     * [chat] — его ключ с веткой (TgWords.chatRefOf), [person] — ключ
     * собеседника этого хода. [shown] — тексты ответов агента, которые модель
     * и так видит в окне этого чата: воспоминанием они не подаются.
     */
    data class Viewer(
        val scope: ChatRecall.Scope?,
        val chat: String? = null,
        val person: String? = null,
        val shown: Set<String> = emptySet(),
    )

    /**
     * Вправе ли [viewer] вспомнить ответ агента [answer]. Ответ без «кому»
     * и без чата — только экрану и владельцу: ошибка в безопасную сторону.
     */
    fun visible(viewer: Viewer, answer: ChatPromises.Line): Boolean = when (viewer.scope) {
        null, ChatRecall.Scope.OWNER -> true
        ChatRecall.Scope.PERSON -> !viewer.person.isNullOrEmpty() && answer.replyTo == viewer.person
        ChatRecall.Scope.GROUP -> !viewer.chat.isNullOrEmpty() && answer.chat == viewer.chat
    }

    /**
     * Ходы своей речи из строк ленты чатов [lines] (все чаты, по порядку),
     * дозволенные [viewer], с местом сказанного — для OwnSpeech.sift вместе с
     * ходами экрана или без них. [ownerKey] — ключ владельца в телеграме,
     * null — не отмечен; [selfLines] — строки о себе (ChatFeed.selfLines).
     */
    fun turns(
        lines: List<ChatPromises.Line>,
        viewer: Viewer,
        ownerKey: String?,
        selfLines: List<String>,
    ): List<OwnSpeech.Turn> {
        val out = ArrayList<OwnSpeech.Turn>()
        for ((_, chatLines) in lines.groupBy { it.chat }) {
            for ((answer, turn) in ChatPromises.turnsOf(chatLines, selfLines)) {
                if (!visible(viewer, answer)) continue
                if (answer.chat == viewer.chat && answer.text in viewer.shown) continue
                out += turn.copy(where = where(viewer, answer, chatLines, ownerKey))
            }
        }
        return out.sortedBy { it.at ?: 0L }
    }

    /** Ходы экрана для чата телеграма: то же, с местом «на экране телефона». */
    fun screenTurns(turns: List<OwnSpeech.Turn>): List<OwnSpeech.Turn> = turns.map { it.copy(where = ON_SCREEN) }

    /**
     * Где и кому сказан [answer] — для того, кто смотрит.
     *
     * Сказанное в этом же чате — «в этом чате»: собеседник тот же. Имя
     * собеседника берётся из его подписи как есть, без падежа — «(личка,
     * собеседник — Пётр)»: падеж по словарю есть не у всякого имени, а
     * подпись без имени оставила бы модель гадать, кому это сказано.
     */
    internal fun where(viewer: Viewer, answer: ChatPromises.Line, chatLines: List<ChatPromises.Line>, ownerKey: String?): String {
        if (viewer.scope != null && answer.chat == viewer.chat) return IN_THIS_CHAT
        val to = answer.replyTo
        if (viewer.scope == ChatRecall.Scope.PERSON) return TO_YOU_IN_GROUP
        val place = if (answer.direct) "личка" else "группа"
        val name = chatLines.lastOrNull { !it.isAgent && it.sender == to }?.signature?.trim()?.takeIf { it.isNotEmpty() }
        val whom = when {
            to != null && to == ownerKey -> if (name != null) "мой владелец $name" else "мой владелец"
            name != null -> name
            else -> null
        }
        return if (whom != null) "в телеграме ($place, собеседник — $whom)" else "в телеграме ($place)"
    }

    const val IN_THIS_CHAT = "в этом чате"
    const val TO_YOU_IN_GROUP = "тебе в группе"
    const val ON_SCREEN = "на экране телефона, моему владельцу"

    /**
     * Сколько мест из [ChatRecall.NARROW_SEATS] узкого отбора отдаётся своей
     * речи, когда она нашлась, — как одно место круга на экране
     * (memory.DolmenCircle.OWN_SPEECH_SEATS).
     */
    const val NARROW_SEATS = 1

    /**
     * Часть строки круга на экране телефона: что из телеграма дошло до окна
     * своей речи. [error] — лента чатов не прочиталась; [allowed] —
     * предложений своей речи из чатов; [found] — из них подошло к вопросу.
     */
    fun screenMeter(error: String?, allowed: Int, found: Int): String =
        if (error != null) "не прочиталось ($error), ищу без него" else "своей речи $allowed, к вопросу $found"

    /**
     * Строка прибора о своей речи — для экрана врат. [asked] — искалась ли (вопрос к агенту или просьба вспомнить);
     * [error] — что не прочиталось; [allowed] — предложений, дозволенных
     * этому месту, из них [fromChats] из телеграма; [found] — подошло к
     * вопросу; [fed] — подано модели.
     */
    fun meter(asked: Boolean, error: String?, allowed: Int, fromChats: Int, found: Int, fed: Int): String = when {
        !asked -> "своя речь: не ищу — вопрос не ко мне и вспомнить не просят"
        error != null -> "своя речь: не прочиталась — $error"
        else -> "своя речь: дозволено $allowed (из телеграма $fromChats), к вопросу $found, подано $fed"
    }
}
