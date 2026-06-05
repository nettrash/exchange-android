/*
 * ImportIdentityScreen.kt
 * Exchange (Android)
 *
 * Sheet for restoring an identity + recipient list from an
 * `EXCBKP1:` passphrase-encrypted backup. The user pastes the blob,
 * enters the passphrase, sees the decoded fingerprint + recipient
 * count, and confirms the destructive replace.
 */

package me.nettrash.exchange.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.IdentityBackup
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportIdentityScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var blobText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<IdentityBackup.DecodedBackup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import identity") },
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
            horizontalAlignment = if (done) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            when {
                done -> {
                    Spacer(Modifier.height(40.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    )
                    Text("Identity imported", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your identity and recipients have been restored from the backup.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onClose) { Text("Done") }
                }

                preview != null -> {
                    val p = preview!!
                    Text("Backup contents", style = MaterialTheme.typography.labelMedium)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Fingerprint: ${Identity.fingerprint(p.identity.publicBundle).toGroupedHex()}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Recipients in backup: ${p.recipients.size}")
                        }
                    }
                    Text(
                        "Confirm the fingerprint matches the device the backup was made on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            working = true
                            coroutineScope.launch {
                                viewModel.applyBackup(p)
                                working = false
                                done = true
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Replace local identity")
                    }
                    Text(
                        "Your current private key on this device will be overwritten with the imported one. Existing recipients will be wiped and replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { preview = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel") }
                }

                else -> {
                    Text("Encrypted backup", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = blobText,
                        onValueChange = { blobText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("Paste the EXCBKP1: blob you exported earlier") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!pasted.isNullOrBlank()) blobText = pasted.trim()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Paste from clipboard")
                    }
                    Text("Passphrase", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            error = null
                            working = true
                            coroutineScope.launch {
                                when (val r = viewModel.decryptBackupPreview(blobText, passphrase)) {
                                    is ExchangeViewModel.ImportPreviewResult.Decoded -> preview = r.backup
                                    is ExchangeViewModel.ImportPreviewResult.Failed -> error = r.message
                                }
                                working = false
                            }
                        },
                        enabled = !working && blobText.isNotBlank() && passphrase.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (working) {
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
