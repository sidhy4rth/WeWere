package com.rollapp.shared.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders an invite link as a QR code.
 *
 * Always drawn on a white card regardless of theme — scanners need the light modules
 * lighter than the dark ones, and an inverted code in dark mode simply will not read
 * on many phones. Error correction is set high so the code still scans when someone
 * photographs it off another screen at an angle, which is how these actually get used.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }

    var bitmap by remember(content, pixels) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(content, pixels) {
        // Encoding is cheap but not free, and this runs during a dialog animation.
        bitmap = withContext(Dispatchers.Default) {
            runCatching { encode(content, pixels) }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "QR code for this group's invite",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size)
            )
        } ?: Box(Modifier.size(size))
    }
}

private fun encode(content: String, pixels: Int): Bitmap {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )

    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val width = matrix.width
    val height = matrix.height
    val buffer = IntArray(width * height)

    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            buffer[offset + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(buffer, 0, width, 0, 0, width, height)
    }
}
