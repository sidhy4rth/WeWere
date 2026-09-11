package com.rollapp.shared.data.repository

import android.net.Uri
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
import com.rollapp.shared.core.StoragePaths
import com.rollapp.shared.core.firebaseCall
import com.rollapp.shared.data.remote.InviteCodes
import com.rollapp.shared.data.remote.snapshots
import com.rollapp.shared.data.remote.str
import com.rollapp.shared.data.remote.toActivityEvent
import com.rollapp.shared.data.remote.toGroup
import com.rollapp.shared.data.remote.toGroupPreview
import com.rollapp.shared.data.remote.toMember
import com.rollapp.shared.domain.model.ActivityEvent
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.GroupPreview
import com.rollapp.shared.domain.model.Member
import com.rollapp.shared.domain.model.MemberRole
import com.rollapp.shared.domain.repository.GroupRepository
import com.rollapp.shared.data.upload.ImageProcessor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@Singleton
class FirestoreGroupRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val imageProcessor: ImageProcessor
) : GroupRepository {

    private fun groups() = firestore.collection(FirestorePaths.GROUPS)
    private fun group(id: String) = groups().document(id)
    private fun members(gid: String) = group(gid).collection(FirestorePaths.MEMBERS)
    private fun invites() = firestore.collection(FirestorePaths.INVITES)
    private fun memberships(uid: String) =
        firestore.collection(FirestorePaths.USERS).document(uid).collection(FirestorePaths.MEMBERSHIPS)

    private fun uidOrNull() = auth.currentUser?.uid

    // ---------------------------------------------------------------- observers

    /**
     * Membership pointers under the user drive the list, and each group doc gets its
     * own listener. A collection-group query over `members` would also work, but it
     * needs a composite index and still costs one read per group; per-doc listeners
     * keep the security rules simple and give each card its own realtime updates.
     */
    override fun observeMyGroups(): Flow<List<Group>> =
        authUidFlow().flatMapLatest { uid ->
            if (uid == null) return@flatMapLatest flowOf(emptyList())

            memberships(uid)
                .snapshots()
                .map { snap -> snap.documents.mapNotNull { it.str("groupId") ?: it.id } }
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList())

                    val perGroup = ids.map { gid ->
                        group(gid).snapshots()
                            .map { it.toGroup() }
                            // A group deleted out from under us should drop off the list,
                            // not tear down the whole home screen.
                            .catch { emit(null) }
                    }

                    combine(perGroup) { results ->
                        results.filterNotNull().sortedByDescending { it.lastActivityAt }
                    }
                }
        }

    override fun observeGroup(groupId: String): Flow<Group?> =
        group(groupId).snapshots().map { it.toGroup() }.catch { emit(null) }

    override fun observeMembers(groupId: String): Flow<List<Member>> =
        members(groupId)
            .orderBy("joinedAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snap -> snap.documents.mapNotNull { it.toMember() } }
            .catch { emit(emptyList()) }

    override fun observeMembership(groupId: String): Flow<Member?> =
        authUidFlow().flatMapLatest { uid ->
            if (uid == null) flowOf(null)
            else members(groupId).document(uid).snapshots()
                .map { it.toMember() }
                .catch { emit(null) }
        }

    override fun observeActivity(limit: Int): Flow<List<ActivityEvent>> =
        authUidFlow().flatMapLatest { uid ->
            if (uid == null) return@flatMapLatest flowOf(emptyList())

            memberships(uid).snapshots()
                .map { snap -> snap.documents.map { it.str("groupId") ?: it.id } }
                .flatMapLatest { ids ->
                    if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList())

                    val perGroup = ids.map { gid ->
                        group(gid).collection(FirestorePaths.ACTIVITY)
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                            .limit(limit.toLong())
                            .snapshots()
                            .map { snap -> snap.documents.mapNotNull { it.toActivityEvent(gid) } }
                            .catch { emit(emptyList()) }
                    }

                    combine(perGroup) { lists ->
                        lists.flatMap { it }
                            .sortedByDescending { it.createdAt }
                            .take(limit)
                    }
                }
        }

    // ------------------------------------------------------------------ writes

    override suspend fun createGroup(
        name: String,
        description: String?,
        coverUri: Uri?
    ): Outcome<Group> {
        val uid = uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return Outcome.Failure(AppError.Validation("Give your group a name"))
        if (trimmed.length > Limits.MAX_GROUP_NAME_LENGTH) {
            return Outcome.Failure(AppError.Validation("That name is a bit long"))
        }

        return firebaseCall {
            val profile = firestore.collection(FirestorePaths.USERS).document(uid).get().await()
            val displayName = profile.getString("name") ?: "Someone"
            val photoUrl = profile.getString("photoUrl")

            val groupRef = groups().document()
            val gid = groupRef.id
            val code = reserveInviteCode()
            val now = System.currentTimeMillis()

            val batch = firestore.batch()

            batch.set(
                groupRef, mapOf(
                    "name" to trimmed,
                    "description" to description?.trim()?.takeIf { it.isNotEmpty() },
                    "coverPhotoUrl" to null,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "lastActivityAt" to FieldValue.serverTimestamp(),
                    "memberCount" to 1,
                    "photoCount" to 0,
                    "inviteCode" to code,
                    "inviteExpiresAt" to null,
                    "recentMemberPhotos" to listOfNotNull(photoUrl)
                )
            )

            batch.set(
                members(gid).document(uid), mapOf(
                    "name" to displayName,
                    "photoUrl" to photoUrl,
                    "role" to MemberRole.ADMIN.name,
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "photoCount" to 0
                )
            )

            batch.set(
                memberships(uid).document(gid), mapOf(
                    "groupId" to gid,
                    "role" to MemberRole.ADMIN.name,
                    "joinedAt" to FieldValue.serverTimestamp()
                )
            )

            batch.set(
                invites().document(code), mapOf(
                    "groupId" to gid,
                    "groupName" to trimmed,
                    "description" to description?.trim()?.takeIf { it.isNotEmpty() },
                    "coverPhotoUrl" to null,
                    "memberCount" to 1,
                    "photoCount" to 0,
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to null,
                    "revoked" to false
                )
            )

            batch.set(
                group(gid).collection(FirestorePaths.ACTIVITY).document(), mapOf(
                    "type" to "GROUP_CREATED",
                    "groupName" to trimmed,
                    "actorId" to uid,
                    "actorName" to displayName,
                    "actorPhotoUrl" to photoUrl,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            batch.commit().await()

            var coverUrl: String? = null
            if (coverUri != null) {
                // A failed cover upload must not lose the group the user just made.
                coverUrl = runCatching { uploadCover(gid, coverUri) }.getOrNull()
            }

            Group(
                id = gid,
                name = trimmed,
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                coverPhotoUrl = coverUrl,
                createdBy = uid,
                createdAt = now,
                memberCount = 1,
                photoCount = 0,
                lastActivityAt = now,
                inviteCode = code,
                recentMemberPhotos = listOfNotNull(photoUrl)
            )
        }
    }

    /** Picks a code and claims it, retrying on the (rare) collision. */
    private suspend fun reserveInviteCode(): String {
        repeat(8) {
            val candidate = InviteCodes.generate()
            val existing = invites().document(candidate).get().await()
            if (!existing.exists()) return candidate
        }
        // Fall back to a longer code rather than failing the whole creation.
        return InviteCodes.generate(Limits.INVITE_CODE_LENGTH + 3)
    }

    override suspend fun previewByInviteCode(code: String): Outcome<GroupPreview> {
        val uid = uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        val normalised = InviteCodes.normalise(code)
        if (normalised.isEmpty()) return Outcome.Failure(AppError.InvalidInviteCode)

        return firebaseCall {
            val invite = invites().document(normalised).get().await()
            if (!invite.exists()) throw AppErrorException(AppError.InvalidInviteCode)
            if (invite.getBoolean("revoked") == true) throw AppErrorException(AppError.InviteExpired)

            val expiresAt = invite.getTimestamp("expiresAt")?.toDate()?.time
            if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
                throw AppErrorException(AppError.InviteExpired)
            }

            val gid = invite.getString("groupId") ?: throw AppErrorException(AppError.InvalidInviteCode)
            val alreadyMember = members(gid).document(uid).get().await().exists()

            invite.toGroupPreview(alreadyMember)
                ?: throw AppErrorException(AppError.InvalidInviteCode)
        }
    }

    override suspend fun joinByInviteCode(code: String): Outcome<Group> {
        val uid = uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        val normalised = InviteCodes.normalise(code)
        if (normalised.isEmpty()) return Outcome.Failure(AppError.InvalidInviteCode)

        return firebaseCall {
            val invite = invites().document(normalised).get().await()
            if (!invite.exists()) throw AppErrorException(AppError.InvalidInviteCode)
            if (invite.getBoolean("revoked") == true) throw AppErrorException(AppError.InviteExpired)

            val expiresAt = invite.getTimestamp("expiresAt")?.toDate()?.time
            if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
                throw AppErrorException(AppError.InviteExpired)
            }

            val gid = invite.getString("groupId")
                ?: throw AppErrorException(AppError.InvalidInviteCode)

            if (members(gid).document(uid).get().await().exists()) {
                // Already in: treat as success so a re-tapped link just opens the group.
                return@firebaseCall group(gid).get().await().toGroup()
                    ?: throw AppErrorException(AppError.GroupNotFound)
            }

            val profile = firestore.collection(FirestorePaths.USERS).document(uid).get().await()
            val displayName = profile.getString("name") ?: "Someone"
            val photoUrl = profile.getString("photoUrl")

            val batch = firestore.batch()

            batch.set(
                members(gid).document(uid), mapOf(
                    "name" to displayName,
                    "photoUrl" to photoUrl,
                    "role" to MemberRole.MEMBER.name,
                    "joinedAt" to FieldValue.serverTimestamp(),
                    "photoCount" to 0
                )
            )

            batch.set(
                memberships(uid).document(gid), mapOf(
                    "groupId" to gid,
                    "role" to MemberRole.MEMBER.name,
                    "joinedAt" to FieldValue.serverTimestamp()
                )
            )

            // The security rules cap this to a +1 change on exactly these fields, so a
            // joining non-member cannot touch anything else on the group document.
            val groupUpdate = mutableMapOf<String, Any>(
                "memberCount" to FieldValue.increment(1),
                "lastActivityAt" to FieldValue.serverTimestamp()
            )
            if (photoUrl != null) {
                groupUpdate["recentMemberPhotos"] = FieldValue.arrayUnion(photoUrl)
            }
            batch.update(group(gid), groupUpdate)

            batch.set(
                group(gid).collection(FirestorePaths.ACTIVITY).document(), mapOf(
                    "type" to "MEMBER_JOINED",
                    "groupName" to (invite.getString("groupName") ?: ""),
                    "actorId" to uid,
                    "actorName" to displayName,
                    "actorPhotoUrl" to photoUrl,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            batch.commit().await()

            group(gid).get().await().toGroup() ?: throw AppErrorException(AppError.GroupNotFound)
        }
    }

    override suspend fun leaveGroup(groupId: String): Outcome<Unit> {
        val uid = uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            val myMember = members(groupId).document(uid).get().await()
            if (!myMember.exists()) return@firebaseCall

            val displayName = myMember.getString("name") ?: "Someone"
            val wasAdmin = MemberRole.from(myMember.getString("role")) == MemberRole.ADMIN

            val remaining = members(groupId)
                .orderBy("joinedAt", Query.Direction.ASCENDING)
                .get().await()
                .documents
                .filter { it.id != uid }

            if (remaining.isEmpty()) {
                // Last one out deletes the group rather than leaving an orphan.
                deleteGroupInternal(groupId)
                memberships(uid).document(groupId).delete().await()
                return@firebaseCall
            }

            val batch = firestore.batch()
            batch.delete(members(groupId).document(uid))
            batch.delete(memberships(uid).document(groupId))
            batch.update(
                group(groupId), mapOf(
                    "memberCount" to FieldValue.increment(-1),
                    "lastActivityAt" to FieldValue.serverTimestamp()
                )
            )

            // Never strand a group without an admin.
            if (wasAdmin && remaining.none { MemberRole.from(it.getString("role")) == MemberRole.ADMIN }) {
                val heir = remaining.first()
                batch.update(members(groupId).document(heir.id), "role", MemberRole.ADMIN.name)
                batch.update(
                    firestore.collection(FirestorePaths.USERS)
                        .document(heir.id)
                        .collection(FirestorePaths.MEMBERSHIPS)
                        .document(groupId),
                    "role", MemberRole.ADMIN.name
                )
            }

            batch.set(
                group(groupId).collection(FirestorePaths.ACTIVITY).document(), mapOf(
                    "type" to "MEMBER_LEFT",
                    "actorId" to uid,
                    "actorName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )

            batch.commit().await()
        }
    }

    override suspend fun updateGroup(groupId: String, name: String?, description: String?): Outcome<Unit> {
        uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        val updates = mutableMapOf<String, Any?>()
        name?.trim()?.let {
            if (it.isEmpty()) return Outcome.Failure(AppError.Validation("Give your group a name"))
            if (it.length > Limits.MAX_GROUP_NAME_LENGTH) {
                return Outcome.Failure(AppError.Validation("That name is a bit long"))
            }
            updates["name"] = it
        }
        description?.let { updates["description"] = it.trim().takeIf { d -> d.isNotEmpty() } }
        if (updates.isEmpty()) return Outcome.Success(Unit)

        return firebaseCall {
            group(groupId).update(updates).await()
            // Keep the invite preview honest about the renamed group.
            val code = group(groupId).get().await().getString("inviteCode")
            if (code != null && updates.containsKey("name")) {
                runCatching { invites().document(code).update("groupName", updates["name"]).await() }
            }
        }
    }

    override suspend fun updateCoverPhoto(groupId: String, uri: Uri): Outcome<String> {
        uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall { uploadCover(groupId, uri) }
    }

    private suspend fun uploadCover(groupId: String, uri: Uri): String {
        val processed = imageProcessor.prepareCover(uri)
        val ref = storage.reference
            .child(StoragePaths.GROUPS).child(groupId)
            .child(StoragePaths.COVERS).child("cover.jpg")

        ref.putBytes(processed.bytes).await()
        val url = ref.downloadUrl.await().toString()

        group(groupId).update("coverPhotoUrl", url).await()
        runCatching {
            val code = group(groupId).get().await().getString("inviteCode")
            if (code != null) invites().document(code).update("coverPhotoUrl", url).await()
        }
        return url
    }

    override suspend fun removeMember(groupId: String, uid: String): Outcome<Unit> {
        val me = uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        if (me == uid) return Outcome.Failure(AppError.Validation("Use Leave group instead"))

        return firebaseCall {
            val batch = firestore.batch()
            batch.delete(members(groupId).document(uid))
            batch.delete(
                firestore.collection(FirestorePaths.USERS)
                    .document(uid)
                    .collection(FirestorePaths.MEMBERSHIPS)
                    .document(groupId)
            )
            batch.update(
                group(groupId), mapOf(
                    "memberCount" to FieldValue.increment(-1),
                    "lastActivityAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
        }
    }

    override suspend fun regenerateInviteCode(groupId: String, expiresAt: Long?): Outcome<String> {
        uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)

        return firebaseCall {
            val snapshot = group(groupId).get().await()
            val previous = snapshot.getString("inviteCode")
            val newCode = reserveInviteCode()

            val batch = firestore.batch()
            batch.set(
                invites().document(newCode), mapOf(
                    "groupId" to groupId,
                    "groupName" to (snapshot.getString("name") ?: ""),
                    "description" to snapshot.getString("description"),
                    "coverPhotoUrl" to snapshot.getString("coverPhotoUrl"),
                    "memberCount" to (snapshot.getLong("memberCount") ?: 0L),
                    "photoCount" to (snapshot.getLong("photoCount") ?: 0L),
                    "createdBy" to (snapshot.getString("createdBy") ?: ""),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to expiresAt?.let { com.google.firebase.Timestamp(java.util.Date(it)) },
                    "revoked" to false
                )
            )
            batch.update(
                group(groupId), mapOf(
                    "inviteCode" to newCode,
                    "inviteExpiresAt" to expiresAt?.let { com.google.firebase.Timestamp(java.util.Date(it)) }
                )
            )
            if (previous != null) batch.update(invites().document(previous), "revoked", true)

            batch.commit().await()
            newCode
        }
    }

    override suspend fun revokeInvite(groupId: String): Outcome<Unit> {
        uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            val code = group(groupId).get().await().getString("inviteCode") ?: return@firebaseCall
            val batch = firestore.batch()
            batch.update(invites().document(code), "revoked", true)
            batch.update(group(groupId), mapOf("inviteCode" to "", "inviteExpiresAt" to null))
            batch.commit().await()
        }
    }

    override suspend fun deleteGroup(groupId: String): Outcome<Unit> {
        uidOrNull() ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall { deleteGroupInternal(groupId) }
    }

    /**
     * Firestore has no recursive delete from a client, so this walks the subcollections
     * in batches. Storage objects go first: a half-deleted group still readable by its
     * members is recoverable, orphaned image blobs nobody can see are not.
     */
    private suspend fun deleteGroupInternal(groupId: String) {
        val photoDocs = group(groupId).collection(FirestorePaths.PHOTOS).get().await().documents

        for (doc in photoDocs) {
            doc.getString("storagePath")?.let { path ->
                runCatching { storage.reference.child(path).delete().await() }
            }
            doc.getString("thumbnailStoragePath")?.let { path ->
                runCatching { storage.reference.child(path).delete().await() }
            }
        }

        val memberDocs = members(groupId).get().await().documents
        for (member in memberDocs) {
            runCatching {
                firestore.collection(FirestorePaths.USERS)
                    .document(member.id)
                    .collection(FirestorePaths.MEMBERSHIPS)
                    .document(groupId)
                    .delete().await()
            }
        }

        val activityDocs = group(groupId).collection(FirestorePaths.ACTIVITY).get().await().documents
        val inviteCode = group(groupId).get().await().getString("inviteCode")

        val toDelete = buildList {
            addAll(photoDocs.map { it.reference })
            addAll(memberDocs.map { it.reference })
            addAll(activityDocs.map { it.reference })
            if (inviteCode != null) add(invites().document(inviteCode))
            add(group(groupId))
        }

        // 500 writes is the hard batch limit; stay under it.
        toDelete.chunked(450).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
        }

        runCatching { storage.reference.child(StoragePaths.GROUPS).child(groupId).child("covers/cover.jpg").delete().await() }
    }

    /** Re-runs every downstream query when the signed-in user changes. */
    private fun authUidFlow(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
}
