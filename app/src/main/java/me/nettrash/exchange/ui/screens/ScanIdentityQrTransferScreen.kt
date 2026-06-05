/*
 * ScanIdentityQrTransferScreen.kt
 * Exchange (Android)
 *
 * Receiving-device side of the in-person QR transfer flow. Opens the
 * camera, decodes the next EXCQR1 QR it sees, decrypts to a candidate
 * identity, then asks the user to confirm by checking the fingerprint
 * matches what their other device shows.
 *
 * Only after explicit confirmation does the local identity get
 * overwritten — the camera scan alone is *not* destructive.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.crypto.IdentityTransferQR
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel
import me.nettrash.exchange.ui.components.CameraPermissionGate
import me.nettrash.exchange.ui.components.QrScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanIdentityQrTransferScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
) {
    var pending by remember { mutableStateOf<IdentityTransferQR.DecodedTransfer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive identity") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            when {
                done -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.size(16.dp))
                        Text("Identity imported", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "This device now uses the same identity as the one you scanned from.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = onClose) { Text("Done") }
                    }
                }
                pending != null -> {
                    val p = pending!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Scanned identity", style = MaterialTheme.typography.labelMedium)
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Fingerprint: ${IdentityTransferQR.fingerprint(p).toGroupedHex()}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                        Text(
                            "Compare this fingerprint with the one shown on the sending device. Both should match exactly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = {
                                working = true
                                coroutineScope.launch {
                                    try {
                                        viewModel.applyTransfer(p)
                                        done = true
                                    } catch (e: Throwable) {
                                        error = "Couldn't apply the transfer: ${e.message ?: e.javaClass.simpleName}."
                                    }
                                    working = false
                                }
                            },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Replace local identity")
                        }
                        OutlinedButton(
                            onClick = { pending = null; error = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel and re-scan") }
                    }
                }
                else -> {
                    CameraPermissionGate {
                        QrScanner(
                            modifier = Modifier.fillMaxSize(),
                            onScan = { payload ->
                                coroutineScope.launch {
                                    when (val r = viewModel.decodeIdentityTransferQR(payload)) {
                                        is ExchangeViewModel.TransferDecodeResult.Decoded ->
                                            pending = r.transfer
                                        is ExchangeViewModel.TransferDecodeResult.Failed ->
                                            error = r.message
                                    }
                                }
                            },
                            onError = { error = it },
                        )
                        error?.let {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Bottom,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Black.copy(alpha = 0.6f),
                                    ),
                                ) {
                                    Text(
                                        it,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
