package com.rollapp.shared.domain.repository

import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /** Emits the signed-in user, or null when signed out. Never completes. */
    val currentUser: Flow<User?>

    /** Synchronous read of the current uid, for repository-layer queries. */
    fun currentUid(): String?

    suspend fun signInWithGoogle(idToken: String): Outcome<User>
    suspend fun signInWithEmail(email: String, password: String): Outcome<User>
    suspend fun signUpWithEmail(name: String, email: String, password: String): Outcome<User>
    suspend fun signInAnonymously(): Outcome<User>

    /** Upgrades the current anonymous account in place, keeping uid and memberships. */
    suspend fun linkAnonymousToEmail(name: String, email: String, password: String): Outcome<User>
    suspend fun linkAnonymousToGoogle(idToken: String): Outcome<User>

    suspend fun sendPasswordReset(email: String): Outcome<Unit>
    suspend fun signOut(): Outcome<Unit>
    suspend fun deleteAccount(): Outcome<Unit>
}
