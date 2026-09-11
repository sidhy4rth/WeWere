package com.rollapp.shared.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.rollapp.shared.domain.model.ActivityEvent
import com.rollapp.shared.domain.model.ActivityType
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.domain.model.GroupPreview
import com.rollapp.shared.domain.model.Member
import com.rollapp.shared.domain.model.MemberRole
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.User

/**
 * Hand-written snapshot mapping rather than `toObject<T>()`.
 *
 * Two reasons: `serverTimestamp()` reads back as null on the local echo of a write
 * (before the server confirms), which would silently become epoch 0 and sort a
 * brand-new photo to the bottom of the feed; and reflection-based mapping forces
 * the domain models to carry Firebase annotations, which is exactly the coupling
 * the repository interfaces exist to prevent.
 */

internal fun DocumentSnapshot.millis(field: String, fallback: Long = 0L): Long =
    when (val v = get(field)) {
        is Timestamp -> v.toDate().time
        is Number -> v.toLong()
        // Pending server timestamp on an unconfirmed local write.
        null -> if (metadata.hasPendingWrites()) System.currentTimeMillis() else fallback
        else -> fallback
    }

internal fun DocumentSnapshot.millisOrNull(field: String): Long? =
    when (val v = get(field)) {
        is Timestamp -> v.toDate().time
        is Number -> v.toLong()
        else -> null
    }

internal fun DocumentSnapshot.int(field: String, fallback: Int = 0): Int =
    (get(field) as? Number)?.toInt() ?: fallback

internal fun DocumentSnapshot.long(field: String, fallback: Long = 0L): Long =
    (get(field) as? Number)?.toLong() ?: fallback

internal fun DocumentSnapshot.str(field: String): String? =
    (get(field) as? String)?.takeIf { it.isNotBlank() }

internal fun DocumentSnapshot.bool(field: String, fallback: Boolean = false): Boolean =
    (get(field) as? Boolean) ?: fallback

@Suppress("UNCHECKED_CAST")
internal fun DocumentSnapshot.stringList(field: String): List<String> =
    (get(field) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

internal fun DocumentSnapshot.intMap(field: String): Map<String, Int> {
    val raw = get(field) as? Map<*, *> ?: return emptyMap()
    return raw.entries.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        val count = (v as? Number)?.toInt() ?: return@mapNotNull null
        if (count > 0) key to count else null
    }.toMap()
}

fun DocumentSnapshot.toUser(): User? {
    if (!exists()) return null
    return User(
        uid = id,
        name = str("name") ?: "Someone",
        email = str("email").orEmpty(),
        photoUrl = str("photoUrl"),
        createdAt = millis("createdAt"),
        isAnonymous = bool("isAnonymous")
    )
}

fun DocumentSnapshot.toGroup(): Group? {
    if (!exists()) return null
    return Group(
        id = id,
        name = str("name") ?: "Untitled group",
        description = str("description"),
        coverPhotoUrl = str("coverPhotoUrl"),
        createdBy = str("createdBy").orEmpty(),
        createdAt = millis("createdAt"),
        memberCount = int("memberCount"),
        photoCount = int("photoCount"),
        lastActivityAt = millis("lastActivityAt"),
        inviteCode = str("inviteCode").orEmpty(),
        inviteExpiresAt = millisOrNull("inviteExpiresAt"),
        recentMemberPhotos = stringList("recentMemberPhotos")
    )
}

fun DocumentSnapshot.toMember(): Member? {
    if (!exists()) return null
    return Member(
        uid = id,
        name = str("name") ?: "Someone",
        photoUrl = str("photoUrl"),
        role = MemberRole.from(str("role")),
        joinedAt = millis("joinedAt"),
        photoCount = int("photoCount")
    )
}

/** Built from the public `invites/{code}` doc, which non-members may read. */
fun DocumentSnapshot.toGroupPreview(alreadyMember: Boolean): GroupPreview? {
    if (!exists()) return null
    return GroupPreview(
        id = str("groupId").orEmpty(),
        name = str("groupName") ?: "A group",
        description = str("description"),
        coverPhotoUrl = str("coverPhotoUrl"),
        memberCount = int("memberCount"),
        photoCount = int("photoCount"),
        alreadyMember = alreadyMember
    )
}

fun DocumentSnapshot.toPhoto(groupId: String): Photo? {
    if (!exists()) return null
    val imageUrl = str("imageUrl") ?: return null
    return Photo(
        id = id,
        groupId = groupId,
        uploadedBy = str("uploadedBy").orEmpty(),
        uploaderName = str("uploaderName") ?: "Someone",
        uploaderPhotoUrl = str("uploaderPhotoUrl"),
        imageUrl = imageUrl,
        thumbnailUrl = str("thumbnailUrl") ?: imageUrl,
        storagePath = str("storagePath").orEmpty(),
        thumbnailStoragePath = str("thumbnailStoragePath").orEmpty(),
        caption = str("caption"),
        width = int("width"),
        height = int("height"),
        sizeBytes = long("sizeBytes"),
        createdAt = millis("createdAt"),
        capturedAt = millisOrNull("capturedAt"),
        reactionCounts = intMap("reactionCounts"),
        favoritedBy = stringList("favoritedBy")
    )
}

fun DocumentSnapshot.toActivityEvent(groupId: String): ActivityEvent? {
    if (!exists()) return null
    return ActivityEvent(
        id = id,
        groupId = groupId,
        groupName = str("groupName").orEmpty(),
        type = ActivityType.from(str("type")),
        actorId = str("actorId").orEmpty(),
        actorName = str("actorName") ?: "Someone",
        actorPhotoUrl = str("actorPhotoUrl"),
        createdAt = millis("createdAt"),
        photoCount = int("photoCount"),
        previewPhotoUrl = str("previewPhotoUrl"),
        reactionKey = str("reactionKey")
    )
}
