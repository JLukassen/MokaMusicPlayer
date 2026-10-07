package com.mokamusic.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class UnicodeTextTest {
    @Test
    fun koreanAndPunctuationArePreservedForDisplay() {
        val value = "빌려온 고양이: I'll Like You! \"Moka\""
        assertEquals(value, UnicodeText.display(value))
    }

    @Test
    fun canonicallyEquivalentUnicodeMatchesSearch() {
        val composed = "Café · 한글"
        val decomposed = "Cafe\u0301 · 한글"
        assertTrue(UnicodeText.contains(composed, decomposed))
    }

    @Test
    fun cp949RiffMetadataDecodesKoreanAndPunctuation() {
        val expected = "앨범: 특별판! 'Moka'"
        val bytes = expected.toByteArray(Charset.forName("x-windows-949"))
        assertEquals(expected, decodeRiffInfoText(bytes))
    }

    @Test
    fun utf8RiffMetadataKeepsQuotesColonAndBang() {
        val expected = "NOT CUTE ANYMORE: \"Special\"!"
        assertEquals(expected, decodeRiffInfoText(expected.toByteArray(Charsets.UTF_8)))
    }
}
