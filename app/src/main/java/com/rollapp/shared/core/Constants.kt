package com.rollapp.shared.core

object FirestorePaths {
    const val USERS = "users"
    const val GROUPS = "groups"
    const val MEMBERS = "members"
    const val PHOTOS = "photos"
    const val REACTIONS = "reactions"
    const val ACTIVITY = "activity"
    const val INVITES = "invites"
    const val MEMBERSHIPS = "memberships"
    const val DEVICES = "devices"
}

/**
 * Object paths inside the image bucket. Bucket-relative, no leading slash; the
 * layout is mirrored by the policies in `supabase/storage-policies.sql`.
 */
object StoragePaths {
    const val GROUPS = "groups"
    const val FULL = "full"
    const val THUMBS = "thumbs"
    const val COVERS = "covers"
    const val AVATARS = "avatars"

    fun photo(groupId: String, category: String, photoId: String) =
        "$GROUPS/$groupId/$category/$photoId.jpg"

    fun cover(groupId: String) = "$GROUPS/$groupId/$COVERS/cover.jpg"

    fun avatar(uid: String) = "$AVATARS/$uid.jpg"
}

object Limits {
    /** Photos fetched per page in the timeline. */
    const val PHOTO_PAGE_SIZE = 60

    /** Longest edge of the uploaded full-size image in a free roll, in pixels. */
    const val FULL_IMAGE_MAX_EDGE = 2048

    /**
     * A developed roll keeps (close to) the original: 4096 px is a 12 MP frame, and
     * at quality 92 it still sits well under the bucket's 15 MB object limit.
     */
    const val DEVELOPED_IMAGE_MAX_EDGE = 4096
    const val DEVELOPED_IMAGE_QUALITY = 92

    /**
     * Exposures a free roll holds. Storage is the one real cost of running WeWere,
     * so this is where the free tier stops and "Develop" begins. Mirrored in
     * firestore.rules.
     */
    const val FREE_ROLL_PHOTO_LIMIT = 200

    /** Longest edge of the grid thumbnail. */
    const val THUMBNAIL_MAX_EDGE = 480

    const val FULL_IMAGE_QUALITY = 86
    const val THUMBNAIL_QUALITY = 75

    const val MAX_CAPTION_LENGTH = 140
    const val MAX_GROUP_NAME_LENGTH = 50
    const val MAX_GROUP_DESCRIPTION_LENGTH = 160

    const val INVITE_CODE_LENGTH = 6
    const val MAX_UPLOAD_ATTEMPTS = 5
    /** Android's photo picker refuses anything above 100 (MediaStore.getPickImagesMaxLimit). */
    const val MAX_GALLERY_SELECTION = 100

    /** Avatars denormalised onto the group doc for the home screen card. */
    const val GROUP_CARD_AVATARS = 4
}
