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
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

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

// ---- Storage Access Framework helpers (run off the main thread) ----------

/** Read all bytes from a content Uri, or null if it can't be opened. */
fun Context.readAllBytes(uri: Uri): ByteArray? =
    contentResolver.openInputStream(uri)?.use { it.readBytes() }

/** Write bytes to a content Uri. Returns true on success. */
fun Context.writeAllBytes(uri: Uri, bytes: ByteArray): Boolean =
    contentResolver.openOutputStream(uri)?.use { it.write(bytes); true } ?: false

/** Best-effort display name for a content Uri, falling back to [fallback]. */
fun Context.displayName(uri: Uri, fallback: String = "file"): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { return it }
        }
    }
    return fallback
}

// ---- Share a produced file back out (FileProvider) ------------------------

/**
 * Stage [bytes] under [filename] in cache/shared/ and return a content Uri
 * a receiving app can read via our FileProvider. The directory is the only
 * one exposed in file_provider_paths.xml.
 */
fun Context.writeToShareCache(filename: String, bytes: ByteArray): Uri {
    val dir = File(cacheDir, "shared").apply { mkdirs() }
    val safeName = filename.substringAfterLast('/').ifBlank { "file" }
    val target = File(dir, safeName)
    target.writeBytes(bytes)
    return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
}

/**
 * Fire the system share sheet for a file [uri] (from [writeToShareCache]),
 * granting the receiver a transient read permission. Mirrors [shareText].
 */
fun Context.shareFile(uri: Uri, mime: String = "application/octet-stream", chooserTitle: String = "Share via") {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(chooser)
}
