/*
 * CryptoEnvelopeTest.kt
 * Exchange (Android)
 *
 * Port of `ExchangeTests/CryptoEnvelopeTests.swift`. Same scenarios,
 * same expectations — the envelope binary format is supposed to be
 * byte-identical across the two platforms, and this suite is the
 * contract.
 *
 * Round-trip / freshness / tamper / forgery / malformed-input coverage.
 */

package me.nettrash.exchange.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class CryptoEnvelopeTest {

    private fun makeIdentity(): Identity = Identity.generate()

    // ---- Round trip -----------------------------------------------------

    @Test
    fun roundTripsShortMessage() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val plaintext = "hello bob".toByteArray()

        val envelope = CryptoEnvelope.seal(
            plaintext = plaintext,
            recipientEncryptionPublicKey = bob.encryptionPublicKey,
            sender = alice,
        )
        val opened = CryptoEnvelope.open(envelope, bob)

        assertArrayEquals(plaintext, opened.plaintext)
        assertArrayEquals(bob.encryptionFingerprint, opened.recipientFingerprint)
        assertArrayEquals(alice.signingPublicKey, opened.senderSigningPublicKey)
        assertEquals(32, opened.ephemeralPublicKey.size)
    }

    @Test
    fun roundTripsLongMessage() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val plaintext = ByteArray(10_000).also { java.security.SecureRandom().nextBytes(it) }

        val envelope = CryptoEnvelope.seal(plaintext, bob.encryptionPublicKey, alice)
        val opened = CryptoEnvelope.open(envelope, bob)

        assertArrayEquals(plaintext, opened.plaintext)
        assertArrayEquals(alice.signingPublicKey, opened.senderSigningPublicKey)
    }

    @Test
    fun roundTripsEmptyMessage() {
        val alice = makeIdentity()
        val bob = makeIdentity()

        val envelope = CryptoEnvelope.seal(ByteArray(0), bob.encryptionPublicKey, alice)
        val opened = CryptoEnvelope.open(envelope, bob)

        assertEquals(0, opened.plaintext.size)
    }

    @Test
    fun envelopeUsesAsciiArmorPrefix() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("hi".toByteArray(), bob.encryptionPublicKey, alice)

        assertTrue(envelope.startsWith("EXC2:"))
        val body = envelope.substring("EXC2:".length)
        assertNotNull(Base64.getDecoder().decode(body))
    }

    // ---- Freshness ------------------------------------------------------

    @Test
    fun freshEphemeralProducesDifferentEnvelopes() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val plaintext = "repeatable".toByteArray()

        val env1 = CryptoEnvelope.seal(plaintext, bob.encryptionPublicKey, alice)
        val env2 = CryptoEnvelope.seal(plaintext, bob.encryptionPublicKey, alice)

        assertNotEquals(env1, env2)
        assertArrayEquals(plaintext, CryptoEnvelope.open(env1, bob).plaintext)
        assertArrayEquals(plaintext, CryptoEnvelope.open(env2, bob).plaintext)
    }

    // ---- Wrong recipient -----------------------------------------------

    @Test
    fun wrongRecipientRejectedByFingerprint() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val mallory = makeIdentity()

        val envelope = CryptoEnvelope.seal("for bob".toByteArray(), bob.encryptionPublicKey, alice)
        try {
            CryptoEnvelope.open(envelope, mallory)
            fail("Expected FingerprintMismatch")
        } catch (e: CryptoEnvelope.Error.FingerprintMismatch) {
            // expected
        }
    }

    // ---- Signature verification -----------------------------------------

    @Test
    fun flippedMessageTagFailsSignatureVerification() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("important".toByteArray(), bob.encryptionPublicKey, alice)
        // Byte at offset 200 is inside the body / its tag — covered by the signature.
        val tampered = EnvelopeMutator.flipBit(envelope, atOffset = 200)
        try {
            CryptoEnvelope.open(tampered, bob)
            fail("Expected SignatureVerificationFailed")
        } catch (e: CryptoEnvelope.Error.SignatureVerificationFailed) {
            // expected
        }
    }

    @Test
    fun flippedSignatureByteFailsSignatureVerification() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("important".toByteArray(), bob.encryptionPublicKey, alice)
        val tampered = EnvelopeMutator.flipLastBit(envelope)
        try {
            CryptoEnvelope.open(tampered, bob)
            fail("Expected SignatureVerificationFailed")
        } catch (e: CryptoEnvelope.Error.SignatureVerificationFailed) {
            // expected
        }
    }

    @Test
    fun flippedFingerprintByteRejectedBeforeSignatureCheck() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("x".toByteArray(), bob.encryptionPublicKey, alice)
        // Fingerprint bytes are at offsets 1..9. We check fingerprint
        // before signature so this throws FingerprintMismatch, not
        // SignatureVerificationFailed.
        val tampered = EnvelopeMutator.flipBit(envelope, atOffset = 1)
        try {
            CryptoEnvelope.open(tampered, bob)
            fail("Expected FingerprintMismatch")
        } catch (e: CryptoEnvelope.Error.FingerprintMismatch) {
            // expected
        }
    }

    @Test
    fun reSigningWithDifferentKeyChangesSenderInOpened() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val mallory = makeIdentity()

        val original = CryptoEnvelope.seal("hello bob".toByteArray(), bob.encryptionPublicKey, alice)

        val prefix = "EXC2:"
        val body = original.substring(prefix.length)
        val blob = Base64.getDecoder().decode(body)

        // Replace sender Ed25519 public key (offsets 9..41) with Mallory's.
        System.arraycopy(mallory.signingPublicKey, 0, blob, 9, 32)

        // Re-sign over bytes [0..blob.length - 64] with Mallory's signing key.
        val signedRegion = blob.copyOfRange(0, blob.size - 64)
        val newSig = CryptoEnvelope.ed25519Sign(mallory.signingPrivateParams, signedRegion)
        System.arraycopy(newSig, 0, blob, blob.size - 64, 64)

        val forged = prefix + Base64.getEncoder().encodeToString(blob)
        val opened = CryptoEnvelope.open(forged, bob)
        // Decryption succeeded — Mallory used a fresh ephemeral, ECDH'd against
        // Bob's encryption pub, encrypted under that key. Fine. BUT the
        // senderSigningPublicKey reflects Mallory, not Alice:
        assertArrayEquals(mallory.signingPublicKey, opened.senderSigningPublicKey)
        assertFalse(opened.senderSigningPublicKey.contentEquals(alice.signingPublicKey))
    }

    // ---- Versioning -----------------------------------------------------

    @Test
    fun unsupportedVersionByteRejected() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("x".toByteArray(), bob.encryptionPublicKey, alice)
        val tampered = EnvelopeMutator.replaceByte(envelope, atOffset = 0, value = 0x99.toByte())
        try {
            CryptoEnvelope.open(tampered, bob)
            fail("Expected UnsupportedVersion")
        } catch (e: CryptoEnvelope.Error.UnsupportedVersion) {
            assertEquals(0x99.toByte(), e.version)
        }
    }

    // ---- Malformed input -----------------------------------------------

    @Test
    fun nonEnvelopeStringRejected() {
        val bob = makeIdentity()
        try {
            CryptoEnvelope.open("not an envelope", bob)
            fail("Expected MalformedEnvelope")
        } catch (e: CryptoEnvelope.Error.MalformedEnvelope) {
            // expected
        }
    }

    @Test
    fun nonBase64BodyRejected() {
        val bob = makeIdentity()
        try {
            CryptoEnvelope.open("EXC2:not_base64!!!!", bob)
            fail("Expected MalformedEnvelope")
        } catch (e: CryptoEnvelope.Error.MalformedEnvelope) {
            // expected
        }
    }

    @Test
    fun tooShortEnvelopeRejected() {
        val bob = makeIdentity()
        val shortBlob = Base64.getEncoder().encodeToString(byteArrayOf(CryptoEnvelope.VERSION))
        try {
            CryptoEnvelope.open("EXC2:$shortBlob", bob)
            fail("Expected MalformedEnvelope")
        } catch (e: CryptoEnvelope.Error.MalformedEnvelope) {
            // expected
        }
    }

    // ---- Identity helpers ----------------------------------------------

    @Test
    fun publicBundleEncodingRoundTrips() {
        val alice = makeIdentity()
        val encoded = Identity.encode(alice.publicBundle)
        val decoded = Identity.decode(encoded)
        assertArrayEquals(alice.encryptionPublicKey, decoded.encryptionPublicKey)
        assertArrayEquals(alice.signingPublicKey, decoded.signingPublicKey)
    }

    @Test
    fun malformedPublicBundleImportRejected() {
        try {
            Identity.decode("%%%not base64%%%")
            fail("Expected MalformedPublicKey")
        } catch (_: Identity.IdentityError.MalformedPublicKey) {
            // expected
        }
        try {
            Identity.decode(Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02)))
            fail("Expected MalformedPublicKey")
        } catch (_: Identity.IdentityError.MalformedPublicKey) {
            // expected
        }
    }

    @Test
    fun combinedFingerprintIsStableEightBytes() {
        val identity = makeIdentity()
        val fp1 = Identity.fingerprint(identity.publicBundle)
        val fp2 = Identity.fingerprint(identity.publicBundle)
        assertArrayEquals(fp1, fp2)
        assertEquals(8, fp1.size)
    }

    @Test
    fun differentBundlesHaveDifferentFingerprints() {
        val a = makeIdentity().publicBundle
        val b = makeIdentity().publicBundle
        assertFalse(Identity.fingerprint(a).contentEquals(Identity.fingerprint(b)))
    }

    @Test
    fun encryptionFingerprintMatchesEnvelopeAddressing() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val envelope = CryptoEnvelope.seal("hi".toByteArray(), bob.encryptionPublicKey, alice)
        val opened = CryptoEnvelope.open(envelope, bob)
        assertArrayEquals(bob.encryptionFingerprint, opened.recipientFingerprint)
    }
}

/** Helpers for surgically corrupting the binary envelope inside an EXC2:<base64> string. */
private object EnvelopeMutator {

    fun decodedBytes(envelope: String): ByteArray {
        if (!envelope.startsWith("EXC2:")) {
            throw CryptoEnvelope.Error.MalformedEnvelope
        }
        val body = envelope.substring("EXC2:".length)
        return Base64.getDecoder().decode(body)
    }

    fun reencode(bytes: ByteArray): String =
        "EXC2:" + Base64.getEncoder().encodeToString(bytes)

    fun flipBit(envelope: String, atOffset: Int): String {
        val bytes = decodedBytes(envelope)
        bytes[atOffset] = (bytes[atOffset].toInt() xor 0x01).toByte()
        return reencode(bytes)
    }

    fun flipLastBit(envelope: String): String {
        val bytes = decodedBytes(envelope)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        return reencode(bytes)
    }

    fun replaceByte(envelope: String, atOffset: Int, value: Byte): String {
        val bytes = decodedBytes(envelope)
        bytes[atOffset] = value
        return reencode(bytes)
    }
}
