package com.rollapp.shared.core

import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 11, 14, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `very recent reads as just now`() {
        assertEquals("just now", TimeFormat.relative(now - 5_000, now))
    }

    @Test
    fun `minutes and hours are abbreviated`() {
        assertEquals("5m ago", TimeFormat.relative(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("2h ago", TimeFormat.relative(now - TimeUnit.HOURS.toMillis(2), now))
    }

    @Test
    fun `one day reads as yesterday`() {
        assertEquals("yesterday", TimeFormat.relative(now - TimeUnit.DAYS.toMillis(1), now))
    }

    @Test
    fun `within the week counts days`() {
        assertEquals("3d ago", TimeFormat.relative(now - TimeUnit.DAYS.toMillis(3), now))
    }

    /** A clock skewed slightly ahead of the server must not render "-1m ago". */
    @Test
    fun `a future timestamp degrades gracefully`() {
        assertEquals("just now", TimeFormat.relative(now + 60_000, now))
    }

    @Test
    fun `an absent timestamp renders as empty rather than 1970`() {
        assertEquals("", TimeFormat.relative(0L, now))
    }

    @Test
    fun `section keys group by calendar day, not by elapsed hours`() {
        val lateLastNight = now - TimeUnit.HOURS.toMillis(18)
        val thisMorning = now - TimeUnit.HOURS.toMillis(6)

        assertEquals(TimeFormat.sectionKey(thisMorning), TimeFormat.sectionKey(now))
        assert(TimeFormat.sectionKey(lateLastNight) != TimeFormat.sectionKey(now))
    }

    @Test
    fun `section labels name today and yesterday`() {
        assertEquals("TODAY", TimeFormat.sectionLabel(now, now))
        assertEquals("YESTERDAY", TimeFormat.sectionLabel(now - TimeUnit.DAYS.toMillis(1), now))
    }
}
