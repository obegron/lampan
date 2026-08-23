package com.egron.lampan.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioCaptureSignalTest {

    @Test
    fun silentPcmHasZeroPeak() {
        assertEquals(0, pcm16LittleEndianPeak(ByteArray(1_408)))
    }

    @Test
    fun findsLargestSignedLittleEndianSample() {
        val pcm = byteArrayOf(
            0x01, 0x00,
            0x00, 0x80.toByte(),
            0xFF.toByte(), 0x7F,
        )

        assertEquals(32_768, pcm16LittleEndianPeak(pcm))
    }

    @Test
    fun ignoresTrailingPartialSample() {
        assertEquals(4_660, pcm16LittleEndianPeak(byteArrayOf(0x34, 0x12, 0x7F)))
    }
}
