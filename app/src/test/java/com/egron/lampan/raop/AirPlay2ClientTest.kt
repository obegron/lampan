package com.egron.lampan.raop

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlay2ClientTest {
    @Test
    fun displayedEightDigitCodesUseHomeKitCanonicalForm() {
        assertEquals("518-40-276", normalizePairingPassword("5184 0276"))
        assertEquals("518-40-276", normalizePairingPassword("51840276"))
        assertEquals("518-40-276", normalizePairingPassword("518-40-276"))
    }

    @Test
    fun customPasswordsArePreservedExactly() {
        assertEquals("custom pass", normalizePairingPassword("custom pass"))
        assertEquals("1234", normalizePairingPassword("1234"))
    }
}
