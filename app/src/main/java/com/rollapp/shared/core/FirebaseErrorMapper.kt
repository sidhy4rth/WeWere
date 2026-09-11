package com.rollapp.shared.core

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException

/** Single place where Firebase's exception zoo becomes an [AppError]. */
object FirebaseErrorMapper {

    fun map(t: Throwable): AppError = when (t) {
        is CancellationException -> throw t

        // Repositories raise this to report a reason the SDK has no code for.
        is AppErrorException -> t.appError

        is FirebaseNetworkException -> AppError.Offline
        is SocketTimeoutException, is TimeoutException -> AppError.Timeout

        is FirebaseAuthWeakPasswordException -> AppError.WeakPassword()
        is FirebaseAuthUserCollisionException -> AppError.EmailInUse()
        is FirebaseAuthInvalidCredentialsException -> AppError.InvalidCredentials()
        is FirebaseAuthInvalidUserException -> AppError.InvalidCredentials()

        is FirebaseFirestoreException -> when (t.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> AppError.PermissionDenied
            FirebaseFirestoreException.Code.NOT_FOUND -> AppError.GroupNotFound
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> AppError.NotAuthenticated
            FirebaseFirestoreException.Code.UNAVAILABLE -> AppError.Offline
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> AppError.Timeout
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> AppError.StorageQuotaExceeded
            else -> AppError.Unknown(t.message)
        }

        is StorageException -> when (t.errorCode) {
            StorageException.ERROR_NOT_AUTHENTICATED -> AppError.NotAuthenticated
            StorageException.ERROR_NOT_AUTHORIZED -> AppError.PermissionDenied
            StorageException.ERROR_OBJECT_NOT_FOUND -> AppError.PhotoNotFound
            StorageException.ERROR_QUOTA_EXCEEDED -> AppError.StorageQuotaExceeded
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> AppError.Offline
            StorageException.ERROR_CANCELED -> AppError.UploadFailed
            else -> AppError.UploadFailed
        }

        is IOException -> AppError.Offline
        else -> AppError.Unknown(t.message)
    }
}

/**
 * Escape hatch for domain failures discovered mid-transaction — an expired invite,
 * a group that vanished between two reads. Throwing keeps the happy path in
 * [firebaseCall] linear instead of threading Outcome through every step.
 */
class AppErrorException(val appError: AppError) : Exception(appError.message)

/** Runs [block], translating anything thrown into an [Outcome.Failure]. */
suspend inline fun <T> firebaseCall(crossinline block: suspend () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (t: Throwable) {
        Outcome.Failure(FirebaseErrorMapper.map(t))
    }
