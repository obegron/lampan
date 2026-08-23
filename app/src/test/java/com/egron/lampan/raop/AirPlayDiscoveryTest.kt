package com.egron.lampan.raop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirPlayDiscoveryTest {
    @Test
    fun recordsAtSameAddressMergeAndPreferAirPlay1ByDefault() {
        val airPlay1 = AirPlayDevice(
            name = "A1B2C3D4E5F6@Living Room",
            ip = "192.168.0.21",
            airPlay1Port = 7000,
        )
        val airPlay2 = AirPlayDevice(
            name = "Living Room",
            ip = "192.168.0.21",
            airPlay2Port = 7001,
            airPlay2RequiresPassword = false,
        )

        val merged = mergeAirPlayDevice(airPlay1, airPlay2)

        assertEquals("Living Room", merged.name)
        assertEquals(7000, merged.airPlay1Port)
        assertEquals(7001, merged.airPlay2Port)
        assertFalse(requireNotNull(merged.airPlay2RequiresPassword))
        assertEquals(AirPlayProtocol.AIRPLAY_1, merged.preferredProtocol)
        assertEquals("AirPlay 1 + 2", merged.protocolLabel)
    }

    @Test
    fun explicitAirPlay2PreferenceOverridesDualTransportDefault() {
        val device = AirPlayDevice(
            name = "Living Room TV",
            ip = "192.168.0.12",
            airPlay1Port = 5000,
            airPlay2Port = 7000,
            protocolPreference = AirPlayProtocol.AIRPLAY_2,
        )

        assertEquals(AirPlayProtocol.AIRPLAY_2, device.preferredProtocol)
        assertEquals(7000, device.portFor(device.preferredProtocol))
    }

    @Test
    fun mergingSecondTransportKeepsRememberedPreference() {
        val rememberedAirPlay1 = AirPlayDevice(
            name = "Living Room TV",
            ip = "192.168.0.12",
            airPlay1Port = 5000,
            protocolPreference = AirPlayProtocol.AIRPLAY_2,
        )
        val discoveredAirPlay2 = AirPlayDevice(
            name = "Living Room TV",
            ip = "192.168.0.12",
            airPlay2Port = 7000,
        )

        val merged = mergeAirPlayDevice(rememberedAirPlay1, discoveredAirPlay2)

        assertEquals(AirPlayProtocol.AIRPLAY_2, merged.protocolPreference)
        assertEquals(AirPlayProtocol.AIRPLAY_2, merged.preferredProtocol)
    }

    @Test
    fun airPlay2OnlyReceiverStillUsesAirPlay2() {
        val device = AirPlayDevice(
            name = "AirPlay 2 only",
            ip = "192.168.0.30",
            airPlay2Port = 7000,
        )

        assertEquals(AirPlayProtocol.AIRPLAY_2, device.preferredProtocol)
    }
}
