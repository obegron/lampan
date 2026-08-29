package com.egron.lampan

import com.egron.lampan.raop.AirPlayDevice
import com.egron.lampan.raop.AirPlayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupSyncProfileTest {
    private val keys = listOf("speaker|AIRPLAY_1", "tv|AIRPLAY_2")

    @Test
    fun referenceNeverReceivesDelayAndOtherDelayIsBounded() {
        val profile = GroupSyncProfile(
            referenceKey = keys.first(),
            delaysMs = mapOf(keys.first() to 200, keys.last() to 900),
        ).normalized(keys)

        assertEquals(0, profile.delaysMs[keys.first()])
        assertEquals(MAX_RECEIVER_DELAY_MS, profile.delaysMs[keys.last()])
    }

    @Test
    fun selectingReferenceResetsCorrections() {
        val profile = GroupSyncProfile(keys.first(), mapOf(keys.last() to 125))
            .selectReference(keys, keys.last())

        assertEquals(keys.last(), profile.referenceKey)
        assertEquals(mapOf(keys.first() to 0, keys.last() to 0), profile.delaysMs)
    }

    @Test
    fun exactGroupIdentityDoesNotDependOnSelectionOrder() {
        assertEquals(groupSyncIdentity(keys), groupSyncIdentity(keys.reversed()))
        assertNotEquals(groupSyncIdentity(keys), groupSyncIdentity(keys + "bedroom|AIRPLAY_1"))
    }

    @Test
    fun receiverIdentityIncludesSelectedProtocol() {
        val device = AirPlayDevice(
            name = "TV",
            ip = "192.168.0.12",
            airPlay1Port = 7000,
            airPlay2Port = 7000,
            receiverId = "stable-tv-id",
        )

        assertNotEquals(
            receiverSyncKey(device.copy(protocolPreference = AirPlayProtocol.AIRPLAY_1)),
            receiverSyncKey(device.copy(protocolPreference = AirPlayProtocol.AIRPLAY_2)),
        )
    }
}
