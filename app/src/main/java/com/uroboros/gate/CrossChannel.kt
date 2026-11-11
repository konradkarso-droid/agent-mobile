package com.uroboros.gate

import com.uroboros.llm.ChatLine
import com.uroboros.llm.Silence
import com.uroboros.memory.ProvenanceLabels
import com.uroboros.memory.nav.LemmaDictionary
import com.uroboros.memory.nav.PersonKey
import com.uroboros.memory.nav.Place as ChatPlace
import java.time.ZoneId

/**
 * Строка между каналами — где ещё агент говорил с прошлой реплики в этом
 * месте и о чём. Собирает её код из лент, без модели. Чистая логика.
 *
 * ЗАЧЕМ. Агент один, а говорит на экране телефона и в чатах телеграма, и
 * каждое место видит только свою ленту. На «о чём вы говорили с Пракс?» или
 * «я писал тебе в телеграме» модель отвечала по пустому месту. Своя речь
 * (memory.nav.OwnSpeech, ChatOwnSpeech) этого не закрывает: она подаёт
 * цитаты агента по вопросу, а не сам факт разговора, и слова агента о
 * собеседнике отсеивает.
 *
 * КОГДА. На реплике человека, если после прошлой реплики в этом месте агент
 * отвечал где-то ещё ([find]). Прошлой реплики здесь нет или её время не
 * известно — смотрится [LOOKBACK_MS] назад. В каждом другом месте берётся
 * последний его разговор (правило ChatFeed.conversations) после этого
 * момента, и только если агент в нём отвечал. Мест — не больше [PLACES],
 * сначала недавние.
 *
 * ГРАНИЦА — запретом, как у своей речи (ChatOwnSpeech.visible), и шире её
 * в одном: экран и личка владельца узнают и о разговорах с другими людьми,
 * со словами, что звучали у собеседника. Это решение владельца: экран врат
 * и так показывает ему всё. Другой человек и группа не узнают ни о каком
 * другом разговоре ([Viewer.OTHER]) — строка для них не собирается вовсе.
 *
 * ЧТО ПОДАЁТСЯ — одно и то же экрану и личке: где, с кем, когда (подпись
 * времени — ProvenanceLabels.ageForModel), сколько раз я ответил и слова,
 * что звучали чаще, — правило головы нити (ChatThread.previous): только из
 * слов людей, имена темой не считаются.
 *
 * ПОЧЕМУ БЕЗ ЦИТАТ. С последним обменом цитатой («Последним мне написали:
 * «…», я ответил: «…»») модель путала, кто что сказал: свои слова и слова
 * другого человека пересказывала словами того, с кем говорит сейчас («ты
 * ответил…»), — при любой испробованной подписи цитаты. Со словами-темами
 * без цитат такой путаницы не было, а тема называлась верно. Цена — ответ о
 * другом разговоре беден подробностями; на прямой вопрос подробности даёт
 * своя речь.
 *
 * МЕСТО В ЗАПРОСЕ — строкой системы к текущей реплике: на экране — блоком
 * реплики (llm.ConversationJournal.composeUserContent), он ложится в ленту и
 * виден модели дальше; в чате — концом нити перед текущей репликой
 * (ChatFeed.messages), он в ленту чата не ложится, и на следующем ходе его
 * уже нет. Блок служебный («Мне от системы») и своей речью потом не
 * возвращается (llm.EchoCheck.serviceBlocks).
 *
 * В ПАМЯТЬ НЕ ИДЁТ. Строка — догадка кода по словам: записью, сном и
 * материалом суда она не становится.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - тема — по словам, не по смыслу; смены темы не видит;
 *  - модель по одним словам-темам досочиняет реплики («Пракс сказал:
 *    «…»») — строка этого не удерживает, как и без неё;
 *  - в чате строка видна только в ходе перехода — вопрос о другом разговоре
 *    второй репликой она не покрывает (его покрывает своя речь, если вопрос к
 *    агенту);
 *  - ходы экрана без записанного времени не видит: «когда» у них нет;
 *  - из каждого места — только последний разговор после прошлой реплики
 *    здесь; что было раньше в том же месте, не называет;
 *  - группа называется «группой телеграма» без названия: в ленте чатов его нет.
 */
object CrossChannel {

    /**
     * Насколько назад смотреть, когда прошлой реплики здесь нет или её время
     * не известно. Объявленное число, не подобранное: двое суток —
     * разговор позавчера ещё «перед этим», дальше подпись времени уже «на
     * днях», и строка говорила бы о давнем как о недавнем.
     */
    const val LOOKBACK_MS = 48L * 60 * 60 * 1000

    /** Сколько других мест называть. Объявленное число, не подобранное: сейчас мест три. */
    const val PLACES = 3

    /** Кто смотрит: экран телефона, личка владельца или кто-то ещё. */
    enum class Viewer { SCREEN, OWNER_DM, OTHER }

    /**
     * Строки одного места по порядку. [key] — ключ чата с веткой
     * (TgWords.chatRefOf) или [SCREEN_KEY]; [direct] — личка; [ownerChat] —
     * личка владельца. У экрана [lines] — вопрос владельца и ответ агента
     * каждого хода ([screenLines]).
     */
    data class Place(val key: String, val direct: Boolean, val ownerChat: Boolean, val lines: List<ChatFeed.Line>)

    /** Разговор в другом месте, найденный [find]: [head] — его выжимка по правилу головы нити. */
    data class Seen(val place: Place, val head: ChatThread.Head)

    sealed class Result {
        /** Подать; [seen] — сначала недавние. */
        data class Fed(val seen: List<Seen>) : Result()

        /** Нечего подавать. */
        object Nothing : Result()

        /** Этому чату не положено. */
        object NotAllowed : Result()
    }

    const val SCREEN_KEY = "экран"

    /**
     * Экран одним местом. [turns] — ходы архива и открытой ленты по порядку:
     * вопрос владельца, ответ агента, записанное время хода. Ход без времени
     * пропускается: «когда» у него нет.
     */
    fun screenPlace(turns: List<Triple<String, String, Long?>>): Place = Place(
        SCREEN_KEY, direct = false, ownerChat = false,
        lines = turns.flatMap { (question, answer, at) ->
            if (at == null) emptyList() else listOfNotNull(
                question.takeIf { it.isNotBlank() }?.let { ChatFeed.Line(false, "", it, true, at = at) },
                ChatFeed.Line(true, "", answer, true, at = at),
            )
        },
    )

    /**
     * Чаты телеграма местами — из строк ленты чатов [rows] (читаемых моделью,
     * по порядку). Строка молчания агента (llm.Silence.RING) ответом не
     * считается: в телеграм она не уходила. [ownerKey] — ключ владельца в
     * телеграме, null — не отмечен.
     */
    fun chatPlaces(rows: List<ChatLine>, ownerKey: String?): List<Place> =
        rows.filterNot { it.isAgent && it.ring == Silence.RING }
            .groupBy { TgWords.chatRefOf(PersonKey(it.chat), it.thread) }
            .map { (key, lines) ->
                val direct = lines.first().place == ChatPlace.DIRECT.name
                Place(
                    key, direct,
                    ownerChat = direct && ownerKey != null && lines.any { !it.isAgent && it.sender == ownerKey },
                    lines = lines.map { ChatFeed.Line(it.isAgent, it.signature, it.text, true, id = it.id, at = it.at, sender = it.sender) },
                )
            }

    /**
     * Разговоры в других местах. [hereKey] — это место; [hereLastAt] — время
     * прошлой реплики человека здесь (до текущей), null — нет или не
     * известно; [now] — время текущей.
     */
    fun find(
        viewer: Viewer,
        hereKey: String,
        hereLastAt: Long?,
        now: Long,
        places: List<Place>,
        dictionary: LemmaDictionary?,
        agentNames: List<String> = emptyList(),
        installedAt: Long? = null,
    ): Result {
        if (viewer == Viewer.OTHER) return Result.NotAllowed
        val since = maxOf(hereLastAt ?: Long.MIN_VALUE, now - LOOKBACK_MS)
        val seen = places.asSequence()
            .filter { it.key != hereKey }
            .mapNotNull { place ->
                val lines = place.lines.filter { it.at in (since + 1)..now }
                if (lines.none { it.isAgent }) return@mapNotNull null
                ChatThread.previous(lines, dictionary, agentNames, installedAt)
                    ?.takeIf { it.agentAnswers > 0 }?.let { Seen(place, it) }
            }
            .sortedByDescending { it.head.until ?: it.head.since }
            .take(PLACES)
            .toList()
        return if (seen.isEmpty()) Result.Nothing else Result.Fed(seen)
    }

    /** Строка модели. [viewer] — экран или личка владельца; [now] — время текущей реплики. */
    fun text(viewer: Viewer, seen: List<Seen>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        seen.joinToString(" ") { s ->
            val age = ProvenanceLabels.ageForModel(s.head.until ?: s.head.since, now, zone)
            val answered = "я ответил ${s.head.agentAnswers} ${times(s.head.agentAnswers)}"
            val words = if (s.head.words.isEmpty()) "" else " Слова, что звучали чаще: ${s.head.words.joinToString(", ")}."
            "Перед этим, ${age}, я говорил ${where(viewer, s)}: $answered.$words"
        }

    /** Где и с кем — словами для [viewer]. */
    internal fun where(viewer: Viewer, s: Seen): String {
        val p = s.place
        if (p.key == SCREEN_KEY) return "с тобой на экране телефона"
        if (p.ownerChat && viewer == Viewer.SCREEN) return "с тобой в телеграме, в личке"
        val names = s.head.voices.names.filter { it.isNotBlank() }
        return if (p.direct) "в телеграме, в личке с ${names.firstOrNull() ?: "собеседником"}"
        else "в группе телеграма" + if (names.isEmpty()) "" else " (писали ${names.joinToString(", ")})"
    }

    /**
     * Строка прибора — «Подробно» на экране и экран врат. [error] — ленты не
     * прочитались. Печатается всегда: молчащая неотличима от сломанной.
     */
    fun meter(result: Result?, error: String?, clock: (Long) -> String): String = "Между каналами: " + when {
        error != null -> "не прочиталось ($error), хожу без строки"
        result == null -> "не считалось"
        result is Result.NotAllowed -> "этому чату не положено"
        result is Result.Nothing -> "нет — с прошлой реплики здесь в других местах не говорил"
        else -> "подано — " + (result as Result.Fed).seen.joinToString("; ") { s ->
            val place = if (s.place.key == SCREEN_KEY) "экран" else if (s.place.direct) "личка ${s.head.voices.names.firstOrNull().orEmpty()}".trim() else "группа"
            "$place ${clock(s.head.since)}–${clock(s.head.until ?: s.head.since)}, ответов ${s.head.agentAnswers}" +
                s.head.words.joinToString(", ").let { if (it.isEmpty()) "" else ", слова: $it" }
        }
    }

    private fun times(n: Int): String {
        val tens = n % 100
        val last = n % 10
        return when {
            tens in 11..14 -> "раз"
            last in 2..4 -> "раза"
            else -> "раз"
        }
    }
}
