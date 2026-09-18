import Foundation
import Combine
import FirebaseAuth

final class LocalUploadQueueRepository: UploadQueueRepository {

    private let store: UploadQueueStore
    private let auth: Auth
    private let scheduler: UploadScheduler

    init(store: UploadQueueStore, auth: Auth, scheduler: UploadScheduler) {
        self.store = store
        self.auth = auth
        self.scheduler = scheduler
    }

    func observePending() -> Stream<[PendingUpload]> {
        auth.uidStream().flatMapLatest { [store] uid -> Stream<[PendingUpload]> in
            guard let uid else { return Just([]).eraseToAnyPublisher() }
            return store.all.map { rows in
                rows.filter { $0.ownerUid == uid && $0.state != .completed && $0.state != .cancelled }
                    .sorted { $0.createdAt < $1.createdAt }
                    .map { $0.toDomain() }
            }.eraseToAnyPublisher()
        }
    }

    func observePendingForGroup(groupId: String) -> Stream<[PendingUpload]> {
        observePending().map { $0.filter { $0.groupId == groupId } }.eraseToAnyPublisher()
    }

    func retry(uploadId: String) async -> Outcome<Void> {
        store.requeue(uploadId)
        await scheduler.restartNow()
        return .success(())
    }

    func retryAllFailed() async -> Outcome<Void> {
        guard let uid = auth.currentUser?.uid else { return .failure(.notAuthenticated) }
        store.requeueAllFailed(uid: uid)
        await scheduler.restartNow()
        return .success(())
    }

    func cancel(uploadId: String) async -> Outcome<Void> {
        store.cancel(uploadId)
        return .success(())
    }

    func clearCompleted() async -> Outcome<Void> {
        store.clearFinished()
        return .success(())
    }
}
