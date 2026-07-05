/*
 * ShareClassifier.kt
 * Exchange (Android)
 *
 * When the user shares an arbitrary file into Exchange (ACTION_SEND with a
 * stream, or ACTION_VIEW of a content/file Uri), we have to decide up-front
 * what to do with the raw bytes *without* an identity or any UI:
 *
 *   - DECRYPT          — the bytes are one of ours: a raw `EXC2:` envelope,
 *                        a `.exc2` file (whose bytes ARE the `EXC2:` string),
 *                        or the `https://exchange.nettrash.me/msg?…` URL form.
 *   - IDENTITY_MATERIAL — the bytes are a secret we must NOT re-encrypt for a
 *                        recipient: an `EXCBKP1:` identity backup or an
 *                        `EXCQR1:` identity transfer. These are handled by the
 *                        Import flow, not by encrypt/decrypt.
 *   - ENCRYPT          — anything else: a foreign file the user wants sealed
 *                        to a chosen recipient.
 *
 * Detection keys off the leading ASCII prefixes only. `EXC2:` contains ':',
 * which is not a base64 character, so it can never collide with the base64
 * bodies of `EXCBKP1:` / `EXCQR1:` — `startsWith("EXC2:")` is unambiguous.
 * Only a bounded prefix of the file is decoded, so a large binary never gets
 * fully materialised as a String just to be classified.
 *
 * Pure JVM (no Android imports) so it unit-tests without Robolectric.
 * Cross-platform counterpart: Exchange/Crypto/ShareClassifier.swift on iOS.
 */

package me.nettrash.exchange.crypto

object ShareClassifier {

    enum class Kind { DECRYPT, ENCRYPT, IDENTITY_MATERIAL }

    /**
     * How many leading bytes to decode for the sniff. Our text artifacts all
     * declare themselves in the first handful of bytes, so this is plenty; a
     * `.exc2` file's `EXC2:` sits at offset 0.
     */
    private const val SNIFF_LIMIT = 8192

    fun classify(bytes: ByteArray): Kind {
        if (bytes.isEmpty()) return Kind.ENCRYPT
        // decodeToString replaces malformed UTF-8 with U+FFFD rather than
        // throwing, so a binary file simply produces text that matches none
        // of our prefixes and falls through to ENCRYPT.
        val head = bytes.decodeToString(0, minOf(bytes.size, SNIFF_LIMIT)).trimStart()
        if (head.startsWith(IdentityBackup.PREFIX) || head.startsWith(IdentityTransferQR.PREFIX)) {
            return Kind.IDENTITY_MATERIAL
        }
        if (EnvelopeUrl.envelopeIfPresent(head) != null) return Kind.DECRYPT
        return Kind.ENCRYPT
    }
}
