package com.rollapp.shared.data.storage

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ImageStore] that keeps images in the app's own files directory.
 *
 * Bound instead of [SupabaseImageStore] when `USE_FIREBASE_EMULATOR` is on, so the
 * emulator workflow needs no network account at all. Returned URLs are `file://` URIs,
 * which Coil loads directly. Only this device can see them — which is also true of
 * the emulated Firestore next to it, so nothing is lost.
 */
class LocalImageStore(context: Context) : ImageStore {

    private val root = File(context.filesDir, "emulated-storage")

    override suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        onProgress(1f)
        Uri.fromFile(file).toString()
    }

    override suspend fun download(path: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val file = fileFor(path)
        if (!file.exists()) throw ImageStoreException(ImageStoreException.Kind.NOT_FOUND)
        if (file.length() > maxBytes) throw ImageStoreException(ImageStoreException.Kind.TOO_LARGE)
        file.readBytes()
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { fileFor(path).delete() }
    }

    private fun fileFor(path: String): File {
        val file = File(root, path.trimStart('/'))
        // Refuse anything that escapes the sandbox, however it was spelled.
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Bad path: $path" }
        return file
    }
}
