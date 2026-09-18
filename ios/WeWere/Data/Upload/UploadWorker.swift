import Foundation
import FirebaseAuth
import FirebaseFirestore
import os

/// Drains the durable upload queue.
///
/// Everything here is built around the queue surviving things the app does not control:
/// the process being killed mid-upload, the network vanishing, the user force-quitting.
/// State lives on disk, not in memory, and each photo is only marked COMPLETED once its
/// Firestore document exists — so a crash between the blob landing and the document
/// being written retries safely instead of leaving an image nobody can see.
final class UploadWorker {

    private let store: UploadQueueStore
    private let imageProcessor: ImageProcessor
    private let firestore: Firestore
    private let imageStore: ImageStore
    private let auth: Auth
    private let stagingDir: URL
    private let log = Logger(subsystem: "com.rollapp.shared", category: "UploadWorker")

    init(store: UploadQueueStore, imageProcessor: ImageProcessor, firestore: Firestore,
         imageStore: ImageStore, auth: Auth, stagingDir: URL) {
        self.store = store
        self.imageProcessor = imageProcessor
        self.firestore = firestore
        self.imageStore = imageStore
        self.auth = auth
        self.stagingDir = stagingDir
    }

    /// Runs the queue to completion. Returns true when something failed retryably and
    /// deserves another pass after a backoff.
    func drain() async -> Bool {
        store.recoverInterrupted()
        sweepOrphanedStagedFiles()

        guard let uid = auth.currentUser?.uid else { return false }

        // Drain the whole queue in this one run. Items that failed retryably this run
        // are set aside so the loop cannot spin on them; they wait for the backoff.
        var deferred = Set<String>()
        var anyRetryable = false
        var done = 0

        while !Task.isCancelled {
            let batch = store.claimBatch(limit: Self.batchSize)
                .filter { $0.ownerUid == uid && !deferred.contains($0.id) }
            if batch.isEmpty { break }

            for item in batch {
                if Task.isCancelled { return true }
                // The user may have cancelled while an earlier item was in flight.
                guard let fresh = store.getById(item.id) else { continue }
                if fresh.state == .cancelled {
                    store.delete(item.id)
                    deleteStagedFile(fresh)
                    continue
                }

                switch await uploadOne(fresh) {
                case .success:
                    store.setState(item.id, .completed, error: nil)
                    store.delete(item.id)
                    deleteStagedFile(fresh)

                case .retryable(let reason):
                    store.incrementAttempts(item.id)
                    let attempts = store.getById(item.id)?.attemptCount ?? 0
                    if attempts >= Limits.maxUploadAttempts {
                        store.setState(item.id, .failed, error: reason)
                    } else {
                        store.setState(item.id, .queued, error: reason)
                        deferred.insert(item.id)
                        anyRetryable = true
                    }

                case .permanent(let reason):
                    store.setState(item.id, .failed, error: reason)
                }
                done += 1
            }
        }

        return anyRetryable
    }

    /// The staged copy exists only so the queue survives the picker's temporary file
    /// going away. Once the photo is up — or abandoned — it is dead weight, and at a
    /// hundred photos a pick that is hundreds of megabytes.
    private func deleteStagedFile(_ item: UploadRow) {
        if let url = URL(string: item.localUri) { try? FileManager.default.removeItem(at: url) }
    }

    /// Rows removed by other paths (clearFinished, a cancelled item nobody drained)
    /// leave their files behind; reconcile the directory against the table on every
    /// run so nothing accumulates, whatever route the row left by.
    private func sweepOrphanedStagedFiles() {
        let live = Set(store.allLocalUris().compactMap { URL(string: $0)?.standardizedFileURL.path })
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: stagingDir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        let cutoff = Date().addingTimeInterval(-Self.sweepGraceSeconds)
        for file in files where !live.contains(file.standardizedFileURL.path) {
            // A file is staged a moment before its row exists. Leave anything that
            // young alone, or a sweep started by the first photo of a pick can eat
            // the third one between its copy and its insert.
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if modified > cutoff { continue }
            try? FileManager.default.removeItem(at: file)
        }
    }

    /// How long a staged file may sit unreferenced before the sweep treats it as an orphan.
    private static let sweepGraceSeconds: TimeInterval = 300

    private func uploadOne(_ item: UploadRow) async -> UploadOutcome {
        do {
            store.setState(item.id, .uploading, error: nil)
            store.setProgress(item.id, 0)

            guard let localUrl = URL(string: item.localUri) else {
                return .permanent("That photo is no longer on this device")
            }
            guard FileManager.default.fileExists(atPath: localUrl.path) else {
                return .permanent("That photo is no longer on this device")
            }
            let processed = try imageProcessor.prepare(url: localUrl)

            // Reuse the id from a previous attempt so a retry overwrites the same
            // storage objects instead of orphaning the ones already uploaded.
            let photoId: String
            if let existing = item.remotePhotoId {
                photoId = existing
            } else {
                photoId = firestore.collection(FirestorePaths.groups).document(item.groupId)
                    .collection(FirestorePaths.photos).document().documentID
                store.setRemotePhotoId(item.id, photoId)
            }

            let fullPath = StoragePaths.photo(groupId: item.groupId, category: StoragePaths.full, photoId: photoId)
            let thumbPath = StoragePaths.photo(groupId: item.groupId, category: StoragePaths.thumbs, photoId: photoId)

            // Thumbnail first and it is small: the grid can render the moment the
            // document appears, even while the full image is still climbing.
            let id = item.id
            let store = self.store
            let thumbUrl = try await imageStore.upload(path: thumbPath, bytes: processed.thumbnail.bytes, contentType: "image/jpeg") { fraction in
                store.setProgress(id, fraction * Self.thumbShare)
            }
            let fullUrl = try await imageStore.upload(path: fullPath, bytes: processed.full.bytes, contentType: "image/jpeg") { fraction in
                store.setProgress(id, Self.thumbShare + fraction * (1 - Self.thumbShare))
            }

            try await writePhotoDocument(item, photoId: photoId, processed: processed,
                                         fullPath: fullPath, thumbPath: thumbPath,
                                         fullUrl: fullUrl, thumbUrl: thumbUrl)

            store.setProgress(item.id, 1)
            return .success
        } catch is CancellationError {
            return .retryable("Upload interrupted")
        } catch {
            return classify(error)
        }
    }

    private func writePhotoDocument(_ item: UploadRow, photoId: String, processed: ProcessedUpload,
                                    fullPath: String, thumbPath: String,
                                    fullUrl: String, thumbUrl: String) async throws {
        let uid = item.ownerUid
        let groupRef = firestore.collection(FirestorePaths.groups).document(item.groupId)
        let memberRef = groupRef.collection(FirestorePaths.members).document(uid)

        let member = try await memberRef.getDocument()
        let uploaderName = member.str("name") ?? "Someone"
        let uploaderPhoto = member.str("photoUrl")

        let group = try await groupRef.getDocument()
        let needsCover = group.str("coverPhotoUrl") == nil

        let batch = firestore.batch()

        batch.setData([
            "uploadedBy": uid,
            "uploaderName": uploaderName,
            "uploaderPhotoUrl": uploaderPhoto as Any,
            "imageUrl": fullUrl,
            "thumbnailUrl": thumbUrl,
            "storagePath": fullPath,
            "thumbnailStoragePath": thumbPath,
            "caption": item.caption.map { String($0.prefix(Limits.maxCaptionLength)) } as Any,
            "width": processed.full.width,
            "height": processed.full.height,
            "sizeBytes": Int64(processed.full.bytes.count),
            "createdAt": FieldValue.serverTimestamp(),
            // EXIF beats the enqueue time: a gallery photo taken last Tuesday
            // belongs under Tuesday in the timeline, not under today.
            "capturedAt": Timestamp(date: Date(millis: processed.capturedAt ?? item.capturedAt)),
            "reactionCounts": [String: Int](),
            "favoritedBy": [String]()
        ], forDocument: groupRef.collection(FirestorePaths.photos).document(photoId))

        var groupUpdate: [String: Any] = [
            "photoCount": FieldValue.increment(Int64(1)),
            "lastActivityAt": FieldValue.serverTimestamp()
        ]
        // First photo in a group doubles as its cover, so the home card is never blank.
        if needsCover { groupUpdate["coverPhotoUrl"] = thumbUrl }
        batch.updateData(groupUpdate, forDocument: groupRef)

        batch.updateData(["photoCount": FieldValue.increment(Int64(1))], forDocument: memberRef)

        batch.setData([
            "type": "PHOTOS_ADDED",
            "groupName": group.str("name") ?? "",
            "actorId": uid,
            "actorName": uploaderName,
            "actorPhotoUrl": uploaderPhoto as Any,
            "photoCount": 1,
            "previewPhotoUrl": thumbUrl,
            "createdAt": FieldValue.serverTimestamp()
        ], forDocument: groupRef.collection(FirestorePaths.activity).document())

        try await batch.commit()
    }

    /// Decides whether a failure is worth another attempt.
    ///
    /// This goes through the same error mapper the UI uses rather than matching on
    /// error messages — a revoked membership and a flaky hotel wifi both surface
    /// as an opaque failure, and retrying the first one forever burns the battery of
    /// someone who has already been removed from the group.
    private func classify(_ error: Error) -> UploadOutcome {
        log.warning("Upload attempt failed: \(String(describing: error))")
        switch FirebaseErrorMapper.map(error) {
        case .permissionDenied, .notAuthenticated:
            return .permanent("You're no longer a member of this group")
        case .groupNotFound:
            return .permanent("That group no longer exists")
        case .storageQuotaExceeded:
            return .permanent("This group has run out of storage")
        case .validation(let message):
            return .permanent(message)
        case let mapped:
            let ns = error as NSError
            if ns.domain == NSCocoaErrorDomain && ns.code == NSFileReadNoSuchFileError {
                return .permanent("That photo is no longer on this device")
            }
            return .retryable(mapped.message ?? "Upload failed")
        }
    }

    private enum UploadOutcome {
        case success
        case retryable(String)
        case permanent(String)
    }

    private static let batchSize = 8
    /// The thumbnail is a small fraction of the bytes; weight the bar accordingly.
    private static let thumbShare: Float = 0.15

    /// Must match where FirestorePhotoRepository.stageLocally writes.
    static let stagingDirName = "upload_queue"
}
