package com.rollapp.shared.data.storage

/**
 * Where image bytes live.
 *
 * Firestore holds every fact about a photo except the pixels. Those go through this
 * interface so the blob backend is swappable: the production build talks to Supabase
 * Storage (Firebase's own Cloud Storage needs the Blaze plan since late 2024), and the
 * emulator build keeps images on disk so the whole app can be exercised offline.
 *
 * Paths are bucket-relative and never start with a slash, e.g.
 * `groups/{groupId}/full/{photoId}.jpg`. See [com.rollapp.shared.core.StoragePaths].
 */
interface ImageStore {

    /**
     * Uploads [bytes] to [path], replacing anything already there, and returns a URL
     * that an image loader can fetch with no further credentials. Retries of a failed
     * upload therefore land on the same object instead of orphaning the first attempt.
     *
     * [onProgress] is called with a 0..1 fraction from whichever thread does the I/O;
     * it must not block.
     */
    suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg",
        onProgress: (Float) -> Unit = {}
    ): String

    /** Fetches the object at [path], failing if it is larger than [maxBytes]. */
    suspend fun download(path: String, maxBytes: Long): ByteArray

    /** Removes the object at [path]. Deleting something already gone is not an error. */
    suspend fun delete(path: String)
}

/** Raised by an [ImageStore] so the error mapper can produce a sensible [com.rollapp.shared.core.AppError]. */
class ImageStoreException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: kind.name, cause) {
    enum class Kind { NOT_AUTHENTICATED, NOT_AUTHORIZED, NOT_FOUND, TOO_LARGE, QUOTA, OTHER }
}
