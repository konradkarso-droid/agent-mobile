package com.uroboros

/**
 * Где стоял главный поток, когда приложение «завис, не отвечал системе», —
 * по снимку потоков, который Android сохраняет вместе с такой комой.
 *
 * ЗАЧЕМ. Строка комы ([ComaCause]) называет причину — «завис», — но не место.
 * А зависание экрана здесь — класс поломок, а не случай: главный поток ждёт
 * то, что держит движок модели или поток, который сам ждёт движок. Мест, где
 * это может случиться, десятки, и починка по догадке гадает, какое из них на
 * этот раз. Android при каждом «не отвечает» снимает стеки всех потоков
 * процесса и хранит их при записи о смерти
 * (ApplicationExitInfo.getTraceInputStream). Здесь из снимка достаётся то, что
 * называет место.
 *
 * ЧТО ДОСТАЁТСЯ:
 *  - состояние главного потока и верхняя строка его стека;
 *  - кадры нашего кода ([OWN_PREFIXES]) сверху вниз — по ним место находится
 *    в исходниках с номером строки;
 *  - если главный поток в движке модели — какой это вызов и ждёт ли он замка
 *    (родной замок виден по кадру с mutex в родном стеке над вызовом);
 *  - если главный поток ждёт Java-замок и снимок назвал держателя — чей это
 *    поток и где стоит он;
 *  - какие ещё потоки в этот миг внутри движка.
 *
 * ЧЕГО НЕ УМЕЕТ:
 *  - снимок — одно мгновение через несколько секунд зависания: это место, а
 *    не причина. Если к этому мигу главный поток уже освободился, место не
 *    поймано, и строка так и говорит, а не называет случайный кадр;
 *  - кто держит РОДНОЙ замок движка, снимок не пишет. Держит один из потоков
 *    внутри движка, который сам замка не ждёт, — это вывод, а не запись, и
 *    строка его не делает: она перечисляет потоки внутри движка с тем, ждут ли
 *    они замка;
 *  - формат снимка — текст среды Android, не обещанный ни одной её версией.
 *    Сменится формат — главный поток не найдётся, и строка скажет «в снимке
 *    нет главного потока», а не выдумает место;
 *  - разбирается только последнее «завис» и только начало снимка
 *    ([MAX_TRACE_CHARS]); сколько снимков и как долго хранит Android, решает
 *    он сам.
 *
 * Чистый объект; снимок у Android читает [AgentLife].
 */
object HangPlace {

    /** Что удалось узнать о последнем «завис». */
    sealed class Reading {
        /** Спросить не удалось или Android таких записей не ведёт; [why] — словами. */
        data class Unavailable(val why: String) : Reading()

        /** Среди записей о смерти, что отдал Android, нет «завис». */
        object NoHang : Reading()

        /** «Завис» в [atMs] есть, а снимка нет; [why] — словами. */
        data class NoTrace(val atMs: Long, val why: String) : Reading()

        /** Снимок «завис» в [atMs]: [text] — его начало; [truncated] — дальше было ещё. */
        data class Trace(val atMs: Long, val text: String, val truncated: Boolean) : Reading()
    }

    /**
     * Поток из снимка: имя, номер в снимке ([tid], не номер в системе),
     * состояние словом среды и стек.
     */
    data class StackThread(
        val name: String,
        val tid: Int,
        val state: String,
        /** Кадры «at …» сверху вниз, без «at ». */
        val frames: List<String>,
        /** Строки родного стека до первого кадра «at» — то, что над вызовом из Java. */
        val native: List<String>,
        /** Номер потока, держащего Java-замок, которого ждёт этот; null — не ждёт или держатель не назван. */
        val heldBy: Int?,
    )

    /** Кадры этих пакетов — наш код: приложение и обёртка движка. */
    val OWN_PREFIXES = listOf("com.uroboros.", "com.dark.")

    /** Сколько кадров нашего кода главного потока показывать. Объявленное число, не подобранное. */
    const val OWN_SHOWN = 6

    /** Сколько кадров нашего кода держателя замка показывать. Объявленное число, не подобранное. */
    const val HOLDER_SHOWN = 3

    /** Сколько прочих потоков внутри движка называть. Объявленное число, не подобранное. */
    const val ENGINE_SHOWN = 3

    /**
     * Сколько знаков снимка читать. Объявленное число, не подобранное: главный
     * поток в снимке среды Android идёт первым, и его хватает с запасом; весь
     * снимок с десятками потоков бывает в несколько раз длиннее, и держать его в
     * памяти ради строки незачем.
     */
    const val MAX_TRACE_CHARS = 2_000_000

    private const val ENGINE_CLASS = "com.dark.gguf_lib.GGUFNativeLib.native"
    private const val IDLE_FRAME = "android.os.MessageQueue.nativePollOnce"
    private const val MAIN_TID = 1

    /** Шапка потока, подключённого к среде: «"main" prio=5 tid=1 Blocked». */
    private val HEADER = Regex("""^"(.*)"\s.*?\btid=(\d+)\s+(\w+)""")
    private val HELD_BY = Regex("""held by thread (\d+)""")

    /**
     * Потоки снимка, подключённые к среде, в порядке снимка. Потоки только
     * родного кода (шапка с одним sysTid) не берутся: кадров нашего кода у них
     * нет.
     */
    fun threads(trace: String): List<StackThread> {
        val out = ArrayList<StackThread>()
        var cur: Builder? = null
        for (raw in trace.lineSequence()) {
            if (raw.startsWith("\"")) {
                cur?.let { out += it.build() }
                cur = HEADER.find(raw)?.let { m ->
                    Builder(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3])
                }
                continue
            }
            val b = cur ?: continue
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("-----") -> {
                    out += b.build()
                    cur = null
                }
                line.startsWith("at ") -> b.frames += line.removePrefix("at ")
                line.startsWith("native:") -> if (b.frames.isEmpty()) b.native += line
                line.startsWith("- waiting to lock") -> if (b.heldBy == null) {
                    b.heldBy = HELD_BY.find(line)?.groupValues?.get(1)?.toInt()
                }
            }
        }
        cur?.let { out += it.build() }
        return out
    }

    private class Builder(val name: String, val tid: Int, val state: String) {
        val frames = ArrayList<String>()
        val native = ArrayList<String>()
        var heldBy: Int? = null
        fun build() = StackThread(name, tid, state, frames.toList(), native.toList(), heldBy)
    }

    /** Строки для экрана. [moment] — время словами («15.11 15:13»). */
    fun lines(reading: Reading, moment: (Long) -> String): String = when (reading) {
        is Reading.Unavailable -> "Где стоял экран: не узнал — ${reading.why}"
        Reading.NoHang -> "Где стоял экран: среди ком, что отдал Android, «завис» нет"
        is Reading.NoTrace -> "Где стоял экран при «завис» ${moment(reading.atMs)}: ${reading.why}"
        is Reading.Trace -> traceLines(reading, moment)
    }

    private fun traceLines(reading: Reading.Trace, moment: (Long) -> String): String {
        val cut = if (reading.truncated) " (снимок обрезан на ${(reading.text.length + 999) / 1000} тыс. знаков)" else ""
        val head = "Где стоял экран при «завис» ${moment(reading.atMs)}"
        val all = threads(reading.text)
        val main = all.firstOrNull { it.tid == MAIN_TID }
            ?: return "$head: в снимке нет главного потока$cut"

        val out = ArrayList<String>()
        val top = main.frames.firstOrNull()
        if (top != null && top.startsWith(IDLE_FRAME)) {
            out += "$head: к снимку главный поток уже свободен — место не поймано$cut"
        } else {
            out += "$head: главный поток ${stateWord(main.state)}$cut"
            if (top == null) {
                out += "сверху: кадров в снимке нет"
            } else if (!isOwn(top)) {
                out += "сверху: ${short(top)}"
            }
            val own = main.frames.filter(::isOwn)
            if (own.isEmpty()) {
                out += "наш код: в стеке нет"
            } else {
                out += "наш код, сверху вниз:"
                own.take(OWN_SHOWN).forEach { out += "· ${short(it)}" }
                if (own.size > OWN_SHOWN) out += "· … и ещё ${own.size - OWN_SHOWN}"
            }
            engineCall(main)?.let { out += "в движке: $it — ${engineWord(main)}" }
            main.heldBy?.let { tid -> out += holderLine(tid, all) }
        }

        val others = all.filter { it.tid != MAIN_TID && it.tid != main.heldBy && engineCall(it) != null }
        others.take(ENGINE_SHOWN).forEach { t ->
            out += "ещё в движке: «${t.name}» — ${engineCall(t)}, ${engineWord(t)}"
        }
        if (others.size > ENGINE_SHOWN) out += "ещё в движке: … и ещё ${others.size - ENGINE_SHOWN}"
        return out.joinToString("\n")
    }

    private fun holderLine(tid: Int, all: List<StackThread>): String {
        val holder = all.firstOrNull { it.tid == tid }
            ?: return "Java-замок держит поток №$tid — в прочитанной части снимка его нет"
        val parts = ArrayList<String>()
        engineCall(holder)?.let { parts += "в движке $it — ${engineWord(holder)}" }
        val own = holder.frames.filter(::isOwn).take(HOLDER_SHOWN)
        if (own.isNotEmpty()) parts += own.joinToString(" ← ") { short(it) }
        if (parts.isEmpty()) parts += "наш код в его стеке не встретился"
        return "Java-замок держит «${holder.name}» (${stateWord(holder.state)}): ${parts.joinToString("; ")}"
    }

    /** Вызов движка, внутри которого стоит поток: имя родного метода; null — поток не в движке. */
    fun engineCall(t: StackThread): String? =
        t.frames.firstOrNull { it.startsWith(ENGINE_CLASS) }
            ?.substringBefore('(')
            ?.substringAfterLast('.')

    /**
     * Ждёт ли поток родного замка: в родном стеке над вызовом есть mutex.
     * Только по кадрам снимка — без родного стека ответ «внутри движка».
     */
    fun waitsEngineLock(t: StackThread): Boolean = t.native.any { it.contains("mutex") }

    private fun engineWord(t: StackThread): String =
        if (waitsEngineLock(t)) "ждёт замка движка" else "внутри движка"

    private fun isOwn(frame: String): Boolean = OWN_PREFIXES.any { frame.startsWith(it) }

    /** Кадр коротко: класс.метод и место — «LlmEngine.applyWall (LlmEngine.kt:1326)». */
    fun short(frame: String): String {
        val name = frame.substringBefore('(')
        val where = frame.substringAfter('(', "").removeSuffix(")")
        val parts = name.split('.')
        val shortName = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else name
        return if (where.isEmpty()) shortName else "$shortName ($where)"
    }

    /** Состояние потока словами; незнакомое — как в снимке. */
    fun stateWord(state: String): String = when (state) {
        "Blocked" -> "ждёт Java-замок"
        "Native" -> "в родном коде"
        "Waiting" -> "ждёт"
        "TimedWaiting" -> "ждёт со сроком"
        "Sleeping" -> "спит"
        "Runnable" -> "работает"
        "Suspended" -> "приостановлен"
        else -> "в состоянии «$state»"
    }
}
