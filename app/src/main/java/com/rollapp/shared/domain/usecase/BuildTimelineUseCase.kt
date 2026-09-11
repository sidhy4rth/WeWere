package com.rollapp.shared.domain.usecase

import com.rollapp.shared.core.TimeFormat
import com.rollapp.shared.domain.model.Photo
import com.rollapp.shared.domain.model.TimelineItem
import javax.inject.Inject

/**
 * Slices a photo list into dated sections.
 *
 * Sections are cut on the day the photo was *taken* where that is known, falling back
 * to upload time. On a trip everyone empties their camera roll at the hotel that
 * night, and grouping those by upload time would file a week of photos under one
 * heading — which is precisely the memory the timeline is supposed to preserve.
 *
 * The list is re-sorted on that same stamp first. Sectioning on capture time while
 * the feed arrives ordered by upload time would emit headers out of order and repeat
 * a day every time someone uploaded an older photo.
 */
class BuildTimelineUseCase @Inject constructor() {

    operator fun invoke(
        photos: List<Photo>,
        now: Long = System.currentTimeMillis()
    ): List<TimelineItem> {
        if (photos.isEmpty()) return emptyList()

        val ordered = photos.sortedWith(
            compareByDescending<Photo> { it.capturedAt ?: it.createdAt }
                // Two shots in the same burst share a timestamp; id keeps the order stable
                // so the grid does not reshuffle on every snapshot.
                .thenByDescending { it.id }
        )

        val result = ArrayList<TimelineItem>(ordered.size + 8)
        var currentKey: String? = null

        for (photo in ordered) {
            val stamp = photo.capturedAt ?: photo.createdAt
            val key = TimeFormat.sectionKey(stamp)
            if (key != currentKey) {
                result += TimelineItem.Header(label = TimeFormat.sectionLabel(stamp, now), key = key)
                currentKey = key
            }
            result += TimelineItem.Item(photo)
        }
        return result
    }

    /** The flat, ordered photo list behind the timeline — what the carousel pages over. */
    fun orderedPhotos(photos: List<Photo>): List<Photo> =
        photos.sortedWith(
            compareByDescending<Photo> { it.capturedAt ?: it.createdAt }
                .thenByDescending { it.id }
        )
}
