package com.uroboros.memory.dream

import com.uroboros.llm.ConversationJournal
import com.uroboros.memory.RiskTrigger

/**
 * Зеркало: модель со случайной выдачей смотрит на конец разговора и сочиняет
 * варианты. Сейчас вариант один по виду — [Kind.NEXT], догадка, что собеседник
 * скажет или спросит дальше. Здесь сборка запроса, разбор ответа в варианты,
 * сверка реплики с вариантом и слова для отчёта ночи; ни базы, ни Android —
 * по образцу [SelfLine]. Модель зовёт и пишет в базу [MirrorStep], сверку в
 * ходе ведёт [MirrorChecker], показ — [MirrorView].
 *
 * ВЫХОДА ИЗ ЗЕРКАЛА НЕТ. Варианты видит только владелец, на экране. В память,
 * в стену, в ответы модели зеркало не пишет и счётчиков касаний, вспоминания
 * и любопытства не трогает. Почему своя таблица, а не сны, — в KDoc
 * [MirrorVariant].
 *
 * ЗАЧЕМ ОНО СЕЙЧАС. Отличить зеркало от шума. Мерка — «сбылось»: сказал ли
 * владелец потом то, что зеркало предположило ([check]).
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - «сбылось» — совпадение основ слов, а не смысла: пересказ другими словами
 *    не сбудется, а два общих слова в разном смысле сбудутся;
 *  - смотрит только на ленту в памяти процесса: после перезапуска, пока лента
 *    не поднята, зеркало молчит с этой причиной, а не читает диск;
 *  - реплика сверяется с вариантами всех ночей, окно которых не кончилось, —
 *    реплика не обязана быть ответом на тот разговор, на который смотрело
 *    зеркало;
 *  - из запроса берётся только набранное владельцем ([ConversationJournal.Turn.question])
 *    и ответ агента: записи памяти, подставленные в реплику, зеркало не видит
 *    намеренно — среди них бывают скрытые.
 */
object Mirror {

    /** Вид варианта. Хранится именем значения в [MirrorVariant.kind]. */
    enum class Kind {
        /** «Что может быть дальше»: что собеседник напишет в следующий раз. */
        NEXT,
    }

    /** Системная инструкция вопроса «что дальше». Своя, не судьи. */
    const val SYSTEM =
        "Вот конец разговора агента с собеседником. Предположи, что собеседник напишет в " +
            "следующий раз. Дай три разных коротких варианта, каждый с новой строки, без " +
            "нумерации и пояснений."

    /** Сколько последних ходов ленты видит зеркало. Объявленное число, не подобранное. */
    const val TURNS = 3

    /**
     * Сколько знаков разговора уходит модели — с конца, чтобы последний ответ
     * агента дошёл целиком. Объявленное число, не подобранное.
     */
    const val MAX_CHARS = 1500

    /** Сколько вариантов остаётся из ответа. Объявленное число, как и в [SYSTEM]. */
    const val MAX_VARIANTS = 3

    /**
     * Сколько общих основ у варианта и реплики нужно, чтобы вариант сбылся.
     * Объявленное число, не подобранное.
     *
     * Строже, чем у подхвата сна (там хватает одной, см. [DreamPickup]), и
     * причина в направлении промаха. «Сбылось» — мерка, по которой решается,
     * чего стоит зеркало. Лишнее «сбылось» заставило бы принять шум за
     * догадку; пропущенное только занижает счёт, и это видно на показе, где
     * рядом стоит сама реплика.
     */
    const val FULFIL_STEMS = 2

    /**
     * С каким числом реплик владельца после ночи сверяется вариант. Потом
     * больше не сверяется: реплика через неделю не проверяет догадку о
     * следующем ходе. Объявленное число, не подобранное.
     */
    const val CHECK_WINDOW = 3

    // ---- Сборка запроса ----

    /** Что готово уйти модели, или почему зеркало молчит. */
    sealed class Request {
        /**
         * @property text конец разговора, не длиннее [MAX_CHARS].
         * @property fromTurn первый ход, на который смотрело зеркало, с единицы.
         * @property toTurn последний такой ход.
         * @property excludedStems основы вопросов владельца и ответов агента этих
         *   ходов целиком, до обрезки: слово, которое уже было в разговоре,
         *   сбывшейся догадкой не считается.
         */
        data class Ready(
            val text: String,
            val fromTurn: Int,
            val toTurn: Int,
            val excludedStems: Set<String>,
        ) : Request()

        data class Silent(val reason: String) : Request()
    }

    /**
     * Конец ленты — в запрос. Берутся последние [TURNS] ходов: реплика
     * владельца, если она была (ход, начатый агентом, реплики не имеет), и
     * ответ агента. [ConversationJournal.Turn.userContent] не берётся никогда.
     */
    fun request(history: List<ConversationJournal.Turn>): Request {
        if (history.isEmpty()) return Request.Silent("лента пуста или не поднята")
        val from = maxOf(0, history.size - TURNS)
        val seen = history.subList(from, history.size)
        val text = seen.joinToString("\n") { turn ->
            buildString {
                if (turn.question.isNotBlank()) append("Собеседник: ").append(turn.question.trim()).append("\n")
                append("Агент: ").append(turn.agentContent.trim())
            }
        }
        val excluded = seen.flatMapTo(HashSet()) {
            RiskTrigger.significantStems(it.question) + RiskTrigger.significantStems(it.agentContent)
        }
        return Request.Ready(
            text = text.takeLast(MAX_CHARS),
            fromTurn = from + 1,
            toTurn = history.size,
            excludedStems = excluded,
        )
    }

    // ---- Разбор ответа ----

    /** Кавычки, которые снимаются с краёв варианта. */
    private const val QUOTES = "\"'«»„“”‘’`"

    /** Нумерация и маркеры списка в начале строки: «1.», «2)», «-», «*», «•», «—». */
    private val LEAD = Regex("^(\\d+[.)]|[-*•–—])\\s*")

    /**
     * Ответ модели — в варианты: непустые строки без нумерации, маркеров и
     * кавычек по краям; дубли по основам убраны (вариант без значимых слов
     * сравнивается текстом); не больше [MAX_VARIANTS]. Пустой список — модель
     * не дала вариантов.
     */
    fun parse(answer: String): List<String> {
        val seen = HashSet<Any>()
        val out = mutableListOf<String>()
        for (raw in answer.lines()) {
            var line = raw.trim()
            line = line.replaceFirst(LEAD, "")
            line = line.trim { it.isWhitespace() || it in QUOTES }
            if (line.isEmpty()) continue
            val stems = RiskTrigger.significantStems(line)
            val key: Any = stems.ifEmpty { line.lowercase() }
            if (!seen.add(key)) continue
            out += line
            if (out.size == MAX_VARIANTS) break
        }
        return out
    }

    // ---- Сверка ----

    /** Итог сверки одной реплики с одним вариантом. */
    data class Checked(
        /** Со сколькими репликами вариант сверен, включая эту. */
        val repliesSeen: Int,
        /** Совпавшие основы, если вариант сбылся; null — не сбылся. */
        val fulfilledWords: Set<String>?,
    )

    /**
     * Сверить реплику владельца [reply] с вариантом [variant]: основы варианта
     * минус [excludedStems], пересечённые с основами реплики. Сбылось — общих
     * основ не меньше [FULFIL_STEMS].
     *
     * @param repliesSeen со сколькими репликами вариант уже сверен.
     * @return null — окно кончилось ([CHECK_WINDOW]), вариант не сверяется.
     */
    fun check(variant: String, excludedStems: Set<String>, repliesSeen: Int, reply: String): Checked? {
        if (repliesSeen >= CHECK_WINDOW) return null
        val common = (RiskTrigger.significantStems(variant) - excludedStems) intersect
            RiskTrigger.significantStems(reply)
        return Checked(repliesSeen + 1, common.takeIf { it.size >= FULFIL_STEMS })
    }

    /** Основы — в строку для базы: через запятую, по алфавиту. */
    fun joinStems(stems: Set<String>): String = stems.sorted().joinToString(",")

    /** Строка основ из базы — обратно в множество. */
    fun splitStems(stored: String?): Set<String> =
        stored.orEmpty().split(",").mapNotNullTo(HashSet()) { it.trim().ifEmpty { null } }

    // ---- Слова для отчёта ночи ----

    /** Начало строки итога — по нему итог узнаётся в отчёте ночи и в приборе. */
    const val OUTCOME_HEAD = "Зеркало: "

    fun silentOutcome(reason: String): String =
        "${OUTCOME_HEAD}не смотрю — " + reason.replaceFirstChar { it.lowercaseChar() }

    /** Зеркало смотрело; [failure] — почему вариантов нет, если их нет. */
    fun lookedOutcome(fromTurn: Int, toTurn: Int, variants: Int, failure: String? = null): String =
        "${OUTCOME_HEAD}смотрело на ходы $fromTurn–$toTurn, " +
            when {
                failure != null -> "вариантов нет — $failure"
                variants == 0 -> "вариантов 0 — модель не дала вариантов"
                else -> "вариантов $variants"
            }
}
