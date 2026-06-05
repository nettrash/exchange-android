/*
 * ShowIdentityQrTransferScreen.kt
 * Exchange (Android)
 *
 * Source-device side of the in-person QR transfer flow. Encrypts the
 * local identity under a fresh ephemeral key and renders the EXCQR1
 * string as a large QR code for the receiving device's camera.
 *
 * The ephemeral key lives inside the QR — there's no separate channel.
 * The user is expected to keep the QR visible only to their other
 * device in a physically trusted setting; the on-screen warning makes
 * that explicit.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.IdentityTransferQR
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.QrCodeView
import me.nettrash.exchange.ui.components.QrCorrectionLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowIdentityQrTransferScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val identityState by viewModel.identityState.collectAsState()
    val identity = (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return

    val payload by produceState<Result<String>?>(initialValue = null, identity) {
        value = runCatching { IdentityTransferQR.encode(identity) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send identity") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        val result = payload
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when {
                result == null -> {
                    Text("Preparing transfer code…")
                }
                result.isFailure -> {
                    Text(
                        "Couldn't prepare transfer code: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    QrCodeView(
                        payload = result.getOrThrow(),
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .fillMaxWidth(),
                        correctionLevel = QrCorrectionLevel.Medium,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.size(4.dp))
                        SelectionContainer {
                            Text(
                                identity.fingerprint.toGroupedHex(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "This QR carries your private key.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "Keep it visible only to the other device you're transferring to. Don't photograph it, screenshot it, or show it on a video call. Anyone who captures this image can decrypt every message ever addressed to your identity.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "On the other device: open Exchange → Settings → Receive identity, then point its camera here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
