package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineLinesTest {

    @Test
    fun `точка — число токенов из строки записи и из строки подъёма`() {
        assertEquals(
            "746 ток.",
            EngineLines.checkpointTokens(
                "I: State save to /data/user/0/com.uroboros/files/prompt_cache/f5/state/checkpoint.bin.tmp: success (746 tokens)",
            ),
        )
        assertEquals(
            "883 ток.",
            EngineLines.checkpointTokens("I: State loaded from /data/x/checkpoint.bin: 883 tokens"),
        )
    }

    @Test
    fun `точка — без числа строка лога остаётся как есть`() {
        assertEquals("лог молчит", EngineLines.checkpointTokens("лог молчит"))
    }

    @Test
    fun `движок — окно, потоки и пачка словами, режим не печатается`() {
        assertEquals(
            "окно 8192 ток. · потоков 3 на выдачу, 8 на запрос · пачка 1024",
            EngineLines.engineParams("I: Model loaded (ctx=8192 threads_gen=3 threads_batch=8 batch=1024 mode=2)"),
        )
    }

    @Test
    fun `движок — физическая пачка показывается отдельно от логической`() {
        assertEquals(
            "окно 8192 ток. · потоков 3 на выдачу, 4 на запрос · пачка 1024 · за проход 256",
            EngineLines.engineParams("I: Model loaded (ctx=8192 threads_gen=3 threads_batch=4 batch=1024 ubatch=256 mode=2)"),
        )
    }

    @Test
    fun `движок — без окна строка лога остаётся как есть`() {
        assertEquals("Loading model x.gguf", EngineLines.engineParams("  Loading model x.gguf "))
    }
    private val loadLog = """
        I: Loading model: /proc/self/fd/88 (ctx=8192 threads=4 batch=512 flash=1 mmap=0 mlock=0)
        I: load_tensors:   CPU_REPACK model buffer size =  1912.50 MiB
        I: load_tensors:          CPU model buffer size =   402.25 MiB
        I: llama_kv_cache: size =  612.00 MiB (  8192 cells,  36 layers,  1/1 seqs), K (q8_0):  306.00 MiB, V (q8_0):  306.00 MiB
        I: llama_context:        CPU compute buffer size =   301.75 MiB
        I: Model loaded (ctx=8192 threads_gen=4 threads_batch=4 batch=512 mode=2)
    """.trimIndent()

    @Test
    fun `память движка — веса, кэш разговора и буфер обсчёта по статьям`() {
        assertEquals(
            "Память движка: веса перепакованные 1912 МБ · веса в своей памяти 402 МБ · " +
                "кэш разговора 612 МБ · буфер обсчёта CPU 301 МБ · итого 3228 МБ",
            EngineLines.engineMemory(loadLog),
        )
    }

    @Test
    fun `память движка — берётся последняя загрузка, чужие буферы после неё не считаются`() {
        val log = "I: Loading model: /old (ctx=4096)\n" +
            "I: load_tensors:   CPU_Mapped model buffer size =  2300.00 MiB\n" +
            "I: Model loaded (ctx=4096)\n" +
            loadLog + "\n" +
            "I: load_tensors:   CPU_Mapped model buffer size =    90.00 MiB\n"
        assertEquals(
            "Память движка: веса перепакованные 1912 МБ · веса в своей памяти 402 МБ · " +
                "кэш разговора 612 МБ · буфер обсчёта CPU 301 МБ · итого 3228 МБ",
            EngineLines.engineMemory(log),
        )
    }

    @Test
    fun `память движка — молчит честно, если строк загрузки нет`() {
        assertEquals(
            "Память движка: строк загрузки в логе нет (лог кольцевой)",
            EngineLines.engineMemory("I: что-то другое"),
        )
        assertEquals(
            "Память движка: в строках загрузки нет размеров буферов",
            EngineLines.engineMemory("I: Loading model: /x (ctx=8192)\nI: Model loaded (ctx=8192)"),
        )
    }

    // Строки лога — из прогона библиотеки с моделью Falcon-H1 (Mamba-слои).
    private fun request(vararg lines: String) =
        listOf("I: Multi-turn prompt length: 2211 tokens, 11 stop seqs") + lines

    @Test
    fun `стена — откат отказан, взята с диска`() {
        assertEquals(
            "Откат к середине невозможен — у модели Mamba-слои (совпало 2210 из 2211, обсчитано 2263), " +
                "ход идёт от снимка стены · Стена: взята с диска готовой (совпало 2186 из 2211, стена 2186)",
            EngineLines.wallReuse(
                request(
                    "I: Rollback refused: 2210/2211 tokens in common, 2263 computed",
                    "I: Prompt cache restored: 2186 tokens",
                    "I: Disk cache hit: reusing 2186/2211 tokens",
                ),
                2186,
            ),
        )
    }

    @Test
    fun `стена — с нуля, снимок стены записан`() {
        assertEquals(
            "Стена посчитана с нуля: готовой на диске не было (обсчитано 2211 токенов) · " +
                "снимок стены записан на диск (2186 ток.)",
            EngineLines.wallReuse(
                request(
                    "I: No context reuse, full re-eval of 2211 tokens",
                    "I: System prompt: 2186 tokens (protected during shifts)",
                    "I: Wall snapshot saved: 2186 tokens",
                ),
                2186,
            ),
        )
    }

    @Test
    fun `стена — снимок на диске не подошёл`() {
        assertEquals(
            "Стена посчитана с нуля: снимок на диске не подошёл (в нём 2211 ток., совпало 2210; " +
                "обсчитано 2211 токенов) · снимок стены записан на диск (2186 ток.)",
            EngineLines.wallReuse(
                request(
                    "I: Prompt cache restored: 2211 tokens",
                    "I: Disk cache unusable: 2211 tokens restored, 2210 in common",
                    "I: No context reuse, full re-eval of 2211 tokens",
                    "I: Wall snapshot saved: 2186 tokens",
                ),
                2186,
            ),
        )
    }

    @Test
    fun `стена — снимок не записан, причина словами`() {
        assertEquals(
            "Стена посчитана с нуля: готовой на диске не было (обсчитано 2211 токенов) · " +
                "снимок стены не записан: стена в запросе режется на токены иначе, чем отдельно",
            EngineLines.wallReuse(
                request(
                    "I: No context reuse, full re-eval of 2211 tokens",
                    "I: Wall snapshot not saved: wall cuts into tokens differently inside the request",
                ),
                2186,
            ),
        )
    }

    @Test
    fun `стена — обычный ход молчит об откате и о снимке`() {
        assertEquals(
            "Стена: взята готовой из этого запуска (совпало 2223 из 2251, стена 2186)",
            EngineLines.wallReuse(
                request("I: Context reuse: 2223/2251 tokens cached, 28 new tokens to eval"),
                2186,
            ),
        )
    }

    @Test
    fun `стена — с диска меньше стены значит кэш испорчен`() {
        assertEquals(
            "КЭШ СТЕНЫ ИСПОРЧЕН: на диске чужое состояние, стена считается заново при каждом запуске " +
                "(совпало 3 из 2211, стена 2186)",
            EngineLines.wallReuse(request("I: Disk cache hit: reusing 3/2211 tokens"), 2186),
        )
    }

    @Test
    fun `стена — без итоговой строки движок не сообщил`() {
        assertEquals(
            "Стена: движок о совпадении не сообщил",
            EngineLines.wallReuse(request("I: Prompt cache restored: 2186 tokens"), 2186),
        )
    }
}
