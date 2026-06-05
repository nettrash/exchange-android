/*
 * AppConstants.kt
 * Exchange (Android)
 *
 * Single source of truth for app-wide identifiers and URLs. Mirrors
 * iOS `Exchange/AppConstants.swift` so cross-platform deep links and
 * documentation references stay aligned.
 */

package me.nettrash.exchange

object AppConstants {
    /**
     * Public-facing privacy policy. Hosted under nettrash.me/play/exchange
     * to match the convention used by the sibling Scan and Geo apps.
     */
    const val PRIVACY_POLICY_URL = "https://nettrash.me/play/exchange/privacy.html"

    /** Public-facing support page. */
    const val SUPPORT_URL = "https://nettrash.me/play/exchange/support.html"
}
