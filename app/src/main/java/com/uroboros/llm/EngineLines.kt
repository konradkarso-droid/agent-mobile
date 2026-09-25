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
}
