/*
 * FilePayload.kt
 * Exchange (Android)
 *
 * Framing for encrypting a *file* (rather than a typed message) inside a
 * CryptoEnvelope. The original filename travels with the bytes so the
 * recipient can restore it on decrypt.
 *
 * This lives *inside* the encrypted plaintext — the EXC2 envelope format
 * is unchanged, so it stays byte-compatible across Android / iOS / macOS.
 * The decrypt side detects a file by the leading magic; anything without
 * it is treated as a plain message as before.
 *
 * Layout of the framed plaintext:
 *     offset  size  field
 *     0       8     magic "EXCFILE1" (ASCII)
 *     8       2     filename length, big-endian UInt16
 *     10      L     filename (UTF-8)
 *     10+L    …     file content bytes
 *
 * Cross-platform counterpart: Exchange/Crypto/FilePayload.swift on iOS.
 */

package me.nettrash.exchange.crypto

import java.io.ByteArrayOutputStream

object FilePayload {
    /** 8-byte ASCII marker distinguishing a file payload from a message. */
    private val MAGIC = "EXCFILE1".toByteArray(Charsets.US_ASCII)

    data class DecodedFile(val filename: String, val content: ByteArray)

    /** Frame a file's name + bytes into the plaintext for CryptoEnvelope.seal. */
    fun encode(filename: String, content: ByteArray): ByteArray {
        var name = filename.toByteArray(Charsets.UTF_8)
        if (name.size > 0xFFFF) name = name.copyOf(0xFFFF)

        val out = ByteArrayOutputStream(MAGIC.size + 2 + name.size + content.size)
        out.write(MAGIC)
        out.write((name.size ushr 8) and 0xFF)
        out.write(name.size and 0xFF)
        out.write(name)
        out.write(content)
        return out.toByteArray()
    }

    /**
     * Reverse of [encode]. Returns null when [data] is not a file payload
     * (no magic) or is malformed — callers then treat it as a message.
     */
    fun decode(data: ByteArray): DecodedFile? {
        if (data.size < MAGIC.size + 2) return null
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return null

        val nameLen = ((data[MAGIC.size].toInt() and 0xFF) shl 8) or
            (data[MAGIC.size + 1].toInt() and 0xFF)
        val nameStart = MAGIC.size + 2
        val nameEnd = nameStart + nameLen
        if (data.size < nameEnd) return null

        val filename = String(data, nameStart, nameLen, Charsets.UTF_8)
        val content = data.copyOfRange(nameEnd, data.size)
        return DecodedFile(filename, content)
    }
}
