/*
 * IdentityTransferQRTest.kt
 * Exchange (Android)
 *
 * Round-trip + tamper coverage for the EXCQR1 in-person identity
 * transfer format.
 */

package me.nettrash.exchange.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class IdentityTransferQRTest {

    @Test
    fun roundTripsIdentity() {
        val identity = Identity.generate()
        val encoded = IdentityTransferQR.encode(identity)
        assertTrue(encoded.startsWith("EXCQR1:"))

        val decoded = IdentityTransferQR.decode(encoded)
        assertArrayEquals(identity.encryptionPrivateKey, decoded.encryption)
        assertArrayEquals(identity.signingPrivateKey, decoded.signing)
    }

    @Test
    fun freshKeyPerEncode() {
        val identity = Identity.generate()
        val a = IdentityTransferQR.encode(identity)
        val b = IdentityTransferQR.encode(identity)
        // Encoded payload differs because key + nonce are fresh per call.
        assertEquals(false, a == b)
    }

    @Test
    fun fingerprintMatchesIdentity() {
        val identity = Identity.generate()
        val decoded = IdentityTransferQR.decode(IdentityTransferQR.encode(identity))
        val fingerprint = IdentityTransferQR.fingerprint(decoded)
        assertArrayEquals(identity.fingerprint, fingerprint)
    }

    @Test
    fun malformedRejected() {
        try {
            IdentityTransferQR.decode("not a transfer payload")
            fail("Expected Malformed")
        } catch (_: IdentityTransferQR.Error.Malformed) {
            // expected
        }
        try {
            IdentityTransferQR.decode("EXCQR1:not_base64!!!")
            fail("Expected Malformed")
        } catch (_: IdentityTransferQR.Error.Malformed) {
            // expected
        }
        val wrongLength = "EXCQR1:" + Base64.getEncoder().encodeToString(ByteArray(20))
        try {
            IdentityTransferQR.decode(wrongLength)
            fail("Expected Malformed")
        } catch (_: IdentityTransferQR.Error.Malformed) {
            // expected
        }
    }

    @Test
    fun tamperedCiphertextRejected() {
        val identity = Identity.generate()
        val encoded = IdentityTransferQR.encode(identity)
        val bytes = Base64.getDecoder().decode(encoded.substring("EXCQR1:".length))
        // Flip a byte in the ciphertext (past the 44-byte key+nonce header).
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = "EXCQR1:" + Base64.getEncoder().encodeToString(bytes)
        try {
            IdentityTransferQR.decode(tampered)
            fail("Expected Tampered")
        } catch (_: IdentityTransferQR.Error.Tampered) {
            // expected
        }
    }
}
