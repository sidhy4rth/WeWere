package com.rollapp.shared.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.AppErrorException
import com.rollapp.shared.core.FirestorePaths
import com.rollapp.shared.core.Limits
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.core.firebaseCall
import com.rollapp.shared.data.local.UploadDao
import com.rollapp.shared.data.local.UploadEntity
import com.rollapp.shared.data.remote.snapshots
import com.rollapp.shared.data.remote.str
import com.rollapp.shared.data.remote.toPhoto
import com.rollapp.shared.data.upload.UploadScheduler
import com.rollapp.shared.domain.model.MemberRole
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.Reaction
import com.rollapp.shared.domain.model.UploadState
import com.rollapp.shared.domain.repository.PhotoPage
import com.rollapp.shared.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

@Singleton
class FirestorePhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val uploadDao: UploadDao,
    private val uploadScheduler: UploadScheduler
) : PhotoRepository {

    /**
     * How far back the live window reaches, per group.
     *
     * Pagination is a growing realtime window rather than independent pages. A
     * shared camera roll gains photos at the top constantly — with cursor pages,
     * every upload by a friend would shift the boundary and duplicate or skip rows.
     * One listener over the newest N keeps the feed consistent; "load older" just
     * widens N. The cost is re-reading the window on each extension, which is fine
     * at the tens-of-photos-per-page this app deals in.
     */
    private val windowSizes = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    private fun windowFor(groupId: String) =
        windowSizes.getOrPut(groupId) { MutableStateFlow(Limits.PHOTO_PAGE_SIZE) }

    private fun photos(groupId: String) =
        firestore.collection(FirestorePaths.GROUPS).document(groupId)
            .collection(FirestorePaths.PHOTOS)

    override fun observePhotos(groupId: String): Flow<PhotoPage> =
        windowFor(groupId).flatMapLatest { limit ->
            photos(groupId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .snapshots()
                .map { snap ->
                    val items = snap.documents.mapNotNull { it.toPhoto(groupId) }
                    PhotoPage(
                        photos = items,
                        // A full window implies there is probably more behind it.
                        hasMore = items.size >= limit
                    )
                }
        }.catch { throwable ->
            if (throwable is Exception) emit(PhotoPage()) else throw throwable
        }

    override suspend fun loadOlder(groupId: String) {
        val flow = windowFor(groupId)
        flow.value = flow.value + Limits.PHOTO_PAGE_SIZE
    }

    override fun resetPagination(groupId: String) {
        windowFor(groupId).value = Limits.PHOTO_PAGE_SIZE
    }

    /** Combines the photo document with this user's own reaction, which lives apart. */
    override fun observePhoto(groupId: String, photoId: String): Flow<Photo?> {
        val uid = auth.currentUser?.uid
        val photoFlow = photos(groupId).document(photoId).snapshots()
            .map { it.toPhoto(groupId) }
            .catch { emit(null) }

        if (uid == null) return photoFlow

        val myReactionFlow = photos(groupId).document(photoId)
            .collection(FirestorePaths.REACTIONS).document(uid)
            .snapshots()
            .map { it.str("key") }
            .catch { emit(null) }

        return combine(photoFlow, myReactionFlow) { photo, reaction ->
            photo?.copy(myReaction = reaction)
        }
    }

    override suspend fun enqueueUpload(
        groupId: String,
        localUri: Uri,
        caption: String?,
        capturedAt: Long
    ): Outcome<String> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            // Copy into app storage first. A gallery uri's permission grant dies with
            // the activity, and the queue may not drain until hours later.
            val staged = stageLocally(localUri)
            val id = UUID.randomUUID().toString()

            uploadDao.insert(
                UploadEntity(
                    id = id,
                    groupId = groupId,
                    ownerUid = uid,
                    localUri = staged.toString(),
                    caption = caption?.trim()?.take(Limits.MAX_CAPTION_LENGTH)?.takeIf { it.isNotEmpty() },
                    capturedAt = capturedAt,
                    createdAt = System.currentTimeMillis(),
                    state = UploadState.QUEUED.name,
                    progress = 0f,
                    attemptCount = 0,
                    errorMessage = null,
                    remotePhotoId = null
                )
            )

            uploadScheduler.ensureRunning()
            id
        }
    }

    private suspend fun stageLocally(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "upload_queue").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw AppErrorException(AppError.Validation("Couldn't read that photo"))
        Uri.fromFile(target)
    }

    override suspend fun deletePhoto(groupId: String, photoId: String): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            val doc = photos(groupId).document(photoId).get().await()
            if (!doc.exists()) throw AppErrorException(AppError.PhotoNotFound)

            val uploadedBy = doc.getString("uploadedBy")
            val groupRef = firestore.collection(FirestorePaths.GROUPS).document(groupId)

            if (uploadedBy != uid) {
                // Only an admin may remove someone else's photo. The rules enforce this
                // too; checking here just produces a better message than a raw denial.
                val me = groupRef.collection(FirestorePaths.MEMBERS).document(uid).get().await()
                if (MemberRole.from(me.getString("role")) != MemberRole.ADMIN) {
                    throw AppErrorException(AppError.PermissionDenied)
                }
            }

            // Blobs first: a photo document pointing at a missing image renders as a
            // broken tile, while an orphaned blob is invisible and merely wasteful.
            doc.getString("storagePath")?.let {
                runCatching { storage.reference.child(it).delete().await() }
            }
            doc.getString("thumbnailStoragePath")?.let {
                runCatching { storage.reference.child(it).delete().await() }
            }

            val batch = firestore.batch()
            batch.delete(photos(groupId).document(photoId))
            batch.update(groupRef, "photoCount", FieldValue.increment(-1))
            if (uploadedBy != null) {
                batch.update(
                    groupRef.collection(FirestorePaths.MEMBERS).document(uploadedBy),
                    "photoCount", FieldValue.increment(-1)
                )
            }
            batch.commit().await()
        }
    }

    override suspend fun setCaption(groupId: String, photoId: String, caption: String?): Outcome<Unit> {
        auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        val cleaned = caption?.trim()?.take(Limits.MAX_CAPTION_LENGTH)?.takeIf { it.isNotEmpty() }
        return firebaseCall {
            photos(groupId).document(photoId).update("caption", cleaned).await()
        }
    }

    /**
     * A reaction is one document per user plus a counter on the photo. The transaction
     * reads the user's existing reaction and adjusts both counters, so switching from
     * ❤️ to 😂 never leaves the old count stranded and a double-tap cannot inflate it.
     */
    override suspend fun setReaction(groupId: String, photoId: String, reaction: Reaction?): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            val photoRef = photos(groupId).document(photoId)
            val myReactionRef = photoRef.collection(FirestorePaths.REACTIONS).document(uid)

            firestore.runTransaction { transaction ->
                val existing = transaction.get(myReactionRef)
                val previousKey = existing.getString("key")
                val nextKey = reaction?.key

                if (previousKey == nextKey) return@runTransaction null

                val updates = mutableMapOf<String, Any>()
                previousKey?.let { updates["reactionCounts.$it"] = FieldValue.increment(-1) }
                nextKey?.let { updates["reactionCounts.$it"] = FieldValue.increment(1) }
                if (updates.isNotEmpty()) transaction.update(photoRef, updates)

                if (nextKey == null) {
                    transaction.delete(myReactionRef)
                } else {
                    transaction.set(
                        myReactionRef, mapOf(
                            "key" to nextKey,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                null
            }.await()
        }
    }

    override suspend fun downloadToGallery(photo: Photo): Outcome<Uri> = firebaseCall {
        withContext(Dispatchers.IO) {
            val bytes = storage.reference.child(photo.storagePath)
                .getBytes(MAX_DOWNLOAD_BYTES).await()

            val filename = "Roll_${photo.id}.jpg"
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Roll")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, values)
                ?: throw AppErrorException(AppError.Unknown("Couldn't save to your gallery"))

            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw AppErrorException(AppError.Unknown("Couldn't save to your gallery"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }
    }

    override suspend fun prepareForSharing(photo: Photo): Outcome<Uri> = firebaseCall {
        withContext(Dispatchers.IO) {
            val bytes = storage.reference.child(photo.storagePath)
                .getBytes(MAX_DOWNLOAD_BYTES).await()

            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "Roll_${photo.id}.jpg")
            file.writeBytes(bytes)

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    override suspend fun reportPhoto(groupId: String, photoId: String, reason: String): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            firestore.collection("reports").document().set(
                mapOf(
                    "groupId" to groupId,
                    "photoId" to photoId,
                    "reportedBy" to uid,
                    "reason" to reason,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private companion object {
        /** Full-size uploads are capped well below this by the image processor. */
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
    }
}
