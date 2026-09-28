package com.uroboros.memory.nav

import com.uroboros.util.TextFold
import java.io.InputStream

/**
 * Таблица глаголов для разворота ([Retelling]): пары форм 1-го и 2-го лица
 * единственного числа («работаю работаешь»), по паре в строке, через пробел.
 * Файл — `app/src/main/assets/verb_person_pairs.txt`, источник и лицензия —
 * рядом, в `verb_person_pairs_SOURCE.md`.
 *
 * Поиск в обе стороны: ключ — форма после util.TextFold, значение — парная
 * форма как в таблице (с «ё»).
 *
 * НЕОДНОЗНАЧНАЯ форма — та, у которой после приведения больше одной пары:
 * «лечу» (лететь — летишь, лечить — лечишь), двойники «е/ё». В словарь пар она
 * не входит, а лежит отдельным набором: разворот не берёт предложение с такой
 * формой целиком, иначе выйдет «ты лечу» или неверный глагол.
 *
 * Строка не из двух слов пропускается и считается ([skippedLines]).
 */
class RetellTable private constructor(
    private val pairs: Map<String, String>,
    private val ambiguous: Set<String>,
    /** Строк таблицы, прочитанных как пара. */
    val pairCount: Int,
    val skippedLines: Int,
) {
    /** Парная форма для слова в любом регистре и с любой «ё»; нет пары или форма неоднозначна — null. */
    fun pairOf(word: String): String? = pairs[TextFold.fold(word)]

    fun isAmbiguous(word: String): Boolean = TextFold.fold(word) in ambiguous

    val ambiguousCount: Int get() = ambiguous.size

    companion object {
        private val SPACES = Regex("\\s+")

        fun parse(lines: Sequence<String>): RetellTable {
            val found = HashMap<String, MutableSet<String>>()
            var skipped = 0
            var read = 0
            for (line in lines) {
                val parts = line.trim().split(SPACES)
                if (parts.size != 2 || parts[0].isEmpty()) {
                    if (line.isNotBlank()) skipped++
                    continue
                }
                val (a, b) = parts
                read++
                found.getOrPut(TextFold.fold(a)) { HashSet() } += b
                found.getOrPut(TextFold.fold(b)) { HashSet() } += a
            }
            val pairs = HashMap<String, String>()
            val ambiguous = HashSet<String>()
            for ((key, values) in found) {
                if (values.size == 1) pairs[key] = values.first() else ambiguous += key
            }
            return RetellTable(pairs, ambiguous, read, skipped)
        }

        /** Таблица без пар: местоимения разворот меняет, глаголы — нет. */
        val EMPTY = RetellTable(emptyMap(), emptySet(), 0, 0)
    }
}

/**
 * Состояние таблицы разворота на всё время жизни процесса. Загружается один
 * раз, не в главном потоке ([load] вызывает экран при создании). Пока таблицы
 * нет или она не загрузилась, разворот недоступен и смешанная запись
 * владельца снимается зеркалом, как до разворота.
 *
 * Память, которую таблица занимает на телефоне, не мерена; прибор показывает
 * число пар и время загрузки, чтобы это было видно.
 */
object RetellHolder {

    sealed class State {
        object Loading : State()
        data class Failed(val reason: String) : State()
        data class Ready(val table: RetellTable, val millis: Long) : State()
    }

    @Volatile
    var state: State = State.Loading
        private set

    private var started = false

    /** Таблица, если загружена; иначе null — разворот недоступен. */
    val table: RetellTable? get() = (state as? State.Ready)?.table

    /** Строка «Подробно» о загруженной таблице; не загружена — null (причину печатает строка зеркала). */
    fun meterLine(): String? = (state as? State.Ready)?.let {
        "Таблица разворота: пар ${it.table.pairCount}, неоднозначных ${it.table.ambiguousCount}, " +
            "загружена за ${it.millis} мс"
    }

    /**
     * Читает таблицу из [open]. Повторный вызов ничего не делает. Любая ошибка
     * (нет файла, нехватка памяти) — состояние [State.Failed] с именем класса
     * ошибки; приложение работает как без разворота.
     */
    fun load(open: () -> InputStream) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val t0 = System.nanoTime()
        state = try {
            val table = open().bufferedReader(Charsets.UTF_8).useLines { RetellTable.parse(it) }
            State.Ready(table, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Throwable) {
            State.Failed(e.javaClass.simpleName)
        }
    }
}
