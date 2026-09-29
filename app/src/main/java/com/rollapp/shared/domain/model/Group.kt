package com.rollapp.shared.domain.model


/** A private shared camera roll. Mirrors `groups/{groupId}`. */
data class Group(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val coverPhotoUrl: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val memberCount: Int = 0,
    val photoCount: Int = 0,
    val lastActivityAt: Long = 0L,
    val inviteCode: String = "",
    /** Null means the invite never expires. */
    val inviteExpiresAt: Long? = null,
    /** Denormalised avatars for the home card, so it renders without N extra reads. */
    val recentMemberPhotos: List<String> = emptyList(),
    /**
     * A developed roll has been paid for: no exposure limit, near-original quality
     * and a whole-roll download for every member. Only the server sets it, after
     * checking the purchase with RevenueCat; the rules refuse it from any client.
     */
    val developed: Boolean = false,
    val developedBy: String? = null,
    val developedAt: Long? = null,
    /**
     * Exposures this roll holds before it must be developed. Null for rolls made
     * before the limit existed — those were promised unlimited and keep it.
     */
    val exposureLimit: Int? = null
) {
    val inviteLink: String get() = "https://$INVITE_HOST/join/$inviteCode"

    /** Exposures left before a free roll is full. Null when there is no limit. */
    val exposuresLeft: Int?
        get() = if (developed || exposureLimit == null) null
        else (exposureLimit - photoCount).coerceAtLeast(0)

    /** An undeveloped roll from before the exposure limit: unlimited, but still developable. */
    val isEarlyRoll: Boolean get() = !developed && exposureLimit == null

    val isFull: Boolean get() = exposuresLeft == 0

    val isInviteActive: Boolean
        get() = inviteCode.isNotBlank() &&
            (inviteExpiresAt == null || inviteExpiresAt > System.currentTimeMillis())
}

enum class MemberRole { ADMIN, MEMBER;

    companion object {
        fun from(raw: String?): MemberRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEMBER
    }
}

/** Mirrors `groups/{groupId}/members/{userId}`. Denormalised so the member
 *  list renders from a single collection read. */
data class Member(
    val uid: String = "",
    val name: String = "",
    val photoUrl: String? = null,
    val role: MemberRole = MemberRole.MEMBER,
    val joinedAt: Long = 0L,
    val photoCount: Int = 0
) {
    val isAdmin: Boolean get() = role == MemberRole.ADMIN
}

/** What a user sees before committing to join. Readable without membership. */
data class GroupPreview(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val coverPhotoUrl: String? = null,
    val memberCount: Int = 0,
    val photoCount: Int = 0,
    val alreadyMember: Boolean = false
)

/** The site that serves invite links; the manifest and assetlinks.json must agree. */
const val INVITE_HOST = "wewere.vercel.app"
