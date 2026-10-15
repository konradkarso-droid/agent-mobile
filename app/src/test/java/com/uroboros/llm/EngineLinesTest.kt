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
}
