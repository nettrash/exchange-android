/*
 * Identity.kt
 * Exchange (Android)
 *
 * The local user's long-term keys.
 *
 * An identity is a pair of Curve25519 keypairs:
 *
 *   - Encryption (X25519). Used as the recipient's static key in the
 *     hybrid ECDH+AEAD envelope.
 *
 *   - Signing (Ed25519). Used by the sender to sign every envelope so
 *     the recipient can authenticate who sent it.
 *
 * Both private keys live on disk as a single 64-byte blob (32 X25519
 * scalar + 32 Ed25519 seed), wrapped under an Android-Keystore AES key
 * so an attacker with raw disk access can't lift them.
 *
 * The "public bundle" is the pair of public keys two users exchange out
 * of band — by QR or paste. It serializes as base64 of 64 bytes (the
 * two raw representations concatenated, X25519 first).
 *
 * This file is byte-compatible with iOS `Identity.swift`. Both
 * platforms produce the same `Identity.encode(publicBundle:)` string
 * for the same key bytes and the same fingerprints.
 */

package me.nettrash.exchange.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/** Raw-byte holders for the two Curve25519 keypairs that make up an identity. */
data class Identity(
    /** 32 raw bytes of the X25519 private scalar (clamping handled inside ECDH). */
    val encryptionPrivateKey: ByteArray,
    /** 32 raw bytes of the Ed25519 seed. */
    val signingPrivateKey: ByteArray,
) {
    init {
        require(encryptionPrivateKey.size == 32) { "encryption private key must be 32 bytes" }
        require(signingPrivateKey.size == 32) { "signing private key must be 32 bytes" }
    }

    /** Internal BouncyCastle wrapper. */
    internal val encryptionPrivateParams: X25519PrivateKeyParameters
        get() = X25519PrivateKeyParameters(encryptionPrivateKey, 0)

    /** Internal BouncyCastle wrapper. */
    internal val signingPrivateParams: Ed25519PrivateKeyParameters
        get() = Ed25519PrivateKeyParameters(signingPrivateKey, 0)

    /** 32-byte X25519 public key. */
    val encryptionPublicKey: ByteArray
        get() = encryptionPrivateParams.generatePublicKey().encoded

    /** 32-byte Ed25519 public key. */
    val signingPublicKey: ByteArray
        get() = signingPrivateParams.generatePublicKey().encoded

    /** Both public keys, ready to be encoded as a base64 / QR bundle. */
    val publicBundle: PublicBundle
        get() = PublicBundle(
            encryptionPublicKey = encryptionPublicKey,
            signingPublicKey = signingPublicKey,
        )

    /** Combined identity fingerprint — first 8 bytes of SHA-256 over both public keys. */
    val fingerprint: ByteArray
        get() = fingerprint(publicBundle)

    /** Encryption-only fingerprint — what gets stamped into every envelope. */
    val encryptionFingerprint: ByteArray
        get() = encryptionFingerprint(encryptionPublicKey)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return encryptionPrivateKey.contentEquals(other.encryptionPrivateKey) &&
            signingPrivateKey.contentEquals(other.signingPrivateKey)
    }

    override fun hashCode(): Int =
        31 * encryptionPrivateKey.contentHashCode() + signingPrivateKey.contentHashCode()

    /** Pair of public keys exchanged out of band. */
    data class PublicBundle(
        val encryptionPublicKey: ByteArray,
        val signingPublicKey: ByteArray,
    ) {
        init {
            require(encryptionPublicKey.size == 32) { "encryption public key must be 32 bytes" }
            require(signingPublicKey.size == 32) { "signing public key must be 32 bytes" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PublicBundle) return false
            return encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
                signingPublicKey.contentEquals(other.signingPublicKey)
        }

        override fun hashCode(): Int =
            31 * encryptionPublicKey.contentHashCode() + signingPublicKey.contentHashCode()

        /** Internal wrapper. */
        internal val encryptionPublicParams: X25519PublicKeyParameters
            get() = X25519PublicKeyParameters(encryptionPublicKey, 0)

        /** Internal wrapper. */
        internal val signingPublicParams: Ed25519PublicKeyParameters
            get() = Ed25519PublicKeyParameters(signingPublicKey, 0)
    }

    sealed class IdentityError(message: String) : Exception(message) {
        object MalformedPublicKey : IdentityError("public-key bundle must decode to 64 bytes")
        object MalformedPrivateKey : IdentityError("private-key blob must decode to 64 bytes")
    }

    companion object {
        /** Generate a fresh Identity from cryptographically random bytes. */
        fun generate(random: SecureRandom = SecureRandom()): Identity {
            val enc = ByteArray(32).also { random.nextBytes(it) }
            val sig = ByteArray(32).also { random.nextBytes(it) }
            return Identity(enc, sig)
        }

        /** Combined fingerprint of both public keys (first 8 bytes of SHA-256). */
        fun fingerprint(bundle: PublicBundle): ByteArray {
            val combined = bundle.encryptionPublicKey + bundle.signingPublicKey
            val digest = MessageDigest.getInstance("SHA-256").digest(combined)
            return digest.copyOfRange(0, 8)
        }

        /** Encryption-only fingerprint of one public key (first 8 bytes of SHA-256). */
        fun encryptionFingerprint(encryptionPublicKey: ByteArray): ByteArray {
            require(encryptionPublicKey.size == 32) { "encryption public key must be 32 bytes" }
            val digest = MessageDigest.getInstance("SHA-256").digest(encryptionPublicKey)
            return digest.copyOfRange(0, 8)
        }

        /**
         * Encode a public bundle as base64(encryption_pub || signing_pub),
         * 64 raw bytes → ~88 base64 characters. Matches the iOS encoder.
         */
        fun encode(bundle: PublicBundle): String {
            val combined = bundle.encryptionPublicKey + bundle.signingPublicKey
            return combined.toBase64Standard()
        }

        /** Inverse of [encode]. Throws on malformed input. */
        fun decode(base64: String): PublicBundle {
            val trimmed = base64.trim()
            val bytes = decodeBase64Standard(trimmed) ?: throw IdentityError.MalformedPublicKey
            if (bytes.size != 64) throw IdentityError.MalformedPublicKey
            return PublicBundle(
                encryptionPublicKey = bytes.copyOfRange(0, 32),
                signingPublicKey = bytes.copyOfRange(32, 64),
            )
        }
    }
}
