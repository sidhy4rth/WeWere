import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore

/// Snapshot listeners as Combine publishers. Errors are surfaced by failing the
/// publisher so the subscribing repository can decide what to emit instead; they
/// are never swallowed.
struct ListenerPublisher<Output>: Publisher {
    typealias Failure = Error

    /// Attaches the platform listener and returns how to detach it.
    let attach: (@escaping (Result<Output, Error>) -> Void) -> () -> Void

    func receive<S: Subscriber>(subscriber: S) where S.Input == Output, S.Failure == Error {
        subscriber.receive(subscription: ListenerSubscription(subscriber: subscriber, attach: attach))
    }

    private final class ListenerSubscription<S: Subscriber>: Subscription
    where S.Input == Output, S.Failure == Error {
        private var subscriber: S?
        private var detach: (() -> Void)?
        private let attach: (@escaping (Result<Output, Error>) -> Void) -> () -> Void
        private let lock = NSLock()

        init(subscriber: S, attach: @escaping (@escaping (Result<Output, Error>) -> Void) -> () -> Void) {
            self.subscriber = subscriber
            self.attach = attach
        }

        func request(_ demand: Subscribers.Demand) {
            lock.lock()
            guard detach == nil, subscriber != nil else { lock.unlock(); return }
            lock.unlock()
            let handle = attach { [weak self] result in
                guard let self else { return }
                self.lock.lock()
                let target = self.subscriber
                self.lock.unlock()
                guard let target else { return }
                switch result {
                case .success(let value): _ = target.receive(value)
                case .failure(let error):
                    target.receive(completion: .failure(error))
                    self.cancel()
                }
            }
            lock.lock(); detach = handle; lock.unlock()
        }

        func cancel() {
            lock.lock()
            let handle = detach
            detach = nil
            subscriber = nil
            lock.unlock()
            handle?()
        }
    }
}

extension Query {
    func snapshots(includeMetadata: Bool = false) -> ListenerPublisher<QuerySnapshot> {
        ListenerPublisher { deliver in
            let registration = self.addSnapshotListener(includeMetadataChanges: includeMetadata) { snapshot, error in
                if let error { deliver(.failure(error)); return }
                if let snapshot { deliver(.success(snapshot)) }
            }
            return { registration.remove() }
        }
    }
}

extension DocumentReference {
    func snapshots(includeMetadata: Bool = false) -> ListenerPublisher<DocumentSnapshot> {
        ListenerPublisher { deliver in
            let registration = self.addSnapshotListener(includeMetadataChanges: includeMetadata) { snapshot, error in
                if let error { deliver(.failure(error)); return }
                if let snapshot { deliver(.success(snapshot)) }
            }
            return { registration.remove() }
        }
    }
}

extension Auth {
    /// The signed-in Firebase user, re-emitted on every auth state change.
    func userStream() -> Stream<FirebaseAuth.User?> {
        ListenerPublisher<FirebaseAuth.User?> { deliver in
            let handle = self.addStateDidChangeListener { _, user in deliver(.success(user)) }
            return { self.removeStateDidChangeListener(handle) }
        }
        .replaceError(with: nil)
        .eraseToAnyPublisher()
    }

    /// Re-runs every downstream query when the signed-in user changes.
    func uidStream() -> Stream<String?> {
        userStream().map { $0?.uid }.removeDuplicates().eraseToAnyPublisher()
    }
}

// MARK: - Flow-shaped helpers

extension Publisher where Failure == Never {
    /// Kotlin's `flatMapLatest`: switch to the newest inner stream, cancelling the old one.
    func flatMapLatest<T>(_ transform: @escaping (Output) -> Stream<T>) -> Stream<T> {
        map(transform).switchToLatest().eraseToAnyPublisher()
    }
}

/// `combine(list)`: the latest value from each stream, as an array in the same order.
/// An empty list emits an empty array once.
func combineLatestAll<T>(_ streams: [Stream<T>]) -> Stream<[T]> {
    guard let first = streams.first else { return Just([]).eraseToAnyPublisher() }
    let seed: Stream<[T]> = first.map { [$0] }.eraseToAnyPublisher()
    return streams.dropFirst().reduce(seed) { acc, next in
        acc.combineLatest(next) { $0 + [$1] }.eraseToAnyPublisher()
    }
}

extension Publisher {
    /// Fold a failure into a fallback value so the stream can be composed with `Never`.
    func orElse(_ fallback: Output) -> Stream<Output> {
        self.catch { _ in Just(fallback) }.eraseToAnyPublisher()
    }
}
