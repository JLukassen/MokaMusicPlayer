package com.mokamusic.player.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMetadataFallbackPolicyTest {
    private val complete = EmbeddedMetadata(
        title = "Title", artist = "Artist", album = "Album",
        sampleRateHz = 96000, bitDepth = 24, channelCount = 2
    )

    @Test fun completeFlacTagsAvoidNativeRetriever() {
        assertFalse(shouldUseNativeMetadataFallback("flac", complete, false))
    }

    @Test fun mediastoreCanFillMissingWavNames() {
        assertFalse(shouldUseNativeMetadataFallback(
            "wav", complete.copy(title = null, artist = null, album = null), true
        ))
    }

    @Test fun fallbackWhenTechnicalDataMissing() {
        assertTrue(shouldUseNativeMetadataFallback(
            "flac", complete.copy(bitDepth = null), true
        ))
    }

    @Test fun fallbackWhenIdentityMissing() {
        assertTrue(shouldUseNativeMetadataFallback(
            "wav", complete.copy(album = null), false
        ))
    }

    @Test fun unknownFormatsStillUseNativeRetriever() {
        assertTrue(shouldUseNativeMetadataFallback("mp3", complete, true))
    }
}
