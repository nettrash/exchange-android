/*
 * Base64Ext.kt
 * Exchange
 *
 * Thin wrapper around java.util.Base64. JVM-only (available on Android
 * since API 26, well below our minSdk of 36) so the crypto package
 * stays free of Android imports and can be unit-tested on the host
 * JVM without Robolectric.
 */

package me.nettrash.exchange.crypto

import java.util.Base64

internal fun ByteArray.toBase64Standard(): String =
    Base64.getEncoder().encodeToString(this)

internal fun decodeBase64Standard(s: String): ByteArray? = try {
    // The standard decoder is strict about non-base64 characters and
    // rejects them — matches the iOS behaviour of returning nil for
    // malformed input.
    Base64.getDecoder().decode(s)
} catch (_: IllegalArgumentException) {
    null
}
