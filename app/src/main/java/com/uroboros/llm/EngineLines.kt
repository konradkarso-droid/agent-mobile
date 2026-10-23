package com.uroboros.llm

/**
 * Строки лога движка — словами для «Подробно».
 *
 * Лог библиотеки пишет по-английски и с полными путями к файлам: путь к точке
 * занимает на экране три строки и ничего не говорит тому, кто смотрит.
 * Здесь из строки лога берутся только числа, которые читают.
 *
 * ЧЕГО НЕ УМЕЕТ: узнаёт строки лога по их нынешнему виду («ctx=8192»,
 * «(746 tokens)»). Если библиотека сменит формат, числа не найдутся — и тогда
 * показывается строка лога как есть, а не пустота: сырая строка хуже
 * читается, но ничего не прячет.
 */
object EngineLines {

    private val TOKENS = Regex("""(\d+)\s+tokens""")

    /**
     * Сколько токенов в точке, по строке лога записи или подъёма точки:
     * «… checkpoint.bin: 883 tokens», «… success (746 tokens)».
     * Не нашлось числа — строка лога целиком.
     */
    fun checkpointTokens(logLine: String): String =
        TOKENS.find(logLine)?.groupValues?.get(1)?.let { "$it ток." } ?: logLine.trim()

    /**
     * Параметры загрузки по строке лога вида
     * «I: Model loaded (ctx=8192 threads_gen=3 threads_batch=8 batch=1024 ubatch=256 mode=2)».
     * «за проход» — физическая пачка: столько токенов идёт через модель за
     * один проход; от неё зависит буфер обсчёта (см. `gguf_lib.cpp`).
     *
     * Режим потоков (mode=) не печатается: его словами называет соседняя
     * строка «Режим потоков», и фактический, а не заданный при загрузке.
     */
    fun engineParams(logLine: String): String {
        val values = Regex("""(\w+)=(\S+?)(?=[\s)]|$)""").findAll(logLine)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val ctx = values["ctx"] ?: return logLine.trim()
        val parts = mutableListOf("окно $ctx ток.")
        val gen = values["threads_gen"]
        val batchThreads = values["threads_batch"]
        if (gen != null && batchThreads != null) {
            parts += "потоков $gen на выдачу, $batchThreads на запрос"
        }
        values["batch"]?.let { parts += "пачка $it" }
        values["ubatch"]?.let { parts += "за проход $it" }
        return parts.joinToString(" · ")
    }
    private val MODEL_BUFFER = Regex("""(\S+)\s+model buffer size\s*=\s*([\d.]+)\s*MiB""")
    private val KV_SIZE = Regex("""kv_cache\w*:\s+size\s*=\s*([\d.]+)\s*MiB""")
    private val COMPUTE_BUFFER = Regex("""(\S+)\s+compute buffer size\s*=\s*([\d.]+)\s*MiB""")

    /**
     * На что движок выделил память при последней загрузке главной модели —
     * по строкам лога библиотеки между «Loading model» и «Model loaded»:
     * веса (по видам буфера), кэш разговора (KV) и рабочий буфер обсчёта.
     *
     * Зачем: строка «Память сейчас» говорит, сколько у процесса рабочей
     * памяти, но не из чего она состоит. Эта строка раскладывает её по
     * статьям, чтобы решать, что урезать, по числам, а не по догадке.
     *
     * Виды буфера весов: CPU_REPACK — веса, перепакованные для скорости;
     * CPU_Mapped — веса, читаемые прямо из отображённого файла; CPU — прочие
     * веса в своей памяти. Незнакомое имя показывается как есть.
     *
     * ЧЕГО НЕ УМЕЕТ: это выделенное, а не занятое — страница, в которую ещё
     * не писали, у системы на счету не числится (так бывает с кэшем
     * разговора, пока окно не заполнено). Лог кольцевой: если строки загрузки
     * из него вытеснены, строка честно говорит, что не нашла их. Модель
     * эмбеддингов грузится отдельно и сюда не входит.
     */
    fun engineMemory(log: String): String {
        val lines = log.lines()
        val start = lines.indexOfLast { it.contains("Loading model:") }
        if (start < 0) return "Память движка: строк загрузки в логе нет (лог кольцевой)"
        val endRel = lines.subList(start, lines.size).indexOfFirst { it.contains("Model loaded (") }
        val block = if (endRel < 0) lines.subList(start, lines.size) else lines.subList(start, start + endRel + 1)

        val parts = mutableListOf<String>()
        var total = 0.0
        block.forEach { line ->
            MODEL_BUFFER.find(line)?.let {
                val mb = it.groupValues[2].toDouble(); total += mb
                parts += "веса ${bufferName(it.groupValues[1])} ${mb.toLong()} МБ"
            }
            KV_SIZE.find(line)?.let {
                val mb = it.groupValues[1].toDouble(); total += mb
                parts += "кэш разговора ${mb.toLong()} МБ"
            }
            COMPUTE_BUFFER.find(line)?.let {
                val mb = it.groupValues[2].toDouble(); total += mb
                parts += "буфер обсчёта ${it.groupValues[1]} ${mb.toLong()} МБ"
            }
        }
        if (parts.isEmpty()) return "Память движка: в строках загрузки нет размеров буферов"
        return "Память движка: " + parts.joinToString(" · ") + " · итого ${total.toLong()} МБ"
    }

    /**
     * Взял ли движок стену готовой в одном запросе — одной фразой для экрана,
     * с числами в скобках для сверки. [request] — строки лога движка от
     * начала этого запроса («Multi-turn prompt length:») до конца лога;
     * [wall] — длина стены в токенах, если движок её уже сообщал.
     *
     * ЗАЧЕМ. Строка «Кэш стены» говорит, что файл на диске ЕСТЬ, но не
     * говорит, подошёл ли он. Испорченный кэш (в файле под именем стены
     * лежит состояние запроса с другим началом) и исправный выглядят там
     * одинаково. Здесь видно, сколько токенов запроса совпало с уже
     * обсчитанным и откуда оно взялось — из памяти процесса или с диска.
     *
     * ИСХОДЫ различаются по тому, откуда совпадение:
     * - с диска, не меньше стены — стена взята готовой;
     * - с диска, МЕНЬШЕ стены — кэш испорчен. Это единственный исход, где
     *   причина одна: файл назван по этой стене, а начинается не с неё;
     * - из памяти процесса, не меньше стены — обычный ход;
     * - из памяти процесса, меньше стены — перед этим к модели ходил запрос
     *   с другим началом (разбор памяти) или стена сменилась на лету (совпало
     *   начало до места правки, хвост новый), и стену пришлось досчитать. Кэш
     *   при этом цел;
     * - совпадения нет — готовой стены на диске не нашлось или снимок не
     *   подошёл, стена посчитана с нуля. Нормально для первого запроса в
     *   свежей папке.
     * Перед исходом может стоять отказ отката: у модели с Mamba-слоями
     * обсчитанное к середине не вернуть, и при любом расхождении раньше конца
     * ход идёт от снимка стены на диске (почему — `gguf_lib.cpp`,
     * «Переиспользование обсчитанного»). После исхода — записан ли снимок
     * стены, если движок писал его в этом запросе.
     *
     * ОТКУДА ЧИСЛА. У каждого многоходового запроса в логе есть ровно одна
     * итоговая строка о совпадении одного из трёх видов. Значит звать это
     * можно ТОЛЬКО по свежему запросу, который точно дошёл до обсчёта: иначе
     * будут показаны числа чужого запроса. Проверяет это вызывающий.
     *
     * ЧЕГО НЕ УМЕЕТ. Сравнение со стеной держится на том, что граница стены
     * в полном запросе режется на токены так же, как стена отдельно. Если
     * это однажды разойдётся на токен-другой, строка скажет «испорчен» при
     * исправном кэше — то есть ошибётся в сторону ложной тревоги, а не
     * пропуска. Проверяется это числами в скобках: при настоящей порче
     * совпадает несколько токенов, а не почти вся стена. Если длины стены
     * нет, сравнивать не с чем — тогда строка говорит только, откуда и
     * сколько. Строки лога узнаются по нынешнему тексту в `gguf_lib.cpp`:
     * правка текста там молча отключит строку здесь — она скажет «не
     * сообщил», а не соврёт числом.
     */
    fun wallReuse(request: List<String>, wall: Int?): String {
        val refused = request.firstNotNullOfOrNull { ROLLBACK_REFUSED.find(it) }?.let {
            "Откат к середине невозможен — у модели Mamba-слои (совпало ${it.groupValues[1]} " +
                "из ${it.groupValues[2]}, обсчитано ${it.groupValues[3]}), ход идёт от снимка стены · "
        } ?: ""
        val saved = request.firstNotNullOfOrNull { WALL_SAVED.find(it) }?.let {
            " · снимок стены записан на диск (${it.groupValues[1]} ток.)"
        } ?: request.firstNotNullOfOrNull { WALL_NOT_SAVED.find(it) }?.let {
            " · снимок стены не записан: " + when (val why = it.groupValues[1].trim()) {
                "write failed" -> "запись на диск не удалась"
                "wall cuts into tokens differently inside the request" ->
                    "стена в запросе режется на токены иначе, чем отдельно"
                else -> why
            }
        } ?: ""
        return refused + reuseOutcome(request, wall) + saved
    }

    private fun reuseOutcome(request: List<String>, wall: Int?): String {
        for (line in request) {
            REUSE_FROM_DISK.find(line)?.let {
                val reused = it.groupValues[1].toInt()
                val numbers = "(совпало $reused из ${it.groupValues[2]}, стена ${wall ?: "?"})"
                return when {
                    wall == null -> "Стена: взята с диска, длина стены не известна $numbers"
                    reused >= wall -> "Стена: взята с диска готовой $numbers"
                    else -> "КЭШ СТЕНЫ ИСПОРЧЕН: на диске чужое состояние, стена " +
                        "считается заново при каждом запуске $numbers"
                }
            }
            REUSE_IN_MEMORY.find(line)?.let {
                val reused = it.groupValues[1].toInt()
                val numbers = "(совпало $reused из ${it.groupValues[2]}, стена ${wall ?: "?"})"
                return when {
                    wall == null -> "Стена: взята из этого запуска, длина стены не известна $numbers"
                    reused >= wall -> "Стена: взята готовой из этого запуска $numbers"
                    else -> "Стена посчитана заново: перед этим к модели ходил другой " +
                        "запрос (например, разбор памяти) или стена сменилась на лету. Кэш цел $numbers"
                }
            }
            NO_REUSE.find(line)?.let { none ->
                val unusable = request.firstNotNullOfOrNull { DISK_UNUSABLE.find(it) }
                return if (unusable != null) {
                    "Стена посчитана с нуля: снимок на диске не подошёл (в нём " +
                        "${unusable.groupValues[1]} ток., совпало ${unusable.groupValues[2]}; " +
                        "обсчитано ${none.groupValues[1]} токенов)"
                } else {
                    "Стена посчитана с нуля: готовой на диске не было " +
                        "(обсчитано ${none.groupValues[1]} токенов)"
                }
            }
        }
        return "Стена: движок о совпадении не сообщил"
    }

    // Строки лога nativeGenerateStreamMultiTurn (`gguf_lib.cpp`), дословно.
    private val REUSE_IN_MEMORY = Regex("""Context reuse: (\d+)/(\d+) tokens cached""")
    private val REUSE_FROM_DISK = Regex("""Disk cache hit: reusing (\d+)/(\d+) tokens""")
    private val NO_REUSE = Regex("""No context reuse, full re-eval of (\d+) tokens""")
    private val ROLLBACK_REFUSED = Regex("""Rollback refused: (\d+)/(\d+) tokens in common, (\d+) computed""")
    private val DISK_UNUSABLE = Regex("""Disk cache unusable: (\d+) tokens restored, (\d+) in common""")
    private val WALL_SAVED = Regex("""Wall snapshot saved: (\d+) tokens""")
    private val WALL_NOT_SAVED = Regex("""Wall snapshot not saved: (.+)$""")

    private fun bufferName(raw: String): String = when (raw) {
        "CPU_REPACK" -> "перепакованные"
        "CPU_Mapped" -> "из файла"
        "CPU" -> "в своей памяти"
        else -> raw
    }
}
