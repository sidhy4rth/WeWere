import Foundation

/// A signed-in person. Mirrors `users/{userId}`.
struct User: Equatable, Hashable {
    var uid: String = ""
    var name: String = ""
    var email: String = ""
    var photoUrl: String? = nil
    var createdAt: Millis = 0
    var isAnonymous: Bool = false

    /// Initials used when there is no profile photo.
    var initials: String { Self.initials(of: name) }

    static func initials(of name: String) -> String {
        let parts = name.trimmingCharacters(in: .whitespaces).split(separator: " ").filter { !$0.isEmpty }.prefix(2)
        let joined = parts.compactMap { $0.first.map { String($0).uppercased() } }.joined()
        return joined.isEmpty ? "?" : joined
    }
}

/// A private shared camera roll. Mirrors `groups/{groupId}`.
struct Group: Equatable, Hashable, Identifiable {
    var id: String = ""
    var name: String = ""
    var description: String? = nil
    var coverPhotoUrl: String? = nil
    var createdBy: String = ""
    var createdAt: Millis = 0
    var memberCount: Int = 0
    var photoCount: Int = 0
    var lastActivityAt: Millis = 0
    var inviteCode: String = ""
    /// Nil means the invite never expires.
    var inviteExpiresAt: Millis? = nil
    /// Denormalised avatars for the home card, so it renders without N extra reads.
    var recentMemberPhotos: [String] = []

    var inviteLink: String { "https://\(inviteHost)/join/\(inviteCode)" }

    var isInviteActive: Bool {
        !inviteCode.trimmingCharacters(in: .whitespaces).isEmpty &&
            (inviteExpiresAt == nil || inviteExpiresAt! > Date.nowMillis)
    }
}

enum MemberRole: String {
    case admin = "ADMIN"
    case member = "MEMBER"

    static func from(_ raw: String?) -> MemberRole {
        guard let raw else { return .member }
        return MemberRole(rawValue: raw.uppercased()) ?? .member
    }
}

/// Mirrors `groups/{groupId}/members/{userId}`. Denormalised so the member
/// list renders from a single collection read.
struct Member: Equatable, Hashable, Identifiable {
    var uid: String = ""
    var name: String = ""
    var photoUrl: String? = nil
    var role: MemberRole = .member
    var joinedAt: Millis = 0
    var photoCount: Int = 0

    var id: String { uid }
    var isAdmin: Bool { role == .admin }
}

/// What a user sees before committing to join. Readable without membership.
struct GroupPreview: Equatable {
    var id: String = ""
    var name: String = ""
    var description: String? = nil
    var coverPhotoUrl: String? = nil
    var memberCount: Int = 0
    var photoCount: Int = 0
    var alreadyMember: Bool = false
}

/// Mirrors `groups/{groupId}/photos/{photoId}`.
struct Photo: Equatable, Hashable, Identifiable {
    var id: String = ""
    var groupId: String = ""
    var uploadedBy: String = ""
    var uploaderName: String = ""
    var uploaderPhotoUrl: String? = nil
    var imageUrl: String = ""
    var thumbnailUrl: String = ""
    var storagePath: String = ""
    var thumbnailStoragePath: String = ""
    var caption: String? = nil
    var width: Int = 0
    var height: Int = 0
    var sizeBytes: Int64 = 0
    var createdAt: Millis = 0
    /// When the camera actually took the shot, if known. May predate upload.
    var capturedAt: Millis? = nil
    var reactionCounts: [String: Int] = [:]
    /// Reaction this device's user has left, resolved separately.
    var myReaction: String? = nil
    /// Who has starred this photo. An array on the photo rather than a subcollection,
    /// because "show me my favourites" then becomes a single indexed
    /// array-contains query instead of a read per photo. Bounded by group size.
    var favoritedBy: [String] = []

    var aspectRatio: CGFloat {
        width > 0 && height > 0 ? CGFloat(width) / CGFloat(height) : 1
    }

    var totalReactions: Int { reactionCounts.values.reduce(0, +) }

    func isFavorited(by uid: String?) -> Bool {
        guard let uid else { return false }
        return favoritedBy.contains(uid)
    }
}

/// What the group feed is currently showing.
enum PhotoFilter: Equatable, Hashable {
    case all
    case favorites
    case byUploader(uid: String, name: String)

    var isActive: Bool { self != .all }
}

/// Encodes the active filter into a navigation argument.
///
/// The carousel has to page over exactly the set the grid was showing — tapping the
/// fifth starred photo must open the fifth starred photo, not the fifth photo overall.
enum PhotoFilterCodec {
    static func encode(_ filter: PhotoFilter) -> String {
        switch filter {
        case .all: return "all"
        case .favorites: return "fav"
        case .byUploader(let uid, let name):
            let encoded = name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? name
            return "by:\(uid):\(encoded)"
        }
    }

    static func decode(_ raw: String?) -> PhotoFilter {
        guard let raw, raw != "all" else { return .all }
        if raw == "fav" { return .favorites }
        if raw.hasPrefix("by:") {
            // Split on the first two colons only; a display name may contain one.
            let rest = raw.dropFirst(3)
            let uid = String(rest.prefix { $0 != ":" })
            let name = rest.contains(":") ? String(rest.drop { $0 != ":" }.dropFirst()) : ""
            if uid.trimmingCharacters(in: .whitespaces).isEmpty { return .all }
            return .byUploader(uid: uid, name: name.removingPercentEncoding ?? name)
        }
        return .all
    }
}

/// The fixed reaction set. Deliberately small — this is not a social network.
enum Reaction: String, CaseIterable, Identifiable {
    case heart, laugh, fire, cry, thumbs

    var id: String { rawValue }
    var key: String { rawValue }

    var emoji: String {
        switch self {
        case .heart: return "❤️"
        case .laugh: return "😂"
        case .fire: return "🔥"
        case .cry: return "😭"
        case .thumbs: return "👍"
        }
    }

    static func fromKey(_ key: String?) -> Reaction? {
        guard let key else { return nil }
        return Reaction(rawValue: key)
    }
}

/// One entry in the chronological timeline: either a date header or a photo.
enum TimelineItem: Equatable, Hashable {
    case header(label: String, key: String)
    case item(Photo)
}

enum ActivityType: String {
    case photosAdded = "PHOTOS_ADDED"
    case memberJoined = "MEMBER_JOINED"
    case memberLeft = "MEMBER_LEFT"
    case reaction = "REACTION"
    case groupCreated = "GROUP_CREATED"
    case unknown = "UNKNOWN"

    static func from(_ raw: String?) -> ActivityType {
        guard let raw else { return .unknown }
        return ActivityType(rawValue: raw.uppercased()) ?? .unknown
    }
}

/// Mirrors `groups/{groupId}/activity/{eventId}`. Drives the Activity tab.
struct ActivityEvent: Equatable, Hashable, Identifiable {
    var id: String = ""
    var groupId: String = ""
    var groupName: String = ""
    var type: ActivityType = .unknown
    var actorId: String = ""
    var actorName: String = ""
    var actorPhotoUrl: String? = nil
    var createdAt: Millis = 0
    var photoCount: Int = 0
    var previewPhotoUrl: String? = nil
    var reactionKey: String? = nil
}

enum UploadState: String, Codable {
    case queued = "QUEUED"
    case uploading = "UPLOADING"
    case failed = "FAILED"
    case completed = "COMPLETED"
    case cancelled = "CANCELLED"
}

/// A photo captured on-device that has not reached Firebase yet. Persisted on disk
/// so a queue survives process death, airplane mode and a dead battery.
struct PendingUpload: Equatable, Hashable, Identifiable {
    var id: String
    var groupId: String
    var localUri: String
    var caption: String?
    var capturedAt: Millis
    var createdAt: Millis
    var state: UploadState
    var progress: Float
    var attemptCount: Int
    var errorMessage: String?

    var isTerminal: Bool { state == .completed || state == .cancelled }
}

struct NotificationPrefs: Equatable {
    var newPhotos: Bool = true
    var reactions: Bool = true
    var memberJoined: Bool = true
    var invites: Bool = true
}
