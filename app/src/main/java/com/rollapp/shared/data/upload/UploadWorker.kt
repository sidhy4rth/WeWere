package com.rollapp.shared.data.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.rollapp.shared.R
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.FirebaseErrorMapper
import com.rollapp.shared.core.FirestorePaths
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.StoragePaths
import com.rollapp.shared.data.local.UploadDao
import com.rollapp.shared.data.local.UploadEntity
import com.rollapp.shared.data.storage.ImageStore
import com.rollapp.shared.domain.model.UploadState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Drains the durable upload queue.
 *
 * Everything here is built around the queue surviving things the app does not control:
 * the process being killed mid-upload, the network vanishing, the user force-quitting.
 * State lives in Room, not in memory, and each photo is only marked COMPLETED once its
 * Firestore document exists — so a crash between the blob landing and the document
 * being written retries safely instead of leaving an image nobody can see.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: UploadDao,
    private val imageProcessor: ImageProcessor,
    private val firestore: FirebaseFirestore,
    private val imageStore: ImageStore,
    private val auth: FirebaseAuth
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        dao.recoverInterrupted()

        val uid = auth.currentUser?.uid ?: return Result.success()
        val batch = dao.claimBatch(BATCH_SIZE).filter { it.ownerUid == uid }
        if (batch.isEmpty()) return Result.success()

        var anyRetryable = false

        for ((index, item) in batch.withIndex()) {
            // The user may have cancelled while an earlier item was in flight.
            val fresh = dao.getById(item.id) ?: continue
            if (fresh.state == UploadState.CANCELLED.name) {
                dao.delete(item.id)
                continue
            }

            setForegroundSafely(index + 1, batch.size)

            when (val outcome = uploadOne(fresh)) {
                UploadOutcome.Success -> {
                    dao.setState(item.id, UploadState.COMPLETED.name, null)
                    dao.delete(item.id)
                }

                is UploadOutcome.Retryable -> {
                    dao.incrementAttempts(item.id)
                    val attempts = (dao.getById(item.id)?.attemptCount ?: 0)
                    if (attempts >= Limits.MAX_UPLOAD_ATTEMPTS) {
                        dao.setState(item.id, UploadState.FAILED.name, outcome.reason)
                    } else {
                        dao.setState(item.id, UploadState.QUEUED.name, outcome.reason)
                        anyRetryable = true
                    }
                }

                is UploadOutcome.Permanent -> {
                    dao.setState(item.id, UploadState.FAILED.name, outcome.reason)
                }
            }
        }

        // More work left in the queue than one batch could carry.
        val remaining = dao.countActive()
        return when {
            anyRetryable -> Result.retry()
            remaining > 0 -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun uploadOne(item: UploadEntity): UploadOutcome {
        return try {
            dao.setState(item.id, UploadState.UPLOADING.name, null)
            dao.setProgress(item.id, 0f)

            val processed = imageProcessor.prepare(Uri.parse(item.localUri))

            // Reuse the id from a previous attempt so a retry overwrites the same
            // storage objects instead of orphaning the ones already uploaded.
            val photoId = item.remotePhotoId ?: firestore
                .collection(FirestorePaths.GROUPS).document(item.groupId)
                .collection(FirestorePaths.PHOTOS).document().id
                .also { dao.setRemotePhotoId(item.id, it) }

            val fullPath = StoragePaths.photo(item.groupId, StoragePaths.FULL, photoId)
            val thumbPath = StoragePaths.photo(item.groupId, StoragePaths.THUMBS, photoId)

            // Thumbnail first and it is small: the grid can render the moment the
            // document appears, even while the full image is still climbing.
            val (thumbUrl, fullUrl) = coroutineScope {
                // The progress callback fires on the HTTP thread, so it must never block
                // or suspend. It hands the fraction to a conflated channel and a collector
                // on the worker's own coroutine persists it — dropping intermediate values
                // under load, which is exactly right for a progress bar.
                val progress = Channel<Float>(Channel.CONFLATED)
                val writer = launch {
                    for (fraction in progress) dao.setProgress(item.id, fraction)
                }
                try {
                    val thumb = imageStore.upload(thumbPath, processed.thumbnail.bytes) { fraction ->
                        progress.trySend(fraction * THUMB_SHARE)
                    }
                    val full = imageStore.upload(fullPath, processed.full.bytes) { fraction ->
                        progress.trySend(THUMB_SHARE + fraction * (1f - THUMB_SHARE))
                    }
                    thumb to full
                } finally {
                    progress.close()
                    writer.join()
                }
            }

            writePhotoDocument(item, photoId, processed, fullPath, thumbPath, fullUrl, thumbUrl)

            dao.setProgress(item.id, 1f)
            UploadOutcome.Success
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            classify(t)
        }
    }

    private suspend fun writePhotoDocument(
        item: UploadEntity,
        photoId: String,
        processed: ProcessedUpload,
        fullPath: String,
        thumbPath: String,
        fullUrl: String,
        thumbUrl: String
    ) {
        val uid = item.ownerUid
        val groupRef = firestore.collection(FirestorePaths.GROUPS).document(item.groupId)
        val memberRef = groupRef.collection(FirestorePaths.MEMBERS).document(uid)

        val member = memberRef.get().await()
        val uploaderName = member.getString("name") ?: "Someone"
        val uploaderPhoto = member.getString("photoUrl")

        val group = groupRef.get().await()
        val needsCover = group.getString("coverPhotoUrl").isNullOrBlank()

        val batch = firestore.batch()

        batch.set(
            groupRef.collection(FirestorePaths.PHOTOS).document(photoId), mapOf(
                "uploadedBy" to uid,
                "uploaderName" to uploaderName,
                "uploaderPhotoUrl" to uploaderPhoto,
                "imageUrl" to fullUrl,
                "thumbnailUrl" to thumbUrl,
                "storagePath" to fullPath,
                "thumbnailStoragePath" to thumbPath,
                "caption" to item.caption?.take(Limits.MAX_CAPTION_LENGTH),
                "width" to processed.full.width,
                "height" to processed.full.height,
                "sizeBytes" to processed.full.bytes.size.toLong(),
                "createdAt" to FieldValue.serverTimestamp(),
                // EXIF beats the enqueue time: a gallery photo taken last Tuesday
                // belongs under Tuesday in the timeline, not under today.
                "capturedAt" to com.google.firebase.Timestamp(
                    java.util.Date(processed.capturedAt ?: item.capturedAt)
                ),
                "reactionCounts" to emptyMap<String, Int>(),
                "favoritedBy" to emptyList<String>()
            )
        )

        val groupUpdate = mutableMapOf<String, Any>(
            "photoCount" to FieldValue.increment(1),
            "lastActivityAt" to FieldValue.serverTimestamp()
        )
        // First photo in a group doubles as its cover, so the home card is never blank.
        if (needsCover) groupUpdate["coverPhotoUrl"] = thumbUrl
        batch.update(groupRef, groupUpdate)

        batch.update(memberRef, "photoCount", FieldValue.increment(1))

        batch.set(
            groupRef.collection(FirestorePaths.ACTIVITY).document(), mapOf(
                "type" to "PHOTOS_ADDED",
                "groupName" to (group.getString("name") ?: ""),
                "actorId" to uid,
                "actorName" to uploaderName,
                "actorPhotoUrl" to uploaderPhoto,
                "photoCount" to 1,
                "previewPhotoUrl" to thumbUrl,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        batch.commit().await()
    }

    /**
     * Decides whether a failure is worth another attempt.
     *
     * This goes through the same error mapper the UI uses rather than matching on
     * exception messages — a revoked membership and a flaky hotel wifi both surface
     * as an opaque failure, and retrying the first one forever burns the battery of
     * someone who has already been removed from the group.
     */
    private fun classify(t: Throwable): UploadOutcome {
        Log.w(TAG, "Upload attempt failed", t)
        return when (val error = FirebaseErrorMapper.map(t)) {
            AppError.PermissionDenied, AppError.NotAuthenticated ->
                UploadOutcome.Permanent("You're no longer a member of this group")

            AppError.GroupNotFound ->
                UploadOutcome.Permanent("That group no longer exists")

            AppError.StorageQuotaExceeded ->
                UploadOutcome.Permanent("This group has run out of storage")

            else -> when (t) {
                is SecurityException ->
                    UploadOutcome.Permanent("WeWere lost access to that photo")
                is java.io.FileNotFoundException ->
                    UploadOutcome.Permanent("That photo is no longer on this device")
                else ->
                    UploadOutcome.Retryable(error.message ?: "Upload failed")
            }
        }
    }

    /**
     * A foreground notification is what buys the upload time to finish when the user
     * leaves the app. If the OS refuses it (background restrictions, permission
     * revoked), the upload still proceeds — it just gets less runway.
     */
    private suspend fun setForegroundSafely(current: Int, total: Int) {
        runCatching { setForeground(buildForegroundInfo(current, total)) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(1, 1)

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        val channelId = applicationContext.getString(R.string.notification_channel_uploads)
        ensureChannel(channelId)

        val text = if (total > 1) "Uploading photo $current of $total" else "Uploading photo"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("WeWere")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, current, total <= 1)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                applicationContext.getString(R.string.notification_channel_uploads_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private sealed interface UploadOutcome {
        data object Success : UploadOutcome
        data class Retryable(val reason: String) : UploadOutcome
        data class Permanent(val reason: String) : UploadOutcome
    }

    companion object {
        const val WORK_NAME = "roll-upload-queue"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "UploadWorker"
        private const val BATCH_SIZE = 8

        /** The thumbnail is a small fraction of the bytes; weight the bar accordingly. */
        private const val THUMB_SHARE = 0.15f
    }
}
