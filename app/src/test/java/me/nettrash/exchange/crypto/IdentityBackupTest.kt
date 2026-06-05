/*
 * IdentityBackupTest.kt
 * Exchange (Android)
 *
 * Round-trip + tamper coverage for the EXCBKP1 passphrase-encrypted
 * backup format.
 *
 * The byte format is locked at EXCBKP1, so any future change here
 * should bump the wire version (EXCBKP2) and keep these tests passing
 * against the v1 reader.
 *
 * PBKDF2 iterations are 600k by default — too slow for unit tests on
 * an emulator — so the tests reach in via the internal helpers and run
 * the whole flow with a small iteration count that's still meaningful
 * for round-trip correctness. Production code path is exercised
 * separately in `IdentityBackupRoundTripTest.realParameters`.
 */

package me.nettrash.exchange.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class IdentityBackupTest {

    @Test
    fun pbkdf2MatchesRfc6070VectorTruncated() {
        // RFC 6070 has no SHA-256 test vectors, but RFC 7914 does. The
        // vector below — password "password", salt "salt", iterations
        // 1, dkLen 32 — is the canonical small-iteration sanity check
        // for PBKDF2-HMAC-SHA256.
        val expectedHex = "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        val out = IdentityBackup.pbkdf2HmacSha256(
            password = "password".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 1,
            keyLength = 32,
        )
        assertEquals(expectedHex, out.toHex())
    }

    @Test
    fun pbkdf2WithTwoBlockOutputAgreesWithSingleBlock() {
        // dkLen larger than one HMAC-SHA256 output (32 bytes) exercises
        // the multi-block code path. Rather than hard-code another
        // vector, assert that the first 32 bytes of a 64-byte derivation
        // equal the same parameters' 32-byte derivation. That property
        // is required by RFC 2898 and is the cheapest catch for an
        // off-by-one in the block counter.
        val single = IdentityBackup.pbkdf2HmacSha256(
            password = "password".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 2,
            keyLength = 32,
        )
        val multi = IdentityBackup.pbkdf2HmacSha256(
            password = "password".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 2,
            keyLength = 64,
        )
        assertEquals(single.toHex(), multi.copyOfRange(0, 32).toHex())
    }

    @Test
    fun appleReferenceDateOffsetIsExact() {
        // 2001-01-01 00:00:00 UTC in Unix ms is exactly 978_307_200_000.
        // Apple seconds at that instant are exactly 0.0.
        assertEquals(0.0, IdentityBackup.unixMsToAppleReferenceSeconds(978_307_200_000L), 1e-9)
        assertEquals(978_307_200_000L, IdentityBackup.appleReferenceSecondsToUnixMs(0.0))
    }

    @Test
    fun encodeDecodeRoundTripsIdentityAndRecipients() {
        // Use the real PBKDF2 iteration count — slow, but this is the
        // contract the tests exist to defend. A few seconds on a dev
        // machine is fine.
        val alice = Identity.generate()
        val bob = Identity.generate()
        val recipients = listOf(
            IdentityBackup.RecipientSnapshot(
                displayName = "Bob",
                encryptionPublicKey = bob.encryptionPublicKey,
                signingPublicKey = bob.signingPublicKey,
                notes = "Met at the conference",
                createdAtUnixMs = 1_700_000_000_000L,
            )
        )

        val blob = IdentityBackup.encode(
            identity = alice,
            recipients = recipients,
            passphrase = "correct horse battery staple",
        )

        assertTrue(blob.startsWith("EXCBKP1:"))

        val decoded = IdentityBackup.decode(blob, "correct horse battery staple")
        assertArrayEquals(alice.encryptionPrivateKey, decoded.identity.encryptionPrivateKey)
        assertArrayEquals(alice.signingPrivateKey, decoded.identity.signingPrivateKey)
        assertEquals(1, decoded.recipients.size)
        val r = decoded.recipients[0]
        assertEquals("Bob", r.displayName)
        assertEquals("Met at the conference", r.notes)
        assertArrayEquals(bob.encryptionPublicKey, r.encryptionPublicKey)
        assertArrayEquals(bob.signingPublicKey, r.signingPublicKey)
        // Date round-trips through the Apple reference offset.
        assertEquals(1_700_000_000_000L, r.createdAtUnixMs)
    }

    @Test
    fun wrongPassphraseRejected() {
        val identity = Identity.generate()
        val blob = IdentityBackup.encode(identity, emptyList(), "correct passphrase 12345")
        try {
            IdentityBackup.decode(blob, "wrong passphrase 12345!")
            fail("Expected WrongPassphraseOrTampered")
        } catch (_: IdentityBackup.Error.WrongPassphraseOrTampered) {
            // expected
        }
    }

    @Test
    fun malformedBlobRejected() {
        try {
            IdentityBackup.decode("not a backup", "anything")
            fail("Expected Malformed")
        } catch (_: IdentityBackup.Error.Malformed) {
            // expected
        }
        try {
            IdentityBackup.decode("EXCBKP1:not_base64!!!", "anything")
            fail("Expected Malformed")
        } catch (_: IdentityBackup.Error.Malformed) {
            // expected
        }
        // Length too short to be plausible (less than salt+nonce+tag = 44 bytes).
        val tinyBytes = ByteArray(10)
        val tinyBlob = "EXCBKP1:" + Base64.getEncoder().encodeToString(tinyBytes)
        try {
            IdentityBackup.decode(tinyBlob, "anything")
            fail("Expected Malformed")
        } catch (_: IdentityBackup.Error.Malformed) {
            // expected
        }
    }

    @Test
    fun tamperedCiphertextRejected() {
        val identity = Identity.generate()
        val blob = IdentityBackup.encode(identity, emptyList(), "correct passphrase 12345")
        val prefix = "EXCBKP1:"
        val bytes = Base64.getDecoder().decode(blob.substring(prefix.length))
        // Flip a bit inside the ciphertext (past the salt+nonce header at offset 28).
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = prefix + Base64.getEncoder().encodeToString(bytes)
        try {
            IdentityBackup.decode(tampered, "correct passphrase 12345")
            fail("Expected WrongPassphraseOrTampered")
        } catch (_: IdentityBackup.Error.WrongPassphraseOrTampered) {
            // expected
        }
    }
}
