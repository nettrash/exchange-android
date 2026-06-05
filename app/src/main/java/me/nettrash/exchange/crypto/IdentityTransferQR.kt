/*
 * IdentityTransferQR.kt
 * Exchange (Android)
 *
 * One-shot, in-person identity transfer via QR code.
 *
 * Wire format (matches iOS):
 *
 *   EXCQR1: base64( key[32] || nonce[12] || ciphertext_with_tag )
 *
 * Where:
 *   - key is a fresh 32-byte symmetric key generated for this single
 *     transfer. Embedded in the QR alongside the ciphertext, so the QR
 *     is itself the secret.
 *   - nonce is 12 random bytes used for ChaCha20-Poly1305.
 *   - ciphertext_with_tag is the AEAD output, plaintext = the 64-byte
 *     identity blob (32 X25519 scalar + 32 Ed25519 seed).
 *
 * Trust model: the QR contains everything an attacker would need to
 * impersonate the identity. Protection comes entirely from keeping the
 * QR visible only to the receiving device — typically the same room,
 * on a screen the user controls.
 */

package me.nettrash.exchange.crypto

import java.security.SecureRandom

object IdentityTransferQR {
    const val PREFIX = "EXCQR1:"

    sealed class Error(message: String) : Exception(message) {
        object Malformed : Error("malformed transfer payload")
        object Tampered : Error("transfer payload failed AEAD verification")
    }

    data class DecodedTransfer(
        val encryption: ByteArray, // 32 raw X25519 scalar
        val signing: ByteArray,    // 32 raw Ed25519 seed
    ) {
        init {
            require(encryption.size == 32)
            require(signing.size == 32)
        }

        /** Reconstruct as a usable Identity. */
        fun toIdentity(): Identity = Identity(
            encryptionPrivateKey = encryption,
            signingPrivateKey = signing,
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedTransfer) return false
            return encryption.contentEquals(other.encryption) &&
                signing.contentEquals(other.signing)
        }

        override fun hashCode(): Int =
            31 * encryption.contentHashCode() + signing.contentHashCode()
    }

    /**
     * Encrypt the supplied identity under a freshly-generated symmetric
     * key, package as the EXCQR1 wire format.
     */
    fun encode(identity: Identity, random: SecureRandom = SecureRandom()): String {
        val plaintext = identity.encryptionPrivateKey + identity.signingPrivateKey
        check(plaintext.size == 64)

        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val ciphertextWithTag = CryptoEnvelope.chachaSeal(key, nonce, plaintext) // 64 + 16 = 80

        val payload = ByteArray(32 + 12 + ciphertextWithTag.size)
        System.arraycopy(key, 0, payload, 0, 32)
        System.arraycopy(nonce, 0, payload, 32, 12)
        System.arraycopy(ciphertextWithTag, 0, payload, 44, ciphertextWithTag.size)
        return PREFIX + payload.toBase64Standard()
    }

    /**
     * Reverse of [encode]. Throws on prefix mismatch, base64 corruption,
     * length mismatch, or AEAD tag failure.
     */
    fun decode(blob: String): DecodedTransfer {
        val trimmed = blob.trim()
        if (!trimmed.startsWith(PREFIX)) throw Error.Malformed
        val body = trimmed.substring(PREFIX.length)
        val bytes = decodeBase64Standard(body) ?: throw Error.Malformed
        // 32 (key) + 12 (nonce) + 64 (plaintext) + 16 (tag) = 124 bytes.
        if (bytes.size != 32 + 12 + 64 + 16) throw Error.Malformed

        val key = bytes.copyOfRange(0, 32)
        val nonce = bytes.copyOfRange(32, 44)
        val ciphertextWithTag = bytes.copyOfRange(44, bytes.size)

        val plaintext = try {
            CryptoEnvelope.chachaOpen(key, nonce, ciphertextWithTag)
        } catch (_: Exception) {
            throw Error.Tampered
        }
        if (plaintext.size != 64) throw Error.Malformed

        return DecodedTransfer(
            encryption = plaintext.copyOfRange(0, 32),
            signing = plaintext.copyOfRange(32, 64),
        )
    }

    /**
     * Compute the identity fingerprint for an in-flight DecodedTransfer
     * so the receiving device can show the fingerprint and let the user
     * confirm before destroying the local identity.
     */
    fun fingerprint(transfer: DecodedTransfer): ByteArray {
        val identity = transfer.toIdentity()
        return Identity.fingerprint(identity.publicBundle)
    }
}
