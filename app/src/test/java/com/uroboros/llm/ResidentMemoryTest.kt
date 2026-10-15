package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResidentMemoryTest {

    private val status = """
        Name:	com.uroboros
        VmHWM:	 3971072 kB
        VmRSS:	 2101248 kB
        RssAnon:	 1945600 kB
        RssFile:	  151552 kB
        RssShmem:	    4096 kB
    """.trimIndent()

    @Test
    fun splitsWorkingAndFilePages() {
        val split = ResidentMemory.parse(status)!!
        assertEquals(1900L, split.anonMb)
        assertEquals(148L, split.fileMb)
        assertEquals("Память сейчас: рабочая 1900 МБ, файлы 148 МБ", ResidentMemory.line(split))
    }

    @Test
    fun silentWhenFieldMissing() {
        assertNull(ResidentMemory.parse("Name:\tx\nRssAnon:\t100 kB\n"))
        assertNull(ResidentMemory.parse(""))
        assertEquals("Память сейчас: не прочитано", ResidentMemory.line(null))
    }

    @Test
    fun doesNotTakeFieldWithLongerName() {
        // RssFileX не должен читаться как RssFile.
        assertNull(ResidentMemory.parse("RssAnon:\t1024 kB\nRssFileX:\t2048 kB\n"))
    }
}
