/*
 * HexExt.kt
 * Exchange
 *
 * Hex encoding / decoding helpers used by fingerprints and tests.
 *
 * Mirrors iOS Data+Hex.swift so fingerprints render identically across
 * the two ports.
 */

package me.nettrash.exchange.crypto

/** Lowercase hex string of every byte, no separators. */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    val digits = "0123456789abcdef"
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(digits[v ushr 4])
        sb.append(digits[v and 0x0f])
    }
    return sb.toString()
}

/**
 * Hex grouped into 4-character chunks separated by dashes,
 * e.g. "a1b2-c3d4-e5f6-0708". Convenient for user-visible fingerprints.
 */
fun ByteArray.toGroupedHex(): String {
    val hex = toHex()
    val groups = mutableListOf<String>()
    var i = 0
    while (i < hex.length) {
        val end = (i + 4).coerceAtMost(hex.length)
        groups.add(hex.substring(i, end))
        i = end
    }
    return groups.joinToString("-")
}

/** Parse a hex string. Spaces, dashes, and colons are ignored. Returns null on malformed input. */
fun hexToBytes(hex: String): ByteArray? {
    val cleaned = hex.replace(" ", "").replace("-", "").replace(":", "")
    if (cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    var i = 0
    while (i < cleaned.length) {
        val hi = Character.digit(cleaned[i], 16)
        val lo = Character.digit(cleaned[i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}
