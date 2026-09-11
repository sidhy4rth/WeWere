package com.rollapp.shared.domain.repository

import android.net.Uri
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun observeUser(uid: String): Flow<User?>
    suspend fun getUser(uid: String): Outcome<User>
    suspend fun updateDisplayName(name: String): Outcome<Unit>
    suspend fun updateProfilePhoto(uri: Uri): Outcome<String>

    /** Registers this device's FCM token so the fan-out function can reach it. */
    suspend fun registerDeviceToken(token: String): Outcome<Unit>
    suspend fun unregisterDeviceToken(token: String): Outcome<Unit>

    fun observeNotificationPrefs(): Flow<NotificationPrefs>
    suspend fun updateNotificationPrefs(prefs: NotificationPrefs): Outcome<Unit>
}

data class NotificationPrefs(
    val newPhotos: Boolean = true,
    val reactions: Boolean = true,
    val memberJoined: Boolean = true,
    val invites: Boolean = true
)
