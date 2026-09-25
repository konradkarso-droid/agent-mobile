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
}
