/*
 * AddRecipientScreen.kt
 * Exchange (Android)
 *
 * Sheet for importing a recipient's public-key bundle — either by paste
 * or by scanning a QR code from the other person's "My identity" screen.
 *
 * In v2 the bundle is base64 of 64 bytes (encryption + signing public
 * keys concatenated), not the 32-byte single-key blob from v1.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.CameraPermissionGate
import me.nettrash.exchange.ui.components.QrScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecipientScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val identityState by viewModel.identityState.collectAsState()
    if (identityState !is ExchangeViewModel.IdentityState.Loaded) return

    var displayName by remember { mutableStateOf("") }
    var publicBundleText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (scanning) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scan public key") },
                    navigationIcon = {
                        IconButton(onClick = { scanning = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel scan")
                        }
                    },
                )
            }
        ) { padding ->
            CameraPermissionGate(modifier = Modifier.padding(padding)) {
                QrScanner(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onScan = { value ->
                        publicBundleText = value
                        errorMessage = null
                        scanning = false
                    },
                    onError = { message ->
                        errorMessage = message
                        scanning = false
                    },
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add recipient") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                if (displayName.isBlank() || publicBundleText.isBlank()) return@launch
                                when (viewModel.saveRecipient(displayName, publicBundleText, notes)) {
                                    is ExchangeViewModel.SaveRecipientResult.Saved -> onClose()
                                    is ExchangeViewModel.SaveRecipientResult.Duplicate ->
                                        errorMessage = "A recipient with this key already exists."
                                    is ExchangeViewModel.SaveRecipientResult.MalformedBundle ->
                                        errorMessage = "That doesn't look like a valid Exchange public key bundle. Make sure you pasted the full base64 string from their My identity screen."
                                }
                            }
                        },
                        enabled = displayName.isNotBlank() && publicBundleText.isNotBlank(),
                    ) { Text("Save") }
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
            Text("Display name", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. Alice") },
                singleLine = true,
            )

            Text("Public key bundle (base64)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = publicBundleText,
                onValueChange = { publicBundleText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("Paste here, or use Scan QR") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                "The bundle includes both the recipient's encryption key and their signing key, so received messages can be authenticated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { scanning = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Scan QR")
            }

            Text("Notes (optional)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Where you got this key") },
            )

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
