package com.rollapp.shared.domain.model

/** A signed-in person. Mirrors `users/{userId}`. */
data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val createdAt: Long = 0L,
    val isAnonymous: Boolean = false
) {
    /** Initials used when there is no profile photo. */
    val initials: String
        get() = name.trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
}
