package com.rollapp.shared.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rollapp.shared.MainActivity
import com.rollapp.shared.R
import com.rollapp.shared.domain.repository.UserRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives group notifications.
 *
 * Messages are sent data-only so this class decides how they are presented. That
 * matters for a shared camera roll: eight friends uploading during one evening would
 * otherwise produce eight separate notifications, so photos from the same group
 * collapse onto a single notification id and replace each other.
 */
@AndroidEntryPoint
class RollMessagingService : FirebaseMessagingService() {

    @Inject lateinit var userRepository: UserRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Tokens rotate; a stale one means silently missing every future notification.
        scope.launch { runCatching { userRepository.registerDeviceToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val type = data["type"] ?: return
        val groupId = data["groupId"] ?: return
        val groupName = data["groupName"].orEmpty()
        val actorName = data["actorName"] ?: "Someone"

        val (channel, title, body) = when (type) {
            "PHOTOS_ADDED" -> {
                val count = data["photoCount"]?.toIntOrNull() ?: 1
                val noun = if (count == 1) "a new photo" else "$count new photos"
                Triple(
                    getString(R.string.notification_channel_photos),
                    groupName,
                    "$actorName added $noun"
                )
            }

            "REACTION" -> Triple(
                getString(R.string.notification_channel_activity),
                groupName,
                "$actorName reacted ${data["reaction"].orEmpty()} to your photo"
            )

            "MEMBER_JOINED" -> Triple(
                getString(R.string.notification_channel_activity),
                groupName,
                "$actorName joined"
            )

            else -> return
        }

        show(channel, groupId, title, body)
    }

    private fun show(channelId: String, groupId: String, title: String, body: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("roll://group/$groupId"),
            this,
            MainActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Keyed on the group, so a busy evening updates one notification rather than
        // stacking a dozen.
        NotificationManagerCompat.from(this).notify(groupId.hashCode(), notification)
    }
}
