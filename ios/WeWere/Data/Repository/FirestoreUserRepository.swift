import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore

final class FirestoreUserRepository: UserRepository {

    private let firestore: Firestore
    private let auth: Auth
    private let imageStore: ImageStore
    private let imageProcessor: ImageProcessor

    init(firestore: Firestore, auth: Auth, imageStore: ImageStore, imageProcessor: ImageProcessor) {
        self.firestore = firestore
        self.auth = auth
        self.imageStore = imageStore
        self.imageProcessor = imageProcessor
    }

    private func users() -> CollectionReference { firestore.collection(FirestorePaths.users) }

    func observeUser(uid: String) -> Stream<User?> {
        users().document(uid).snapshots().map { $0.toUser() }.orElse(nil)
    }

    func getUser(uid: String) async -> Outcome<User> {
        await firebaseCall {
            guard let user = try await users().document(uid).getDocument().toUser() else {
                throw AppErrorException(.unknown("No such user"))
            }
            return user
        }
    }

    func updateDisplayName(_ name: String) async -> Outcome<Void> {
        guard let user = auth.currentUser else { return .failure(.notAuthenticated) }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return .failure(.validation("Names can't be empty")) }

        return await firebaseCall {
            let change = user.createProfileChangeRequest()
            change.displayName = trimmed
            try await change.commitChanges()
            try await users().document(user.uid).setData(["name": trimmed], merge: true)
            await fanOutProfileChange(uid: user.uid, name: trimmed, photoUrl: nil)
        }
    }

    func updateProfilePhoto(url: URL) async -> Outcome<String> {
        guard let user = auth.currentUser else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            let processed = try imageProcessor.prepareAvatar(url: url)
            let remote = try await imageStore.upload(path: StoragePaths.avatar(uid: user.uid), bytes: processed.bytes)

            let change = user.createProfileChangeRequest()
            change.photoURL = URL(string: remote)
            try await change.commitChanges()
            try await users().document(user.uid).setData(["photoUrl": remote], merge: true)
            await fanOutProfileChange(uid: user.uid, name: nil, photoUrl: remote)
            return remote
        }
    }

    /// Member documents carry a copy of the name and avatar so a member list or a photo
    /// caption renders from one read instead of one per person. That copy has to be
    /// refreshed when the original changes, which is this fan-out.
    ///
    /// It is bounded by how many groups one person is in — a handful — and failures are
    /// swallowed per group: a stale avatar in one group is not worth failing the whole
    /// profile update the user just made.
    private func fanOutProfileChange(uid: String, name: String?, photoUrl: String?) async {
        guard let memberships = try? await users().document(uid).collection(FirestorePaths.memberships).getDocuments() else { return }

        for doc in memberships.documents {
            let groupId = doc.str("groupId") ?? doc.documentID
            var updates: [String: Any] = [:]
            if let name { updates["name"] = name }
            if let photoUrl { updates["photoUrl"] = photoUrl }
            if updates.isEmpty { continue }

            _ = try? await firestore.collection(FirestorePaths.groups).document(groupId)
                .collection(FirestorePaths.members).document(uid)
                .updateData(updates)
        }
    }

    func observeNotificationPrefs() -> Stream<NotificationPrefs> {
        auth.uidStream().flatMapLatest { [users] uid -> Stream<NotificationPrefs> in
            guard let uid else { return Just(NotificationPrefs()).eraseToAnyPublisher() }
            return users().document(uid).snapshots()
                .map { doc -> NotificationPrefs in
                    guard let raw = doc.get("notificationPrefs") as? [String: Any] else { return NotificationPrefs() }
                    return NotificationPrefs(
                        newPhotos: raw["newPhotos"] as? Bool ?? true,
                        reactions: raw["reactions"] as? Bool ?? true,
                        memberJoined: raw["memberJoined"] as? Bool ?? true,
                        invites: raw["invites"] as? Bool ?? true
                    )
                }
                .orElse(NotificationPrefs())
        }
    }

    func updateNotificationPrefs(_ prefs: NotificationPrefs) async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            try await users().document(uid).setData([
                "notificationPrefs": [
                    "newPhotos": prefs.newPhotos,
                    "reactions": prefs.reactions,
                    "memberJoined": prefs.memberJoined,
                    "invites": prefs.invites
                ]
            ], merge: true)
        }
    }
}
