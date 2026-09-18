import Foundation

/// Slices a photo list into dated sections.
///
/// Sections are cut on the day the photo was *taken* where that is known, falling back
/// to upload time. On a trip everyone empties their camera roll at the hotel that
/// night, and grouping those by upload time would file a week of photos under one
/// heading — which is precisely the memory the timeline is supposed to preserve.
///
/// The list is re-sorted on that same stamp first. Sectioning on capture time while
/// the feed arrives ordered by upload time would emit headers out of order and repeat
/// a day every time someone uploaded an older photo.
enum BuildTimeline {

    static func build(_ photos: [Photo], now: Millis = .nowMillis) -> [TimelineItem] {
        if photos.isEmpty { return [] }

        let ordered = orderedPhotos(photos)
        var result: [TimelineItem] = []
        result.reserveCapacity(ordered.count + 8)
        var currentKey: String? = nil

        for photo in ordered {
            let stamp = photo.capturedAt ?? photo.createdAt
            let key = TimeFormat.sectionKey(stamp)
            if key != currentKey {
                result.append(.header(label: TimeFormat.sectionLabel(stamp, now: now), key: key))
                currentKey = key
            }
            result.append(.item(photo))
        }
        return result
    }

    /// The flat, ordered photo list behind the timeline — what the carousel pages over.
    static func orderedPhotos(_ photos: [Photo]) -> [Photo] {
        photos.sorted { a, b in
            let sa = a.capturedAt ?? a.createdAt
            let sb = b.capturedAt ?? b.createdAt
            if sa != sb { return sa > sb }
            // Two shots in the same burst share a timestamp; id keeps the order stable
            // so the grid does not reshuffle on every snapshot.
            return a.id > b.id
        }
    }
}
