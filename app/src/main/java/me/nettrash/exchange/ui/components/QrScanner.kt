/*
 * QrScanner.kt
 * Exchange (Android)
 *
 * Live-camera QR scanner, exposed as a Composable. Wraps CameraX
 * (preview + image analysis) and ZXing (QR decoding).
 *
 * Fires onScan with the decoded string the first time it sees a QR,
 * then stops analysis. Errors (permission denied, no camera, CameraX
 * misconfig) come back via onError so the caller can show a message.
 *
 * Permission handling: the caller is responsible for ensuring
 * Manifest.permission.CAMERA has been granted before composing this
 * view. The Compose wrappers in the *Screen files do that with the
 * activity-result API and show a friendly explainer if denied.
 */

package me.nettrash.exchange.ui.components

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val currentOnScan by rememberUpdatedState(onScan)
    val currentOnError by rememberUpdatedState(onError)
    val hasReported = remember { AtomicBoolean(false) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdownNow()
        }
    }

    LaunchedEffect(Unit) {
        try {
            bindCamera(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                onFrame = { proxy ->
                    if (hasReported.get()) {
                        proxy.close()
                        return@bindCamera
                    }
                    val text = analyzeFrameForQr(proxy)
                    proxy.close()
                    if (text != null && hasReported.compareAndSet(false, true)) {
                        currentOnScan(text)
                    }
                },
                executor = cameraExecutor,
                onBindError = { currentOnError(it) },
            )
        } catch (e: Throwable) {
            Log.e("QrScanner", "Camera bind failed", e)
            currentOnError("Couldn't open the camera: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onFrame: (ImageProxy) -> Unit,
    executor: java.util.concurrent.Executor,
    onBindError: (String) -> Unit,
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val cameraProvider = try {
            providerFuture.get()
        } catch (e: Throwable) {
            onBindError("Couldn't access the camera: ${e.message ?: e.javaClass.simpleName}.")
            return@addListener
        }

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .build()
            .apply { surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolution)
            .build()
            .apply { setAnalyzer(executor) { onFrame(it) } }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Throwable) {
            onBindError("Couldn't start the camera: ${e.message ?: e.javaClass.simpleName}.")
        }
    }, ContextCompat.getMainExecutor(context))
}

/** Pull the first QR code text out of a CameraX frame, or null if none. */
private fun analyzeFrameForQr(proxy: ImageProxy): String? {
    val plane = proxy.planes[0]
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val width = proxy.width
    val height = proxy.height
    val rowStride = plane.rowStride
    // ZXing wants tightly-packed luminance; CameraX may give us a
    // padded rowStride. PlanarYUVLuminanceSource handles that natively
    // when we pass width=rowStride and crop the right edge implicitly
    // via dataWidth/dataHeight parameters.
    val source = PlanarYUVLuminanceSource(
        data,
        rowStride,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = QRCodeReader()
    return try {
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)
        reader.decode(bitmap, hints).text
    } catch (_: Throwable) {
        null
    }
}
