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
        val query = SearchWords("Копия фотографии фотография", dictionary)
        assertEquals(listOf("копия", "фотографии", "фотография", "фотографи*"), query.terms)

        val oneForm = query.scoreIndexMatch(matchInfo(0 to 0, 3 to 0, 0 to 0, 0 to 0))
        val twoForms = query.scoreIndexMatch(matchInfo(0 to 0, 3 to 0, 3 to 0, 3 to 0))
        assertEquals(listOf("копия"), twoForms.missingWords)
        assertEquals(oneForm, twoForms)

        val wordsAcrossTitleAndBody = query.scoreIndexMatch(matchInfo(0 to 1, 0 to 0, 0 to 0, 1 to 0))
        assertTrue(wordsAcrossTitleAndBody.missingWords.isEmpty())
        assertEquals(listOf("фотографии"),
            query.scoreIndexMatch(matchInfo(0 to 1, 0 to 0, 0 to 0, 0 to 0)).missingWords)
    }

    @Test
    fun russianQuestionWordsPreserveTechnicalTargetsForTheSitemap() {
        val query = SearchWords("Можно ли использовать CSS Subgrid?")
        assertEquals(listOf("css", "subgrid"), sitemapTerms(query.terms))
        val full = SearchWords("Что такое CSS Subgrid?")
        assertEquals(listOf("subgrid"), full.scoreIndexMatch(matchInfo(1 to 0, 0 to 0)).missingWords)
        assertTrue(full.scoreIndexMatch(matchInfo(1 to 0, 0 to 1)).missingWords.isEmpty())
    }

    @Test
    fun excerptCountsRussianFormsOnceAndMatchesWholeTechnicalTerms() {
        val query = SearchWords("CSS фотографии", dictionary)
        val unrelated = "В CSSOM нет разницы между метками «фотография» и «фотографии»."
        val matching = "Для фотографий подходит сетка CSS."
        val page = IndexedPage("https://developer.mozilla.org/en-US/docs/Web/CSS", "Сетка", "$unrelated\n$matching")

        assertEquals(1, query.matchingGroups(unrelated).size)
        assertEquals(2, query.matchingGroups(matching).size)
        assertEquals(matching, bestExcerpt(page, query))
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
