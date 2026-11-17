package com.uroboros

import com.uroboros.HangPlace.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Где стоял экран при «завис».
 *
 * ГЛАВНОЕ ЗДЕСЬ: место называется по главному потоку снимка, кадрами нашего
 * кода; держатель Java-замка — по номеру из снимка; где места нет (главный
 * поток уже свободен, его нет в снимке, снимка нет) — строка так и говорит, а
 * не называет случайный кадр.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что Android на этом телефоне хранит снимки и что их
 * формат совпадает с образцом ниже. Образец собран по снимкам среды Android
 * (ART) вручную; живой снимок виден только на устройстве.
 */
class HangPlaceTest {

    private val moment: (Long) -> String = { "t$it" }

    private fun trace(vararg blocks: String) =
        "----- pid 7140 at 2026-11-15 15:13:02.517 -----\n" +
            "Cmd line: com.uroboros\n\n" +
            "DALVIK THREADS (40):\n" +
            blocks.joinToString("\n\n") + "\n\n----- end 7140 -----\n"

    private val mainBlockedOnWall = """
        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a3c0f8 self=0xb400007a5f6b3c00
          | sysTid=7140 nice=-10 cgrp=top-app sched=0/0 handle=0x7b8e5534f8
          at com.uroboros.llm.LlmEngine.applyWall(LlmEngine.kt:1326)
          - waiting to lock <0x0f1a2b3c> (a java.lang.Object) held by thread 27
          at com.uroboros.llm.LlmEngine.configureAfterLoad(LlmEngine.kt:971)
          at com.uroboros.llm.LlmEngine.loadModelFromUri(LlmEngine.kt:900)
          at com.uroboros.AgentService${'$'}startNight${'$'}1.invokeSuspend(AgentService.kt:1150)
          at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
          at android.os.Looper.loop(Looper.java:317)
    """.trimIndent()

    private val workerSavingCheckpoint = """
        "DefaultDispatcher-worker-3" daemon prio=5 tid=27 Native
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x13c00000 self=0xb400007a5f6c0000
          native: #00 pc 000000000008a4cc  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          native: #01 pc 000000000008f1a8  /apex/com.android.runtime/lib64/bionic/libc.so (__futex_wait_ex(void volatile*, bool, int, bool, timespec const*)+144)
          native: #02 pc 00000000000f4c6c  /apex/com.android.runtime/lib64/bionic/libc.so (pthread_mutex_lock+208)
          native: #03 pc 00000000001234ab  /data/app/~~x/com.uroboros-y/lib/arm64/libgguf_lib.so (Java_com_dark_gguf_1lib_GGUFNativeLib_nativeStateSaveToFile+60)
          at com.dark.gguf_lib.GGUFNativeLib.nativeStateSaveToFile(Native method)
          - locked <0x0f1a2b3c> (a java.lang.Object)
          at com.dark.gguf_lib.GGMLEngine.stateSaveToFile(GGMLEngine.kt:691)
          at com.uroboros.llm.LlmEngine.saveStateCheckpoint(LlmEngine.kt:1906)
          at kotlinx.coroutines.scheduling.CoroutineScheduler${'$'}Worker.run(CoroutineScheduler.kt:684)
    """.trimIndent()

    private val workerGenerating = """
        "DefaultDispatcher-worker-5" daemon prio=5 tid=31 Native
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x13c40000 self=0xb400007a5f6c8000
          native: #00 pc 00000000004a1b2c  /data/app/~~x/com.uroboros-y/lib/arm64/libgguf_lib.so (ggml_graph_compute+412)
          native: #01 pc 00000000001244ab  /data/app/~~x/com.uroboros-y/lib/arm64/libgguf_lib.so (Java_com_dark_gguf_1lib_GGUFNativeLib_nativeGenerateStreamMultiTurn+880)
          at com.dark.gguf_lib.GGUFNativeLib.nativeGenerateStreamMultiTurn(Native method)
          at com.dark.gguf_lib.GGMLEngine${'$'}generateMultiTurnFlow${'$'}1.invokeSuspend(GGMLEngine.kt:310)
    """.trimIndent()

    private val unrelated = """
        "Signal Catcher" daemon prio=10 tid=6 Runnable
          | group="system" sCount=0 ucsCount=0 flags=0 obj=0x13c00200 self=0xb400007a5f6b5000
          native: #00 pc 00000000005c1a2c  /apex/com.android.art/lib64/libart.so (art::DumpNativeStack+108)
    """.trimIndent()

    private fun reading(text: String, truncated: Boolean = false) = Reading.Trace(15_13, text, truncated)

    @Test
    fun `главный поток на Java-замке — состояние, наш код сверху вниз и держатель`() {
        val text = HangPlace.lines(reading(trace(mainBlockedOnWall, workerSavingCheckpoint, workerGenerating)), moment)
        val lines = text.lines()
        assertEquals("Где стоял экран при «завис» t1513: главный поток ждёт Java-замок", lines[0])
        assertEquals("наш код, сверху вниз:", lines[1])
        assertEquals("· LlmEngine.applyWall (LlmEngine.kt:1326)", lines[2])
        assertEquals("· LlmEngine.configureAfterLoad (LlmEngine.kt:971)", lines[3])
        assertTrue(text, text.contains("Java-замок держит «DefaultDispatcher-worker-3» (в родном коде): в движке nativeStateSaveToFile — ждёт замка движка; GGUFNativeLib.nativeStateSaveToFile (Native method) ← GGMLEngine.stateSaveToFile (GGMLEngine.kt:691) ← LlmEngine.saveStateCheckpoint (LlmEngine.kt:1906)"))
        assertTrue(text, text.contains("ещё в движке: «DefaultDispatcher-worker-5» — nativeGenerateStreamMultiTurn, внутри движка"))
    }

    @Test
    fun `держатель не повторяется в списке потоков внутри движка`() {
        val text = HangPlace.lines(reading(trace(mainBlockedOnWall, workerSavingCheckpoint)), moment)
        assertFalse(text, text.contains("ещё в движке: «DefaultDispatcher-worker-3»"))
    }

    @Test
    fun `главный поток внутри движка — вызов и ждёт ли он замка`() {
        val main = """
            "main" prio=5 tid=1 Native
              | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a3c0f8 self=0xb400007a5f6b3c00
              native: #00 pc 000000000008a4cc  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
              native: #01 pc 00000000000f4c6c  /apex/com.android.runtime/lib64/bionic/libc.so (pthread_mutex_lock+208)
              native: #02 pc 00000000001200aa  /data/app/~~x/com.uroboros-y/lib/arm64/libgguf_lib.so (Java_com_dark_gguf_1lib_GGUFNativeLib_nativeSetSampling+52)
              at com.dark.gguf_lib.GGUFNativeLib.nativeSetSampling(Native method)
              at com.dark.gguf_lib.GGMLEngine.setSampling(GGMLEngine.kt:485)
              at com.uroboros.llm.LlmEngine.applyChatSampling(LlmEngine.kt:1345)
        """.trimIndent()
        val text = HangPlace.lines(reading(trace(main, workerGenerating)), moment)
        assertTrue(text, text.startsWith("Где стоял экран при «завис» t1513: главный поток в родном коде"))
        assertTrue(text, text.contains("в движке: nativeSetSampling — ждёт замка движка"))
        assertTrue(text, text.contains("ещё в движке: «DefaultDispatcher-worker-5» — nativeGenerateStreamMultiTurn, внутри движка"))
    }

    @Test
    fun `верхний кадр не наш — назван отдельно, наш код под ним`() {
        val main = """
            "main" prio=5 tid=1 Runnable
              at java.util.HashMap.get(HashMap.java:556)
              at com.uroboros.memory.dream.DreamWeaver.weave(DreamWeaver.kt:120)
        """.trimIndent()
        val lines = HangPlace.lines(reading(trace(main)), moment).lines()
        assertEquals("Где стоял экран при «завис» t1513: главный поток работает", lines[0])
        assertEquals("сверху: HashMap.get (HashMap.java:556)", lines[1])
        assertEquals("· DreamWeaver.weave (DreamWeaver.kt:120)", lines[3])
    }

    @Test
    fun `главный поток уже свободен — место не поймано и не выдумано`() {
        val main = """
            "main" prio=5 tid=1 Native
              native: #00 pc 00000000000a2d8c  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+12)
              at android.os.MessageQueue.nativePollOnce(Native method)
              at android.os.MessageQueue.next(MessageQueue.java:335)
              at com.uroboros.MainActivity.onCreate(MainActivity.kt:1)
        """.trimIndent()
        val text = HangPlace.lines(reading(trace(main)), moment)
        assertEquals("Где стоял экран при «завис» t1513: к снимку главный поток уже свободен — место не поймано", text)
    }

    @Test
    fun `чужих кадров в нашем коде нет — так и сказано`() {
        val main = """
            "main" prio=5 tid=1 Blocked
              at android.database.sqlite.SQLiteConnectionPool.waitForConnection(SQLiteConnectionPool.java:1000)
              at android.app.ActivityThread.main(ActivityThread.java:8000)
        """.trimIndent()
        val text = HangPlace.lines(reading(trace(main)), moment)
        assertTrue(text, text.contains("наш код: в стеке нет"))
        assertFalse(text, text.contains("в движке:"))
        assertFalse(text, text.contains("держит"))
    }

    @Test
    fun `кадров нашего кода больше предела — показан предел и остаток`() {
        val frames = (1..HangPlace.OWN_SHOWN + 2).joinToString("\n") { "  at com.uroboros.A.f$it(A.kt:$it)" }
        val text = HangPlace.lines(reading(trace("\"main\" prio=5 tid=1 Runnable\n$frames")), moment)
        assertTrue(text, text.contains("· A.f${HangPlace.OWN_SHOWN} (A.kt:${HangPlace.OWN_SHOWN})"))
        assertFalse(text, text.contains("· A.f${HangPlace.OWN_SHOWN + 1} "))
        assertTrue(text, text.contains("· … и ещё 2"))
    }

    @Test
    fun `держатель назван, но в прочитанной части его нет`() {
        val text = HangPlace.lines(reading(trace(mainBlockedOnWall), truncated = true), moment)
        assertTrue(text, text.contains("Java-замок держит поток №27 — в прочитанной части снимка его нет"))
        assertTrue(text, text.lines().first().endsWith("(снимок обрезан на 1 тыс. знаков)"))
    }

    @Test
    fun `главного потока в снимке нет — так и сказано`() {
        val text = HangPlace.lines(reading(trace(unrelated, workerGenerating)), moment)
        assertTrue(text, text.startsWith("Где стоял экран при «завис» t1513: в снимке нет главного потока"))
    }

    @Test
    fun `потоки только родного кода и чужие строки не принимаются за потоки`() {
        val text = trace(
            "\"ggml-worker\" sysTid=7200\n  native: #00 pc 0000 /lib.so (ggml_barrier+40)",
            mainBlockedOnWall,
        )
        val threads = HangPlace.threads(text)
        assertEquals(listOf(1), threads.map { it.tid })
        assertEquals(27, threads.single().heldBy)
    }

    @Test
    fun `нет «завис», нет снимка, не спросить — три разных слова`() {
        assertEquals("Где стоял экран: среди ком, что отдал Android, «завис» нет", HangPlace.lines(Reading.NoHang, moment))
        assertEquals(
            "Где стоял экран при «завис» t5: Android снимка потоков не сохранил",
            HangPlace.lines(Reading.NoTrace(5, "Android снимка потоков не сохранил"), moment),
        )
        assertEquals("Где стоял экран: не узнал — SecurityException", HangPlace.lines(Reading.Unavailable("SecurityException"), moment))
    }

    @Test
    fun `кадр коротко — класс, метод и место`() {
        assertEquals("LlmEngine.applyWall (LlmEngine.kt:1326)", HangPlace.short("com.uroboros.llm.LlmEngine.applyWall(LlmEngine.kt:1326)"))
        assertEquals("GGUFNativeLib.nativeSetSampling (Native method)", HangPlace.short("com.dark.gguf_lib.GGUFNativeLib.nativeSetSampling(Native method)"))
        assertNull(HangPlace.engineCall(HangPlace.StackThread("x", 2, "Runnable", listOf("com.uroboros.A.f(A.kt:1)"), emptyList(), null)))
    }
}
