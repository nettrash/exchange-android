/*
 * SettingsScreen.kt
 * Exchange (Android)
 *
 * Mirrors iOS Settings.
 *
 * Sections:
 *   1. Identity — fingerprint readout.
 *   2. Backup & transfer — Export / Import (EXCBKP1) and Send / Receive
 *      identity QR (EXCQR1).
 *   3. About / Links — version, privacy policy, support.
 *   4. Reset identity — destructive, with explicit confirmation dialog.
 *
 * There is intentionally no "iCloud Keychain sync" toggle: the
 * Android port doesn't try to bridge to Google account-level key
 * sync — the user moves identity via the passphrase export or in-person
 * QR, both end-to-end encrypted.
 */

package me.nettrash.exchange.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nettrash.exchange.AppConstants
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.ui.ExchangeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ExchangeViewModel,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSendQr: () -> Unit,
    onReceiveQr: () -> Unit,
) {
    val context = LocalContext.current
    val identityState by viewModel.identityState.collectAsState()
    val identity =
        (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    var resetDialogOpen by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val versionName = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "—"
        } catch (_: Throwable) {
            "—"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.size(8.dp))
                SectionHeader("Identity")
                SectionCard {
                    KeyValueRow("Fingerprint", identity.fingerprint.toGroupedHex())
                }
            }

            item {
                SectionHeader("Backup & transfer")
                SectionCard {
                    SettingsAction(
                        icon = Icons.Default.LockPerson,
                        label = "Export identity (passphrase)",
                        onClick = onExport,
                    )
                    SettingsAction(
                        icon = Icons.Default.LockOpen,
                        label = "Import identity (passphrase)",
                        onClick = onImport,
                    )
                    SettingsAction(
                        icon = Icons.Default.QrCode,
                        label = "Send identity (QR code)",
                        onClick = onSendQr,
                    )
                    SettingsAction(
                        icon = Icons.Default.QrCodeScanner,
                        label = "Receive identity (Scan QR)",
                        onClick = onReceiveQr,
                    )
                }
                Text(
                    "Export creates a passphrase-encrypted blob you can keep in a password manager and import on another device. Send / Receive QR transfers the identity in person between two devices held by you — faster than passphrase, but only safe if both phones are physically with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SectionHeader("About")
                SectionCard {
                    KeyValueRow("Version", versionName)
                }
            }

            item {
                SectionHeader("Links")
                SectionCard {
                    SettingsAction(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = "Privacy Policy",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.PRIVACY_POLICY_URL))
                            )
                        },
                    )
                    SettingsAction(
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        label = "Support",
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AppConstants.SUPPORT_URL))
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader("Danger zone")
                SectionCard {
                    SettingsAction(
                        icon = Icons.Default.Delete,
                        label = "Reset identity",
                        destructive = true,
                        onClick = { resetDialogOpen = true },
                    )
                }
                Text(
                    "Wipes your private key and every saved recipient. A new identity is generated on the next launch. Anything previously encrypted to your old key becomes unreadable. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(32.dp))
            }
        }
    }

    if (resetDialogOpen) {
        AlertDialog(
            onDismissRequest = { resetDialogOpen = false },
            title = { Text("Reset identity?") },
            text = {
                Text(
                    "Your private key and all saved recipients will be deleted. A new identity will be generated. Messages previously sent to your old identity will be unrecoverable. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetDialogOpen = false
                        coroutineScope.launch {
                            viewModel.resetIdentity()
                            onClose()
                        }
                    }
                ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { resetDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
