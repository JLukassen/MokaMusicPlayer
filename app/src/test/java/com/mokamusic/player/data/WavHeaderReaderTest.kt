package com.mokamusic.player.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavHeaderReaderTest {
    private fun pcmWav(rate: Int = 44_100, bits: Int = 16, channels: Int = 2, tag: Int = 1): ByteArray {
        val width = bits / 8
        val blockAlign = width * channels
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(tag.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(0)
        }.array()
    }

    @Test fun readsPcmWavWithoutAndroidMetadataRetriever() {
        assertEquals(
            AudioTechnicalMetadata(44_100, 16, 1_411_200),
            parsePcmWavHeader(pcmWav())
        )
        assertEquals(
            AudioTechnicalMetadata(96_000, 24, 4_608_000),
            parsePcmWavHeader(pcmWav(rate = 96_000, bits = 24))
        )
    }

    @Test fun rejectsCompressedOrInvalidHeaderRatherThanGuessing() {
        assertNull(parsePcmWavHeader(pcmWav(tag = 85)))
        assertNull(parsePcmWavHeader(byteArrayOf(1, 2, 3)))
        val invalid = pcmWav().apply { this[0] = 0 }
        assertNull(parsePcmWavHeader(invalid))
    }

    @Test fun skipsJunkChunksBeforeFmt() {
        val original = pcmWav()
        val junk = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("JUNK".toByteArray(Charsets.US_ASCII))
            putInt(4)
            putInt(0)
        }.array()
        val withJunk = original.copyOfRange(0, 12) + junk + original.copyOfRange(12, original.size)
        assertEquals(AudioTechnicalMetadata(44_100, 16, 1_411_200), parsePcmWavHeader(withJunk))
    }
}
