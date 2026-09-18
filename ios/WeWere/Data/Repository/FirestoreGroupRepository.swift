import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore

final class FirestoreGroupRepository: GroupRepository {

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

    private func groups() -> CollectionReference { firestore.collection(FirestorePaths.groups) }
    private func group(_ id: String) -> DocumentReference { groups().document(id) }
    private func members(_ gid: String) -> CollectionReference { group(gid).collection(FirestorePaths.members) }
    private func invites() -> CollectionReference { firestore.collection(FirestorePaths.invites) }
    private func memberships(_ uid: String) -> CollectionReference {
        firestore.collection(FirestorePaths.users).document(uid).collection(FirestorePaths.memberships)
    }

    private func uidOrNull() -> String? { auth.currentUser?.uid }

    // ---------------------------------------------------------------- observers

    /// Membership pointers under the user drive the list, and each group doc gets its
    /// own listener. A collection-group query over `members` would also work, but it
    /// needs a composite index and still costs one read per group; per-doc listeners
    /// keep the security rules simple and give each card its own realtime updates.
    func observeMyGroups() -> Stream<[Group]> {
        auth.uidStream().flatMapLatest { [self] uid -> Stream<[Group]> in
            guard let uid else { return Just([]).eraseToAnyPublisher() }

            return memberships(uid).snapshots()
                .map { snap in snap.documents.map { $0.str("groupId") ?? $0.documentID } }
                .orElse([])
                .flatMapLatest { [self] ids -> Stream<[Group]> in
                    if ids.isEmpty { return Just([]).eraseToAnyPublisher() }

                    let perGroup: [Stream<Group?>] = ids.map { gid in
                        group(gid).snapshots()
                            .map { $0.toGroup() }
                            // A group deleted out from under us should drop off the list,
                            // not tear down the whole home screen.
                            .orElse(nil)
                    }

                    return combineLatestAll(perGroup)
                        .map { $0.compactMap { $0 }.sorted { $0.lastActivityAt > $1.lastActivityAt } }
                        .eraseToAnyPublisher()
                }
        }
    }

    func observeGroup(groupId: String) -> Stream<Group?> {
        group(groupId).snapshots().map { $0.toGroup() }.orElse(nil)
    }

    func observeMembers(groupId: String) -> Stream<[Member]> {
        members(groupId)
            .order(by: "joinedAt", descending: false)
            .snapshots()
            .map { snap in snap.documents.compactMap { $0.toMember() } }
            .orElse([])
    }

    func observeMembership(groupId: String) -> Stream<Member?> {
        auth.uidStream().flatMapLatest { [self] uid -> Stream<Member?> in
            guard let uid else { return Just(nil).eraseToAnyPublisher() }
            return members(groupId).document(uid).snapshots().map { $0.toMember() }.orElse(nil)
        }
    }

    func observeActivity(limit: Int) -> Stream<[ActivityEvent]> {
        auth.uidStream().flatMapLatest { [self] uid -> Stream<[ActivityEvent]> in
            guard let uid else { return Just([]).eraseToAnyPublisher() }

            return memberships(uid).snapshots()
                .map { snap in snap.documents.map { $0.str("groupId") ?? $0.documentID } }
                .orElse([])
                .flatMapLatest { [self] ids -> Stream<[ActivityEvent]> in
                    if ids.isEmpty { return Just([]).eraseToAnyPublisher() }

                    let perGroup: [Stream<[ActivityEvent]>] = ids.map { gid in
                        group(gid).collection(FirestorePaths.activity)
                            .order(by: "createdAt", descending: true)
                            .limit(to: limit)
                            .snapshots()
                            .map { snap in snap.documents.compactMap { $0.toActivityEvent(groupId: gid) } }
                            .orElse([])
                    }

                    return combineLatestAll(perGroup)
                        .map { lists in Array(lists.flatMap { $0 }.sorted { $0.createdAt > $1.createdAt }.prefix(limit)) }
                        .eraseToAnyPublisher()
                }
        }
    }

    // ------------------------------------------------------------------ writes

    func createGroup(name: String, description: String?, coverUrl: URL?) async -> Outcome<Group> {
        guard let uid = uidOrNull() else { return .failure(.notAuthenticated) }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return .failure(.validation("Give your group a name")) }
        if trimmed.count > Limits.maxGroupNameLength { return .failure(.validation("That name is a bit long")) }
        let cleanedDescription = description?.trimmingCharacters(in: .whitespaces).nonEmpty

        return await firebaseCall {
            let profile = try await firestore.collection(FirestorePaths.users).document(uid).getDocument()
            let displayName = profile.str("name") ?? "Someone"
            let photoUrl = profile.str("photoUrl")

            let groupRef = groups().document()
            let gid = groupRef.documentID
            let code = try await reserveInviteCode()
            let now = Date.nowMillis

            let batch = firestore.batch()

            batch.setData([
                "name": trimmed,
                "description": cleanedDescription as Any,
                "coverPhotoUrl": NSNull(),
                "createdBy": uid,
                "createdAt": FieldValue.serverTimestamp(),
                "lastActivityAt": FieldValue.serverTimestamp(),
                "memberCount": 1,
                "photoCount": 0,
                "inviteCode": code,
                "inviteExpiresAt": NSNull(),
                "recentMemberPhotos": [photoUrl].compactMap { $0 }
            ], forDocument: groupRef)

            batch.setData([
                "name": displayName,
                "photoUrl": photoUrl as Any,
                "role": MemberRole.admin.rawValue,
                "joinedAt": FieldValue.serverTimestamp(),
                "photoCount": 0
            ], forDocument: members(gid).document(uid))

            batch.setData([
                "groupId": gid,
                "role": MemberRole.admin.rawValue,
                "joinedAt": FieldValue.serverTimestamp()
            ], forDocument: memberships(uid).document(gid))

            batch.setData([
                "groupId": gid,
                "groupName": trimmed,
                "description": cleanedDescription as Any,
                "coverPhotoUrl": NSNull(),
                "memberCount": 1,
                "photoCount": 0,
                "createdBy": uid,
                "createdAt": FieldValue.serverTimestamp(),
                "expiresAt": NSNull(),
                "revoked": false
            ], forDocument: invites().document(code))

            batch.setData([
                "type": "GROUP_CREATED",
                "groupName": trimmed,
                "actorId": uid,
                "actorName": displayName,
                "actorPhotoUrl": photoUrl as Any,
                "createdAt": FieldValue.serverTimestamp()
            ], forDocument: group(gid).collection(FirestorePaths.activity).document())

            try await batch.commit()

            var coverUrlString: String? = nil
            if let coverUrl {
                // A failed cover upload must not lose the group the user just made.
                coverUrlString = try? await uploadCover(groupId: gid, url: coverUrl)
            }

            return Group(
                id: gid,
                name: trimmed,
                description: cleanedDescription,
                coverPhotoUrl: coverUrlString,
                createdBy: uid,
                createdAt: now,
                memberCount: 1,
                photoCount: 0,
                lastActivityAt: now,
                inviteCode: code,
                recentMemberPhotos: [photoUrl].compactMap { $0 }
            )
        }
    }

    /// Picks a code and claims it, retrying on the (rare) collision.
    private func reserveInviteCode() async throws -> String {
        for _ in 0..<8 {
            let candidate = InviteCodes.generate()
            let existing = try await invites().document(candidate).getDocument()
            if !existing.exists { return candidate }
        }
        // Fall back to a longer code rather than failing the whole creation.
        return InviteCodes.generate(length: Limits.inviteCodeLength + 3)
    }

    func previewByInviteCode(_ code: String) async -> Outcome<GroupPreview> {
        guard let uid = uidOrNull() else { return .failure(.notAuthenticated) }
        let normalised = InviteCodes.normalise(code)
        if normalised.isEmpty { return .failure(.invalidInviteCode) }

        return await firebaseCall {
            let invite = try await invites().document(normalised).getDocument()
            if !invite.exists { throw AppErrorException(.invalidInviteCode) }
            if invite.bool("revoked") { throw AppErrorException(.inviteExpired) }

            if let expiresAt = invite.millisOrNull("expiresAt"), expiresAt < Date.nowMillis {
                throw AppErrorException(.inviteExpired)
            }

            guard let gid = invite.str("groupId") else { throw AppErrorException(.invalidInviteCode) }
            let alreadyMember = try await members(gid).document(uid).getDocument().exists

            guard let preview = invite.toGroupPreview(alreadyMember: alreadyMember) else {
                throw AppErrorException(.invalidInviteCode)
            }
            return preview
        }
    }

    func joinByInviteCode(_ code: String) async -> Outcome<Group> {
        guard let uid = uidOrNull() else { return .failure(.notAuthenticated) }
        let normalised = InviteCodes.normalise(code)
        if normalised.isEmpty { return .failure(.invalidInviteCode) }

        return await firebaseCall {
            let invite = try await invites().document(normalised).getDocument()
            if !invite.exists { throw AppErrorException(.invalidInviteCode) }
            if invite.bool("revoked") { throw AppErrorException(.inviteExpired) }

            if let expiresAt = invite.millisOrNull("expiresAt"), expiresAt < Date.nowMillis {
                throw AppErrorException(.inviteExpired)
            }

            guard let gid = invite.str("groupId") else { throw AppErrorException(.invalidInviteCode) }

            if try await members(gid).document(uid).getDocument().exists {
                // Already in: treat as success so a re-tapped link just opens the group.
                guard let existing = try await group(gid).getDocument().toGroup() else {
                    throw AppErrorException(.groupNotFound)
                }
                return existing
            }

            let profile = try await firestore.collection(FirestorePaths.users).document(uid).getDocument()
            let displayName = profile.str("name") ?? "Someone"
            let photoUrl = profile.str("photoUrl")

            let batch = firestore.batch()

            batch.setData([
                "name": displayName,
                "photoUrl": photoUrl as Any,
                "role": MemberRole.member.rawValue,
                "joinedAt": FieldValue.serverTimestamp(),
                "photoCount": 0
            ], forDocument: members(gid).document(uid))

            batch.setData([
                "groupId": gid,
                "role": MemberRole.member.rawValue,
                "joinedAt": FieldValue.serverTimestamp()
            ], forDocument: memberships(uid).document(gid))

            // The security rules cap this to a +1 change on exactly these fields, so a
            // joining non-member cannot touch anything else on the group document.
            var groupUpdate: [String: Any] = [
                "memberCount": FieldValue.increment(Int64(1)),
                "lastActivityAt": FieldValue.serverTimestamp()
            ]
            if let photoUrl { groupUpdate["recentMemberPhotos"] = FieldValue.arrayUnion([photoUrl]) }
            batch.updateData(groupUpdate, forDocument: group(gid))

            batch.setData([
                "type": "MEMBER_JOINED",
                "groupName": invite.str("groupName") ?? "",
                "actorId": uid,
                "actorName": displayName,
                "actorPhotoUrl": photoUrl as Any,
                "createdAt": FieldValue.serverTimestamp()
            ], forDocument: group(gid).collection(FirestorePaths.activity).document())

            try await batch.commit()

            guard let joined = try await group(gid).getDocument().toGroup() else {
                throw AppErrorException(.groupNotFound)
            }
            return joined
        }
    }

    func leaveGroup(groupId: String) async -> Outcome<Void> {
        guard let uid = uidOrNull() else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            let myMember = try await members(groupId).document(uid).getDocument()
            if !myMember.exists { return }

            let displayName = myMember.str("name") ?? "Someone"
            let wasAdmin = MemberRole.from(myMember.str("role")) == .admin

            let remaining = try await members(groupId)
                .order(by: "joinedAt", descending: false)
                .getDocuments()
                .documents
                .filter { $0.documentID != uid }

            if remaining.isEmpty {
                // Last one out deletes the group rather than leaving an orphan.
                try await deleteGroupInternal(groupId)
                try await memberships(uid).document(groupId).delete()
                return
            }

            let batch = firestore.batch()
            batch.deleteDocument(members(groupId).document(uid))
            batch.deleteDocument(memberships(uid).document(groupId))
            batch.updateData([
                "memberCount": FieldValue.increment(Int64(-1)),
                "lastActivityAt": FieldValue.serverTimestamp()
            ], forDocument: group(groupId))

            // Never strand a group without an admin.
            if wasAdmin && !remaining.contains(where: { MemberRole.from($0.str("role")) == .admin }) {
                let heir = remaining[0]
                batch.updateData(["role": MemberRole.admin.rawValue], forDocument: members(groupId).document(heir.documentID))
                batch.updateData(
                    ["role": MemberRole.admin.rawValue],
                    forDocument: firestore.collection(FirestorePaths.users).document(heir.documentID)
                        .collection(FirestorePaths.memberships).document(groupId)
                )
            }

            batch.setData([
                "type": "MEMBER_LEFT",
                "actorId": uid,
                "actorName": displayName,
                "createdAt": FieldValue.serverTimestamp()
            ], forDocument: group(groupId).collection(FirestorePaths.activity).document())

            try await batch.commit()
        }
    }

    func updateGroup(groupId: String, name: String?, description: String?) async -> Outcome<Void> {
        guard uidOrNull() != nil else { return .failure(.notAuthenticated) }
        var updates: [String: Any] = [:]
        if let name {
            let trimmed = name.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { return .failure(.validation("Give your group a name")) }
            if trimmed.count > Limits.maxGroupNameLength { return .failure(.validation("That name is a bit long")) }
            updates["name"] = trimmed
        }
        if let description {
            updates["description"] = description.trimmingCharacters(in: .whitespaces).nonEmpty ?? NSNull()
        }
        if updates.isEmpty { return .success(()) }

        return await firebaseCall {
            try await group(groupId).updateData(updates)
            // Keep the invite preview honest about the renamed group.
            if let newName = updates["name"] as? String,
               let code = try await group(groupId).getDocument().str("inviteCode") {
                _ = try? await invites().document(code).updateData(["groupName": newName])
            }
        }
    }

    func updateCoverPhoto(groupId: String, url: URL) async -> Outcome<String> {
        guard uidOrNull() != nil else { return .failure(.notAuthenticated) }
        return await firebaseCall { try await uploadCover(groupId: groupId, url: url) }
    }

    private func uploadCover(groupId: String, url: URL) async throws -> String {
        let processed = try imageProcessor.prepareCover(url: url)
        let remote = try await imageStore.upload(path: StoragePaths.cover(groupId: groupId), bytes: processed.bytes)

        try await group(groupId).updateData(["coverPhotoUrl": remote])
        if let code = try? await group(groupId).getDocument().str("inviteCode") {
            _ = try? await invites().document(code).updateData(["coverPhotoUrl": remote])
        }
        return remote
    }

    func removeMember(groupId: String, uid: String) async -> Outcome<Void> {
        guard let me = uidOrNull() else { return .failure(.notAuthenticated) }
        if me == uid { return .failure(.validation("Use Leave group instead")) }

        return await firebaseCall {
            let batch = firestore.batch()
            batch.deleteDocument(members(groupId).document(uid))
            batch.deleteDocument(
                firestore.collection(FirestorePaths.users).document(uid)
                    .collection(FirestorePaths.memberships).document(groupId)
            )
            batch.updateData([
                "memberCount": FieldValue.increment(Int64(-1)),
                "lastActivityAt": FieldValue.serverTimestamp()
            ], forDocument: group(groupId))
            try await batch.commit()
        }
    }

    func regenerateInviteCode(groupId: String, expiresAt: Millis?) async -> Outcome<String> {
        guard let uid = uidOrNull() else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            let snapshot = try await group(groupId).getDocument()
            let previous = snapshot.str("inviteCode")
            let newCode = try await reserveInviteCode()
            let expiry: Any = expiresAt.map { Timestamp(date: Date(millis: $0)) } ?? NSNull()

            let batch = firestore.batch()
            batch.setData([
                "groupId": groupId,
                "groupName": snapshot.str("name") ?? "",
                "description": snapshot.str("description") as Any,
                "coverPhotoUrl": snapshot.str("coverPhotoUrl") as Any,
                "memberCount": snapshot.long("memberCount"),
                "photoCount": snapshot.long("photoCount"),
                // Who minted this invite — the rules require it to be the caller.
                "createdBy": uid,
                "createdAt": FieldValue.serverTimestamp(),
                "expiresAt": expiry,
                "revoked": false
            ], forDocument: invites().document(newCode))
            batch.updateData([
                "inviteCode": newCode,
                "inviteExpiresAt": expiry
            ], forDocument: group(groupId))
            if let previous { batch.updateData(["revoked": true], forDocument: invites().document(previous)) }

            try await batch.commit()
            return newCode
        }
    }

    func revokeInvite(groupId: String) async -> Outcome<Void> {
        guard uidOrNull() != nil else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            guard let code = try await group(groupId).getDocument().str("inviteCode") else { return }
            let batch = firestore.batch()
            batch.updateData(["revoked": true], forDocument: invites().document(code))
            batch.updateData(["inviteCode": "", "inviteExpiresAt": NSNull()], forDocument: group(groupId))
            try await batch.commit()
        }
    }

    func deleteGroup(groupId: String) async -> Outcome<Void> {
        guard uidOrNull() != nil else { return .failure(.notAuthenticated) }
        return await firebaseCall { try await deleteGroupInternal(groupId) }
    }

    /// Firestore has no recursive delete from a client, so this walks the subcollections
    /// in batches. Storage objects go first: a half-deleted group still readable by its
    /// members is recoverable, orphaned image blobs nobody can see are not.
    private func deleteGroupInternal(_ groupId: String) async throws {
        let photoDocs = try await group(groupId).collection(FirestorePaths.photos).getDocuments().documents

        for doc in photoDocs {
            if let path = doc.str("storagePath") { try? await imageStore.delete(path: path) }
            if let path = doc.str("thumbnailStoragePath") { try? await imageStore.delete(path: path) }
        }

        let memberDocs = try await members(groupId).getDocuments().documents
        for member in memberDocs {
            try? await firestore.collection(FirestorePaths.users).document(member.documentID)
                .collection(FirestorePaths.memberships).document(groupId).delete()
        }

        let activityDocs = try await group(groupId).collection(FirestorePaths.activity).getDocuments().documents
        let inviteCode = try await group(groupId).getDocument().str("inviteCode")

        var toDelete: [DocumentReference] = []
        toDelete += photoDocs.map(\.reference)
        toDelete += memberDocs.map(\.reference)
        toDelete += activityDocs.map(\.reference)
        if let inviteCode { toDelete.append(invites().document(inviteCode)) }
        toDelete.append(group(groupId))

        // 500 writes is the hard batch limit; stay under it.
        for start in stride(from: 0, to: toDelete.count, by: 450) {
            let batch = firestore.batch()
            for ref in toDelete[start..<min(start + 450, toDelete.count)] { batch.deleteDocument(ref) }
            try await batch.commit()
        }

        try? await imageStore.delete(path: StoragePaths.cover(groupId: groupId))
    }
}

extension String {
    /// Nil when blank, so optional fields never store an empty string.
    var nonEmpty: String? { isEmpty ? nil : self }
}
