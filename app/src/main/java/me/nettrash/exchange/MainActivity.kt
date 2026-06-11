/*
 * MainActivity.kt
 * Exchange (Android)
 *
 * Single Activity that hosts the Compose UI. Also handles:
 *
 *   - Every incoming intent that carries an Exchange envelope:
 *       - Share Sheet (ACTION_SEND text/plain)
 *       - Deep links (ACTION_VIEW https://exchange.nettrash.me/msg?…)
 *       - Custom scheme (ACTION_VIEW exc2://…)
 *     All funnelled through `viewModel.acceptIncomingText`.
 *
 *   - The optional biometric / device-credential app-lock: this Activity
 *     owns the BiometricPrompt (which needs a FragmentActivity) and the
 *     lifecycle hooks that re-lock the app after it has been in the
 *     background longer than the configured grace period.
 */

package me.nettrash.exchange

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import me.nettrash.exchange.crypto.EnvelopeUrl
import me.nettrash.exchange.ui.ExchangeApp
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.theme.ExchangeTheme

class MainActivity : FragmentActivity() {

    private val viewModel: ExchangeViewModel by viewModels()

    private lateinit var biometricPrompt: BiometricPrompt
    private val promptInfo: BiometricPrompt.PromptInfo by lazy {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Exchange")
            .setSubtitle("Authenticate to read and send your encrypted messages")
            // Biometric OR device PIN/pattern/password — the credential
            // fallback means a user with no enrolled biometrics (or a
            // failed scan) can still get in rather than being locked out.
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            // Don't require an extra "Confirm" tap after a passive
            // biometric (e.g. face) succeeds — unlock as soon as it passes.
            .setConfirmationRequired(false)
            .build()
    }

    /** Guards against overlapping prompts and suppresses the re-lock that
     *  the device-credential screen's stop/start would otherwise trigger. */
    private var authInProgress = false

    private val authCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            authInProgress = false
            viewModel.setLocked(false)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Cancel / lockout / user dismissed — stay locked; the lock
            // screen keeps an Unlock button for a manual retry.
            authInProgress = false
        }

        override fun onAuthenticationFailed() {
            // A single non-matching attempt; the prompt stays up for retry,
            // so keep authInProgress true.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        biometricPrompt = BiometricPrompt(this, mainExecutor, authCallback)
        consumeIncomingIntent(intent)
        // Fail open: if the lock is on but the device has no way to
        // authenticate, don't strand the user behind a lock they can't pass.
        if (viewModel.appLockEnabled && !canAuthenticate()) {
            viewModel.setLocked(false)
        }
        setContent {
            ExchangeTheme {
                ExchangeApp(
                    viewModel = viewModel,
                    onRequestUnlock = { authenticate() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIncomingIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Don't treat the device-credential confirm screen (which stops us)
        // as a real backgrounding.
        if (!authInProgress) viewModel.noteBackgrounded(System.currentTimeMillis())
    }

    override fun onStart() {
        super.onStart()
        if (!authInProgress) viewModel.maybeRelock(System.currentTimeMillis())
    }

    // ---- App lock -------------------------------------------------------

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Show the biometric/credential prompt. Called from the lock screen's
     *  auto-prompt and its Unlock button. */
    private fun authenticate() {
        if (authInProgress) return
        if (!canAuthenticate()) {
            viewModel.setLocked(false)
            return
        }
        authInProgress = true
        biometricPrompt.authenticate(promptInfo)
    }

    // ---- Incoming intents ----------------------------------------------

    /**
     * Pull an EXC2 envelope (or a `exchange.nettrash.me/msg` URL, or a
     * `exc2://` URI) out of the supplied intent, normalise it, and hand
     * it to the ViewModel. No-op for intents that don't carry one.
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
     * rebuilding the canonical "EXC2:<base64>" string.
     */
    private fun extractFromExc2Scheme(uri: Uri): String? {
        if (!"exc2".equals(uri.scheme, ignoreCase = true)) return null
        val body = uri.lastPathSegment ?: uri.schemeSpecificPart?.trimStart('/')
        if (body.isNullOrBlank()) return null
        return "EXC2:" + body.trim()
    }
}
