package com.rollapp.shared.ui.navigation

import android.net.Uri

/**
 * Routes as plain strings with typed builders. Arguments are encoded on the way in so
 * a caption or a file uri containing `/` cannot break the path.
 */
object Routes {

    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"

    const val HOME = "home"
    const val CAMERA_TAB = "camera_tab"
    const val ACTIVITY = "activity"
    const val PROFILE = "profile"

    const val CREATE_GROUP = "create_group"

    private const val JOIN_GROUP_BASE = "join_group"
    const val JOIN_GROUP = "$JOIN_GROUP_BASE?code={code}"
    fun joinGroup(code: String? = null): String =
        if (code.isNullOrBlank()) JOIN_GROUP_BASE else "$JOIN_GROUP_BASE?code=${code.encode()}"

    private const val GROUP_BASE = "group"
    const val GROUP = "$GROUP_BASE/{groupId}"
    fun group(groupId: String) = "$GROUP_BASE/${groupId.encode()}"

    private const val GROUP_SETTINGS_BASE = "group_settings"
    const val GROUP_SETTINGS = "$GROUP_SETTINGS_BASE/{groupId}"
    fun groupSettings(groupId: String) = "$GROUP_SETTINGS_BASE/${groupId.encode()}"

    private const val MEMBERS_BASE = "members"
    const val MEMBERS = "$MEMBERS_BASE/{groupId}"
    fun members(groupId: String) = "$MEMBERS_BASE/${groupId.encode()}"

    private const val CAROUSEL_BASE = "carousel"
    const val CAROUSEL =
        "$CAROUSEL_BASE/{groupId}?photoId={photoId}&filter={filter}&slideshow={slideshow}"

    fun carousel(groupId: String, photoId: String, filter: String = "all") =
        "$CAROUSEL_BASE/${groupId.encode()}?photoId=${photoId.encode()}" +
            "&filter=${filter.encode()}&slideshow=false"

    /** Same screen, opened at the top of the roll with autoplay already running. */
    fun slideshow(groupId: String, filter: String = "all") =
        "$CAROUSEL_BASE/${groupId.encode()}?photoId=&filter=${filter.encode()}&slideshow=true"

    private const val CAMERA_BASE = "camera"
    const val CAMERA = "$CAMERA_BASE/{groupId}"
    fun camera(groupId: String) = "$CAMERA_BASE/${groupId.encode()}"

    private const val REVIEW_BASE = "review"
    const val REVIEW = "$REVIEW_BASE/{groupId}?uri={uri}&capturedAt={capturedAt}"
    fun review(groupId: String, uri: String, capturedAt: Long) =
        "$REVIEW_BASE/${groupId.encode()}?uri=${uri.encode()}&capturedAt=$capturedAt"

    const val SCAN_QR = "scan_qr"

    private const val GALLERY_REVIEW_BASE = "gallery_review"
    const val GALLERY_REVIEW = "$GALLERY_REVIEW_BASE/{groupId}"
    fun galleryReview(groupId: String) = "$GALLERY_REVIEW_BASE/${groupId.encode()}"

    private fun String.encode(): String = Uri.encode(this)
}

object NavArgs {
    const val GROUP_ID = "groupId"
    const val PHOTO_ID = "photoId"
    const val CODE = "code"
    const val URI = "uri"
    const val CAPTURED_AT = "capturedAt"
    const val FILTER = "filter"
    const val SLIDESHOW = "slideshow"
}
