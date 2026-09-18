import Foundation

/// Relative time, in the register people actually use when talking about photos.
///
/// Deliberately coarse past a week — "3 days ago" is useful, "17 days ago" is not, so
/// that becomes a date. Anything from a previous year keeps the year.
enum TimeFormat {

    static func relative(_ timestamp: Millis, now: Millis = .nowMillis) -> String {
        if timestamp <= 0 { return "" }
        let delta = now - timestamp
        if delta < 0 { return "just now" }

        let minutes = delta / 60_000
        let hours = delta / 3_600_000
        let days = delta / 86_400_000

        switch true {
        case minutes < 1: return "just now"
        case minutes < 60: return "\(minutes)m ago"
        case hours < 24: return "\(hours)h ago"
        case days == 1: return "yesterday"
        case days < 7: return "\(days)d ago"
        default: return absoluteDate(timestamp, now: now)
        }
    }

    /// Header text for a timeline section: TODAY, YESTERDAY, or a date.
    static func sectionLabel(_ timestamp: Millis, now: Millis = .nowMillis) -> String {
        let cal = Calendar.current
        let then = Date(millis: timestamp)
        let today = Date(millis: now)
        let yesterday = cal.date(byAdding: .day, value: -1, to: today) ?? today

        if cal.isDate(then, inSameDayAs: today) { return "TODAY" }
        if cal.isDate(then, inSameDayAs: yesterday) { return "YESTERDAY" }
        return absoluteDate(timestamp, now: now).uppercased()
    }

    /// Stable key so two photos from the same day land in the same section.
    static func sectionKey(_ timestamp: Millis) -> String {
        let cal = Calendar.current
        let date = Date(millis: timestamp)
        let year = cal.component(.year, from: date)
        let day = cal.ordinality(of: .day, in: .year, for: date) ?? 0
        return String(format: "%04d-%03d", year, day)
    }

    static func absoluteDate(_ timestamp: Millis, now: Millis = .nowMillis) -> String {
        let cal = Calendar.current
        let then = Date(millis: timestamp)
        let sameYear = cal.component(.year, from: then) == cal.component(.year, from: Date(millis: now))
        let f = DateFormatter()
        f.locale = .current
        f.dateFormat = sameYear ? "MMMM d" : "MMMM d, yyyy"
        return f.string(from: then)
    }

    /// Full stamp for the photo details sheet.
    static func fullTimestamp(_ timestamp: Millis) -> String {
        let f = DateFormatter()
        f.locale = .current
        f.dateFormat = "d MMM yyyy 'at' h:mm a"
        return f.string(from: Date(millis: timestamp))
    }
}
