package com.uroboros.documents

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatDocumentsTest {
    @Test fun textSurvivesWordAndTxtWithCyrillicAndBlankLines() {
        val body = "  Срок <30> & дата\n\nЦена 15 €\tза лист\n"
        for (ext in listOf("docx", "txt")) {
            assertEquals(body, DocumentText.read(ext, DocumentText.write(ext, body)))
        }
    }

    @Test fun refusesMalformedUtf8AndUnboundedInput() {
        assertThrows(Exception::class.java) { DocumentText.read("txt", byteArrayOf(-1)) }
        assertThrows(Exception::class.java) { DocumentText.bounded(ByteArray(11).inputStream(), 10) }
    }

    @Test fun refusesDoctypeAndOversizedExpansion() {
        fun zip(xml: String): ByteArray = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use {
                it.putNextEntry(ZipEntry("word/document.xml"))
                it.write(xml.toByteArray())
            }
        }.toByteArray()
        for (xml in listOf("<!DOCTYPE x><x/>", "x".repeat(DocumentText.MAX_BYTES * 2 + 1))) {
            assertThrows(Exception::class.java) { DocumentText.read("docx", zip(xml)) }
        }
    }

    @Test fun requestComesFromPersonAndOperationsAreLocal() {
        val source = JSONObject().put("text", "Оплата завтра\nДоставка сегодня")
        assertFalse(ChatDocuments.requested("Не отправляй документ"))
        assertFalse(ChatDocuments.requested("В архиве лежал документ"))
        assertTrue(ChatDocuments.requested("Замени «завтра» на «сегодня» в документе"))
        assertEquals("Оплата сегодня\nДоставка сегодня", ChatDocuments.local("Замени «завтра» на «сегодня» в документе", source))
        assertEquals("Доставка сегодня", ChatDocuments.local("Найди в документе абзац про доставка", source))
        assertThrows(Exception::class.java) { ChatDocuments.local("Замени «ошибка» на «правка»", source) }
    }
}
