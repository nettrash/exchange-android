/*
 * ComposeScreen.kt
 * Exchange (Android)
 *
 * Encrypt-and-sign-a-message flow.
 *
 * Pick a recipient, type plaintext, tap Encrypt. The view swaps to a
 * result section showing the EXC2: envelope (signed by your identity,
 * encrypted to the recipient) with Copy / Share buttons. Sharing routes
 * through the standard Android Share Sheet — works with any messenger
 * that accepts text.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import me.nettrash.exchange.data.Recipient
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.copyToClipboard
import me.nettrash.exchange.ui.components.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity = (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val recipients by viewModel.recipientsByName.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedRecipient by remember { mutableStateOf<Recipient?>(null) }
    var plaintext by remember { mutableStateOf("") }
    var envelope by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(recipients) {
        if (selectedRecipient == null) selectedRecipient = recipients.firstOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = if (envelope == null) "Cancel" else "Done")
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
            if (recipients.isEmpty()) {
                Text(
                    "No recipients yet. Add a recipient's public key from the home screen first, then come back to send them an encrypted message.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            val currentEnvelope = envelope
            if (currentEnvelope == null) {
                // Recipient picker
                Text("Recipient", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(
                        onClick = { pickerOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedRecipient?.displayName ?: "Pick a recipient…")
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        recipients.forEach { r ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(r.displayName)
                                        Text(
                                            r.fingerprintDisplay,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedRecipient = r
                                    pickerOpen = false
                                },
                            )
                        }
                    }
                }
                selectedRecipient?.let {
                    Text(
                        it.fingerprintDisplay,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("Message", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = plaintext,
                    onValueChange = { plaintext = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = { Text("Type your message…") },
                )
                Text(
                    "Plaintext stays on this device. The envelope that leaves is encrypted to your recipient and signed by your identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = {
                        val recipient = selectedRecipient ?: return@Button
                        coroutineScope.launch {
                            when (val result = viewModel.encrypt(plaintext, recipient, identity)) {
                                is ExchangeViewModel.EncryptResult.Sealed -> {
                                    envelope = result.envelope
                                    errorMessage = null
                                }
                                is ExchangeViewModel.EncryptResult.Failed -> {
                                    errorMessage = result.message
                                }
                            }
                        }
                    },
                    enabled = selectedRecipient != null && plaintext.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Encrypt & sign")
                }
            } else {
                val url = EnvelopeUrl.urlFor(currentEnvelope)
                val shareable = url ?: currentEnvelope
                Text(
                    if (url == null) "Encrypted envelope" else "Encrypted message link",
                    style = MaterialTheme.typography.labelMedium,
                )
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer(modifier = Modifier.padding(16.dp)) {
                        Text(
                            shareable,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                Text(
                    "Send this through any messenger — Signal, WhatsApp, Telegram, Messages, email. Only ${selectedRecipient?.displayName ?: "the recipient"} can read it, and they can verify it came from you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { context.copyToClipboard("Exchange envelope", shareable) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = { context.shareText(shareable) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Share")
                    }
                }

                OutlinedButton(
                    onClick = {
                        envelope = null
                        plaintext = ""
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Encrypt another message") }
            }
        }
    }
}
