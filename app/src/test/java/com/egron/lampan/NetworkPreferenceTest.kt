package com.egron.lampan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPreferenceTest {
    @Test
    fun namedWifiCanOwnAReceiverList() {
        assertTrue("Home Wi-Fi".isUsableNetworkName())
    }

    @Test
    fun unavailableWifiIdentityDoesNotCreateAReceiverList() {
        assertFalse(null.isUsableNetworkName())
        assertFalse("".isUsableNetworkName())
        assertFalse("<unknown ssid>".isUsableNetworkName())
    }
}
