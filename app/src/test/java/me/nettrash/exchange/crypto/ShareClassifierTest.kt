/*
 * ShareClassifierTest.kt
 * Exchange (Android)
 *
 * The share-in decision table: given raw shared bytes, do we decrypt,
 * encrypt-for-a-recipient, or bounce to Import (identity material)?
 */

package me.nettrash.exchange.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareClassifierTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun rawEnvelopeIsDecrypt() {
        // A real .exc2 file's bytes ARE the "EXC2:<base64>" string.
        val alice = Identity.generate()
        val bob = Identity.generate()
        val payload = FilePayload.encode("hello.txt", bytes("hi"))
        val envelope = CryptoEnvelope.seal(payload, bob.encryptionPublicKey, alice)
        assertEquals(ShareClassifier.Kind.DECRYPT, ShareClassifier.classify(bytes(envelope)))
    }

    @Test
    fun envelopeUrlFormIsDecrypt() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val envelope = CryptoEnvelope.seal(bytes("hi"), bob.encryptionPublicKey, alice)
        val url = EnvelopeUrl.urlFor(envelope)!!
        assertEquals(ShareClassifier.Kind.DECRYPT, ShareClassifier.classify(bytes(url)))
    }

    @Test
    fun leadingWhitespaceStillDecrypts() {
        assertEquals(ShareClassifier.Kind.DECRYPT, ShareClassifier.classify(bytes("\n  EXC2:AAAA")))
    }

    @Test
    fun identityBackupIsIdentityMaterial() {
        assertEquals(
            ShareClassifier.Kind.IDENTITY_MATERIAL,
            ShareClassifier.classify(bytes(IdentityBackup.PREFIX + "AAAABBBBCCCC")),
        )
    }

    @Test
    fun identityTransferIsIdentityMaterial() {
        assertEquals(
            ShareClassifier.Kind.IDENTITY_MATERIAL,
            ShareClassifier.classify(bytes(IdentityTransferQR.PREFIX + "AAAABBBBCCCC")),
        )
    }

    @Test
    fun foreignTextIsEncrypt() {
        assertEquals(ShareClassifier.Kind.ENCRYPT, ShareClassifier.classify(bytes("just a note to a friend")))
    }

    @Test
    fun foreignBinaryIsEncrypt() {
        val binary = ByteArray(256) { it.toByte() } // includes invalid UTF-8 sequences
        assertEquals(ShareClassifier.Kind.ENCRYPT, ShareClassifier.classify(binary))
    }

    @Test
    fun emptyIsEncrypt() {
        assertEquals(ShareClassifier.Kind.ENCRYPT, ShareClassifier.classify(ByteArray(0)))
    }
}
