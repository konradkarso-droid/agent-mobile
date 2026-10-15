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
     * «I: Model loaded (ctx=8192 threads_gen=3 threads_batch=8 batch=1024 mode=2)».
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

    private fun bufferName(raw: String): String = when (raw) {
        "CPU_REPACK" -> "перепакованные"
        "CPU_Mapped" -> "из файла"
        "CPU" -> "в своей памяти"
        else -> raw
    }
}
