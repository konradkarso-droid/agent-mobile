package com.uroboros.gate

import com.uroboros.llm.ChatLine
import com.uroboros.memory.desk.Desk
import com.uroboros.memory.desk.Promises
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.OwnSpeech
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place

/**
 * Обещания агента в чатах телеграма — с адресом: кому и где. Чистая логика.
 *
 * ЧТО ТАКОЕ ОБЕЩАНИЕ — правило одно с экраном (memory.desk.Promises.find):
 * форма предложения, срок, отсев. Здесь — только то, чем чат отличается:
 *  - ХОД. На экране ход — реплика владельца и ответ. В чате ответ агента
 *    помнит, кому он ([Line.replyTo]), и ходом считаются строки этого
 *    собеседника после прошлого ответа агента в том же чате (в группе между
 *    ними пишут и другие — их слова не его). Таких строк нет — берётся его
 *    последняя строка в этом чате до ответа;
 *  - НАЖИМ. Строка прочности в ленте чата не хранится — она пересчитывается
 *    тем же правилом, что на ходе (ChatFeed.prochnostNote): по тексту хода,
 *    прежним ответам агента в этом чате и нынешним строкам о себе. Обещание
 *    под нажимом не берётся, эхо строки прочности отсеивается — как на экране;
 *  - АДРЕС (Promises.To): имя в дательном и творительном падеже по
 *    NameCases, «личка» или «группа», ключ чата. Владелец, отмеченный «это
 *    я», — «вам».
 *
 * ЧЕГО НЕ УМЕЕТ: всё, чего не умеет Promises.find; адрес — тот, кому агент
 * отвечал, хотя в группе обещают и всем сразу; названия группы в ленте чатов
 * нет; строки о себе — нынешние, а не того хода: сменилась стена — строка
 * прочности старого хода может выйти другой.
 */
object ChatPromises {

    /**
     * Строка ленты чата для обещаний: [chat] — ключ чата с веткой
     * (TgWords.chatRefOf); [replyTo] — у своего ответа агента — кому ответ;
     * [direct] — личка.
     */
    data class Line(
        val chat: String,
        val isAgent: Boolean,
        val sender: String,
        val signature: String,
        val text: String,
        val at: Long,
        val replyTo: String?,
        val direct: Boolean,
    )

    /** Строка ленты чатов (llm.ChatLine) — для [find]. */
    fun lineOf(l: ChatLine) = Line(
        chat = TgWords.chatRefOf(PersonKey(l.chat), l.thread),
        isAgent = l.isAgent,
        sender = l.sender,
        signature = l.signature,
        text = l.text,
        at = l.at,
        replyTo = l.replyTo,
        direct = l.place == Place.DIRECT.name,
    )

    /**
     * Обещания в строках [lines] (все чаты, по порядку) за срок до [now].
     * [ownerKey] — ключ владельца в телеграме, null — не отмечен; [selfLines]
     * — строки о себе из стены (ChatFeed.selfLines), для строки прочности.
     */
    fun find(lines: List<Line>, now: Long, dictionary: LemmaDictionary?, ownerKey: String?, selfLines: List<String>): Promises.Found {
        if (dictionary == null) return Promises.Found.Silent(Promises.NO_DICTIONARY)
        val out = ArrayList<Promises.Promise>()
        var undated = 0
        var pressured = 0
        for ((_, chatLines) in lines.groupBy { it.chat }) {
            val pending = HashMap<String, MutableList<String>>()
            val last = HashMap<String, String>()
            val said = ArrayList<String>()
            for (line in chatLines) {
                if (!line.isAgent) {
                    pending.getOrPut(line.sender) { ArrayList() } += line.text
                    last[line.sender] = line.text
                    continue
                }
                val to = line.replyTo ?: continue
                val question = pending.remove(to)?.joinToString("\n") ?: last[to].orEmpty()
                // На ходе строка прочности строится по последней строке собеседника.
                val note = ChatFeed.prochnostNote(question.substringAfterLast('\n'), said.toList(), selfLines)
                said += line.text
                val turn = OwnSpeech.Turn(
                    answer = line.text,
                    question = question,
                    records = emptyList(),
                    at = line.at,
                    serviceBlocks = listOfNotNull(note),
                )
                when (val f = Promises.find(listOf(turn), now, dictionary)) {
                    is Promises.Found.Silent -> return f
                    is Promises.Found.Ok -> {
                        undated += f.undated
                        pressured += f.pressureSkipped
                        val address = address(chatLines, to, line, ownerKey, dictionary)
                        f.promises.mapTo(out) { it.copy(to = address) }
                    }
                }
            }
        }
        return Promises.Found.Ok(out.sortedByDescending { it.at }, undated, pressured)
    }

    /** Обещания одного чата [chat] из [found] — что видит модель в этом чате. */
    fun ofChat(found: Promises.Found, chat: String): List<Promises.Promise> =
        (found as? Promises.Found.Ok)?.promises.orEmpty().filter { it.to?.chat == chat }

    /**
     * Строка модели в чате: до [Desk.WINDOW] обещаний этого чата, новые
     * первыми, и сколько ещё — как у взгляда на приборы на экране. null —
     * обещаний нет. [day] — день словами («30 октября»).
     */
    fun chatText(promises: List<Promises.Promise>, day: (Long) -> String): String? {
        if (promises.isEmpty()) return null
        val shown = promises.take(Desk.WINDOW).joinToString("; ") { p ->
            val to = p.to
            val whom = when {
                to == null -> ""
                p.together -> "вместе ${to.withWhom}, "
                else -> "${to.dative}, "
            }
            "$whom${day(p.at)} — «${p.sentence}»"
        }
        val rest = promises.size - Desk.WINDOW
        return "Что я обещал в этом чате за ${Promises.DAYS} дн.: $shown." + if (rest > 0) " Ещё обещаний: $rest." else ""
    }

    private fun address(lines: List<Line>, to: String, answer: Line, ownerKey: String?, dictionary: LemmaDictionary): Promises.To {
        val where = if (answer.direct) "личка" else "группа"
        if (to == ownerKey) return Promises.To("вам", "с вами", where, answer.chat)
        val name = lines.lastOrNull { it.sender == to && !it.isAgent }?.signature?.trim()?.split(' ')?.firstOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return Promises.To("собеседнику", "с собеседником", where, answer.chat)
        val forms = NameCases.of(name, dictionary)
        return Promises.To(forms.of(NameCases.Case.DAT), "с " + forms.of(NameCases.Case.INS), where, answer.chat)
    }
}
