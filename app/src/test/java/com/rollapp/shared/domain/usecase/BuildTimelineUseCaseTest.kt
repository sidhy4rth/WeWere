package com.rollapp.shared.domain.usecase

import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.TimelineItem
import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTimelineUseCaseTest {

    private val useCase = BuildTimelineUseCase()

    /** A fixed "now" so the tests do not drift across midnight. */
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 11, 14, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun daysAgo(days: Int) = now - TimeUnit.DAYS.toMillis(days.toLong())

    private fun photo(
        id: String,
        createdAt: Long,
        capturedAt: Long? = null
    ) = Photo(
        id = id,
        groupId = "g",
        uploadedBy = "u",
        imageUrl = "http://x/$id.jpg",
        createdAt = createdAt,
        capturedAt = capturedAt
    )

    @Test
    fun `empty input produces empty timeline`() {
        assertEquals(emptyList<TimelineItem>(), useCase(emptyList(), now))
    }

    @Test
    fun `photos from today land under TODAY`() {
        val result = useCase(listOf(photo("a", now)), now)

        assertEquals(2, result.size)
        assertEquals("TODAY", (result[0] as TimelineItem.Header).label)
        assertEquals("a", (result[1] as TimelineItem.Item).photo.id)
    }

    @Test
    fun `yesterday gets its own header`() {
        val result = useCase(
            listOf(photo("today", now), photo("yesterday", daysAgo(1))),
            now
        )

        val headers = result.filterIsInstance<TimelineItem.Header>().map { it.label }
        assertEquals(listOf("TODAY", "YESTERDAY"), headers)
    }

    @Test
    fun `several photos on one day share a single header`() {
        val result = useCase(
            listOf(
                photo("a", now),
                photo("b", now - TimeUnit.HOURS.toMillis(2)),
                photo("c", now - TimeUnit.HOURS.toMillis(4))
            ),
            now
        )

        assertEquals(1, result.filterIsInstance<TimelineItem.Header>().size)
        assertEquals(3, result.filterIsInstance<TimelineItem.Item>().size)
    }

    /**
     * The bug this guards: the feed arrives ordered by upload time, but sections are
     * cut on capture time. Without re-sorting, a photo uploaded now but taken days ago
     * emits a second, out-of-order header for a day already passed.
     */
    @Test
    fun `a photo uploaded now but captured earlier sorts by capture time`() {
        val result = useCase(
            listOf(
                photo("uploadedNowTakenLastWeek", createdAt = now, capturedAt = daysAgo(6)),
                photo("takenToday", createdAt = now - 1000, capturedAt = now)
            ),
            now
        )

        val headers = result.filterIsInstance<TimelineItem.Header>()
        assertEquals("headers must not repeat a day", headers.size, headers.distinctBy { it.key }.size)
        assertEquals("TODAY", headers.first().label)

        // The older capture must come after the newer one.
        val ids = result.filterIsInstance<TimelineItem.Item>().map { it.photo.id }
        assertEquals(listOf("takenToday", "uploadedNowTakenLastWeek"), ids)
    }

    @Test
    fun `headers stay in strictly descending date order`() {
        val result = useCase(
            listOf(
                photo("a", daysAgo(3)),
                photo("b", now),
                photo("c", daysAgo(1)),
                photo("d", daysAgo(10))
            ),
            now
        )

        val keys = result.filterIsInstance<TimelineItem.Header>().map { it.key }
        assertEquals(keys.sortedDescending(), keys)
    }

    @Test
    fun `ordering is stable for photos sharing a timestamp`() {
        val shared = now - 5000
        val first = useCase.orderedPhotos(
            listOf(photo("b", shared), photo("a", shared), photo("c", shared))
        ).map { it.id }
        val second = useCase.orderedPhotos(
            listOf(photo("c", shared), photo("b", shared), photo("a", shared))
        ).map { it.id }

        assertEquals(first, second)
    }

    @Test
    fun `orderedPhotos matches the order items appear in the timeline`() {
        val photos = listOf(
            photo("a", daysAgo(2)),
            photo("b", now),
            photo("c", daysAgo(1), capturedAt = daysAgo(5))
        )

        val fromTimeline = useCase(photos, now)
            .filterIsInstance<TimelineItem.Item>()
            .map { it.photo.id }

        assertEquals(useCase.orderedPhotos(photos).map { it.id }, fromTimeline)
    }

    @Test
    fun `older photos fall back to an absolute date`() {
        val result = useCase(listOf(photo("old", daysAgo(40))), now)
        val label = (result[0] as TimelineItem.Header).label

        assertTrue("expected a date, got '$label'", label != "TODAY" && label != "YESTERDAY")
    }
}
