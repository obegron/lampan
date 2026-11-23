package com.egron.lampan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaMuteTest {
    private class Output(override var isMuted: Boolean = false, override val isFixed: Boolean = false) : MediaMuteOutput {
        val changes = mutableListOf<Boolean>()
        override fun changeMute(muted: Boolean) { changes += muted; isMuted = muted }
    }

    @Test fun ownMuteIsAppliedOnceAndRestoredOnce() {
        val output = Output()
        val controller = PhoneMediaMute(output)
        controller.apply()
        controller.apply()
        assertTrue(output.isMuted)
        controller.restore()
        controller.restore()
        assertFalse(output.isMuted)
        assertEquals(listOf(true, false), output.changes)
    }

    @Test fun anExistingMuteIsNeverReleased() {
        val output = Output(isMuted = true)
        val controller = PhoneMediaMute(output)
        controller.apply()
        controller.restore()
        assertTrue(output.isMuted)
        assertEquals(emptyList<Boolean>(), output.changes)
    }

    @Test fun userUnmuteIsNotOverriddenByRefreshOrStop() {
        val output = Output()
        val controller = PhoneMediaMute(output)
        controller.apply()
        output.isMuted = false
        controller.apply()
        controller.restore()
        assertFalse(output.isMuted)
        assertEquals(listOf(true), output.changes)
    }

    @Test fun fixedVolumeOutputIsLeftAlone() {
        val output = Output(isFixed = true)
        val controller = PhoneMediaMute(output)
        controller.apply()
        controller.restore()
        assertEquals(emptyList<Boolean>(), output.changes)
    }

    @Test fun enablingAgainAfterDisablingCanOwnANewMute() {
        val output = Output()
        val controller = PhoneMediaMute(output)
        controller.apply()
        controller.restore()
        controller.apply()
        controller.restore()
        assertEquals(listOf(true, false, true, false), output.changes)
    }

    @Test fun rejectedMuteDoesNotAcquireOwnershipOfALaterUserMute() {
        var muted = false
        val output = object : MediaMuteOutput {
            override val isFixed = false
            override val isMuted get() = muted
            override fun changeMute(muted: Boolean) { throw SecurityException("denied") }
        }
        val controller = PhoneMediaMute(output)
        runCatching { controller.apply() }
        muted = true
        controller.restore() // Must not attempt an unmute, which would throw.
        assertTrue(muted)
    }
}
