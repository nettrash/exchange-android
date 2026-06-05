/*
 * QrCodeView.kt
 * Exchange (Android)
 *
 * Composable that renders an arbitrary string as a square QR code via
 * ZXing's QRCodeWriter. Mirrors iOS QRCodeView.swift.
 *
 * No anti-aliasing or smoothing — QR codes are pixel art; we draw each
 * module as a crisp square so any scanner reads it cleanly.
 */

package me.nettrash.exchange.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class QrCorrectionLevel(val ec: ErrorCorrectionLevel) {
    Low(ErrorCorrectionLevel.L),
    Medium(ErrorCorrectionLevel.M),
    Quartile(ErrorCorrectionLevel.Q),
    High(ErrorCorrectionLevel.H),
}

@Composable
fun QrCodeView(
    payload: String,
    modifier: Modifier = Modifier,
    correctionLevel: QrCorrectionLevel = QrCorrectionLevel.Medium,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, payload, correctionLevel) {
        value = withContext(Dispatchers.Default) {
            runCatching { generateQrBitmap(payload, correctionLevel) }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "QR code",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("…", color = Color.Black)
        }
    }
}

private fun generateQrBitmap(
    payload: String,
    correctionLevel: QrCorrectionLevel,
    pixelsPerModule: Int = 16,
): Bitmap {
    val hints: MutableMap<EncodeHintType, Any> = mutableMapOf(
        EncodeHintType.ERROR_CORRECTION to correctionLevel.ec,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
    val side = matrix.width
    val pixelSide = side * pixelsPerModule
    val bmp = Bitmap.createBitmap(pixelSide, pixelSide, Bitmap.Config.ARGB_8888)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    val row = IntArray(pixelSide)
    for (y in 0 until side) {
        for (x in 0 until side) {
            val color = if (matrix.get(x, y)) black else white
            for (px in 0 until pixelsPerModule) row[x * pixelsPerModule + px] = color
        }
        for (line in 0 until pixelsPerModule) {
            bmp.setPixels(row, 0, pixelSide, 0, y * pixelsPerModule + line, pixelSide, 1)
        }
    }
    return bmp
}
