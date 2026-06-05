/*
 * PlatformActions.kt
 * Exchange (Android)
 *
 * Small wrappers for clipboard + share-sheet so the screens read at a
 * higher level of intent than raw Android APIs. Kept in :ui so the
 * crypto module stays free of platform imports.
 */

package me.nettrash.exchange.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

fun Context.copyToClipboard(label: String, value: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    // Android 13+ shows a system-level "Copied" overlay automatically.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareText(text: String, chooserTitle: String = "Share via") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}
