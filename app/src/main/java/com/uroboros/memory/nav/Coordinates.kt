package com.uroboros.memory.nav

import com.uroboros.memory.SourceKind

/**
 * Координаты реплики и записи — СЧИТАЮТСЯ НА ЛЕТУ, А НЕ ХРАНЯТСЯ.
 *
 * Всё, что выводится из уже записанного (кто сказал, где, кому, о ком, эпизод,
 * соседи), здесь вычисляется. Хранить выводимое значило бы завести поле,
 * которое надо не забыть заполнить и которое молча устареет при смене
 * правила. Цена: после правки правила прошлый показ меняется — для прибора и
 * отбора это приемлемо.
 *
 * Оси не сводятся в один балл и не смешиваются со слоем и доверием.
 *
 * Чистый объект: ни базы, ни Android.
 */
object Coordinates {

    /**
     * Кто сказал запись памяти — по её `source`. null — не ясно: распознанное
     * на картинке («я» там — ни владелец, ни агент) и незнакомое значение.
     */
    fun speakerOf(source: String): PersonKey? = when (source) {
        SourceKind.USER_STATED.name -> PersonKey.OWNER
        SourceKind.AGENT_INFERRED.name -> PersonKey.AGENT
        else -> null
    }

    /**
     * Кому сказано. На экране собеседников двое: реплика владельца — агенту,
     * ответ агента — владельцу. В группе адресат — ответ на сообщение или
     * обращение по имени; этого здесь нет (см. Docking).
     */
    fun addresseeOf(speaker: PersonKey?, place: Place = Place.SCREEN): PersonKey? = when {
        place != Place.SCREEN -> null
        speaker == PersonKey.OWNER -> PersonKey.AGENT
        speaker == PersonKey.AGENT -> PersonKey.OWNER
        else -> null
    }

    /**
     * О ком запись: набор персон и признак «не ясно». Пустой набор без «не
     * ясно» — о мире.
     */
    data class About(val persons: Set<PersonKey>, val unclear: Boolean) {
        val aboutWorld: Boolean get() = persons.isEmpty() && !unclear

        companion object {
            val UNCLEAR = About(emptySet(), unclear = true)
        }
    }

    /**
     * О ком — от точки отправления: FIRST → кто сказал, SECOND → кому сказано,
     * «мы с тобой» → оба. Кто сказал не ясен — и «о ком» не ясно.
     */
    fun aboutOf(text: String, speaker: PersonKey?, addressee: PersonKey? = addresseeOf(speaker)): About {
        if (speaker == null) return About.UNCLEAR
        val form = PersonForm.of(text)
        val persons = HashSet<PersonKey>()
        if (form.aboutSpeaker) persons += speaker
        if (form.aboutAddressee && addressee != null) persons += addressee
        return About(persons, form.unclear)
    }

    /** «О ком» записи памяти целиком: кто сказал — по `source`. */
    fun aboutRecord(content: String, source: String): About = aboutOf(content, speakerOf(source))

    /** Подпись «кто → о ком» для прибора: «владелец → владелец+агент». */
    fun mark(speaker: PersonKey?, about: About): String {
        val who = nameOf(speaker)
        val whom = when {
            about.persons.isNotEmpty() ->
                listOf(PersonKey.OWNER, PersonKey.AGENT).filter { it in about.persons }.joinToString("+") { nameOf(it) } +
                    if (about.unclear) "+не ясно" else ""
            about.unclear -> "не ясно"
            else -> "о мире"
        }
        return "$who → $whom"
    }

    fun nameOf(key: PersonKey?): String = when (key) {
        PersonKey.OWNER -> "владелец"
        PersonKey.AGENT -> "агент"
        null -> "не ясно"
        else -> key.id
    }

    // --- Время хода ---

    /** Откуда известно время хода. */
    enum class TimeSource {
        /** Записано при ходе (JournalTurn.at). */
        RECORDED,

        /** По записи памяти с тем же текстом, что вопрос хода. */
        FROM_MEMORY,

        /** Не позже закрытия разговора (JournalArchiveTurn.archivedAt). */
        NOT_LATER_THAN_CLOSE,

        /** Неизвестно: ход в открытой ленте, записи с его текстом нет. */
        UNKNOWN,
    }

    data class TurnTime(val at: Long?, val source: TimeSource)

    /**
     * Время хода. Старым ходам (до столбца `at`) оно выводится на лету и НЕ
     * дописывается: запись памяти с тем же текстом, что `question`, — её
     * `createdAt` (автозапись кладёт набранное владельцем целиком); нет такой —
     * «не позже закрытия разговора».
     *
     * @param memoryCreatedAt время записи памяти по тексту вопроса, или null.
     */
    fun turnTime(
        recorded: Long?,
        question: String,
        archivedAt: Long?,
        memoryCreatedAt: (String) -> Long?,
    ): TurnTime {
        if (recorded != null) return TurnTime(recorded, TimeSource.RECORDED)
        if (question.isNotBlank()) {
            memoryCreatedAt(question)?.let { return TurnTime(it, TimeSource.FROM_MEMORY) }
        }
        if (archivedAt != null) return TurnTime(archivedAt, TimeSource.NOT_LATER_THAN_CLOSE)
        return TurnTime(null, TimeSource.UNKNOWN)
    }

    // --- Адрес вопроса ---

    /** О ком вопрос владельца. */
    enum class Address { AGENT, OWNER, BOTH, UNDEFINED }

    /**
     * Адрес вопроса от точки отправления (на экране говорит владелец, отвечает
     * агент):
     *  - есть оборот темы («обо мне», «про тебя», «о нас» — PersonForm.topicPersons)
     *    → адрес по нему, лица остального вопроса не смотрятся: в «Что знаешь
     *    обо мне?» глагол «знаешь» — рамка вопроса, а не его тема. «Обо мне» →
     *    владелец, «о тебе» → агент, оба оборота или «о нас» → оба;
     *  - иначе по лицам всего вопроса (PersonForm.of):
     *  - SECOND → агент; FIRST без SECOND → владелец; оба или «мы с тобой» → оба;
     *  - только повелительное → не определён: это просьба, а не вопрос о ком-то;
     *  - «не ясно» (голое «мы», прошедшее без местоимения) → не определён:
     *    сомнение не выдаётся за адрес;
     *  - без лица на экране и в личке → [previous], если прошлый вопрос
     *    владельца в том же эпизоде имел адрес (продолжение: «А про чай?»),
     *    иначе агент — вопрос без лица задан тому, кому задан. Это объявленное
     *    правило, а не понимание: безличный вопрос о мире тоже получит адрес
     *    «агент»;
     *  - в группе правило без лица не действует (объявлено; в коде сейчас
     *    групп нет).
     *
     * @param previous адрес прошлого вопроса владельца В ТОМ ЖЕ ЭПИЗОДЕ, или null.
     */
    fun questionAddress(question: String, previous: Address? = null, place: Place = Place.SCREEN): Address {
        val topic = PersonForm.topicPersons(question)
        if (topic.isNotEmpty()) {
            val owner = PersonForm.Person.FIRST in topic
            val agent = PersonForm.Person.SECOND in topic
            return when {
                PersonForm.Person.WE_WITH_YOU in topic || (owner && agent) -> Address.BOTH
                owner -> Address.OWNER
                else -> Address.AGENT
            }
        }
        val form = PersonForm.of(question)
        return when {
            form.aboutSpeaker && form.aboutAddressee -> Address.BOTH
            form.aboutAddressee -> Address.AGENT
            form.aboutSpeaker -> Address.OWNER
            form.unclear -> Address.UNDEFINED
            form.imperative -> Address.UNDEFINED
            place == Place.GROUP -> Address.UNDEFINED
            previous != null && previous != Address.UNDEFINED -> previous
            else -> Address.AGENT
        }
    }

    /**
     * Адрес нового вопроса с учётом прошлых вопросов владельца той же ленты:
     * адреса считаются по порядку, продолжение наследует адрес прошлого
     * вопроса только в том же эпизоде (Episodes.startsNew — тишина дольше
     * часа его обрывает). Ходы, начатые агентом (пустой вопрос), пропускаются.
     *
     * @param history вопросы ленты и время их хода (null — неизвестно).
     */
    fun addressInRibbon(history: List<Pair<String, Long?>>, question: String, now: Long): Address {
        var previous: Address? = null
        var prevAt: Long? = null
        for ((q, at) in history) {
            if (q.isBlank()) continue
            if (Episodes.startsNew(prevAt, at, closedBetween = false)) previous = null
            previous = questionAddress(q, previous)
            if (at != null) prevAt = at
        }
        if (Episodes.startsNew(prevAt, now, closedBetween = false)) previous = null
        return questionAddress(question, previous)
    }

    /**
     * Адрес последнего хода ленты, начатого владельцем, — пересчётом по
     * [addressInRibbon] с его же прошлыми вопросами. null — такого хода нет.
     *
     * Для прибора: строки хода живут, пока открыт экран, а адрес — чистый
     * расчёт по ленте, его можно показать и после того, как экран создан
     * заново. Считается по нынешнему правилу, не по тому, что стояло в момент
     * хода (см. описание объекта). Время хода неизвестно — берётся [now].
     */
    fun lastRibbonAddress(history: List<Pair<String, Long?>>, now: Long): Address? {
        val last = history.indexOfLast { it.first.isNotBlank() }
        if (last < 0) return null
        val (question, at) = history[last]
        return addressInRibbon(history.subList(0, last), question, at ?: now)
    }

    /** Слова адреса для прибора. */
    fun addressLabel(address: Address): String = when (address) {
        Address.AGENT -> "агент"
        Address.OWNER -> "владелец"
        Address.BOTH -> "владелец и агент"
        Address.UNDEFINED -> "не определён"
    }
}
