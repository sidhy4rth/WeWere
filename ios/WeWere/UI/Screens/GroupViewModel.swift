import SwiftUI
import Combine

enum BulkAction { case saving, deleting }

struct BulkProgress: Equatable {
    let action: BulkAction
    let done: Int
    let total: Int

    var fraction: Double { total == 0 ? 0 : Double(done) / Double(total) }
}

struct GroupUiState: Equatable {
    var group: Group? = nil
    var timeline: [TimelineItem] = []
    var photos: [Photo] = []
    var members: [Member] = []
    var pendingUploads: [PendingUpload] = []
    var myUid: String? = nil
    var isAdmin: Bool = false
    var hasMore: Bool = false
    var isLoading: Bool = true
    var filter: PhotoFilter = .all
    /// Empty means normal browsing; non-empty puts the grid in selection mode.
    var selectedIds: Set<String> = []
    var bulkProgress: BulkProgress? = nil
    /// Set when the user stops being a member while the screen is open.
    var accessRevoked: Bool = false
    var transientError: AppError? = nil

    var isEmpty: Bool { !isLoading && photos.isEmpty && pendingUploads.isEmpty }
    var isSelecting: Bool { !selectedIds.isEmpty }
    var selectedPhotos: [Photo] { photos.filter { selectedIds.contains($0.id) } }

    /// How many of the selection this user is actually allowed to remove.
    func deletableCount() -> Int { selectedPhotos.filter { isAdmin || $0.uploadedBy == myUid }.count }
    var uploadingCount: Int { pendingUploads.filter { $0.state != .failed }.count }
    var failedCount: Int { pendingUploads.filter { $0.state == .failed }.count }
}

@MainActor
final class GroupViewModel: ObservableObject {
    @Published private(set) var state = GroupUiState()

    let groupId: String
    private let photoRepository: PhotoRepository
    private let uploadQueueRepository: UploadQueueRepository
    private let authRepository: AuthRepository

    private let transientError = CurrentValueSubject<AppError?, Never>(nil)
    private var loadingMore = false
    private let filter = CurrentValueSubject<PhotoFilter, Never>(.all)
    private let selectedIds = CurrentValueSubject<Set<String>, Never>([])
    private let bulkProgress = CurrentValueSubject<BulkProgress?, Never>(nil)
    private var cancellables = Set<AnyCancellable>()

    init(groupId: String, container: AppContainer = .shared) {
        self.groupId = groupId
        photoRepository = container.photoRepository
        uploadQueueRepository = container.uploadQueueRepository
        authRepository = container.authRepository
        let groupRepository = container.groupRepository

        let photoPage: Stream<PhotoPage> = filter.flatMapLatest { [photoRepository] active -> Stream<PhotoPage> in
            photoRepository.resetPagination(groupId: groupId)
            return photoRepository.observePhotos(groupId: groupId, filter: active)
        }

        let content: Stream<GroupUiState> = groupRepository.observeGroup(groupId: groupId)
            .combineLatest(photoPage, groupRepository.observeMembers(groupId: groupId),
                           uploadQueueRepository.observePendingForGroup(groupId: groupId))
            .combineLatest(groupRepository.observeMembership(groupId: groupId))
            .map { [authRepository] first, membership -> GroupUiState in
                let (group, page, members, pending) = first
                let myUid = authRepository.currentUid()
                return GroupUiState(
                    group: group,
                    timeline: BuildTimeline.build(page.photos),
                    photos: BuildTimeline.orderedPhotos(page.photos),
                    members: members,
                    pendingUploads: pending,
                    myUid: myUid,
                    isAdmin: membership?.isAdmin == true,
                    hasMore: page.hasMore,
                    isLoading: false,
                    // A null membership while the group still exists means an admin removed
                    // this user — distinct from the group itself being deleted.
                    accessRevoked: membership == nil && group != nil && myUid != nil
                )
            }
            .eraseToAnyPublisher()

        content
            .combineLatest(transientError, filter, selectedIds)
            .combineLatest(bulkProgress)
            .map { first, progress -> GroupUiState in
                var (base, error, active, selection) = first
                base.transientError = error
                base.filter = active
                // Drop ids that have scrolled out of the window or been deleted, so the
                // count in the toolbar always matches what is actually selected.
                base.selectedIds = selection.intersection(base.photos.map(\.id))
                base.bulkProgress = progress
                return base
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
    }

    func loadOlder() {
        if loadingMore || !state.hasMore { return }
        loadingMore = true
        Task {
            await photoRepository.loadOlder(groupId: groupId)
            loadingMore = false
        }
    }

    func uploadFromGallery(_ urls: [URL], caption: String? = nil) {
        if urls.isEmpty { return }
        Task {
            for url in urls {
                // Gallery picks carry no reliable capture time at this point; the
                // worker reads EXIF off the staged copy and corrects it.
                _ = await photoRepository.enqueueUpload(groupId: groupId, localUrl: url, caption: caption, capturedAt: Date.nowMillis)
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    func retryFailed() {
        Task { _ = await uploadQueueRepository.retryAllFailed() }
    }

    func cancelUpload(_ uploadId: String) {
        Task { _ = await uploadQueueRepository.cancel(uploadId: uploadId) }
    }

    // ------------------------------------------------------------------ filters

    func setFilter(_ next: PhotoFilter) {
        if filter.value == next { return }
        clearSelection()
        filter.send(next)
    }

    func clearFilter() { setFilter(.all) }

    // ---------------------------------------------------------------- selection

    func toggleSelection(_ photoId: String) {
        var current = selectedIds.value
        if current.contains(photoId) { current.remove(photoId) } else { current.insert(photoId) }
        selectedIds.send(current)
    }

    func selectAll() { selectedIds.send(Set(state.photos.map(\.id))) }

    func clearSelection() { selectedIds.send([]) }

    // ------------------------------------------------------------------ actions

    func toggleFavorite(_ photo: Photo) {
        Task {
            _ = await photoRepository.setFavorite(groupId: groupId, photoId: photo.id, favorite: !photo.isFavorited(by: state.myUid))
        }
    }

    /// Saves the current selection to the device, reporting progress as it runs.
    func saveSelectionToDevice(onDone: @escaping (Int) -> Void) {
        let chosen = state.selectedPhotos
        if chosen.isEmpty { return }

        Task {
            bulkProgress.send(BulkProgress(action: .saving, done: 0, total: chosen.count))
            let outcome = await photoRepository.downloadAllToGallery(chosen) { [bulkProgress] done, total in
                DispatchQueue.main.async { bulkProgress.send(BulkProgress(action: .saving, done: done, total: total)) }
            }
            bulkProgress.send(nil)
            switch outcome {
            case .success(let count): clearSelection(); onDone(count)
            case .failure(let error): transientError.send(error)
            }
        }
    }

    /// Removes only the photos this user may remove; the UI states the count first.
    func deleteSelection(onDone: @escaping (Int) -> Void) {
        let current = state
        let removable = current.selectedPhotos.filter { current.isAdmin || $0.uploadedBy == current.myUid }.map(\.id)
        if removable.isEmpty { return }

        Task {
            bulkProgress.send(BulkProgress(action: .deleting, done: 0, total: removable.count))
            switch await photoRepository.deletePhotos(groupId: groupId, photoIds: removable) {
            case .success(let count): clearSelection(); onDone(count)
            case .failure(let error): transientError.send(error)
            }
            bulkProgress.send(nil)
        }
    }

    func dismissError() { transientError.send(nil) }
}
