package com.uroboros.memory.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Отпечатки судей ([JudgePrint]).
 *
 * ГЛАВНОЕ ЗДЕСЬ: тот же файл модели под другим адресом — тот же судья; другой
 * файл — другой. И отпечаток по адресу считается ровно так, как считался
 * раньше: иначе строки, снятые под ним, не нашлись бы для переноса.
 *
 * ЧЕГО ФАЙЛ НЕ ДОКАЗЫВАЕТ: что система отдаёт имя и размер файла модели по
 * его адресу и что перенос строк в базе прошёл. Это видно на телефоне:
 * строка «Судья:» в отчёте прогона и число разобранных пар после него.
 */
class JudgePrintTest {

    private val prompt = "промпт судьи"
    private val oldPhone = "content://com.android.externalstorage.documents/tree/primary%3AModels/document/a.gguf"
    private val newPhone = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/a.gguf"

    @Test
    fun `тот же файл под другим адресом — тот же судья`() {
        val before = JudgePrint.printed(JudgePrint.model(oldPhone, "Falcon.gguf", 4_500_000_000L), prompt)
        val after = JudgePrint.printed(JudgePrint.model(newPhone, "Falcon.gguf", 4_500_000_000L), prompt)
        assertEquals(before, after)
        assertEquals(
            JudgePrint.direct(JudgePrint.model(oldPhone, "Falcon.gguf", 4_500_000_000L), 3),
            JudgePrint.direct(JudgePrint.model(newPhone, "Falcon.gguf", 4_500_000_000L), 3),
        )
    }

    @Test
    fun `другой размер или другое имя — другой судья`() {
        val base = JudgePrint.model(newPhone, "Falcon.gguf", 4_500_000_000L)
        assertNotEquals(base, JudgePrint.model(newPhone, "Falcon.gguf", 4_500_000_001L))
        assertNotEquals(base, JudgePrint.model(newPhone, "Vikhr.gguf", 4_500_000_000L))
    }

    @Test
    fun `другой промпт — другой судья и при том же файле`() {
        val model = JudgePrint.model(newPhone, "Falcon.gguf", 4_500_000_000L)
        assertNotEquals(JudgePrint.printed(model, prompt), JudgePrint.printed(model, "$prompt."))
    }

    @Test
    fun `имя или размер не прочитались — отпечаток по адресу, как раньше`() {
        assertEquals(JudgePrint.legacyModel(newPhone), JudgePrint.model(newPhone, null, 4_500_000_000L))
        assertEquals(JudgePrint.legacyModel(newPhone), JudgePrint.model(newPhone, "Falcon.gguf", null))
        assertEquals(JudgePrint.legacyModel(newPhone), JudgePrint.model(newPhone, " ", 4_500_000_000L))
        assertEquals(JudgePrint.legacyModel(newPhone), JudgePrint.model(newPhone, "Falcon.gguf", 0L))
    }

    @Test
    fun `отпечаток по адресу считается так же, как до имени и размера`() {
        assertEquals(
            Integer.toHexString(newPhone.hashCode()) + "-" + Integer.toHexString(prompt.hashCode()),
            JudgePrint.printed(JudgePrint.legacyModel(newPhone), prompt),
        )
        assertEquals(
            Integer.toHexString(newPhone.hashCode()) + "-d" + 3,
            JudgePrint.direct(JudgePrint.legacyModel(newPhone), 3),
        )
    }
}
