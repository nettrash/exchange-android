/*
 * DecryptFileScreen.kt
 * Exchange (Android)
 *
 * Decrypt-a-file flow. Pick an .exc2 file (Storage Access Framework),
 * Exchange opens it against your identity, and — if it carries a file
 * (FilePayload magic) — restores the original name + bytes for you to
 * save. If it's actually a text message, it shows the text instead.
 */

package me.nettrash.exchange.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.exchange.crypto.ShareClassifier
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.readAllBytes
import me.nettrash.exchange.ui.components.shareFile
import me.nettrash.exchange.ui.components.writeAllBytes
import me.nettrash.exchange.ui.components.writeToShareCache

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecryptFileScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity = (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ExchangeViewModel.FileDecryptResult?>(null) }
    var saved by remember { mutableStateOf(false) }

    // Opened from a share/VIEW intent: an encrypted file was handed in. Read it
    // from the ViewModel flow (not a one-shot snapshot) so it survives rotation,
    // and auto-decrypt instead of showing the picker. Cleared only when the user
    // leaves (Close / "Decrypt another file").
    val pendingShared by viewModel.pendingSharedFile.collectAsState()
    val incoming = pendingShared?.takeIf { it.kind == ShareClassifier.Kind.DECRYPT }
    LaunchedEffect(Unit) {
        // Runs once per composition; after a rotation the recreated composition
        // re-runs it and (since `result` was lost) re-decrypts, restoring it.
        val f = incoming
        if (f != null && result == null) {
            working = true
            errorMessage = null
            val r = viewModel.decryptFile(f.bytes.toString(Charsets.UTF_8), identity)
            if (r is ExchangeViewModel.FileDecryptResult.Failed) {
                errorMessage = r.message
                result = null
            } else {
                result = r
            }
            working = false
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = result as? ExchangeViewModel.FileDecryptResult.File
        if (uri != null && file != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { context.writeAllBytes(uri, file.content) }
                if (ok) { saved = true; errorMessage = null } else errorMessage = "Couldn't save the file."
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            working = true
            errorMessage = null
            saved = false
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { context.readAllBytes(uri) }
                val text = bytes?.toString(Charsets.UTF_8)
                if (text == null) {
                    errorMessage = "That doesn't look like an Exchange file."
                    working = false
                    return@launch
                }
                val r = viewModel.decryptFile(text, identity)
                if (r is ExchangeViewModel.FileDecryptResult.Failed) {
                    errorMessage = r.message
                    result = null
                } else {
                    result = r
                }
                working = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Decrypt file") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val r = result) {
                is ExchangeViewModel.FileDecryptResult.File -> {
                    Text("Decrypted file", style = MaterialTheme.typography.labelMedium)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("File: ${r.filename}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Size: ${Formatter.formatShortFileSize(context, r.content.size.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SenderFooter(r.senderDisplayName, r.senderSigningKeyFingerprint)
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (saved) {
                        Text("Saved.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { saveLauncher.launch(r.filename.substringAfterLast('/')) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer8()
                        Text("Save file…")
                    }
                    OutlinedButton(
                        onClick = {
                            val uri = context.writeToShareCache(r.filename, r.content)
                            context.shareFile(uri)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer8()
                        Text("Share file…")
                    }
                    OutlinedButton(
                        onClick = { result = null; saved = false; viewModel.clearPendingSharedFile() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Decrypt another file")
                    }
                }

                is ExchangeViewModel.FileDecryptResult.Message -> {
                    Text("Decrypted message", style = MaterialTheme.typography.labelMedium)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            if (r.text != null) {
                                SelectionContainer { Text(r.text) }
                            } else {
                                Text(
                                    "(Binary content, ${r.binaryByteCount ?: 0} bytes — not a file or text.)",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    SenderFooter(r.senderDisplayName, r.senderSigningKeyFingerprint)
                    OutlinedButton(
                        onClick = { result = null; viewModel.clearPendingSharedFile() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Decrypt another file")
                    }
                }

                else -> {
                    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { pickLauncher.launch(arrayOf("*/*")) },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                        }
                        Spacer8()
                        Text("Choose .exc2 file…")
                    }
                    Text(
                        "Pick an encrypted .exc2 file someone sent you. Exchange decrypts it against your identity and restores the original file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SenderFooter(senderDisplayName: String?, senderFingerprint: ByteArray?) {
    val text = when {
        senderDisplayName != null -> "Signature verified — from $senderDisplayName."
        senderFingerprint != null ->
            "Signature verified, but the sender's key (fingerprint ${senderFingerprint.toGroupedHex()}) doesn't match anyone in your recipients."
        else -> "Verified as addressed to your identity."
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Spacer8() {
    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
}
