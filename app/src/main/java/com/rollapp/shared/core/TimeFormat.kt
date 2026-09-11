package com.rollapp.shared.core

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Relative time, in the register people actually use when talking about photos.
 *
 * Deliberately coarse past a week — "3 days ago" is useful, "17 days ago" is not, so
 * that becomes a date. Anything from a previous year keeps the year.
 */
object TimeFormat {

    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return ""
        val delta = now - timestamp
        if (delta < 0) return "just now"

        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        val days = TimeUnit.MILLISECONDS.toDays(delta)

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "yesterday"
            days < 7 -> "${days}d ago"
            else -> absoluteDate(timestamp, now)
        }
    }

    /** Header text for a timeline section: TODAY, YESTERDAY, or a date. */
    fun sectionLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }

        return when {
            isSameDay(then, today) -> "TODAY"
            isSameDay(then, yesterday) -> "YESTERDAY"
            else -> absoluteDate(timestamp, now).uppercase(Locale.getDefault())
        }
    }

    /** Stable key so two photos from the same day land in the same section. */
    fun sectionKey(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return "%04d-%03d".format(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR))
    }

    fun absoluteDate(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val pattern = if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            "MMMM d"
        } else {
            "MMMM d, yyyy"
        }
        return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(then.time)
    }

    /** Full stamp for the photo details sheet. */
    fun fullTimestamp(timestamp: Long): String =
        java.text.SimpleDateFormat("d MMM yyyy 'at' h:mm a", Locale.getDefault())
            .format(java.util.Date(timestamp))

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
