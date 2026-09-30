package com.rollapp.shared.data.moments

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rollapp.shared.MainActivity
import com.rollapp.shared.R
import java.util.Calendar

/**
 * The daily "your 5 seconds" nudge. Each person picks their own time per roll, and
 * the phone schedules it itself — no server, so it costs nothing to run. Inexact on
 * purpose (no exact-alarm permission): a few minutes' drift is fine for a nudge.
 */
object MomentReminders {
    private const val PREFS = "moment_reminders"

    /** Minutes after midnight, or null when no reminder is set for this roll. */
    fun get(context: Context, groupId: String): Int? =
        prefs(context).getInt(groupId, -1).takeIf { it >= 0 }

    fun set(context: Context, groupId: String, groupName: String, minuteOfDay: Int) {
        prefs(context).edit().putInt(groupId, minuteOfDay).putString("$groupId.name", groupName).apply()
        schedule(context, groupId)
    }

    fun clear(context: Context, groupId: String) {
        prefs(context).edit().remove(groupId).remove("$groupId.name").apply()
        alarms(context).cancel(pending(context, groupId))
    }

    internal fun schedule(context: Context, groupId: String) {
        val minute = get(context, groupId) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending(context, groupId))
    }

    internal fun name(context: Context, groupId: String) =
        prefs(context).getString("$groupId.name", null) ?: "your roll"

    private fun pending(context: Context, groupId: String) = PendingIntent.getBroadcast(
        context, groupId.hashCode(),
        Intent(context, MomentReminderReceiver::class.java).putExtra("groupId", groupId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class MomentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getStringExtra("groupId") ?: return
        val open = PendingIntent.getActivity(
            context, groupId.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("roll://group/$groupId"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, context.getString(R.string.notification_channel_moments))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Your 5 seconds · ${MomentReminders.name(context, groupId)}")
            .setContentText("What's going on right now? Record a 5-second moment.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("m" + groupId).hashCode(), notification) }
        // Same time tomorrow.
        MomentReminders.schedule(context, groupId)
    }
}
