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

object StoragePaths {
    const val GROUPS = "groups"
    const val FULL = "full"
    const val THUMBS = "thumbs"
    const val COVERS = "covers"
    const val AVATARS = "avatars"
}

object Limits {
    /** Photos fetched per page in the timeline. */
    const val PHOTO_PAGE_SIZE = 60

    /** Longest edge of the uploaded full-size image, in pixels. */
    const val FULL_IMAGE_MAX_EDGE = 2560

    /** Longest edge of the grid thumbnail. */
    const val THUMBNAIL_MAX_EDGE = 480

    const val FULL_IMAGE_QUALITY = 88
    const val THUMBNAIL_QUALITY = 75

    const val MAX_CAPTION_LENGTH = 140
    const val MAX_GROUP_NAME_LENGTH = 50
    const val MAX_GROUP_DESCRIPTION_LENGTH = 160

    const val INVITE_CODE_LENGTH = 6
    const val MAX_UPLOAD_ATTEMPTS = 5
    const val MAX_GALLERY_SELECTION = 30

    /** Avatars denormalised onto the group doc for the home screen card. */
    const val GROUP_CARD_AVATARS = 4
}
