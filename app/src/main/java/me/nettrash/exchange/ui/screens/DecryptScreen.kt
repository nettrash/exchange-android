/*
 * DecryptScreen.kt
 * Exchange (Android)
 *
 * Paste-and-decrypt flow.
 *
 * User pastes an EXC2: envelope (or pulls one from the clipboard with
 * one tap), Decrypt runs it through CryptoEnvelope.open against the
 * local identity, and the plaintext appears with a Copy button. The
 * view also matches the sender's signing public key against the saved
 * Recipient list so it can show "From Alice" instead of just an opaque
 * fingerprint.
 *
 * If the screen was opened with a pending envelope (share sheet, deep
 * link), it pre-fills and auto-decrypts.
 */

package me.nettrash.exchange.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.crypto.EnvelopeUrl
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecryptScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity = (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val pendingEnvelope by viewModel.pendingDecryptEnvelope.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var envelopeText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ExchangeViewModel.DecryptResult?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    // 1) Share-sheet / deep-link path: pre-fill and auto-decrypt.
    LaunchedEffect(pendingEnvelope, identity) {
        val p = pendingEnvelope
        if (!p.isNullOrEmpty()) {
            envelopeText = p
            isWorking = true
            result = viewModel.decrypt(p, identity)
            isWorking = false
            viewModel.setPendingDecryptEnvelope(null)
        } else if (envelopeText.isEmpty()) {
            // 2) Manual entry: try the clipboard once.
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!pasted.isNullOrBlank()) {
                EnvelopeUrl.envelopeIfPresent(pasted)?.let { envelopeText = it }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Decrypt") },
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
                is ExchangeViewModel.DecryptResult.Opened -> ResultBlock(r, onCopy = {
                    r.plaintextText?.let { context.copyToClipboard("Decrypted message", it) }
                }, onDecryptAnother = {
                    result = null
                    envelopeText = ""
                })

                is ExchangeViewModel.DecryptResult.Failed -> {
                    Text("Couldn't decrypt", style = MaterialTheme.typography.titleMedium)
                    Text(r.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { result = null }) { Text("Try again") }
                }

                null -> {
                    Text("Paste an Exchange envelope", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = envelopeText,
                        onValueChange = { envelopeText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        placeholder = { Text("EXC2:… or an exchange.nettrash.me/msg link") },
                    )
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!pasted.isNullOrBlank()) {
                                envelopeText = EnvelopeUrl.envelopeIfPresent(pasted) ?: pasted
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Paste from clipboard")
                    }
                    Button(
                        onClick = {
                            isWorking = true
                            coroutineScope.launch {
                                result = viewModel.decrypt(envelopeText, identity)
                                isWorking = false
                            }
                        },
                        enabled = envelopeText.isNotBlank() && !isWorking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        } else {
                            Icon(Icons.Default.LockOpen, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Decrypt")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBlock(
    opened: ExchangeViewModel.DecryptResult.Opened,
    onCopy: () -> Unit,
    onDecryptAnother: () -> Unit,
) {
    Text("Decrypted message", style = MaterialTheme.typography.labelMedium)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val plaintext = opened.plaintextText
            if (plaintext != null) {
                SelectionContainer { Text(plaintext) }
            } else {
                Text(
                    "(Binary content, ${opened.binaryByteCount ?: 0} bytes — not displayable as text.)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val footer = when {
        opened.senderDisplayName != null ->
            "Signature verified — from ${opened.senderDisplayName}."
        opened.senderSigningKeyFingerprint != null ->
            "Signature verified, but the sender's signing key (fingerprint ${opened.senderSigningKeyFingerprint.toGroupedHex()}) doesn't match anyone in your recipients. Add them to confirm their identity."
        else -> "Verified as addressed to your identity."
    }
    Text(
        footer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (opened.plaintextText != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Copy plaintext")
            }
        }
    }
    OutlinedButton(onClick = onDecryptAnother, modifier = Modifier.fillMaxWidth()) {
        Text("Decrypt another envelope")
    }
}
