/*
 * EnvelopeUrlTest.kt
 * Exchange (Android)
 *
 * Round-trip + envelope-extraction tests for the URL form used by
 * deep links and cross-app sharing.
 */

package me.nettrash.exchange.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeUrlTest {

    @Test
    fun urlBuiltAndExtractedRoundTrips() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val envelope = CryptoEnvelope.seal("hi".toByteArray(), bob.encryptionPublicKey, alice)
        val url = EnvelopeUrl.urlFor(envelope)
        assertTrue(url != null && url.startsWith("https://exchange.nettrash.me/msg?"))
        val recovered = EnvelopeUrl.extract(url!!)
        assertEquals(envelope, recovered)
        // And full round-trip: extracted envelope decrypts cleanly.
        val opened = CryptoEnvelope.open(recovered!!, bob)
        assertEquals("hi", String(opened.plaintext))
    }

    @Test
    fun urlIgnoresEnvelopeWithoutPrefix() {
        assertNull(EnvelopeUrl.urlFor("not an envelope"))
    }

    @Test
    fun extractIgnoresWrongHost() {
        assertNull(EnvelopeUrl.extract("https://example.com/msg?p=abcd"))
    }

    @Test
    fun extractIgnoresMissingPathOrParam() {
        assertNull(EnvelopeUrl.extract("https://exchange.nettrash.me/other?p=abcd"))
        assertNull(EnvelopeUrl.extract("https://exchange.nettrash.me/msg"))
    }

    @Test
    fun envelopeIfPresentFindsRawEnvelopeInsideMessage() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val envelope = CryptoEnvelope.seal("hi".toByteArray(), bob.encryptionPublicKey, alice)
        val message = "Look at this: $envelope -- decrypt it"
        val found = EnvelopeUrl.envelopeIfPresent(message)
        assertEquals(envelope, found)
    }

    @Test
    fun envelopeIfPresentFindsUrlInsideMessage() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val envelope = CryptoEnvelope.seal("hi".toByteArray(), bob.encryptionPublicKey, alice)
        val url = EnvelopeUrl.urlFor(envelope)!!
        val message = "From Bob: $url great talking earlier"
        val found = EnvelopeUrl.envelopeIfPresent(message)
        assertEquals(envelope, found)
    }
}
