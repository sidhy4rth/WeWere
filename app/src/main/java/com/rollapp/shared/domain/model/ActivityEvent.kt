package com.rollapp.shared.domain.model

enum class ActivityType { PHOTOS_ADDED, MEMBER_JOINED, MEMBER_LEFT, REACTION, GROUP_CREATED, UNKNOWN;

    companion object {
        fun from(raw: String?): ActivityType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Mirrors `groups/{groupId}/activity/{eventId}`. Drives the Activity tab and FCM fan-out. */
data class ActivityEvent(
    val id: String = "",
    val groupId: String = "",
    val groupName: String = "",
    val type: ActivityType = ActivityType.UNKNOWN,
    val actorId: String = "",
    val actorName: String = "",
    val actorPhotoUrl: String? = null,
    val createdAt: Long = 0L,
    val photoCount: Int = 0,
    val previewPhotoUrl: String? = null,
    val reactionKey: String? = null
)
