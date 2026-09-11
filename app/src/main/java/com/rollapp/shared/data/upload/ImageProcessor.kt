package com.rollapp.shared.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.rollapp.shared.core.Limits
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProcessedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
) {
    // ByteArray gives identity equals by default, which silently breaks any data
    // class holding one. Spell it out rather than leave a trap.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessedImage) return false
        return width == other.width && height == other.height && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + width) * 31 + height
}

data class ProcessedUpload(
    val full: ProcessedImage,
    val thumbnail: ProcessedImage,
    /** From EXIF, when the shot was actually taken. Null if the file had none. */
    val capturedAt: Long?
)

/**
 * Turns whatever the camera or gallery hands us into two JPEGs: one sized for
 * full-screen viewing and one for the grid.
 *
 * Re-encoding is also how EXIF gets stripped. `Bitmap.compress` writes no metadata,
 * so GPS coordinates, the device model and the original timestamp never leave the
 * phone — orientation is baked into the pixels first so the image still appears the
 * right way up. Capture time is read out before that and carried in the Firestore
 * document instead, where it is group-visible rather than embedded in a file anyone
 * could download.
 */
@Singleton
class ImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun prepare(uri: Uri): ProcessedUpload = withContext(Dispatchers.Default) {
        val capturedAt = readCaptureTime(uri)
        val source = decodeScaled(uri, Limits.FULL_IMAGE_MAX_EDGE)
            ?: error("Could not read that image")

        try {
            val oriented = applyOrientation(uri, source)
            val full = oriented.scaledTo(Limits.FULL_IMAGE_MAX_EDGE)
            val thumb = oriented.scaledTo(Limits.THUMBNAIL_MAX_EDGE)

            val result = ProcessedUpload(
                full = full.toJpeg(Limits.FULL_IMAGE_QUALITY),
                thumbnail = thumb.toJpeg(Limits.THUMBNAIL_QUALITY),
                capturedAt = capturedAt
            )

            if (thumb !== oriented) thumb.recycle()
            if (full !== oriented) full.recycle()
            if (oriented !== source) oriented.recycle()
            result
        } finally {
            source.recycle()
        }
    }

    suspend fun prepareCover(uri: Uri): ProcessedImage = withContext(Dispatchers.Default) {
        val source = decodeScaled(uri, COVER_MAX_EDGE) ?: error("Could not read that image")
        try {
            val oriented = applyOrientation(uri, source)
            val scaled = oriented.scaledTo(COVER_MAX_EDGE)
            val result = scaled.toJpeg(Limits.FULL_IMAGE_QUALITY)
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== source) oriented.recycle()
            result
        } finally {
            source.recycle()
        }
    }

    suspend fun prepareAvatar(uri: Uri): ProcessedImage = withContext(Dispatchers.Default) {
        val source = decodeScaled(uri, AVATAR_MAX_EDGE) ?: error("Could not read that image")
        try {
            val oriented = applyOrientation(uri, source)
            val scaled = oriented.scaledTo(AVATAR_MAX_EDGE)
            val result = scaled.toJpeg(85)
            if (scaled !== oriented) scaled.recycle()
            if (oriented !== source) oriented.recycle()
            result
        } finally {
            source.recycle()
        }
    }

    /**
     * Two-pass decode. The bounds-only pass picks a power-of-two subsample so a 48MP
     * phone photo never lands in memory at full size — decoding one of those directly
     * is ~190MB and an immediate OOM on a mid-range device.
     */
    private fun decodeScaled(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; only the stream itself is checked.
        val probe = context.contentResolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = max(width, height)
        // Halve until one more halving would drop below the target.
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Reads the tag directly rather than calling `ExifInterface.dateTimeOriginal`,
     * which is marked @RestrictedApi and is not part of the library's public surface.
     *
     * EXIF timestamps carry no timezone, so they are parsed in the device's zone —
     * which is the right guess for a photo the user took on this phone, and the only
     * guess available for one they were sent.
     */
    private fun readCaptureTime(uri: Uri): Long? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ExifInterface(stream).getAttribute(ExifInterface.TAG_DATETIME)
        } ?: return@runCatching null

        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .parse(raw)
            ?.time
            // A camera with an unset clock reports 1980; that is worse than no value.
            ?.takeIf { it > MIN_PLAUSIBLE_CAPTURE_MILLIS }
    }.getOrNull()

    private fun Bitmap.scaledTo(maxEdge: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= maxEdge) return this
        val scale = maxEdge.toFloat() / longest
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun Bitmap.toJpeg(quality: Int): ProcessedImage {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return ProcessedImage(stream.toByteArray(), width, height)
    }

    private companion object {
        const val COVER_MAX_EDGE = 1280
        const val AVATAR_MAX_EDGE = 512

        /** 2000-01-01. Anything older is a camera with a dead clock battery. */
        const val MIN_PLAUSIBLE_CAPTURE_MILLIS = 946_684_800_000L
    }
}
