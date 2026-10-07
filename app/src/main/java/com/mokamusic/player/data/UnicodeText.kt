package com.mokamusic.player.data

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Unicode-safe helpers for library metadata, grouping, searching and sorting.
 *
 * Display text is normalized to NFC so Hangul, accented characters, emoji and punctuation are
 * preserved exactly as text. Search/group keys use NFKC + lowercase to make canonically equivalent
 * forms match without stripping punctuation such as :, ', \" and ! from the visible metadata.
 */
object UnicodeText {
    fun display(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }

    fun key(value: String?): String = Normalizer
        .normalize(value.orEmpty().trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)

    fun searchKey(value: String?): String = key(value)

    fun contains(haystack: String?, needle: String): Boolean {
        if (needle.isBlank()) return true
        return searchKey(haystack).contains(searchKey(needle))
    }

    fun comparator(locale: Locale = Locale.getDefault()): Comparator<String> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }
        return Comparator { a, b -> collator.compare(
            Normalizer.normalize(a, Normalizer.Form.NFC),
            Normalizer.normalize(b, Normalizer.Form.NFC)
        ) }
    }
}
