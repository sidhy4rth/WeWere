package com.rollapp.shared.domain.model

/** Mirrors `groups/{groupId}/photos/{photoId}`. */
data class Photo(
    val id: String = "",
    val groupId: String = "",
    val uploadedBy: String = "",
    val uploaderName: String = "",
    val uploaderPhotoUrl: String? = null,
    val imageUrl: String = "",
    val thumbnailUrl: String = "",
    val storagePath: String = "",
    val thumbnailStoragePath: String = "",
    val caption: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val createdAt: Long = 0L,
    /** When the camera actually took the shot, if known. May predate upload. */
    val capturedAt: Long? = null,
    val reactionCounts: Map<String, Int> = emptyMap(),
    /** Reaction this device's user has left, resolved separately. */
    val myReaction: String? = null
) {
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    val totalReactions: Int get() = reactionCounts.values.sum()
}

/** The fixed reaction set. Deliberately small — this is not a social network. */
enum class Reaction(val key: String, val emoji: String) {
    HEART("heart", "❤️"),
    LAUGH("laugh", "😂"),
    FIRE("fire", "🔥"),
    CRY("cry", "😭"),
    THUMBS("thumbs", "👍");

    companion object {
        fun fromKey(key: String?): Reaction? = entries.firstOrNull { it.key == key }
    }
}

/** One entry in the chronological timeline: either a date header or a photo. */
sealed interface TimelineItem {
    data class Header(val label: String, val key: String) : TimelineItem
    data class Item(val photo: Photo) : TimelineItem
}
