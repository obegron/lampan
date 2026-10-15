package com.egron.lampan

import com.egron.lampan.calibration.CalibrationSignal
import com.egron.lampan.raop.NetworkClock
import com.egron.lampan.raop.RtpNtpTimeline
import com.egron.lampan.raop.ntpFromUnixNanos
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class CalibrationTest {
    @Test fun groupClockKeepsSamplePrecisionAndExistingReceiversWhenJoining() {
        val group = GroupTimeline(10_000_000_123L)
        val original = RtpNtpTimeline()
        original.synchronizeAtNanos(17_600, group.startUnixNanos)
        repeat(125) { group.advance(352) }
        group.advance(100)
        val joining = RtpNtpTimeline()
        joining.synchronizeAtNanos(88_200, group.timeAt(group.nextFrame))
        assertEquals(11_000_000_123L, joining.unixNanosAt(88_200, -1))
        assertEquals(joining.unixNanosAt(88_200, -1), original.unixNanosAt(61_700, -1))
        assertEquals(10_000_022_798L, group.timeAt(1))
    }

    @Test fun delayChangesAreReversibleAndIndependentOfCurrentWallTime() {
        val timeline = RtpNtpTimeline()
        timeline.synchronizeAtNanos(0xFFFF_FF00L, 10_000_000_123L)
        val before = timeline.unixNanosAt(0x100L, -1)
        timeline.shiftByMillis(125)
        assertEquals(before + 125_000_000L, timeline.unixNanosAt(0x100L, Long.MAX_VALUE))
        timeline.shiftByMillis(-125)
        assertEquals(before, timeline.unixNanosAt(0x100L, 1))
    }

    @Test fun networkClockAndMicrophoneUseTheSameMonotonicConversion() {
        var now = 900L
        val clock = NetworkClock(1_234_000_000_000L, 800L) { now }
        assertEquals(1_234_000_000_100L, clock.unixNanos())
        now += 3_000_000_000L
        assertEquals(now, clock.monotonicAt(clock.unixNanos()))
        assertEquals((2_208_988_801L shl 32) or 0x8000_0000L, ntpFromUnixNanos(1_500_000_000L))
    }

    @Test fun detectorFindsQuietInvertedSignalInNoiseAtNonPacketBoundary() {
        val random = Random(13)
        val samples = ShortArray(44_100) { (random.nextInt(61) - 30).toShort() }
        val offset = 12_347
        CalibrationSignal.template.forEachIndexed { i, value ->
            samples[offset + i] = (samples[offset + i] - value / 3).toShort()
        }
        assertTrue(kotlin.math.abs(CalibrationSignal.detect(samples) - offset) <= 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun silenceIsNotAcceptedAsCalibration() {
        CalibrationSignal.detect(ShortArray(44_100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEchoesAreRejected() {
        val samples = ShortArray(44_100)
        CalibrationSignal.template.copyInto(samples, 4_000)
        CalibrationSignal.template.copyInto(samples, 15_000)
        CalibrationSignal.detect(samples)
    }

    @Test fun commonMicrophoneLatencyCancelsAndSlowestSpeakerBecomesReference() {
        val first = calibratedProfile(mapOf("a" to 100.2, "b" to 145.4, "c" to 120.0))
        val second = calibratedProfile(mapOf("a" to 300.2, "b" to 345.4, "c" to 320.0))
        assertEquals(first, second)
        assertEquals("b", first.referenceKey)
        assertEquals(mapOf("a" to 45, "b" to 0, "c" to 25), first.delaysMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun changingDelayIsRejectedRatherThanAveragedIntoASavedCorrection() {
        CalibrationSignal.medianResidual(listOf(10.0, 12.0, 45.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveDelayIsNotSilentlyClamped() {
        calibratedProfile(mapOf("a" to 0.0, "b" to 750.0))
    }

    @Test fun probePacketizationPreservesAllThreeChirps() {
        val pcm = CalibrationSignal.pcm(-1, CalibrationSignal.SPACING * 2 + CalibrationSignal.LENGTH + 2)
        fun sample(frame: Int): Short = ((pcm[frame * 4].toInt() and 255) or (pcm[frame * 4 + 1].toInt() shl 8)).toShort()
        assertEquals(0.toShort(), sample(0))
        for (burst in 0..2) {
            CalibrationSignal.template.forEachIndexed { i, value ->
                assertEquals(value, sample(1 + burst * CalibrationSignal.SPACING + i))
            }
        }
        assertEquals(0.toShort(), sample(pcm.size / 4 - 1))
    }
}
