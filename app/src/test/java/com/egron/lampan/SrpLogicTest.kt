package com.egron.lampan

import com.egron.lampan.raop.CryptoUtils
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.math.BigInteger
import java.security.SecureRandom

class SrpLogicTest {

    @Test
    fun testSrpFlow() {
        val username = "testUser"
        val pin = "1234"
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        
        // Client Init
        val client = CryptoUtils.SrpClient(username, pin)
        val A = client.getClientPublicKey()
        assertNotNull(A)
        assertTrue(A.isNotEmpty())
        
        // Mock Server (Simplified: just generate a random B and verify client can compute session)
        // In a real test we would use a server verifier to generate B and s.
        // But here we just verify no crash and determinism.
        
        // Generate a dummy B (Server Public Key)
        val B_BigInt = BigInteger("12345678901234567890") // Just a dummy positive integer
        val B = B_BigInt.toByteArray().stripLeadingZero()
        
        // Client Compute Session
        val K = client.computeSession(salt, B)
        assertNotNull(K)
        assertTrue(K.isNotEmpty())
        
        // Client Compute Proof (M1)
        val M1 = client.computeM1(salt, B)
        assertNotNull(M1)
        assertTrue(M1.isNotEmpty())
        
        // Verify M1 length (SHA-512 digest size = 64 bytes)
        assertTrue(M1.size == 64)
        
        println("SRP Logic Test Passed. Generated M1: ${M1.joinToString("") { "%02x".format(it) }}")
    }
    
    private fun ByteArray.stripLeadingZero(): ByteArray {
        if (this.size > 1 && this[0] == 0.toByte()) {
            return this.copyOfRange(1, this.size)
        }
        return this
    }
}
