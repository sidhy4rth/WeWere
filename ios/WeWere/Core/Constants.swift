import Foundation

enum FirestorePaths {
    static let users = "users"
    static let groups = "groups"
    static let members = "members"
    static let photos = "photos"
    static let reactions = "reactions"
    static let activity = "activity"
    static let invites = "invites"
    static let memberships = "memberships"
    static let devices = "devices"
}

/// Object paths inside the image bucket. Bucket-relative, no leading slash; the
/// layout is mirrored by the policies in `supabase/storage-policies.sql`.
enum StoragePaths {
    static let groups = "groups"
    static let full = "full"
    static let thumbs = "thumbs"
    static let covers = "covers"
    static let avatars = "avatars"

    static func photo(groupId: String, category: String, photoId: String) -> String {
        "\(groups)/\(groupId)/\(category)/\(photoId).jpg"
    }

    static func cover(groupId: String) -> String { "\(groups)/\(groupId)/\(covers)/cover.jpg" }

    static func avatar(uid: String) -> String { "\(avatars)/\(uid).jpg" }
}

enum Limits {
    /// Photos fetched per page in the timeline.
    static let photoPageSize = 60

    /// Longest edge of the uploaded full-size image, in pixels.
    static let fullImageMaxEdge = 2560

    /// Longest edge of the grid thumbnail.
    static let thumbnailMaxEdge = 480

    static let fullImageQuality = 88
    static let thumbnailQuality = 75

    static let maxCaptionLength = 140
    static let maxGroupNameLength = 50
    static let maxGroupDescriptionLength = 160

    static let inviteCodeLength = 6
    static let maxUploadAttempts = 5
    /// Same cap as Android's photo picker, so a pick behaves the same on both.
    static let maxGallerySelection = 100

    /// Avatars denormalised onto the group doc for the home screen card.
    static let groupCardAvatars = 4
}

/// The site that serves invite links; the site's /join page and this must agree.
let inviteHost = "wewere.vercel.app"

/// Milliseconds since the epoch, the unit every timestamp in the app uses.
typealias Millis = Int64

extension Date {
    var millis: Millis { Millis((timeIntervalSince1970 * 1000).rounded()) }
    init(millis: Millis) { self.init(timeIntervalSince1970: TimeInterval(millis) / 1000) }
    static var nowMillis: Millis { Date().millis }
}

extension Int64 {
    /// `System.currentTimeMillis()`, usable as a default argument of type `Millis`.
    static var nowMillis: Millis { Date().millis }
}
