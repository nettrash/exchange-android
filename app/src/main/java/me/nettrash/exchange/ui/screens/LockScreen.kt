/*
 * LockScreen.kt
 * Exchange (Android)
 *
 * Full-screen biometric / device-credential lock overlay shown when the
 * app lock is engaged. Auto-prompts on first composition and offers a
 * manual retry. The actual BiometricPrompt lives in MainActivity (it
 * needs the Activity); this screen just renders and calls back through
 * [onUnlock].
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    // Auto-prompt once each time the lock engages (LaunchedEffect re-runs
    // when this screen re-enters composition). MainActivity guards against
    // overlapping prompts.
    LaunchedEffect(Unit) { onUnlock() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(20.dp))
            Text("Exchange is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(24.dp))
            Button(onClick = onUnlock) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Unlock")
            }
        }
    }
}
