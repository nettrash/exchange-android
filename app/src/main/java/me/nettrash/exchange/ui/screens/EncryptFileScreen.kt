/*
 * EncryptFileScreen.kt
 * Exchange (Android)
 *
 * Encrypt-a-file flow. Pick a recipient, pick a file (Storage Access
 * Framework), and Exchange seals the file (name + bytes, framed by
 * FilePayload) into an EXC2 envelope, then saves it as an .exc2 document
 * you can share from anywhere. Same crypto as text messages.
 */

package me.nettrash.exchange.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nettrash.exchange.data.Recipient
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.displayName
import me.nettrash.exchange.ui.components.readAllBytes
import me.nettrash.exchange.ui.components.writeAllBytes

private const val WARN_SIZE_BYTES = 20 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptFileScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity = (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val recipients by viewModel.recipientsByManualOrder.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedRecipient by remember { mutableStateOf<Recipient?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var envelope by remember { mutableStateOf<String?>(null) }
    var encryptedSize by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(recipients) {
        if (selectedRecipient == null && recipients.isNotEmpty()) {
            val lastId = viewModel.lastRecipientId
            selectedRecipient = recipients.firstOrNull { it.id == lastId } ?: recipients.firstOrNull()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val env = envelope
        if (uri != null && env != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    context.writeAllBytes(uri, env.toByteArray(Charsets.UTF_8))
                }
                if (ok) { saved = true; errorMessage = null } else errorMessage = "Couldn't save the encrypted file."
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val recipient = selectedRecipient
        if (uri != null && recipient != null) {
            working = true
            errorMessage = null
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { context.readAllBytes(uri) }
                val name = withContext(Dispatchers.IO) { context.displayName(uri) }
                if (bytes == null) {
                    errorMessage = "Couldn't read that file."
                    working = false
                    return@launch
                }
                when (val r = viewModel.encryptFile(bytes, name, recipient, identity)) {
                    is ExchangeViewModel.EncryptResult.Sealed -> {
                        envelope = r.envelope
                        sourceName = name
                        encryptedSize = r.envelope.toByteArray(Charsets.UTF_8).size
                        saved = false
                    }
                    is ExchangeViewModel.EncryptResult.Failed -> errorMessage = r.message
                }
                working = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Encrypt file") },
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
            if (recipients.isEmpty()) {
                Text(
                    "No recipients yet. Add a recipient's public key from the home screen first, then come back to send them an encrypted file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val env = envelope
            if (env == null) {
                Text("Recipient", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedRecipient?.displayName ?: "Pick a recipient…")
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        recipients.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.displayName) },
                                onClick = {
                                    selectedRecipient = r
                                    viewModel.rememberLastRecipient(r.id)
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

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Button(
                    onClick = { pickLauncher.launch(arrayOf("*/*")) },
                    enabled = selectedRecipient != null && !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("Choose file to encrypt…")
                }
                Text(
                    "The file is encrypted to ${selectedRecipient?.displayName ?: "your recipient"} and signed by you, then saved as an .exc2 file you can send through any app. Only they can open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Encrypted file", style = MaterialTheme.typography.labelMedium)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Original: ${sourceName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Encrypted size: ${Formatter.formatShortFileSize(context, encryptedSize.toLong())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (encryptedSize > WARN_SIZE_BYTES)
                        "That's a large file. Files / Drive / email handle big attachments best."
                    else
                        "Save the .exc2 file, then send it to ${selectedRecipient?.displayName ?: "the recipient"} through any app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saved) {
                    Text("Saved.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = {
                        val base = (sourceName ?: "file").substringBeforeLast('.').ifEmpty { "file" }
                        saveLauncher.launch("$base.exc2")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Save encrypted file…")
                }
                OutlinedButton(
                    onClick = {
                        envelope = null
                        sourceName = null
                        errorMessage = null
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Encrypt another file") }
            }
        }
    }
}
