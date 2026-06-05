/*
 * CameraPermissionGate.kt
 * Exchange (Android)
 *
 * Wraps a Composable that needs the CAMERA permission. On first show
 * we request it via the activity-result API; while denied we render
 * an explainer with a "Try again" button.
 */

package me.nettrash.exchange.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun CameraPermissionGate(
    modifier: Modifier = Modifier,
    granted: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var status by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) Status.Granted else Status.Initial
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        status = if (isGranted) Status.Granted else Status.Denied
    }

    LaunchedEffect(Unit) {
        if (status == Status.Initial) launcher.launch(Manifest.permission.CAMERA)
    }

    when (status) {
        Status.Granted -> granted()
        Status.Initial, Status.Denied -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.NoPhotography,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    if (status == Status.Initial)
                        "Camera access is needed to scan QR codes."
                    else
                        "Camera access was denied. Open Settings → Apps → Exchange → Permissions to enable it, then try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { launcher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(if (status == Status.Initial) "Allow camera" else "Try again")
                }
            }
        }
    }
}

private enum class Status { Initial, Granted, Denied }
