package com.uroboros.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplatesTest {

    @Test
    fun `Falcon-H1 — свой шаблон`() {
        assertEquals(ChatTemplates.ALL_MESSAGES, ChatTemplates.forArchitecture("falcon-h1"))
        assertEquals("свой, falcon-h1", ChatTemplates.sourceWords("falcon-h1"))
    }

    @Test
    fun `прочие и неизвестная архитектура — шаблон из файла модели`() {
        for (arch in listOf("qwen2", "falcon", "", null)) {
            assertNull(arch, ChatTemplates.forArchitecture(arch))
            assertEquals("из файла модели", ChatTemplates.sourceWords(arch))
        }
    }

    @Test
    fun `свой шаблон не отбирает сообщения по роли и держит разметку Falcon-H1`() {
        val t = ChatTemplates.ALL_MESSAGES
        assertFalse(t.contains("role !="))
        assertFalse(t.contains("role =="))
        assertTrue(t.startsWith("{{bos_token}}"))
        // Переводы строк внутри кавычек — настоящие, как в шаблоне модели.
        assertTrue(t.contains("message['role'] + '\n' + message['content'] + '<|im_end|>' + '\n'"))
        assertTrue(t.contains("'<|im_start|>assistant\n'"))
    }
}
