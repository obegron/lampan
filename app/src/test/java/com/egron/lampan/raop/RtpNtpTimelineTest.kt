package com.egron.lampan.raop

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpNtpTimelineTest {
    @Test
    fun differentRtpOriginsMapTheSamePcmPositionToOneNetworkTime() {
        val airPlay1 = RtpNtpTimeline()
        val airPlay2 = RtpNtpTimeline()
        val sharedStart = 10_000L

        airPlay1.synchronizeAt(rtpTimestamp = 17_600L, unixTimeMillis = sharedStart)
        airPlay2.synchronizeAt(rtpTimestamp = 88_200L, unixTimeMillis = sharedStart)

        assertEquals(sharedStart, airPlay1.unixTimeAt(17_600L, -1L))
        assertEquals(sharedStart, airPlay2.unixTimeAt(88_200L, -1L))
        assertEquals(11_000L, airPlay1.unixTimeAt(61_700L, -1L))
        assertEquals(11_000L, airPlay2.unixTimeAt(132_300L, -1L))
    }

    @Test
    fun mappingSurvivesRtpTimestampWrap() {
        val timeline = RtpNtpTimeline(sampleRate = 1_000L)
        timeline.synchronizeAt(0xFFFF_FF00L, 20_000L)

        assertEquals(20_512L, timeline.unixTimeAt(0x0000_0100L, -1L))
    }

    @Test
    fun unanchoredTimelineUsesCurrentClockFallback() {
        assertEquals(42_000L, RtpNtpTimeline().unixTimeAt(123L, 42_000L))
    }
}
