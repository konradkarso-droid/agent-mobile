package com.uroboros.search

import com.uroboros.memory.nav.LemmaDictionary
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchWordsTest {
    private val dictionary = LemmaDictionary.read(sequenceOf(
        "LEM1",
        "T 1", "NOUN",
        "E 3", "|я|0", "|и|0", "|ям|0",
        "P 1", "я|0 1 2",
        "S 1", "0фотографи 0",
    ))

    @Test
    fun russianFormsReportOneMissingWordAndDoNotMultiplyItsWeight() {
        val query = FtsQuery("Копия фотографии фотография", dictionary)
        assertEquals(listOf("копия", "фотографии", "фотография", "фотографи*"), query.terms)

        val oneForm = query.score(matchInfo(0 to 0, 3 to 0, 0 to 0, 0 to 0))
        val twoForms = query.score(matchInfo(0 to 0, 3 to 0, 3 to 0, 3 to 0))
        assertEquals(listOf("копия"), twoForms.missingWords)
        assertEquals(oneForm, twoForms)

        val wordsAcrossTitleAndBody = query.score(matchInfo(0 to 1, 0 to 0, 0 to 0, 1 to 0))
        assertTrue(wordsAcrossTitleAndBody.missingWords.isEmpty())
        assertEquals(listOf("фотографии"), query.score(matchInfo(0 to 1, 0 to 0, 0 to 0, 0 to 0)).missingWords)
    }

    @Test
    fun russianQuestionWordsPreserveTechnicalTargetsForTheSitemap() {
        val query = FtsQuery("Можно ли использовать CSS Subgrid?")
        assertEquals(listOf("css", "subgrid"), sitemapTerms(query.terms))
        val full = FtsQuery("Что такое CSS Subgrid?")
        assertEquals(listOf("subgrid"), full.score(matchInfo(1 to 0, 0 to 0)).missingWords)
        assertTrue(full.score(matchInfo(1 to 0, 0 to 1)).missingWords.isEmpty())
    }

    private fun matchInfo(vararg hits: Pair<Int, Int>): ByteArray {
        val data = ByteBuffer.allocate((2 + hits.size * 2 * 3) * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        data.putInt(hits.size).putInt(2)
        for ((title, body) in hits) {
            data.putInt(title).putInt(20).putInt(4)
            data.putInt(body).putInt(25).putInt(5)
        }
        return data.array()
    }
}
