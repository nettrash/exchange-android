/*
 * MainActivity.kt
 * Exchange (Android)
 *
 * Single Activity that hosts the Compose UI. Also handles every
 * incoming intent that carries an Exchange envelope:
 *
 *   - Share Sheet (ACTION_SEND text/plain) — e.g. user shares a
 *     pasted message from any messenger to Exchange.
 *   - Deep links (ACTION_VIEW https://exchange.nettrash.me/msg?…)
 *   - Custom scheme (ACTION_VIEW exc2://…)
 *
 * All three paths are funnelled through `viewModel.acceptIncomingText`,
 * which canonicalises the envelope and stashes it for DecryptScreen
 * to consume on first composition.
 */

package me.nettrash.exchange

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.nettrash.exchange.crypto.EnvelopeUrl
import me.nettrash.exchange.ui.ExchangeApp
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.theme.ExchangeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ExchangeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIncomingIntent(intent)
        setContent {
            ExchangeTheme {
                ExchangeApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIncomingIntent(intent)
    }

    /**
     * Pull an EXC2 envelope (or a `exchange.nettrash.me/msg` URL, or a
     * `exc2://` URI) out of the supplied intent, normalise it, and hand
     * it to the ViewModel. No-op for intents that don't carry one
     * (e.g. the normal launcher intent).
     */
    private fun consumeIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val payload: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                EnvelopeUrl.extract(uri.toString())
                    ?: extractFromExc2Scheme(uri)
            }
            else -> null
        }
        viewModel.acceptIncomingText(payload)
    }

    /**
     * `exc2://envelope/<base64>` and `exc2:<base64>` both decode by
     * rebuilding the canonical "EXC2:<base64>" string. Used for the
     * custom-scheme intent filter, which is mostly a convenience for
     * in-app deep links.
     */
    private fun extractFromExc2Scheme(uri: Uri): String? {
        if (!"exc2".equals(uri.scheme, ignoreCase = true)) return null
        // Allow either `exc2:<base64>` (uri.schemeSpecificPart) or
        // `exc2://envelope/<base64>` (uri.lastPathSegment).
        val body = uri.lastPathSegment ?: uri.schemeSpecificPart?.trimStart('/')
        if (body.isNullOrBlank()) return null
        return "EXC2:" + body.trim()
    }
}
