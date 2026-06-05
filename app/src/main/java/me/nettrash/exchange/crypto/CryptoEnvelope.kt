/*
 * CryptoEnvelope.kt
 * Exchange (Android)
 *
 * Hybrid public-key encryption with sender authentication. Format v2.
 *
 * Byte-compatible with the iOS Swift implementation in
 * `Exchange/Crypto/CryptoEnvelope.swift`. An envelope produced on
 * either platform decrypts on the other.
 *
 * Per-message scheme:
 *   1. Sender generates a fresh ephemeral X25519 keypair.
 *   2. ECDH(ephemeral_priv, recipient_encryption_pub) → shared secret.
 *   3. HKDF-SHA256(shared, salt: ephemeral_pub, info: "exchange-v2-keywrap")
 *      → 32-byte key-wrapping key.
 *   4. Generate a fresh random 32-byte message key K.
 *   5. ChaCha20-Poly1305 wrap K under the wrapping key.
 *   6. ChaCha20-Poly1305 encrypt the plaintext under K.
 *   7. Pack the bytes (see binary layout below).
 *   8. Ed25519-sign all the packed bytes with the sender's signing
 *      private key. Append the 64-byte signature.
 *   9. Base64 the final blob and prefix with "EXC2:".
 *
 * Binary layout (v2, all fixed sizes):
 *     offset  size  field
 *     0       1     version byte (0x02)
 *     1       8     recipient encryption-key fingerprint
 *     9       32    sender Ed25519 signing public key
 *     41      32    ephemeral X25519 public key
 *     73      12    wrap nonce
 *     85      32    wrapped K (ciphertext)
 *     117     16    wrapped K (Poly1305 tag)
 *     133     12    message nonce
 *     145     N     message ciphertext
 *     145+N   16    message Poly1305 tag
 *     161+N   64    Ed25519 signature over bytes [0 .. 161+N-1]
 */

package me.nettrash.exchange.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

object CryptoEnvelope {

    const val VERSION: Byte = 0x02
    const val PREFIX: String = "EXC2:"
    private val HKDF_INFO: ByteArray = "exchange-v2-keywrap".toByteArray(Charsets.UTF_8)

    // Fixed sizes (bytes)
    private const val FINGERPRINT_SIZE = 8
    private const val PUBLIC_KEY_SIZE = 32
    private const val SIGNING_PUBLIC_KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val MESSAGE_KEY_SIZE = 32
    private const val TAG_SIZE = 16
    private const val SIGNATURE_SIZE = 64

    /** Total fixed overhead in the binary blob, before base64 / prefix. */
    const val FIXED_OVERHEAD = 1 +
        FINGERPRINT_SIZE +              // 8
        SIGNING_PUBLIC_KEY_SIZE +       // 32
        PUBLIC_KEY_SIZE +               // 32
        NONCE_SIZE +                    // 12
        MESSAGE_KEY_SIZE +              // 32 (wrapped K ciphertext)
        TAG_SIZE +                      // 16
        NONCE_SIZE +                    // 12
        TAG_SIZE +                      // 16
        SIGNATURE_SIZE                  // 64
    // = 225

    sealed class Error(message: String) : Exception(message) {
        /** The string is not a well-formed Exchange envelope. */
        object MalformedEnvelope : Error("malformed envelope")
        /** We understand the framing but the version byte is one we don't speak. */
        data class UnsupportedVersion(val version: Byte) :
            Error("unsupported envelope version 0x${"%02x".format(version)}")
        /** The envelope is addressed to a different identity than the one we opened with. */
        object FingerprintMismatch : Error("recipient fingerprint mismatch")
        /** The Ed25519 signature didn't verify under the embedded sender key. */
        object SignatureVerificationFailed : Error("signature verification failed")
        /** AEAD failed: ciphertext or tag was modified, or key derivation went wrong. */
        object DecryptionFailed : Error("decryption failed")
    }

    /** Result of opening an envelope. */
    data class Opened(
        /** Decrypted message body. */
        val plaintext: ByteArray,
        /** Recipient fingerprint the envelope was addressed to. Matches the opener. */
        val recipientFingerprint: ByteArray,
        /**
         * Raw 32-byte Ed25519 public key of the sender (as the envelope
         * claimed). The signature has been verified against this key.
         * Whether the key belongs to a known contact is a UI decision.
         */
        val senderSigningPublicKey: ByteArray,
        /** Ephemeral X25519 public key, useful as a per-message session ID. */
        val ephemeralPublicKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Opened) return false
            return plaintext.contentEquals(other.plaintext) &&
                recipientFingerprint.contentEquals(other.recipientFingerprint) &&
                senderSigningPublicKey.contentEquals(other.senderSigningPublicKey) &&
                ephemeralPublicKey.contentEquals(other.ephemeralPublicKey)
        }

        override fun hashCode(): Int {
            var result = plaintext.contentHashCode()
            result = 31 * result + recipientFingerprint.contentHashCode()
            result = 31 * result + senderSigningPublicKey.contentHashCode()
            result = 31 * result + ephemeralPublicKey.contentHashCode()
            return result
        }
    }

    // MARK: - Seal

    /**
     * Encrypt [plaintext] for delivery to [recipientEncryptionPublicKey],
     * signed by [sender]. Returns an ASCII-armored envelope string.
     */
    fun seal(
        plaintext: ByteArray,
        recipientEncryptionPublicKey: ByteArray,
        sender: Identity,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(recipientEncryptionPublicKey.size == 32) {
            "recipient encryption public key must be 32 bytes"
        }
        val recipientFingerprint = Identity.encryptionFingerprint(recipientEncryptionPublicKey)
        val senderSigningPub = sender.signingPublicKey

        // (1) ephemeral keypair — generate raw 32 bytes, let BC clamp on use.
        val ephemeralPrivBytes = ByteArray(32).also { random.nextBytes(it) }
        val ephemeralPriv = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(
            ephemeralPrivBytes, 0
        )
        val ephemeralPub = ephemeralPriv.generatePublicKey().encoded

        // (2) ECDH and (3) HKDF → wrapping key
        val recipientPub = X25519PublicKeyParameters(recipientEncryptionPublicKey, 0)
        val wrappingKey = deriveWrappingKey(
            agreementPriv = ephemeralPriv,
            agreementPub = recipientPub,
            ephemeralPub = ephemeralPub,
        )

        // (4) fresh per-message symmetric key
        val messageKey = ByteArray(32).also { random.nextBytes(it) }

        // (5) wrap K
        val wrapNonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val wrappedKey = chachaSeal(wrappingKey, wrapNonce, messageKey) // 32 + 16 = 48 bytes

        // (6) encrypt body
        val messageNonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val body = chachaSeal(messageKey, messageNonce, plaintext) // N + 16 bytes

        // (7) pack everything except the trailing signature
        val signedRegion = ByteArray(FIXED_OVERHEAD - SIGNATURE_SIZE + plaintext.size)
        var off = 0
        signedRegion[off++] = VERSION
        System.arraycopy(recipientFingerprint, 0, signedRegion, off, FINGERPRINT_SIZE)
        off += FINGERPRINT_SIZE
        System.arraycopy(senderSigningPub, 0, signedRegion, off, SIGNING_PUBLIC_KEY_SIZE)
        off += SIGNING_PUBLIC_KEY_SIZE
        System.arraycopy(ephemeralPub, 0, signedRegion, off, PUBLIC_KEY_SIZE)
        off += PUBLIC_KEY_SIZE
        System.arraycopy(wrapNonce, 0, signedRegion, off, NONCE_SIZE)
        off += NONCE_SIZE
        System.arraycopy(wrappedKey, 0, signedRegion, off, wrappedKey.size) // 32 + 16
        off += wrappedKey.size
        System.arraycopy(messageNonce, 0, signedRegion, off, NONCE_SIZE)
        off += NONCE_SIZE
        System.arraycopy(body, 0, signedRegion, off, body.size) // N + 16
        off += body.size
        check(off == signedRegion.size)

        // (8) sign and append
        val signature = ed25519Sign(sender.signingPrivateParams.let {
            // get a clean Ed25519PrivateKeyParameters from sender
            it
        }, signedRegion)

        val full = ByteArray(signedRegion.size + SIGNATURE_SIZE)
        System.arraycopy(signedRegion, 0, full, 0, signedRegion.size)
        System.arraycopy(signature, 0, full, signedRegion.size, SIGNATURE_SIZE)

        // (9) ASCII armor
        return PREFIX + full.toBase64Standard()
    }

    // MARK: - Open

    /**
     * Decrypt an envelope string with the supplied identity. Throws if
     * the envelope is malformed, addressed elsewhere, signature-invalid,
     * or AEAD-tampered.
     */
    fun open(envelope: String, identity: Identity): Opened {
        val trimmed = envelope.trim()
        if (!trimmed.startsWith(PREFIX)) throw Error.MalformedEnvelope
        val base64 = trimmed.substring(PREFIX.length)
        val blob = decodeBase64Standard(base64) ?: throw Error.MalformedEnvelope
        if (blob.size < FIXED_OVERHEAD) throw Error.MalformedEnvelope

        var off = 0

        // version
        val versionByte = blob[off]
        off += 1
        if (versionByte != VERSION) throw Error.UnsupportedVersion(versionByte)

        // recipient fingerprint
        val fingerprint = blob.copyOfRange(off, off + FINGERPRINT_SIZE)
        off += FINGERPRINT_SIZE

        // sender signing public key
        val senderSigningPub = blob.copyOfRange(off, off + SIGNING_PUBLIC_KEY_SIZE)
        off += SIGNING_PUBLIC_KEY_SIZE

        // ephemeral X25519 public key
        val ephemeralPub = blob.copyOfRange(off, off + PUBLIC_KEY_SIZE)
        off += PUBLIC_KEY_SIZE

        // wrap nonce + wrapped K + tag
        val wrapNonce = blob.copyOfRange(off, off + NONCE_SIZE)
        off += NONCE_SIZE
        val wrappedKCiphertext = blob.copyOfRange(off, off + MESSAGE_KEY_SIZE)
        off += MESSAGE_KEY_SIZE
        val wrappedKTag = blob.copyOfRange(off, off + TAG_SIZE)
        off += TAG_SIZE

        // body nonce + body ciphertext + body tag + trailing signature
        val messageNonce = blob.copyOfRange(off, off + NONCE_SIZE)
        off += NONCE_SIZE

        val signatureStart = blob.size - SIGNATURE_SIZE
        val bodyCiphertextEnd = signatureStart - TAG_SIZE
        if (bodyCiphertextEnd < off) throw Error.MalformedEnvelope
        val messageCiphertext = blob.copyOfRange(off, bodyCiphertextEnd)
        val messageTag = blob.copyOfRange(bodyCiphertextEnd, signatureStart)
        val signatureBytes = blob.copyOfRange(signatureStart, blob.size)

        // 1. Cheap "not for me" reject.
        if (!fingerprint.contentEquals(identity.encryptionFingerprint)) {
            throw Error.FingerprintMismatch
        }

        // 2. Verify the signature next, before any ECDH or AEAD work.
        val senderVerifyKey = try {
            org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(senderSigningPub, 0)
        } catch (_: Exception) {
            throw Error.MalformedEnvelope
        }
        val signedRegion = blob.copyOfRange(0, blob.size - SIGNATURE_SIZE)
        if (!ed25519Verify(senderVerifyKey, signedRegion, signatureBytes)) {
            throw Error.SignatureVerificationFailed
        }

        // 3. ECDH + HKDF → wrapping key
        val ephemeralPubParams = try {
            X25519PublicKeyParameters(ephemeralPub, 0)
        } catch (_: Exception) {
            throw Error.MalformedEnvelope
        }
        val wrappingKey = try {
            deriveWrappingKey(
                agreementPriv = identity.encryptionPrivateParams,
                agreementPub = ephemeralPubParams,
                ephemeralPub = ephemeralPub,
            )
        } catch (_: Exception) {
            throw Error.DecryptionFailed
        }

        // 4. Unwrap K
        val messageKeyBytes = try {
            chachaOpen(wrappingKey, wrapNonce, wrappedKCiphertext + wrappedKTag)
        } catch (_: Exception) {
            throw Error.DecryptionFailed
        }
        if (messageKeyBytes.size != MESSAGE_KEY_SIZE) throw Error.DecryptionFailed

        // 5. Decrypt body
        val plaintext = try {
            chachaOpen(messageKeyBytes, messageNonce, messageCiphertext + messageTag)
        } catch (_: Exception) {
            throw Error.DecryptionFailed
        }

        return Opened(
            plaintext = plaintext,
            recipientFingerprint = fingerprint,
            senderSigningPublicKey = senderSigningPub,
            ephemeralPublicKey = ephemeralPub,
        )
    }

    // MARK: - Helpers

    /**
     * X25519 ECDH + HKDF-SHA256 → 32-byte symmetric key.
     * [ephemeralPub] is the salt; the info string is constant.
     */
    private fun deriveWrappingKey(
        agreementPriv: org.bouncycastle.crypto.params.X25519PrivateKeyParameters,
        agreementPub: X25519PublicKeyParameters,
        ephemeralPub: ByteArray,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(agreementPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(agreementPub, shared, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, ephemeralPub, HKDF_INFO))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    /** ChaCha20-Poly1305 encryption. Returns ciphertext || tag (size = plaintext.size + 16). */
    internal fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 nonce must be 12 bytes" }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        if (len != out.size) {
            // BC always sizes exactly; defensive trim if not.
            return out.copyOfRange(0, len)
        }
        return out
    }

    /**
     * ChaCha20-Poly1305 decryption. [ciphertextWithTag] is ciphertext || tag(16).
     * Throws if tag verification fails (caller wraps as `DecryptionFailed`).
     */
    internal fun chachaOpen(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20-Poly1305 nonce must be 12 bytes" }
        require(ciphertextWithTag.size >= 16) { "ciphertext is shorter than the tag" }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, ParametersWithIV(KeyParameter(key), nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        var len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
        len += cipher.doFinal(out, len)
        if (len != out.size) return out.copyOfRange(0, len)
        return out
    }

    /** Ed25519 sign — returns 64 bytes. */
    internal fun ed25519Sign(
        priv: org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters,
        message: ByteArray,
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Ed25519 verify. */
    internal fun ed25519Verify(
        pub: org.bouncycastle.crypto.params.Ed25519PublicKeyParameters,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, pub)
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
