package com.rollapp.shared.data.moments

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.rollapp.shared.core.FirestorePaths
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.core.StoragePaths
import com.rollapp.shared.core.firebaseCall
import com.rollapp.shared.data.remote.millis
import com.rollapp.shared.data.remote.str
import com.rollapp.shared.data.storage.ImageStore
import com.rollapp.shared.domain.model.Moment
import com.rollapp.shared.domain.model.weekKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Moments: record, upload, and list this week's 5-second clips for a roll. */
@Singleton
class MomentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val imageStore: ImageStore
) {
    private fun moments(groupId: String) = firestore
        .collection(FirestorePaths.GROUPS).document(groupId)
        .collection(FirestorePaths.MOMENTS)

    /** This week's moments, oldest first — the order the montage plays them in. */
    fun observeThisWeek(groupId: String): Flow<List<Moment>> = callbackFlow {
        val registration = moments(groupId)
            .whereEqualTo("week", weekKey())
            .addSnapshotListener { snap, error ->
                if (error != null) { trySend(emptyList()); return@addSnapshotListener }
                val items = snap?.documents.orEmpty().map { doc ->
                    Moment(
                        id = doc.id,
                        uploadedBy = doc.str("uploadedBy").orEmpty(),
                        uploaderName = doc.str("uploaderName") ?: "Someone",
                        videoUrl = doc.str("videoUrl").orEmpty(),
                        createdAt = doc.millis("createdAt"),
                        week = doc.str("week").orEmpty()
                    )
                }.sortedBy { it.createdAt }
                trySend(items)
            }
        awaitClose { registration.remove() }
    }

    suspend fun upload(groupId: String, video: Uri, onProgress: (Float) -> Unit): Outcome<Unit> =
        firebaseCall {
            val user = auth.currentUser ?: error("Sign in first")
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(video)?.use { it.readBytes() }
            } ?: error("Couldn't read that clip")

            val ref = moments(groupId).document()
            val url = imageStore.upload(
                StoragePaths.moment(groupId, ref.id), bytes, "video/mp4", onProgress
            )
            ref.set(
                mapOf(
                    "uploadedBy" to user.uid,
                    "uploaderName" to (user.displayName?.takeIf { it.isNotBlank() } ?: "Someone"),
                    "videoUrl" to url,
                    "week" to weekKey(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
}
