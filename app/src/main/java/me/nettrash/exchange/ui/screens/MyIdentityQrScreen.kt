/*
 * MyIdentityQrScreen.kt
 * Exchange (Android)
 *
 * Sheet that shows the local identity's public-key bundle as a QR code,
 * plus the human-readable fingerprint underneath. The recipient on the
 * other phone scans this; their AddRecipient view fills with the
 * bundle text, they type a name, save.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.QrCodeView
import me.nettrash.exchange.ui.components.copyToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyIdentityQrScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity =
        (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val payload = Identity.encode(identity.publicBundle)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My identity") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            QrCodeView(
                payload = payload,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(),
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
            Text(
                "Have the other person scan this to add you as a recipient. Always verify the fingerprint matches what they see on their phone — that's how you confirm the key wasn't intercepted in transit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = { context.copyToClipboard("Exchange public bundle", payload) },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Copy public key bundle")
            }
        }
    }
}
