import Foundation
import FirebaseFirestore

/// Hand-written snapshot mapping rather than Codable.
///
/// Two reasons: `serverTimestamp()` reads back as nil on the local echo of a write
/// (before the server confirms), which would silently become epoch 0 and sort a
/// brand-new photo to the bottom of the feed; and decoder-based mapping forces the
/// domain models to carry Firebase annotations, which is exactly the coupling the
/// repository interfaces exist to prevent.

extension DocumentSnapshot {
    func millis(_ field: String, fallback: Millis = 0) -> Millis {
        switch get(field) {
        case let ts as Timestamp: return ts.dateValue().millis
        case let n as NSNumber: return n.int64Value
        // Pending server timestamp on an unconfirmed local write.
        case nil: return metadata.hasPendingWrites ? Date.nowMillis : fallback
        default: return fallback
        }
    }

    func millisOrNull(_ field: String) -> Millis? {
        switch get(field) {
        case let ts as Timestamp: return ts.dateValue().millis
        case let n as NSNumber: return n.int64Value
        default: return nil
        }
    }

    func int(_ field: String, fallback: Int = 0) -> Int {
        (get(field) as? NSNumber)?.intValue ?? fallback
    }

    func long(_ field: String, fallback: Int64 = 0) -> Int64 {
        (get(field) as? NSNumber)?.int64Value ?? fallback
    }

    func str(_ field: String) -> String? {
        guard let s = get(field) as? String else { return nil }
        return s.trimmingCharacters(in: .whitespaces).isEmpty ? nil : s
    }

    func bool(_ field: String, fallback: Bool = false) -> Bool {
        (get(field) as? Bool) ?? fallback
    }

    func stringList(_ field: String) -> [String] {
        (get(field) as? [Any])?.compactMap { $0 as? String } ?? []
    }

    func intMap(_ field: String) -> [String: Int] {
        guard let raw = get(field) as? [String: Any] else { return [:] }
        var out: [String: Int] = [:]
        for (k, v) in raw {
            guard let count = (v as? NSNumber)?.intValue, count > 0 else { continue }
            out[k] = count
        }
        return out
    }

    func toUser() -> User? {
        guard exists else { return nil }
        return User(
            uid: documentID,
            name: str("name") ?? "Someone",
            email: str("email") ?? "",
            photoUrl: str("photoUrl"),
            createdAt: millis("createdAt"),
            isAnonymous: bool("isAnonymous")
        )
    }

    func toGroup() -> Group? {
        guard exists else { return nil }
        return Group(
            id: documentID,
            name: str("name") ?? "Untitled group",
            description: str("description"),
            coverPhotoUrl: str("coverPhotoUrl"),
            createdBy: str("createdBy") ?? "",
            createdAt: millis("createdAt"),
            memberCount: int("memberCount"),
            photoCount: int("photoCount"),
            lastActivityAt: millis("lastActivityAt"),
            inviteCode: str("inviteCode") ?? "",
            inviteExpiresAt: millisOrNull("inviteExpiresAt"),
            recentMemberPhotos: stringList("recentMemberPhotos")
        )
    }

    func toMember() -> Member? {
        guard exists else { return nil }
        return Member(
            uid: documentID,
            name: str("name") ?? "Someone",
            photoUrl: str("photoUrl"),
            role: MemberRole.from(str("role")),
            joinedAt: millis("joinedAt"),
            photoCount: int("photoCount")
        )
    }

    /// Built from the public `invites/{code}` doc, which non-members may read.
    func toGroupPreview(alreadyMember: Bool) -> GroupPreview? {
        guard exists else { return nil }
        return GroupPreview(
            id: str("groupId") ?? "",
            name: str("groupName") ?? "A group",
            description: str("description"),
            coverPhotoUrl: str("coverPhotoUrl"),
            memberCount: int("memberCount"),
            photoCount: int("photoCount"),
            alreadyMember: alreadyMember
        )
    }

    func toPhoto(groupId: String) -> Photo? {
        guard exists, let imageUrl = str("imageUrl") else { return nil }
        return Photo(
            id: documentID,
            groupId: groupId,
            uploadedBy: str("uploadedBy") ?? "",
            uploaderName: str("uploaderName") ?? "Someone",
            uploaderPhotoUrl: str("uploaderPhotoUrl"),
            imageUrl: imageUrl,
            thumbnailUrl: str("thumbnailUrl") ?? imageUrl,
            storagePath: str("storagePath") ?? "",
            thumbnailStoragePath: str("thumbnailStoragePath") ?? "",
            caption: str("caption"),
            width: int("width"),
            height: int("height"),
            sizeBytes: long("sizeBytes"),
            createdAt: millis("createdAt"),
            capturedAt: millisOrNull("capturedAt"),
            reactionCounts: intMap("reactionCounts"),
            favoritedBy: stringList("favoritedBy")
        )
    }

    func toActivityEvent(groupId: String) -> ActivityEvent? {
        guard exists else { return nil }
        return ActivityEvent(
            id: documentID,
            groupId: groupId,
            groupName: str("groupName") ?? "",
            type: ActivityType.from(str("type")),
            actorId: str("actorId") ?? "",
            actorName: str("actorName") ?? "Someone",
            actorPhotoUrl: str("actorPhotoUrl"),
            createdAt: millis("createdAt"),
            photoCount: int("photoCount"),
            previewPhotoUrl: str("previewPhotoUrl"),
            reactionKey: str("reactionKey")
        )
    }
}
