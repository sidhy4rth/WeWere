package com.rollapp.shared.data.repository

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.FirestorePaths
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.core.firebaseCall
import com.rollapp.shared.domain.model.User
import com.rollapp.shared.di.ApplicationScope
import com.rollapp.shared.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @ApplicationScope private val appScope: CoroutineScope
) : AuthRepository {

    override val currentUser: Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { a ->
            trySend(a.currentUser?.toDomain())
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun currentUid(): String? = auth.currentUser?.uid

    override suspend fun signInWithGoogle(idToken: String): Outcome<User> = firebaseCall {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        val user = result.user ?: throw IllegalStateException("Sign-in returned no user")
        upsertProfile(user).also { profile ->
            // A Google account always has a name; anonymous upgrades may not.
            if (profile.name.isBlank()) updateAuthDisplayName(user, "Someone")
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Outcome<User> {
        val validation = validateEmailPassword(email, password)
        if (validation != null) return Outcome.Failure(validation)
        return firebaseCall {
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: throw IllegalStateException("Sign-in returned no user")
            provisionProfile(user)
        }
    }

    override suspend fun signUpWithEmail(name: String, email: String, password: String): Outcome<User> {
        if (name.isBlank()) return Outcome.Failure(AppError.Validation("What should we call you?"))
        val validation = validateEmailPassword(email, password)
        if (validation != null) return Outcome.Failure(validation)
        return firebaseCall {
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: throw IllegalStateException("Sign-up returned no user")
            updateAuthDisplayName(user, name.trim())
            provisionProfile(user, overrideName = name.trim())
        }
    }

    override suspend fun signInAnonymously(): Outcome<User> = firebaseCall {
        val result = auth.signInAnonymously().await()
        val user = result.user ?: throw IllegalStateException("Anonymous sign-in returned no user")
        provisionProfile(user, overrideName = "Guest")
    }

    override suspend fun linkAnonymousToEmail(name: String, email: String, password: String): Outcome<User> {
        val current = auth.currentUser
            ?: return Outcome.Failure(AppError.NotAuthenticated)
        if (!current.isAnonymous) return Outcome.Failure(AppError.Validation("This account is already permanent"))
        val validation = validateEmailPassword(email, password)
        if (validation != null) return Outcome.Failure(validation)

        return firebaseCall {
            val credential = EmailAuthProvider.getCredential(email.trim(), password)
            val result = current.linkWithCredential(credential).await()
            val user = result.user ?: current
            updateAuthDisplayName(user, name.trim())
            // The uid is preserved by linking, so every existing membership survives.
            provisionProfile(user, overrideName = name.trim(), markPermanent = true)
        }
    }

    override suspend fun linkAnonymousToGoogle(idToken: String): Outcome<User> {
        val current = auth.currentUser ?: return Outcome.Failure(AppError.NotAuthenticated)
        if (!current.isAnonymous) return Outcome.Failure(AppError.Validation("This account is already permanent"))
        return firebaseCall {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = current.linkWithCredential(credential).await()
            val user = result.user ?: current
            provisionProfile(user, markPermanent = true)
        }
    }

    override suspend fun sendPasswordReset(email: String): Outcome<Unit> {
        if (!email.contains("@")) return Outcome.Failure(AppError.Validation("Enter a valid email"))
        return firebaseCall { auth.sendPasswordResetEmail(email.trim()).await() }
    }

    override suspend fun signOut(): Outcome<Unit> = firebaseCall {
        auth.signOut()
    }

    override suspend fun deleteAccount(): Outcome<Unit> {
        val user = auth.currentUser ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            // The profile doc goes first: once the auth user is gone the client can no
            // longer satisfy the `request.auth.uid == userId` rule that guards it.
            firestore.collection(FirestorePaths.USERS).document(user.uid).delete().await()
            user.delete().await()
        }
    }

    /**
     * Runs the profile write on the application scope and waits for it there.
     *
     * The caller is a ViewModel that is about to be destroyed: signing in flips the
     * auth-state listener, which navigates away from the sign-in screen and cancels
     * its scope. Handing the work to a longer-lived scope means the cancellation
     * stops the *waiting*, not the write.
     */
    private suspend fun provisionProfile(
        user: FirebaseUser,
        overrideName: String? = null,
        markPermanent: Boolean = false
    ): User = appScope.async { upsertProfile(user, overrideName, markPermanent) }.await()

    /**
     * Makes sure the signed-in user has a profile document, repairing accounts whose
     * first attempt never landed. Idempotent and safe to call on every launch.
     */
    override suspend fun ensureProfile(): Outcome<Unit> {
        val user = auth.currentUser ?: return Outcome.Failure(AppError.NotAuthenticated)
        return firebaseCall {
            val doc = firestore.collection(FirestorePaths.USERS).document(user.uid).get().await()
            if (!doc.exists()) {
                provisionProfile(user, overrideName = if (user.isAnonymous) "Guest" else null)
            }
            Unit
        }
    }

    /**
     * Creates `users/{uid}` on first sign-in and refreshes the mutable fields on every
     * later one. `merge` keeps fields the client does not own — notification prefs,
     * device tokens — intact.
     */
    private suspend fun upsertProfile(
        user: FirebaseUser,
        overrideName: String? = null,
        markPermanent: Boolean = false
    ): User {
        val doc = firestore.collection(FirestorePaths.USERS).document(user.uid)
        val name = overrideName
            ?: user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
            ?: "Guest"

        val data = mutableMapOf<String, Any?>(
            "name" to name,
            "email" to (user.email ?: ""),
            "photoUrl" to user.photoUrl?.toString(),
            "isAnonymous" to (user.isAnonymous && !markPermanent),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val existing = doc.get().await()
        if (!existing.exists()) {
            data["createdAt"] = FieldValue.serverTimestamp()
        } else if (existing.getString("photoUrl") != null && user.photoUrl == null) {
            // Don't clear a photo the user uploaded themselves just because the
            // auth provider has none.
            data.remove("photoUrl")
        }

        doc.set(data, SetOptions.merge()).await()

        return User(
            uid = user.uid,
            name = name,
            email = user.email.orEmpty(),
            photoUrl = user.photoUrl?.toString() ?: existing.getString("photoUrl"),
            createdAt = System.currentTimeMillis(),
            isAnonymous = user.isAnonymous && !markPermanent
        )
    }

    private suspend fun updateAuthDisplayName(user: FirebaseUser, name: String) {
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name).build()
        ).await()
    }

    private fun validateEmailPassword(email: String, password: String): AppError? = when {
        email.isBlank() || !email.contains("@") -> AppError.Validation("Enter a valid email")
        password.length < 6 -> AppError.WeakPassword()
        else -> null
    }

    private fun FirebaseUser.toDomain() = User(
        uid = uid,
        name = displayName?.takeIf { it.isNotBlank() } ?: email?.substringBefore("@") ?: "Guest",
        email = email.orEmpty(),
        photoUrl = photoUrl?.toString(),
        createdAt = metadata?.creationTimestamp ?: 0L,
        isAnonymous = isAnonymous
    )
}
