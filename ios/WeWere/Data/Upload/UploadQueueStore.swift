import Foundation
import Combine

/// One row of the durable upload queue. Room's `pending_uploads` table on Android.
struct UploadRow: Codable, Equatable {
    var id: String
    var groupId: String
    /// Whose queue this is. A shared device must not upload one user's photos as another.
    var ownerUid: String
    var localUri: String
    var caption: String?
    var capturedAt: Millis
    var createdAt: Millis
    var state: UploadState
    var progress: Float = 0
    var attemptCount: Int = 0
    var errorMessage: String?
    /// Set once the bytes land, so a retry never creates a second Firestore document.
    var remotePhotoId: String?

    func toDomain() -> PendingUpload {
        PendingUpload(
            id: id, groupId: groupId, localUri: localUri, caption: caption,
            capturedAt: capturedAt, createdAt: createdAt, state: state,
            progress: progress, attemptCount: attemptCount, errorMessage: errorMessage
        )
    }
}

/// The queue itself: a small JSON file rewritten atomically after every change.
///
/// Room on Android; here the whole table is a few hundred rows at most, so a file is
/// simpler than a database and just as durable. Every mutation runs under one lock
/// and publishes the new table, which is what the "3 waiting" banners subscribe to.
final class UploadQueueStore {

    private let fileUrl: URL
    private let lock = NSLock()
    private var rows: [UploadRow]
    private let subject: CurrentValueSubject<[UploadRow], Never>

    init(directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileUrl = directory.appendingPathComponent("upload_queue.json")
        let loaded = (try? Data(contentsOf: fileUrl)).flatMap { try? JSONDecoder().decode([UploadRow].self, from: $0) } ?? []
        rows = loaded
        subject = CurrentValueSubject(loaded)
    }

    var all: Stream<[UploadRow]> { subject.eraseToAnyPublisher() }

    // MARK: reads

    func getById(_ id: String) -> UploadRow? { read { $0.first { $0.id == id } } }

    /// Oldest first, so photos reach the group in the order they were taken.
    func claimBatch(limit: Int) -> [UploadRow] {
        read { rows in
            Array(rows.filter { $0.state == .queued || $0.state == .uploading }
                .sorted { $0.createdAt < $1.createdAt }
                .prefix(limit))
        }
    }

    func countActive() -> Int { read { $0.filter { $0.state == .queued || $0.state == .uploading }.count } }

    /// Every staged file still referenced by a row; anything else on disk is an orphan.
    func allLocalUris() -> [String] { read { $0.map(\.localUri) } }

    // MARK: writes

    func insert(_ row: UploadRow) { mutate { $0.removeAll { $0.id == row.id }; $0.append(row) } }

    func setState(_ id: String, _ state: UploadState, error: String?) {
        mutate { rows in
            guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
            rows[i].state = state
            rows[i].errorMessage = error
        }
    }

    func setProgress(_ id: String, _ progress: Float) {
        mutate { rows in
            guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
            rows[i].progress = progress
        }
    }

    func incrementAttempts(_ id: String) {
        mutate { rows in
            guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
            rows[i].attemptCount += 1
        }
    }

    func setRemotePhotoId(_ id: String, _ photoId: String) {
        mutate { rows in
            guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
            rows[i].remotePhotoId = photoId
        }
    }

    func requeue(_ id: String) {
        mutate { rows in
            guard let i = rows.firstIndex(where: { $0.id == id }), rows[i].state == .failed else { return }
            rows[i].state = .queued
            rows[i].errorMessage = nil
            rows[i].attemptCount = 0
        }
    }

    func requeueAllFailed(uid: String) {
        mutate { rows in
            for i in rows.indices where rows[i].ownerUid == uid && rows[i].state == .failed {
                rows[i].state = .queued
                rows[i].errorMessage = nil
                rows[i].attemptCount = 0
            }
        }
    }

    /// An upload already in flight is left alone — the worker checks for cancellation
    /// at its next checkpoint and cleans up after itself.
    func cancel(_ id: String) { setState(id, .cancelled, error: nil) }

    func delete(_ id: String) { mutate { $0.removeAll { $0.id == id } } }

    func clearFinished() { mutate { $0.removeAll { $0.state == .completed || $0.state == .cancelled } } }

    /// Anything left UPLOADING at startup belongs to a run the OS killed. Put it
    /// back in the queue rather than leaving a spinner that never resolves.
    func recoverInterrupted() {
        mutate { rows in
            for i in rows.indices where rows[i].state == .uploading {
                rows[i].state = .queued
                rows[i].progress = 0
            }
        }
    }

    // MARK: plumbing

    private func read<T>(_ block: ([UploadRow]) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return block(rows)
    }

    private func mutate(_ block: (inout [UploadRow]) -> Void) {
        lock.lock()
        block(&rows)
        let snapshot = rows
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: fileUrl, options: .atomic)
        }
        lock.unlock()
        subject.send(snapshot)
    }
}
