package com.egron.lampan

import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlayProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerVolumesTest {
    @Test fun groupChangesRetainBalanceAfterClipping() {
        val original = SpeakerVolumes.from(mapOf("a" to 60, "b" to 30))
        val quiet = original.adjustGroup(10)
        assertEquals(mapOf("a" to 10, "b" to 0), quiet.levels)
        assertEquals(original.levels, quiet.adjustGroup(60).levels)
    }

    @Test fun joiningDoesNotChangeExistingSpeakers() {
        val original = SpeakerVolumes.from(mapOf("a" to 40, "b" to 20))
        val joined = original.add("c", 90)
        assertEquals(90, joined.group)
        assertEquals(mapOf("a" to 40, "b" to 20, "c" to 90), joined.levels)
        val louder = joined.adjustGroup(100)
        assertEquals(mapOf("a" to 50, "b" to 30, "c" to 100), louder.levels)
        assertEquals(joined.levels, louder.adjustGroup(90).levels)
    }

    @Test fun zeroMutesEverySpeakerAndPreservesBalanceForRestoration() {
        val original = SpeakerVolumes.from(mapOf("a" to 40)).add("b", 90)
        val muted = original.adjustGroup(0)
        assertEquals(mapOf("a" to 0, "b" to 0), muted.levels)
        assertEquals(original.levels, muted.adjustGroup(original.group).levels)
        assertEquals(mapOf("a" to 0, "b" to 2), muted.adjustGroup(2).levels)
    }

    @Test fun speakerJoiningMutedGroupStartsLowOnUnmute() {
        val joined = SpeakerVolumes.from(mapOf("a" to 40)).adjustGroup(0).add("b", 90)
        assertEquals(mapOf("a" to 0, "b" to 0), joined.levels)
        assertEquals(mapOf("a" to 2, "b" to 2), joined.adjustGroup(2).levels)
    }

    @Test fun editingIndividualRebalancesWithoutChangingOtherLevels() {
        val original = SpeakerVolumes.from(mapOf("a" to 40, "b" to 60))
        val edited = original.adjustSpeaker("a", 80)
        assertEquals(mapOf("a" to 80, "b" to 60), edited.levels)
        assertEquals(mapOf("a" to 70, "b" to 50), edited.adjustGroup(70).levels)
        assertEquals(edited, edited.adjustSpeaker("missing", 10))
    }

    @Test fun editingOneSpeakerPreservesOthersUnclippedOffsets() {
        val original = SpeakerVolumes.from(mapOf("a" to 80, "b" to 30, "c" to 50))
        val edited = original.adjustGroup(20).adjustSpeaker("c", 10)
        assertEquals(mapOf("a" to 80, "b" to 30, "c" to 70), edited.adjustGroup(80).levels)
    }

    @Test fun boundsAndRemovingToSingleSpeakerRemainConsistent() {
        val state = SpeakerVolumes.from(mapOf("a" to -20, "b" to 200))
        assertEquals(mapOf("a" to 0, "b" to 100), state.levels)
        assertEquals(0, state.adjustGroup(-1).group)
        assertEquals(100, state.adjustGroup(101).group)
        val remaining = state.adjustSpeaker("a", 30).remove("b")
        assertEquals(30, remaining.group)
        assertEquals(mapOf("a" to 30), remaining.levels)
    }

    @Test fun savedIdentitySurvivesProtocolAndAddressChanges() {
        val speaker = AirPlayDevice(
            name = "Speaker", ip = "192.168.1.10", receiverId = "AA:BB",
            airPlay1Port = 7000, airPlay2Port = 7001,
        )
        assertEquals(receiverVolumeKey(speaker), receiverVolumeKey(speaker.copy(
            ip = "192.168.1.20", protocolPreference = AirPlayProtocol.AIRPLAY_2,
        )))
    }
}
