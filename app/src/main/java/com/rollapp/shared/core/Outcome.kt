package com.rollapp.shared.core

/**
 * Result type for the repository layer. Deliberately not kotlin.Result — that type
 * cannot be returned from suspend functions cleanly and carries a raw Throwable
 * rather than an error we have already translated into something showable.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val data: T) : Outcome<T>
    data class Failure(val error: AppError) : Outcome<Nothing>

    val dataOrNull: T? get() = (this as? Success)?.data
    val errorOrNull: AppError? get() = (this as? Failure)?.error
    val isSuccess: Boolean get() = this is Success
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(data))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(data)
    return this
}

inline fun <T> Outcome<T>.onFailure(action: (AppError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}
