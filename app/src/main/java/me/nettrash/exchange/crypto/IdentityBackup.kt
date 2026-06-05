/*
 * IdentityBackup.kt
 * Exchange (Android)
 *
 * Passphrase-encrypted, copy-paste-friendly backup of the user's
 * identity + saved recipient list. Cross-platform with iOS: a blob
 * produced on either side decrypts on the other under the same
 * passphrase.
 *
 * Wire format:
 *
 *   EXCBKP1: base64( salt[16] || nonce[12] || ciphertext_with_tag )
 *
 * Where the plaintext (before AEAD) is a JSON-encoded `Payload`:
 *
 *   {
 *     "version": 1,
 *     "identity": {
 *       "encryptionPrivateKey": "<base64 32 bytes>",
 *       "signingPrivateKey":    "<base64 32 bytes>"
 *     },
 *     "recipients": [
 *       {
 *         "displayName": "...",
 *         "encryptionPublicKey": "<base64 32 bytes>",
 *         "signingPublicKey":    "<base64 32 bytes>",
 *         "notes": "...",
 *         "createdAt": <Double seconds since 2001-01-01 UTC>
 *       },
 *       ...
 *     ]
 *   }
 *
 * Date format note: Swift's `JSONEncoder` default for `Date` is
 * `timeIntervalSinceReferenceDate`, i.e. seconds since 2001-01-01
 * 00:00:00 UTC as a Double. We replicate that so iOS-produced JSON
 * round-trips on Android and vice versa. To convert to/from Unix
 * epoch, add/subtract 978_307_200 seconds (the offset between the two
 * epochs).
 *
 * Crypto choices (matching iOS):
 *   - KDF: PBKDF2-HMAC-SHA256, 600,000 iterations, 32-byte output.
 *   - Cipher: ChaCha20-Poly1305.
 */

package me.nettrash.exchange.crypto

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IdentityBackup {
    const val PREFIX = "EXCBKP1:"

    /**
     * PBKDF2-HMAC-SHA256 iterations. Tuned to keep derivation under ~1.5 s
     * on a 2023-class phone while making moderately-strong passphrases
     * impractical to brute-force. Locked at 600k so existing exports stay
     * decryptable; bump in a future EXCBKP2 if hardware moves the goalposts.
     */
    const val PBKDF2_ITERATIONS = 600_000

    /** Below this length the export sheet refuses to encrypt. Imports of older blobs with shorter passphrases still work. */
    const val MIN_PASSPHRASE_LENGTH = 12

    /** Seconds between Unix epoch (1970-01-01 UTC) and Apple reference date (2001-01-01 UTC). */
    private const val APPLE_REFERENCE_OFFSET_SECONDS: Long = 978_307_200L

    sealed class Error(message: String) : Exception(message) {
        object Malformed : Error("malformed backup blob")
        data class UnsupportedVersion(val version: Int) :
            Error("unsupported backup version $version")
        object WrongPassphraseOrTampered : Error("wrong passphrase or backup tampered")
    }

    /** Lightweight in-memory shape of a Recipient row, decoupled from Room. */
    data class RecipientSnapshot(
        val displayName: String,
        val encryptionPublicKey: ByteArray,
        val signingPublicKey: ByteArray,
        val notes: String,
        /** Epoch milliseconds since 1970-01-01 UTC. */
        val createdAtUnixMs: Long,
        /** Manual sort position (0 for backups written before this field). */
        val orderIndex: Int = 0,
        /** Last label/position edit time, epoch ms (falls back to createdAt). */
        val updatedAtUnixMs: Long = createdAtUnixMs,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RecipientSnapshot) return false
            return displayName == other.displayName &&
                encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
                signingPublicKey.contentEquals(other.signingPublicKey) &&
                notes == other.notes &&
                createdAtUnixMs == other.createdAtUnixMs &&
                orderIndex == other.orderIndex &&
                updatedAtUnixMs == other.updatedAtUnixMs
        }

        override fun hashCode(): Int {
            var result = displayName.hashCode()
            result = 31 * result + encryptionPublicKey.contentHashCode()
            result = 31 * result + signingPublicKey.contentHashCode()
            result = 31 * result + notes.hashCode()
            result = 31 * result + createdAtUnixMs.hashCode()
            result = 31 * result + orderIndex
            result = 31 * result + updatedAtUnixMs.hashCode()
            return result
        }
    }

    data class DecodedBackup(
        val identity: Identity,
        val recipients: List<RecipientSnapshot>,
    )

    /**
     * Encrypt the supplied identity + recipient list under [passphrase].
     * Returns a single-line `EXCBKP1:<base64>` string.
     */
    fun encode(
        identity: Identity,
        recipients: List<RecipientSnapshot>,
        passphrase: String,
        random: SecureRandom = SecureRandom(),
    ): String {
        val plaintext = encodePayloadJson(identity, recipients).toByteArray(Charsets.UTF_8)

        val salt = ByteArray(16).also { random.nextBytes(it) }
        val key = pbkdf2HmacSha256(
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            iterations = PBKDF2_ITERATIONS,
            keyLength = 32,
        )

        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val ciphertextWithTag = CryptoEnvelope.chachaSeal(key, nonce, plaintext)

        val payload = ByteArray(16 + 12 + ciphertextWithTag.size)
        System.arraycopy(salt, 0, payload, 0, 16)
        System.arraycopy(nonce, 0, payload, 16, 12)
        System.arraycopy(ciphertextWithTag, 0, payload, 28, ciphertextWithTag.size)
        return PREFIX + payload.toBase64Standard()
    }

    /**
     * Reverse of [encode]. Throws [Error.Malformed], [Error.WrongPassphraseOrTampered],
     * or [Error.UnsupportedVersion].
     */
    fun decode(blob: String, passphrase: String): DecodedBackup {
        val trimmed = blob.trim()
        if (!trimmed.startsWith(PREFIX)) throw Error.Malformed
        val body = trimmed.substring(PREFIX.length)
        val bytes = decodeBase64Standard(body) ?: throw Error.Malformed
        // 16 (salt) + 12 (nonce) + 16 (tag) = 44 bytes minimum.
        if (bytes.size < 44) throw Error.Malformed

        val salt = bytes.copyOfRange(0, 16)
        val nonce = bytes.copyOfRange(16, 28)
        val ciphertextWithTag = bytes.copyOfRange(28, bytes.size)

        val key = pbkdf2HmacSha256(
            password = passphrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            iterations = PBKDF2_ITERATIONS,
            keyLength = 32,
        )

        val plaintext = try {
            CryptoEnvelope.chachaOpen(key, nonce, ciphertextWithTag)
        } catch (_: Exception) {
            // AEAD tag rejected — on a structurally sound blob this means
            // the passphrase is wrong (or the blob was tampered with;
            // same recovery flow either way).
            throw Error.WrongPassphraseOrTampered
        }

        return try {
            decodePayloadJson(String(plaintext, Charsets.UTF_8))
        } catch (e: Error) {
            throw e
        } catch (_: Exception) {
            throw Error.Malformed
        }
    }

    // ---- JSON shape ------------------------------------------------------

    private fun encodePayloadJson(
        identity: Identity,
        recipients: List<RecipientSnapshot>,
    ): String {
        val identityObj = JSONObject().apply {
            put("encryptionPrivateKey", identity.encryptionPrivateKey.toBase64Standard())
            put("signingPrivateKey", identity.signingPrivateKey.toBase64Standard())
        }
        val recipientsArr = JSONArray().apply {
            for (r in recipients) {
                put(JSONObject().apply {
                    put("displayName", r.displayName)
                    put("encryptionPublicKey", r.encryptionPublicKey.toBase64Standard())
                    put("signingPublicKey", r.signingPublicKey.toBase64Standard())
                    put("notes", r.notes)
                    put("createdAt", unixMsToAppleReferenceSeconds(r.createdAtUnixMs))
                    // Optional v1.2 fields. Kept in the same Apple-reference
                    // seconds form as createdAt so iOS round-trips them.
                    // Older clients simply ignore the extra keys.
                    put("orderIndex", r.orderIndex)
                    put("updatedAt", unixMsToAppleReferenceSeconds(r.updatedAtUnixMs))
                })
            }
        }
        return JSONObject().apply {
            put("version", 1)
            put("identity", identityObj)
            put("recipients", recipientsArr)
        }.toString()
    }

    private fun decodePayloadJson(json: String): DecodedBackup {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw Error.Malformed
        }
        val version = root.optInt("version", -1)
        if (version != 1) {
            if (version <= 0) throw Error.Malformed
            throw Error.UnsupportedVersion(version)
        }
        val identityObj = root.optJSONObject("identity") ?: throw Error.Malformed
        val identity = Identity(
            encryptionPrivateKey = decodeBase64FieldOrThrow(identityObj, "encryptionPrivateKey", 32),
            signingPrivateKey = decodeBase64FieldOrThrow(identityObj, "signingPrivateKey", 32),
        )

        val recipientsArr = root.optJSONArray("recipients") ?: JSONArray()
        val out = ArrayList<RecipientSnapshot>(recipientsArr.length())
        for (i in 0 until recipientsArr.length()) {
            val o = recipientsArr.optJSONObject(i) ?: throw Error.Malformed
            val createdAtSeconds = o.optDouble("createdAt", Double.NaN)
            if (createdAtSeconds.isNaN()) throw Error.Malformed
            val createdAtUnixMs = appleReferenceSecondsToUnixMs(createdAtSeconds)
            // Optional v1.2 fields; older backups lack them, so default
            // orderIndex to 0 and updatedAt to createdAt.
            val orderIndex = o.optInt("orderIndex", 0)
            val updatedAtSeconds = o.optDouble("updatedAt", Double.NaN)
            val updatedAtUnixMs =
                if (updatedAtSeconds.isNaN()) createdAtUnixMs
                else appleReferenceSecondsToUnixMs(updatedAtSeconds)
            out.add(
                RecipientSnapshot(
                    displayName = o.optString("displayName", ""),
                    encryptionPublicKey = decodeBase64FieldOrThrow(o, "encryptionPublicKey", 32),
                    signingPublicKey = decodeBase64FieldOrThrow(o, "signingPublicKey", 32),
                    notes = o.optString("notes", ""),
                    createdAtUnixMs = createdAtUnixMs,
                    orderIndex = orderIndex,
                    updatedAtUnixMs = updatedAtUnixMs,
                )
            )
        }
        return DecodedBackup(identity = identity, recipients = out)
    }

    private fun decodeBase64FieldOrThrow(o: JSONObject, key: String, expectedSize: Int): ByteArray {
        val s = o.optString(key, "")
        if (s.isEmpty()) throw Error.Malformed
        val bytes = decodeBase64Standard(s) ?: throw Error.Malformed
        if (bytes.size != expectedSize) throw Error.Malformed
        return bytes
    }

    // ---- Apple reference-date offset ------------------------------------

    /** Convert Unix-epoch milliseconds to seconds since 2001-01-01 UTC (Apple reference date). */
    internal fun unixMsToAppleReferenceSeconds(unixMs: Long): Double =
        (unixMs / 1000.0) - APPLE_REFERENCE_OFFSET_SECONDS

    /** Convert seconds since 2001-01-01 UTC (Apple reference date) to Unix-epoch milliseconds. */
    internal fun appleReferenceSecondsToUnixMs(appleRefSeconds: Double): Long =
        ((appleRefSeconds + APPLE_REFERENCE_OFFSET_SECONDS) * 1000.0).toLong()

    // ---- PBKDF2-HMAC-SHA256 (hand-rolled for byte exactness) -------------

    /**
     * Standard PBKDF2 derivation on top of HMAC-SHA256. We implement it
     * by hand rather than reach for `SecretKeyFactory("PBKDF2WithHmacSHA256")`
     * so we're guaranteed byte-identical to the iOS implementation
     * (which also does it by hand on top of CryptoKit's HMAC).
     */
    internal fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
    ): ByteArray {
        require(iterations > 0)
        require(keyLength > 0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val out = ByteArray(keyLength)
        var pos = 0
        var blockIndex = 1
        while (pos < keyLength) {
            // U_1 = HMAC(password, salt || INT(blockIndex))
            mac.reset()
            mac.update(salt)
            mac.update(intToBigEndianBytes(blockIndex))
            var u = mac.doFinal()
            val block = u.copyOf()
            for (j in 1 until iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (k in block.indices) {
                    block[k] = (block[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            val toCopy = minOf(hLen, keyLength - pos)
            System.arraycopy(block, 0, out, pos, toCopy)
            pos += toCopy
            blockIndex++
        }
        return out
    }

    private fun intToBigEndianBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
