/*
 * AppPreferences.kt
 * Exchange (Android)
 *
 * Small, non-secret UI state that should outlive a process restart but
 * isn't worth a Room table or DataStore. Currently just the last
 * recipient picked in Compose, so the picker pre-selects them next time
 * instead of resetting to the top of the list.
 *
 * Kept separate from `IdentityStore` (which is Keystore-wrapped secrets)
 * on purpose — this is plain, low-stakes preference data.
 *
 * iOS counterpart: the App-Group `UserDefaults` helpers in
 * `Exchange/AppConstants.swift` (`saveLastRecipientID` / `loadLastRecipientID`).
 */

package me.nettrash.exchange.data

import android.content.Context
import android.content.SharedPreferences

/**
 * How long Exchange may sit in the background before the app-lock
 * re-engages on return. `ONLY_ON_LAUNCH` (millis == null) never re-locks
 * while the process is alive — it only locks on a cold launch. Mirrors
 * iOS `AppLockTimeout`.
 */
enum class AppLockTimeout(val millis: Long?, val label: String) {
    IMMEDIATELY(0L, "Immediately"),
    ONE_MINUTE(60_000L, "After 1 minute"),
    FIVE_MINUTES(5 * 60_000L, "After 5 minutes"),
    FIFTEEN_MINUTES(15 * 60_000L, "After 15 minutes"),
    ONLY_ON_LAUNCH(null, "Only on launch"),
}

/**
 * How a sealed message is handed off from the Compose result screen:
 * the rich `exchange.nettrash.me/msg` link or the raw `EXC2:` envelope.
 */
enum class MessageShareFormat { LINK, ENVELOPE }

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The `Recipient.id` of the recipient the user most recently composed
     * to, or null if there isn't one yet. Callers must still confirm the
     * id matches a recipient that currently exists before selecting it.
     */
    var lastRecipientId: String?
        get() = prefs.getString(KEY_LAST_RECIPIENT, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_RECIPIENT) else putString(KEY_LAST_RECIPIENT, value)
            }.apply()
        }

    // ---- App lock (biometric / device-credential gate) -----------------
    // A UI gate only; the identity keys stay protected by the Keystore.

    /** Master switch. Off by default. */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply() }

    /** Re-lock grace period. Defaults to immediate. */
    var appLockTimeout: AppLockTimeout
        get() = prefs.getString(KEY_LOCK_TIMEOUT, null)
            ?.let { runCatching { AppLockTimeout.valueOf(it) }.getOrNull() }
            ?: AppLockTimeout.IMMEDIATELY
        set(value) { prefs.edit().putString(KEY_LOCK_TIMEOUT, value.name).apply() }

    /**
     * Whether the lock also gates incoming message links / shared
     * envelopes (not just opening the main app). Defaults to true.
     */
    var appLockCoversIncoming: Boolean
        get() = prefs.getBoolean(KEY_LOCK_COVERS_INCOMING, true)
        set(value) { prefs.edit().putBoolean(KEY_LOCK_COVERS_INCOMING, value).apply() }

    /** Preferred Compose share representation (link vs raw EXC2). Link by default. */
    var composeShareFormat: MessageShareFormat
        get() = prefs.getString(KEY_SHARE_FORMAT, null)
            ?.let { runCatching { MessageShareFormat.valueOf(it) }.getOrNull() }
            ?: MessageShareFormat.LINK
        set(value) { prefs.edit().putString(KEY_SHARE_FORMAT, value.name).apply() }

    companion object {
        private const val PREFS_NAME = "exchange.ui.v1"
        private const val KEY_LAST_RECIPIENT = "last_recipient_id"
        private const val KEY_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_LOCK_TIMEOUT = "app_lock_timeout"
        private const val KEY_LOCK_COVERS_INCOMING = "app_lock_covers_incoming"
        private const val KEY_SHARE_FORMAT = "compose_share_format"
    }
}
