package com.rollapp.shared.data.moments

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.rollapp.shared.domain.model.Moment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Stitches a week's moments into one montage, on the phone, with Media3 Transformer.
 * Every clip is fitted into the same 720x1280 frame so front and back cameras,
 * portrait and landscape all cut together cleanly. The result lands in the gallery.
 */
@Singleton
class MontageMaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient
) {
    @OptIn(UnstableApi::class)
    suspend fun make(rollName: String, moments: List<Moment>, onProgress: (String) -> Unit): Uri {
        require(moments.isNotEmpty()) { "No moments this week yet" }
        val dir = File(context.cacheDir, "montage").apply { deleteRecursively(); mkdirs() }

        val clips = withContext(Dispatchers.IO) {
            moments.mapIndexed { i, m ->
                onProgress("Fetching clip ${i + 1} of ${moments.size}…")
                File(dir, "clip$i.mp4").also { file ->
                    client.newCall(Request.Builder().url(m.videoUrl).build()).execute().use { r ->
                        check(r.isSuccessful) { "Couldn't fetch a clip (${r.code})" }
                        file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                    }
                }
            }
        }

        onProgress("Cutting the montage…")
        val frame = Effects(
            emptyList(),
            listOf(Presentation.createForWidthAndHeight(720, 1280, Presentation.LAYOUT_SCALE_TO_FIT))
        )
        val items = clips.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).setEffects(frame).build() }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val output = File(dir, "montage.mp4")

        // Transformer must be built and started on a thread with a Looper.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { cont.resume(Unit) }
                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                            cont.resumeWithException(e)
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
            }
        }

        onProgress("Saving to your gallery…")
        return withContext(Dispatchers.IO) { saveToGallery(output, rollName) }
    }

    private fun saveToGallery(file: File, rollName: String): Uri {
        val name = "WeWere-${rollName.replace(Regex("[^A-Za-z0-9]+"), "-")}-${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/WeWere")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't save the montage")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        return uri
    }
}
