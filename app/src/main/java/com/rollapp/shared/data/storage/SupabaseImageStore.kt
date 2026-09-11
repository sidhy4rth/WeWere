package com.rollapp.shared.data.storage

import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject

/**
 * [ImageStore] backed by a private Supabase Storage bucket.
 *
 * This talks to the Storage REST API directly rather than through supabase-kt: the
 * four calls we need are one request each, and the SDK would drag Ktor plus a newer
 * Kotlin metadata version into the build for no gain.
 *
 * Authentication is the user's **Firebase** ID token. Supabase is configured to trust
 * `securetoken.google.com/<firebase-project>` as a third-party issuer, so the same
 * sign-in that gates Firestore gates the bucket, and the bucket's row-level policies
 * (see `supabase/storage-policies.sql`) can read the Firebase uid from the token.
 *
 * Reads go through long-lived signed URLs minted at upload time and stored on the
 * photo document — the equivalent of Firebase's tokenised download URLs, with the
 * same caveat: whoever holds the URL can fetch the image.
 */
class SupabaseImageStore(
    private val baseUrl: String,
    private val anonKey: String,
    private val bucket: String,
    private val auth: FirebaseAuth,
    private val client: OkHttpClient
) : ImageStore {

    private val storageRoot: HttpUrl = baseUrl.trimEnd('/').toHttpUrlOrThrow()
        .newBuilder().addPathSegments("storage/v1").build()

    override suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String,
        onProgress: (Float) -> Unit
    ): String {
        val token = idToken()
        val body = ProgressRequestBody(bytes.toRequestBody(contentType.toMediaType()), onProgress)

        val request = authed(token)
            .url(objectUrl("object", path))
            // Overwrite rather than 409: a retried upload must reuse its object.
            .header("x-upsert", "true")
            .post(body)
            .build()
        client.newCall(request).await().use { it.requireSuccess("upload") }

        return signedUrl(token, path)
    }

    override suspend fun download(path: String, maxBytes: Long): ByteArray {
        val request = authed(idToken())
            .url(objectUrl("object/authenticated", path))
            .get()
            .build()
        return client.newCall(request).await().use { response ->
            response.requireSuccess("download")
            val body = response.body ?: throw ImageStoreException(ImageStoreException.Kind.OTHER, "Empty body")
            if (body.contentLength() > maxBytes) {
                throw ImageStoreException(ImageStoreException.Kind.TOO_LARGE)
            }
            val bytes = body.bytes()
            if (bytes.size > maxBytes) throw ImageStoreException(ImageStoreException.Kind.TOO_LARGE)
            bytes
        }
    }

    override suspend fun delete(path: String) {
        val request = authed(idToken())
            .url(objectUrl("object", path))
            .delete()
            .build()
        client.newCall(request).await().use { response ->
            if (response.isSuccessful) return
            val (status, detail) = response.errorStatus()
            if (status != 404) throw storeError("delete", status, detail)
        }
    }

    // ---------------------------------------------------------------------------------

    private suspend fun signedUrl(token: String, path: String): String {
        val payload = JSONObject().put("expiresIn", SIGNED_URL_TTL_SECONDS).toString()
        val request = authed(token)
            .url(objectUrl("object/sign", path))
            .post(payload.toRequestBody(JSON))
            .build()
        return client.newCall(request).await().use { response ->
            response.requireSuccess("sign")
            val relative = JSONObject(response.body?.string().orEmpty()).optString("signedURL")
            if (relative.isBlank()) {
                throw ImageStoreException(ImageStoreException.Kind.OTHER, "No signedURL in response")
            }
            // The API returns a path relative to /storage/v1, e.g. "/object/sign/bucket/…?token=…".
            storageRoot.toString().trimEnd('/') + "/" + relative.trimStart('/')
        }
    }

    private suspend fun idToken(): String {
        val user = auth.currentUser ?: throw ImageStoreException(ImageStoreException.Kind.NOT_AUTHENTICATED)
        return user.getIdToken(false).await().token
            ?: throw ImageStoreException(ImageStoreException.Kind.NOT_AUTHENTICATED)
    }

    private fun authed(token: String): Request.Builder = Request.Builder()
        .header("apikey", anonKey)
        .header("Authorization", "Bearer $token")

    private fun objectUrl(operation: String, path: String): HttpUrl = storageRoot.newBuilder()
        .addPathSegments(operation)
        .addPathSegment(bucket)
        .addPathSegments(path.trimStart('/'))
        .build()

    private fun Response.requireSuccess(what: String) {
        if (isSuccessful) return
        val (status, detail) = errorStatus()
        throw storeError(what, status, detail)
    }

    /**
     * Storage reports most failures as HTTP 400 with the real status in the JSON body
     * (`{"statusCode":"403",...}`), so that field wins over the transport code.
     */
    private fun Response.errorStatus(): Pair<Int, String?> {
        val raw = runCatching { body?.string() }.getOrNull()
        val embedded = raw?.let { runCatching { JSONObject(it).optString("statusCode").toIntOrNull() }.getOrNull() }
        return (embedded ?: code) to raw?.take(300)
    }

    private fun storeError(what: String, status: Int, detail: String?): ImageStoreException {
        val kind = when (status) {
            401 -> ImageStoreException.Kind.NOT_AUTHENTICATED
            403 -> ImageStoreException.Kind.NOT_AUTHORIZED
            404 -> ImageStoreException.Kind.NOT_FOUND
            413 -> ImageStoreException.Kind.TOO_LARGE
            // The bucket's file_size_limit trips as a 400 whose message says "exceeded".
            400 -> if (detail?.contains("exceeded", ignoreCase = true) == true) {
                ImageStoreException.Kind.TOO_LARGE
            } else ImageStoreException.Kind.OTHER
            507 -> ImageStoreException.Kind.QUOTA
            else -> ImageStoreException.Kind.OTHER
        }
        return ImageStoreException(kind, "Supabase $what failed: HTTP $status $detail")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Ten years. The URL is stored on the photo document, so it has to outlive the photo. */
        const val SIGNED_URL_TTL_SECONDS = 10L * 365 * 24 * 60 * 60
    }
}

private fun String.toHttpUrlOrThrow(): HttpUrl =
    toHttpUrlOrNull() ?: throw IllegalArgumentException("Bad Supabase URL: $this")

/** Wraps a body so bytes written to the wire are reported as a 0..1 fraction. */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val counting = object : ForwardingSink(sink) {
            var written = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/** Runs the call off the caller's thread and cancels it if the coroutine is cancelled. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
