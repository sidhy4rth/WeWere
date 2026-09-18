import Foundation
import BackgroundTasks
import Network
import UIKit

/// Owns the single queue-draining run.
///
/// WorkManager's job on Android. The queue runs as one task at a time: enqueuing ten
/// photos in a row joins the run already in progress, it does not restart it ten times
/// and lose the partial upload each time. A retryable failure schedules another pass
/// after an exponential backoff; the network coming back, the app returning to the
/// foreground, or an explicit "Retry" restart it immediately.
@MainActor
final class UploadScheduler {

    private let worker: UploadWorker
    private let store: UploadQueueStore
    private var task: Task<Void, Never>?
    private var backoffStep = 0
    private var backgroundTaskId: UIBackgroundTaskIdentifier = .invalid
    private let monitor = NWPathMonitor()
    private var wasOnline = true

    static let backgroundTaskIdentifier = "com.rollapp.shared.upload"

    init(worker: UploadWorker, store: UploadQueueStore) {
        self.worker = worker
        self.store = store

        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Coming back online is worth an immediate pass, not the rest of a backoff.
                if online && !self.wasOnline { self.restartNow() }
                self.wasOnline = online
            }
        }
        monitor.start(queue: DispatchQueue(label: "com.rollapp.shared.network"))
    }

    /// KEEP semantics: join the run in progress if there is one.
    func ensureRunning() {
        if let task, !task.isCancelled { return }
        start()
    }

    /// Used by an explicit "Retry now" tap, which should not wait out the backoff.
    func restartNow() {
        task?.cancel()
        task = nil
        backoffStep = 0
        start()
    }

    private func start() {
        beginBackgroundTask()
        task = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                let retry = await self.worker.drain()
                if Task.isCancelled { break }
                guard retry, self.store.countActive() > 0 else { break }
                // 30s, 60s, 2min… capped at ten minutes, like WorkManager's exponential policy.
                let delay = min(30.0 * pow(2.0, Double(self.backoffStep)), 600)
                self.backoffStep += 1
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
            await MainActor.run {
                self.task = nil
                self.endBackgroundTask()
                self.scheduleBackgroundProcessingIfNeeded()
            }
        }
    }

    // MARK: - Background time

    /// Buys the upload a little time to finish when the user leaves the app. If the
    /// OS refuses it, the upload still proceeds — it just gets less runway, and the
    /// queue on disk picks up where it left off on the next launch.
    private func beginBackgroundTask() {
        guard backgroundTaskId == .invalid else { return }
        backgroundTaskId = UIApplication.shared.beginBackgroundTask(withName: "upload-queue") { [weak self] in
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTaskId != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskId)
        backgroundTaskId = .invalid
    }

    /// Registers the processing task; must run before the app finishes launching.
    func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.backgroundTaskIdentifier, using: nil) { [weak self] bgTask in
            guard let self, let processing = bgTask as? BGProcessingTask else { bgTask.setTaskCompleted(success: false); return }
            let run = Task { [weak self] in
                guard let self else { return }
                let retry = await self.worker.drain()
                processing.setTaskCompleted(success: !retry)
                await MainActor.run { self.scheduleBackgroundProcessingIfNeeded() }
            }
            processing.expirationHandler = { run.cancel() }
        }
    }

    /// Asks for a background slot when photos are still waiting. iOS decides when.
    func scheduleBackgroundProcessingIfNeeded() {
        guard store.countActive() > 0 else { return }
        let request = BGProcessingTaskRequest(identifier: Self.backgroundTaskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }
}
