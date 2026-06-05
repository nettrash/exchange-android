/*
 * ExportIdentityScreen.kt
 * Exchange (Android)
 *
 * Sheet for producing an `EXCBKP1:` passphrase-encrypted backup of the
 * user's identity + recipient list. The user enters a passphrase
 * twice, the view runs PBKDF2 (600k iterations) + ChaCha20-Poly1305
 * off the main thread, and shows the resulting blob with Copy / Share.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.crypto.IdentityBackup
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.copyToClipboard
import me.nettrash.exchange.ui.components.shareText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportIdentityScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var blob by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export identity") },
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
            val currentBlob = blob
            if (currentBlob == null) {
                Text("Passphrase", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("At least ${IdentityBackup.MIN_PASSPHRASE_LENGTH} characters") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Confirm passphrase") },
                    singleLine = true,
                )
                Text(
                    "The strength of this passphrase is the only thing protecting your private key in the exported blob — pick something long, memorable, and unique. There's no way to recover the export if you forget it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        error = null
                        working = true
                        coroutineScope.launch {
                            when (val result = viewModel.exportIdentity(passphrase)) {
                                is ExchangeViewModel.ExportResult.Done -> blob = result.blob
                                is ExchangeViewModel.ExportResult.Failed -> error = result.message
                            }
                            working = false
                        }
                    },
                    enabled = !working &&
                        passphrase.length >= IdentityBackup.MIN_PASSPHRASE_LENGTH &&
                        passphrase == confirmation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text("Encrypt and export")
                }
            } else {
                Text("Encrypted backup", style = MaterialTheme.typography.labelMedium)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    SelectionContainer(modifier = Modifier.padding(16.dp)) {
                        Text(
                            currentBlob,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                Text(
                    "Save this somewhere safe (a password manager works well). To restore on another device, use Settings → Import identity and supply the same passphrase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { context.copyToClipboard("Exchange backup", currentBlob) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Copy")
                    }
                    Button(
                        onClick = { context.shareText(currentBlob) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Share")
                    }
                }
                OutlinedButton(
                    onClick = {
                        blob = null
                        passphrase = ""
                        confirmation = ""
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Encrypt again with a different passphrase") }
            }
        }
    }
}
