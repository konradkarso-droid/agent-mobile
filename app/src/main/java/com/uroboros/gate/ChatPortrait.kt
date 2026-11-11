package com.uroboros.gate

import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.Sticker
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.PersonFacts
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Portrait
import com.uroboros.memory.nav.RetellTable
import com.uroboros.memory.nav.Retelling

/**
 * Портрет собеседника в чате телеграма — тот же портрет, что на экране
 * (memory.nav.Portrait: какие строки, когда, в каком порядке, факты первыми),
 * для того, с кем идёт разговор. Чистая логика.
 *
 * ЧЕЙ ПОРТРЕТ — по границе чата (ChatRecall.Scope), и строже её нет нужды:
 *  - личка владельца — портрет владельца, его слова с экрана и из телеграма;
 *  - личка другого человека — его портрет, только его собственные слова
 *    (запись с его ключом; Portrait снимает с них его подпись);
 *  - группа — портрета нет: портрет собран из всего, что человек сказал
 *    где угодно, а группа видит только сказанное в ней.
 *
 * ПОДПИСЬ — как на экране (Portrait.recall). Имя с «мой создатель» — только
 * владельцу; другому человеку — подпись без имени: «создатель» про него была
 * бы неправдой.
 *
 * ЧЕГО НЕ УМЕЕТ — всё, чего не умеет портрет; и ещё: на вопрос о
 * собеседнике в группе портрета нет вовсе, даже если спросил владелец.
 */
object ChatPortrait {

    /**
     * Что портрет даёт ходу: [lines] — строки модели (пересказом), [texts] —
     * их тексты, [records] — тексты записей, из которых они взяты (такая
     * запись в отбор того же хода не идёт — Portrait, «КАК ИДЁТ МОДЕЛИ»),
     * [reason] — повод, [nothing] — вопрос о собеседнике, а сказать нечего,
     * [name] — имя для подписи, [meter] — строка экрана врат.
     */
    data class Fed(
        val lines: List<String>,
        val texts: List<String>,
        val records: List<String>,
        val reason: Portrait.Reason,
        val nothing: Boolean,
        val name: String?,
        val meter: String,
    )

    /** Чей портрет смотрит чат с границей [scope]; [sender] — ключ собеседника хода. null — портрета нет. */
    fun personOf(scope: ChatRecall.Scope, sender: String): PersonKey? = when (scope) {
        ChatRecall.Scope.OWNER -> PersonKey.OWNER
        ChatRecall.Scope.PERSON -> PersonKey(sender)
        ChatRecall.Scope.GROUP -> null
    }

    /**
     * Портрет для хода. [records] — все записи памяти; [ownerKey] — ключ
     * владельца в телеграме; [ownerName] — как владелец назвал себя
     * (nav.NameClaim); [question] — реплики этого хода; [table] — таблица
     * разворота (без неё портрет модели не подаётся — цитатой малая модель
     * переворачивает «я» собеседника в своё); [marks] — ночные отметки
     * (PersonFacts) и [outdated] — устаревшие строки (FactAging); [conversationStart] — начало нынешнего разговора чата:
     * запись раньше него подписывается «в прошлом разговоре».
     */
    fun of(
        scope: ChatRecall.Scope,
        sender: String,
        records: List<Sticker>,
        ownerKey: String?,
        ownerName: String?,
        question: String,
        dictionary: LemmaDictionary?,
        table: RetellTable?,
        marks: Map<PersonFacts.Key, Boolean>,
        now: Long,
        conversationStart: Long,
        chatName: String,
        outdated: Set<PersonFacts.Key> = emptySet(),
    ): Fed {
        val head = "Портрет ($chatName): "
        val person = personOf(scope, sender)
            ?: return Fed(emptyList(), emptyList(), emptyList(), Portrait.Reason.NONE, false, null, head + "группе не положен")
        val result = Portrait.of(records, person, current = question, ownerKey = ownerKey, marks = marks, outdated = outdated)
        val asks = Portrait.asksAbout(question)
        val topic = if (asks || dictionary == null) null else Portrait.topic(result, records, question, dictionary, person, ownerKey)
        val reason = Portrait.reasonOf(asks, topic)
        val fed = Portrait.fedLines(reason, result, topic)
        val notFed = if (fed.isNotEmpty() && table == null) "таблица разворота не готова" else null
        val byId = records.associateBy { it.id }
        val retold = if (table == null) emptyList() else fed.mapNotNull { line ->
            val sticker = byId[line.recordId] ?: return@mapNotNull null
            Retelling.retell(line.sentence, table).text?.let { sticker to it }
        }
        val lines = retold.map { (sticker, text) ->
            ProvenanceLabels.retoldForModel(
                sticker, text, now,
                recordEpisode = if (sticker.createdAt < conversationStart) 0 else 1, currentEpisode = 1,
            )
        }
        val meter = head + Portrait.meterLine(
            reason, result, topic,
            fed = reason != Portrait.Reason.NONE && notFed == null,
            whyNotFed = notFed,
            topicOff = if (!asks && dictionary == null) "словарь начальных форм не загружен" else null,
        )
        return Fed(
            lines, retold.map { it.second }, retold.map { it.first.content }, reason,
            nothing = reason == Portrait.Reason.QUESTION && result.chosen.isEmpty() && notFed == null,
            name = if (person == PersonKey.OWNER) ownerName else null,
            meter = meter,
        )
    }

    /**
     * Сообщение с записями хода: портрет под своей подписью первым, потом
     * остальные строки [rest] (Portrait.recall). Повода нет — как без портрета
     * (ChatRecall.message).
     */
    fun message(fed: Fed, rest: List<String>): String? =
        if (fed.reason == Portrait.Reason.NONE) ChatRecall.message(rest)
        else Portrait.recall(
            fed.lines + rest, fed.lines.toSet(), fed.nothing,
            name = fed.name, topic = fed.reason == Portrait.Reason.TOPIC,
        )
}
