import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore
import Photos

final class FirestorePhotoRepository: PhotoRepository {

    private let firestore: Firestore
    private let auth: Auth
    private let imageStore: ImageStore
    private let store: UploadQueueStore
    private let scheduler: UploadScheduler
    private let stagingDir: URL
    private let sharedDir: URL

    init(firestore: Firestore, auth: Auth, imageStore: ImageStore, store: UploadQueueStore,
         scheduler: UploadScheduler, stagingDir: URL, sharedDir: URL) {
        self.firestore = firestore
        self.auth = auth
        self.imageStore = imageStore
        self.store = store
        self.scheduler = scheduler
        self.stagingDir = stagingDir
        self.sharedDir = sharedDir
    }

    /// How far back the live window reaches, per group.
    ///
    /// Pagination is a growing realtime window rather than independent pages. A
    /// shared camera roll gains photos at the top constantly — with cursor pages,
    /// every upload by a friend would shift the boundary and duplicate or skip rows.
    /// One listener over the newest N keeps the feed consistent; "load older" just
    /// widens N. The cost is re-reading the window on each extension, which is fine
    /// at the tens-of-photos-per-page this app deals in.
    private var windowSizes: [String: CurrentValueSubject<Int, Never>] = [:]
    private let windowLock = NSLock()

    private func windowFor(_ groupId: String) -> CurrentValueSubject<Int, Never> {
        windowLock.lock(); defer { windowLock.unlock() }
        if let existing = windowSizes[groupId] { return existing }
        let created = CurrentValueSubject<Int, Never>(Limits.photoPageSize)
        windowSizes[groupId] = created
        return created
    }

    private func photos(_ groupId: String) -> CollectionReference {
        firestore.collection(FirestorePaths.groups).document(groupId).collection(FirestorePaths.photos)
    }

    func observePhotos(groupId: String, filter: PhotoFilter) -> Stream<PhotoPage> {
        windowFor(groupId).flatMapLatest { [self] limit -> Stream<PhotoPage> in
            baseQuery(groupId, filter)
                .limit(to: limit)
                .snapshots()
                .map { snap -> PhotoPage in
                    let items = snap.documents.compactMap { $0.toPhoto(groupId: groupId) }
                    // A full window implies there is probably more behind it.
                    return PhotoPage(photos: items, hasMore: items.count >= limit)
                }
                .orElse(PhotoPage())
        }
    }

    /// Filters run as real queries rather than a client-side pass over the loaded
    /// window — otherwise "only Priya's photos" would show whichever of hers happened
    /// to fall in the last 60 uploads, and paging would appear to do nothing.
    private func baseQuery(_ groupId: String, _ filter: PhotoFilter) -> Query {
        let uid = auth.currentUser?.uid
        switch filter {
        case .all:
            return photos(groupId).order(by: "createdAt", descending: true)
        case .byUploader(let byUid, _):
            return photos(groupId).whereField("uploadedBy", isEqualTo: byUid).order(by: "createdAt", descending: true)
        case .favorites:
            guard let uid else { return photos(groupId).order(by: "createdAt", descending: true) }
            return photos(groupId).whereField("favoritedBy", arrayContains: uid).order(by: "createdAt", descending: true)
        }
    }

    func loadOlder(groupId: String) async {
        let subject = windowFor(groupId)
        subject.send(subject.value + Limits.photoPageSize)
    }

    func resetPagination(groupId: String) {
        windowFor(groupId).send(Limits.photoPageSize)
    }

    /// Combines the photo document with this user's own reaction, which lives apart.
    func observePhoto(groupId: String, photoId: String) -> Stream<Photo?> {
        let photoStream: Stream<Photo?> = photos(groupId).document(photoId).snapshots()
            .map { $0.toPhoto(groupId: groupId) }
            .orElse(nil)

        guard let uid = auth.currentUser?.uid else { return photoStream }

        let myReaction: Stream<String?> = photos(groupId).document(photoId)
            .collection(FirestorePaths.reactions).document(uid)
            .snapshots()
            .map { $0.str("key") }
            .orElse(nil)

        return photoStream.combineLatest(myReaction) { photo, reaction -> Photo? in
            guard var photo else { return nil }
            photo.myReaction = reaction
            return photo
        }.eraseToAnyPublisher()
    }

    func enqueueUpload(groupId: String, localUrl: URL, caption: String?, capturedAt: Millis) async -> Outcome<String> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            // Copy into app storage first. A picker's temporary file dies with the
            // screen, and the queue may not drain until hours later.
            let staged = try stageLocally(localUrl)
            let id = UUID().uuidString

            store.insert(UploadRow(
                id: id,
                groupId: groupId,
                ownerUid: uid,
                localUri: staged.absoluteString,
                caption: caption?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Limits.maxCaptionLength).description.nonEmpty,
                capturedAt: capturedAt,
                createdAt: Date.nowMillis,
                state: .queued,
                progress: 0,
                attemptCount: 0,
                errorMessage: nil,
                remotePhotoId: nil
            ))

            await scheduler.ensureRunning()
            return id
        }
    }

    private func stageLocally(_ url: URL) throws -> URL {
        try FileManager.default.createDirectory(at: stagingDir, withIntermediateDirectories: true)
        let target = stagingDir.appendingPathComponent("\(UUID().uuidString).img")
        do {
            try FileManager.default.copyItem(at: url, to: target)
        } catch {
            throw AppErrorException(.validation("Couldn't read that photo"))
        }
        return target
    }

    func deletePhoto(groupId: String, photoId: String) async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            let doc = try await photos(groupId).document(photoId).getDocument()
            if !doc.exists { throw AppErrorException(.photoNotFound) }

            let uploadedBy = doc.str("uploadedBy")
            let groupRef = firestore.collection(FirestorePaths.groups).document(groupId)

            if uploadedBy != uid {
                // Only an admin may remove someone else's photo. The rules enforce this
                // too; checking here just produces a better message than a raw denial.
                let me = try await groupRef.collection(FirestorePaths.members).document(uid).getDocument()
                if MemberRole.from(me.str("role")) != .admin { throw AppErrorException(.permissionDenied) }
            }

            // Blobs first: a photo document pointing at a missing image renders as a
            // broken tile, while an orphaned blob is invisible and merely wasteful.
            if let path = doc.str("storagePath") { try? await imageStore.delete(path: path) }
            if let path = doc.str("thumbnailStoragePath") { try? await imageStore.delete(path: path) }

            let batch = firestore.batch()
            batch.deleteDocument(photos(groupId).document(photoId))
            batch.updateData(["photoCount": FieldValue.increment(Int64(-1))], forDocument: groupRef)
            if let uploadedBy {
                batch.updateData(["photoCount": FieldValue.increment(Int64(-1))],
                                 forDocument: groupRef.collection(FirestorePaths.members).document(uploadedBy))
            }
            try await batch.commit()
        }
    }

    func setCaption(groupId: String, photoId: String, caption: String?) async -> Outcome<Void> {
        guard auth.currentUser != nil else { return .failure(.notAuthenticated) }
        let cleaned = caption?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Limits.maxCaptionLength).description.nonEmpty
        return await firebaseCall {
            try await photos(groupId).document(photoId).updateData(["caption": cleaned ?? NSNull()])
        }
    }

    /// A reaction is one document per user plus a counter on the photo. The transaction
    /// reads the user's existing reaction and adjusts both counters, so switching from
    /// ❤️ to 😂 never leaves the old count stranded and a double-tap cannot inflate it.
    func setReaction(groupId: String, photoId: String, reaction: Reaction?) async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }

        return await firebaseCall {
            let photoRef = photos(groupId).document(photoId)
            let myReactionRef = photoRef.collection(FirestorePaths.reactions).document(uid)

            _ = try await firestore.runTransaction { transaction, errorPointer -> Any? in
                let existing: DocumentSnapshot
                do {
                    existing = try transaction.getDocument(myReactionRef)
                } catch {
                    errorPointer?.pointee = error as NSError
                    return nil
                }
                let previousKey = existing.str("key")
                let nextKey = reaction?.key

                if previousKey == nextKey { return nil }

                var updates: [String: Any] = [:]
                if let previousKey { updates["reactionCounts.\(previousKey)"] = FieldValue.increment(Int64(-1)) }
                if let nextKey { updates["reactionCounts.\(nextKey)"] = FieldValue.increment(Int64(1)) }
                if !updates.isEmpty { transaction.updateData(updates, forDocument: photoRef) }

                if let nextKey {
                    transaction.setData(["key": nextKey, "createdAt": FieldValue.serverTimestamp()], forDocument: myReactionRef)
                } else {
                    transaction.deleteDocument(myReactionRef)
                }
                return nil
            }
        }
    }

    /// Starring writes only this user's id into the array. The rules verify the diff
    /// touches nobody else's, so one member cannot star a photo on another's behalf
    /// or wipe the list while they are at it.
    func setFavorite(groupId: String, photoId: String, favorite: Bool) async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            try await photos(groupId).document(photoId).updateData([
                "favoritedBy": favorite ? FieldValue.arrayUnion([uid]) : FieldValue.arrayRemove([uid])
            ])
        }
    }

    func downloadAllToGallery(_ photos: [Photo], onProgress: @escaping (Int, Int) -> Void) async -> Outcome<Int> {
        if photos.isEmpty { return .success(0) }

        var saved = 0
        var lastError: AppError? = nil

        for (index, photo) in photos.enumerated() {
            switch await downloadToGallery(photo) {
            case .success: saved += 1
            // One unreadable photo should not abandon the other twenty-nine.
            case .failure(let error): lastError = error
            }
            onProgress(index + 1, photos.count)
        }

        if saved == 0, let lastError { return .failure(lastError) }
        return .success(saved)
    }

    func deletePhotos(groupId: String, photoIds: [String]) async -> Outcome<Int> {
        if photoIds.isEmpty { return .success(0) }

        var removed = 0
        var lastError: AppError? = nil

        for id in photoIds {
            switch await deletePhoto(groupId: groupId, photoId: id) {
            case .success: removed += 1
            case .failure(let error): lastError = error
            }
        }

        if removed == 0, let lastError { return .failure(lastError) }
        return .success(removed)
    }

    func downloadToGallery(_ photo: Photo) async -> Outcome<Void> {
        await firebaseCall {
            let bytes = try await imageStore.download(path: photo.storagePath, maxBytes: Self.maxDownloadBytes)

            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard status == .authorized || status == .limited else {
                throw AppErrorException(.validation("Allow WeWere to add to your photos in Settings"))
            }

            do {
                try await PHPhotoLibrary.shared().performChanges {
                    let request = PHAssetCreationRequest.forAsset()
                    let options = PHAssetResourceCreationOptions()
                    options.originalFilename = "WeWere_\(photo.id).jpg"
                    request.addResource(with: .photo, data: bytes, options: options)
                }
            } catch {
                throw AppErrorException(.unknown("Couldn't save to your photos"))
            }
        }
    }

    func prepareForSharing(_ photo: Photo) async -> Outcome<URL> {
        await firebaseCall {
            let bytes = try await imageStore.download(path: photo.storagePath, maxBytes: Self.maxDownloadBytes)
            try FileManager.default.createDirectory(at: sharedDir, withIntermediateDirectories: true)
            let file = sharedDir.appendingPathComponent("WeWere_\(photo.id).jpg")
            try bytes.write(to: file, options: .atomic)
            return file
        }
    }

    func reportPhoto(groupId: String, photoId: String, reason: String) async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            try await firestore.collection("reports").document().setData([
                "groupId": groupId,
                "photoId": photoId,
                "reportedBy": uid,
                "reason": reason,
                "createdAt": FieldValue.serverTimestamp()
            ])
        }
    }

    /// Full-size uploads are capped well below this by the image processor.
    private static let maxDownloadBytes: Int64 = 20 * 1024 * 1024
}
