package com.rollapp.shared.core

/**
 * Every failure the UI can encounter, already translated out of Firebase's
 * exception vocabulary. The UI switches on these; it never sees a raw exception.
 */
sealed class AppError(open val message: String?) {
    data object Offline : AppError("You're offline")
    data object Timeout : AppError("That took too long")
    data object NotAuthenticated : AppError("You need to be signed in")
    data object PermissionDenied : AppError("You don't have access to this")
    data object GroupNotFound : AppError("That group no longer exists")
    data object InvalidInviteCode : AppError("That invite code isn't valid")
    data object InviteExpired : AppError("That invite link has expired")
    data object AlreadyMember : AppError("You're already in this group")
    data object RemovedFromGroup : AppError("You're no longer a member of this group")
    data object PhotoNotFound : AppError("That photo was deleted")
    data object StorageQuotaExceeded : AppError("Storage is full")
    data object UploadFailed : AppError("Upload failed")
    data object CameraUnavailable : AppError("Camera unavailable")
    data class EmailInUse(override val message: String? = "That email is already registered") : AppError(message)
    data class WeakPassword(override val message: String? = "Password must be at least 6 characters") : AppError(message)
    data class InvalidCredentials(override val message: String? = "Wrong email or password") : AppError(message)
    data class Validation(override val message: String?) : AppError(message)
    data class Unknown(override val message: String?) : AppError(message)

    /** Whether retrying the same call unchanged could plausibly succeed. */
    val isRetryable: Boolean
        get() = this is Offline || this is Timeout || this is UploadFailed || this is Unknown
}
