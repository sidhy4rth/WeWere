package com.rollapp.shared.ui.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.rollapp.shared.BuildConfig
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google sign-in through Credential Manager.
 *
 * Two passes: the first asks only for accounts already used with this app, which is
 * the one-tap case for a returning user. If there are none, it asks again with the
 * filter off so a first-time user still sees their accounts rather than an empty
 * sheet — the failure mode that makes one-tap look broken.
 */
@Singleton
class GoogleSignInHelper @Inject constructor() {

    // Lint cannot see the GoogleIdTokenCredential usage because it happens inside the
    // CustomCredential type-check in requestToken() below, not in this function.
    @Suppress("CredentialManagerSignInWithGoogle")
    suspend fun getIdToken(context: Context): Outcome<String> {
        if (BuildConfig.WEB_CLIENT_ID.isBlank()) {
            return Outcome.Failure(
                AppError.Validation(
                    "Google Sign-In isn't configured — add WEB_CLIENT_ID to local.properties"
                )
            )
        }

        val credentialManager = CredentialManager.create(context)

        return try {
            val token = requestToken(context, credentialManager, filterByAuthorized = true)
                ?: requestToken(context, credentialManager, filterByAuthorized = false)
                ?: return Outcome.Failure(AppError.Validation("No Google account available"))
            Outcome.Success(token)
        } catch (e: GetCredentialCancellationException) {
            Outcome.Failure(AppError.Validation("Sign-in cancelled"))
        } catch (e: GetCredentialException) {
            Outcome.Failure(AppError.Unknown(e.message ?: "Google sign-in failed"))
        }
    }

    private suspend fun requestToken(
        context: Context,
        credentialManager: CredentialManager,
        filterByAuthorized: Boolean
    ): String? {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setAutoSelectEnabled(filterByAuthorized)
            .setNonce(generateNonce())
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val response = credentialManager.getCredential(context, request)
            val credential = response.credential
            if (credential is androidx.credentials.CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                null
            }
        } catch (e: NoCredentialException) {
            // Expected on the filtered pass for a first-time user.
            null
        }
    }

    /** Binds the token to this request so a stolen one cannot be replayed. */
    private fun generateNonce(): String {
        val raw = UUID.randomUUID().toString() + SecureRandom().nextLong()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
