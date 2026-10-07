package com.mokamusic.player.metadata

import android.content.Context

enum class MetadataNamePreference(val label: String, val description: String) {
    FILE_TAGS(
        "File tags",
        "Use the names embedded in your files. This is the safest choice for carefully tagged libraries."
    ),
    PREFER_ENGLISH_LATIN(
        "Prefer English / Latin",
        "Prefer an English MusicBrainz artist alias when available, otherwise favor the more Latin-script name."
    ),
    MUSICBRAINZ_CANONICAL(
        "MusicBrainz canonical",
        "Use MusicBrainz's canonical album/artist names when an enriched match exists."
    )
}

class MetadataNamePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moka_metadata_display", Context.MODE_PRIVATE)

    fun load(): MetadataNamePreference = runCatching {
        MetadataNamePreference.valueOf(
            prefs.getString(KEY, MetadataNamePreference.FILE_TAGS.name)
                ?: MetadataNamePreference.FILE_TAGS.name
        )
    }.getOrDefault(MetadataNamePreference.FILE_TAGS)

    fun save(value: MetadataNamePreference) {
        prefs.edit().putString(KEY, value.name).apply()
    }

    private companion object { const val KEY = "name_preference" }
}

fun resolveArtistName(
    local: String,
    canonical: String?,
    englishAlias: String?,
    preference: MetadataNamePreference
): String = when (preference) {
    MetadataNamePreference.FILE_TAGS -> local
    MetadataNamePreference.MUSICBRAINZ_CANONICAL -> canonical?.takeIf(String::isNotBlank) ?: local
    MetadataNamePreference.PREFER_ENGLISH_LATIN ->
        englishAlias?.takeIf(String::isNotBlank)
            ?: listOf(local, canonical.orEmpty())
                .filter(String::isNotBlank)
                .maxByOrNull { latinPercent(it) + if (it == local) 1 else 0 }
            ?: local
}

fun resolveAlbumName(
    local: String,
    canonical: String?,
    preference: MetadataNamePreference
): String = when (preference) {
    MetadataNamePreference.FILE_TAGS -> local
    MetadataNamePreference.MUSICBRAINZ_CANONICAL -> canonical?.takeIf(String::isNotBlank) ?: local
    MetadataNamePreference.PREFER_ENGLISH_LATIN ->
        listOf(local, canonical.orEmpty())
            .filter(String::isNotBlank)
            .maxByOrNull { latinPercent(it) + if (it == local) 1 else 0 }
            ?: local
}

private fun latinPercent(value: String): Int {
    var letters = 0
    var latin = 0
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        if (Character.isLetter(codePoint)) {
            letters++
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
        }
        offset += Character.charCount(codePoint)
    }
    return if (letters == 0) 0 else (latin * 100) / letters
}
