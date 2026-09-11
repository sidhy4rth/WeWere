package com.rollapp.shared.data.repository

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.FirestorePaths
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.core.StoragePaths
import com.rollapp.shared.data.storage.ImageStore
import com.rollapp.shared.core.firebaseCall
import com.rollapp.shared.data.remote.snapshots
import com.rollapp.shared.data.remote.toUser
import com.rollapp.shared.data.upload.ImageProcessor
import com.rollapp.shared.domain.model.User
import com.rollapp.shared.domain.repository.NotificationPrefs
import com.rollapp.shared.domain.repository.UserRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@Singleton
class FirestoreUserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val imageStore: ImageStore,
    private val imageProcessor: ImageProcessor
) : UserRepository {

    private fun users() = firestore.collection(FirestorePaths.USERS)

    override fun observeUser(uid: String): Flow<User?> =
        users().document(uid).snapshots().map { it.toUser() }.catch { emit(null) }

    override suspend fun getUser(uid: String): Outcome<User> = firebaseCall {
        users().document(uid).get().await().toUser()
            ?: throw com.rollapp.shared.core.AppErrorException(AppError.Unknown("No such user"))
    }

    override suspend fun updateDisplayName(name: String): Outcome<Unit> {
        val user = auth.currentUser ?: return Outcome.Failure(AppError.NotAuthenticated)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Outcome.Failure(AppError.Validation("Names can't be empty"))

        return firebaseCall {
            user.updateProfile(
                UserProfileChangeRequest.Builder().setDisplayName(trimmed).build()
            ).await()
            users().document(user.uid).set(mapOf("name" to trimmed), SetOptions.merge()).await()
            fanOutProfileChange(user.uid, name = trimmed, photoUrl = null)
        }
    }

    override suspend fun updateProfilePhoto(uri: Uri): Outcome<String> {
        val user = auth.currentUser ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            val processed = imageProcessor.prepareAvatar(uri)
            val url = imageStore.upload(StoragePaths.avatar(user.uid), processed.bytes)

            user.updateProfile(
                UserProfileChangeRequest.Builder().setPhotoUri(Uri.parse(url)).build()
            ).await()
            users().document(user.uid).set(mapOf("photoUrl" to url), SetOptions.merge()).await()
            fanOutProfileChange(user.uid, name = null, photoUrl = url)
            url
        }
    }

    /**
     * Member documents carry a copy of the name and avatar so a member list or a photo
     * caption renders from one read instead of one per person. That copy has to be
     * refreshed when the original changes, which is this fan-out.
     *
     * It is bounded by how many groups one person is in — a handful — and failures are
     * swallowed per group: a stale avatar in one group is not worth failing the whole
     * profile update the user just made.
     */
    private suspend fun fanOutProfileChange(uid: String, name: String?, photoUrl: String?) {
        val memberships = users().document(uid)
            .collection(FirestorePaths.MEMBERSHIPS)
            .get().await()

        for (doc in memberships.documents) {
            val groupId = doc.getString("groupId") ?: doc.id
            val updates = buildMap<String, Any> {
                name?.let { put("name", it) }
                photoUrl?.let { put("photoUrl", it) }
            }
            if (updates.isEmpty()) continue

            runCatching {
                firestore.collection(FirestorePaths.GROUPS).document(groupId)
                    .collection(FirestorePaths.MEMBERS).document(uid)
                    .update(updates).await()
            }
        }
    }

    override suspend fun registerDeviceToken(token: String): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            users().document(uid).collection(FirestorePaths.DEVICES).document(token).set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    override suspend fun unregisterDeviceToken(token: String): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            users().document(uid).collection(FirestorePaths.DEVICES).document(token).delete().await()
        }
    }

    override fun observeNotificationPrefs(): Flow<NotificationPrefs> =
        authUid().flatMapLatest { uid ->
            if (uid == null) return@flatMapLatest flowOf(NotificationPrefs())
            users().document(uid).snapshots()
                .map { doc ->
                    val raw = doc.get("notificationPrefs") as? Map<*, *> ?: return@map NotificationPrefs()
                    NotificationPrefs(
                        newPhotos = raw["newPhotos"] as? Boolean ?: true,
                        reactions = raw["reactions"] as? Boolean ?: true,
                        memberJoined = raw["memberJoined"] as? Boolean ?: true,
                        invites = raw["invites"] as? Boolean ?: true
                    )
                }
                .catch { emit(NotificationPrefs()) }
        }

    override suspend fun updateNotificationPrefs(prefs: NotificationPrefs): Outcome<Unit> {
        val uid = auth.currentUser?.uid ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            users().document(uid).set(
                mapOf(
                    "notificationPrefs" to mapOf(
                        "newPhotos" to prefs.newPhotos,
                        "reactions" to prefs.reactions,
                        "memberJoined" to prefs.memberJoined,
                        "invites" to prefs.invites
                    )
                ),
                SetOptions.merge()
            ).await()
        }
    }

    private fun authUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
}
