/*
 * EnvelopeUrl.kt
 * Exchange (Android)
 *
 * Bidirectional codec between Exchange's `EXC2:` envelope strings and
 * the URL form used for cross-app sharing and deep links.
 *
 * URL form (also produced by the iOS app):
 *   https://exchange.nettrash.me/msg?v=2&p=<base64url envelope body>
 *
 * The URL host is `exchange.nettrash.me`, also used by iOS Universal
 * Links. On Android this is wired up via the `<intent-filter android:autoVerify="true">`
 * in AndroidManifest.xml — tap such a URL in any messenger and Android
 * will offer Exchange as a handler.
 *
 * No Android imports — built on java.net.URI so the crypto package
 * stays JVM-pure and unit-testable without Robolectric. The Activity-
 * layer code converts `android.net.Uri` to a `String` and calls
 * [extract(String)] directly.
 *
 * Matches `Exchange/Crypto/EnvelopeURL.swift`.
 */

package me.nettrash.exchange.crypto

object EnvelopeUrl {
    const val SCHEME = "https"
    const val HOST = "exchange.nettrash.me"
    const val PATH = "/msg"

    /**
     * Wrap an `EXC2:<base64>` envelope into a cross-platform URL. The
     * envelope body is base64url-encoded (so it survives URL parsing
     * without percent-encoding) as the `p` query parameter.
     *
     * Returns null only for inputs that don't start with `EXC2:`.
     */
    fun urlFor(envelope: String): String? {
        if (!envelope.startsWith(CryptoEnvelope.PREFIX)) return null
        val body = envelope.substring(CryptoEnvelope.PREFIX.length)
        val urlSafe = body
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
        return "$SCHEME://$HOST$PATH?v=2&p=$urlSafe"
    }

    /**
     * Reverse of [urlFor]. Returns the canonical `EXC2:<base64>` envelope,
     * or null for any URL whose host/path/payload doesn't match what we'd
     * produce. Accepts the full URL string.
     *
     * Callers holding an `android.net.Uri` should pass `uri.toString()`.
     */
    fun extract(urlString: String): String? {
        val parsed = parseExchangeUrl(urlString) ?: return null
        val standard = parsed.replace('-', '+').replace('_', '/')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return CryptoEnvelope.PREFIX + padded
    }

    /**
     * Scan arbitrary text for the first thing that looks like an Exchange
     * envelope — raw `EXC2:` form or the URL form — and return it in
     * canonical `EXC2:<base64>` form. Returns null if neither shows up.
     */
    fun envelopeIfPresent(text: String): String? {
        extractRawEnvelope(text)?.let { return it }
        val urlString = firstUrlString(text) ?: return null
        return extract(urlString)
    }

    // ---- Internals ------------------------------------------------------

    /**
     * Parse a string like
     *   https://exchange.nettrash.me/msg?v=2&p=abcdef
     * Return the value of `p` if host/path match, otherwise null.
     */
    private fun parseExchangeUrl(urlString: String): String? {
        // Cheap structural check first to avoid throwing on garbage
        // input. Match the iOS behaviour of requiring path == "/msg"
        // exactly — the next character after the path must be either
        // a query-string '?' or end-of-string. Without that check we'd
        // incorrectly accept e.g. "/msgs?p=…" or "/msg/foo?p=…", which
        // iOS would reject.
        val schemePrefix = "$SCHEME://$HOST$PATH"
        if (!urlString.startsWith(schemePrefix, ignoreCase = true)) return null
        val nextChar = urlString.getOrNull(schemePrefix.length)
        if (nextChar != null && nextChar != '?') return null
        val qIndex = urlString.indexOf('?')
        if (qIndex < 0) return null
        val query = urlString.substring(qIndex + 1)
        // Split on '&'; pick out the first `p=…` pair. Values are raw
        // (base64url, no percent-escapes we care about), so no decoding.
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (key == "p" && value.isNotEmpty()) return value
        }
        return null
    }

    /** Locate a raw `EXC2:` envelope inside arbitrary text. */
    private fun extractRawEnvelope(text: String): String? {
        val prefix = CryptoEnvelope.PREFIX
        val start = text.indexOf(prefix)
        if (start < 0) return null
        val bodyStart = start + prefix.length
        var end = bodyStart
        while (end < text.length && isBase64Char(text[end])) end++
        if (end == bodyStart) return null
        return text.substring(start, end)
    }

    /** Find the first https://exchange.nettrash.me URL inside text. */
    private fun firstUrlString(text: String): String? {
        val needle = "$SCHEME://$HOST"
        val start = text.indexOf(needle)
        if (start < 0) return null
        var end = start + needle.length
        while (end < text.length && !text[end].isWhitespace()) end++
        return text.substring(start, end)
    }

    private fun isBase64Char(c: Char): Boolean = when (c) {
        in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/', '=' -> true
        else -> false
    }
}
