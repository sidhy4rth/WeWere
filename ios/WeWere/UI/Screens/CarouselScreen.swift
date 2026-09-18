import SwiftUI
import Combine
import Kingfisher

struct CarouselUiState: Equatable {
    var photos: [Photo] = []
    var myUid: String? = nil
    var isAdmin: Bool = false
    var isLoading: Bool = true
    var hasMoreToLoad: Bool = false
}

enum CarouselEvent: Equatable {
    case saved(String)
    case shareReady(URL, String?)
    case failed(AppError)
    case deleted
}

@MainActor
final class CarouselViewModel: ObservableObject {
    @Published private(set) var state = CarouselUiState()
    @Published private(set) var currentReaction: Reaction? = nil
    @Published private(set) var event: CarouselEvent? = nil

    let groupId: String
    let initialPhotoId: String?
    let startsInSlideshow: Bool

    private let photoRepository: PhotoRepository
    private let currentPhotoId = CurrentValueSubject<String?, Never>(nil)
    private var cancellables = Set<AnyCancellable>()

    init(groupId: String, initialPhotoId: String?, filterRaw: String, startsInSlideshow: Bool,
         container: AppContainer = .shared) {
        self.groupId = groupId
        self.initialPhotoId = initialPhotoId.flatMap { $0.isEmpty ? nil : $0 }
        self.startsInSlideshow = startsInSlideshow
        photoRepository = container.photoRepository
        currentPhotoId.send(self.initialPhotoId)

        // The grid's filter, so page N here is the photo the user tapped at position N.
        let filter = PhotoFilterCodec.decode(filterRaw)
        let authRepository = container.authRepository

        photoRepository.observePhotos(groupId: groupId, filter: filter)
            .combineLatest(container.groupRepository.observeMembership(groupId: groupId))
            .map { page, membership in
                CarouselUiState(
                    // Identical ordering to the grid, so tapping tile N lands on page N.
                    photos: BuildTimeline.orderedPhotos(page.photos),
                    myUid: authRepository.currentUid(),
                    isAdmin: membership?.role == .admin,
                    isLoading: false,
                    hasMoreToLoad: page.hasMore
                )
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)

        // The viewer's own reaction for whichever photo is on screen. It lives in its own
        // document, so this watches only the current page rather than fetching a reaction
        // for every photo in the feed.
        currentPhotoId
            .flatMapLatest { [photoRepository] id -> Stream<Reaction?> in
                guard let id else { return Just(nil).eraseToAnyPublisher() }
                return photoRepository.observePhoto(groupId: groupId, photoId: id)
                    .map { Reaction.fromKey($0?.myReaction) }
                    .eraseToAnyPublisher()
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.currentReaction = $0 }
            .store(in: &cancellables)
    }

    func onPageChanged(_ photoId: String) { currentPhotoId.send(photoId) }

    func loadOlder() { Task { await photoRepository.loadOlder(groupId: groupId) } }

    /// Tapping the reaction already set clears it, which is what users expect.
    func toggleReaction(_ photoId: String, _ reaction: Reaction) {
        Task {
            let next: Reaction? = currentReaction == reaction ? nil : reaction
            _ = await photoRepository.setReaction(groupId: groupId, photoId: photoId, reaction: next)
        }
    }

    func setCaption(_ photoId: String, _ caption: String?) {
        Task { _ = await photoRepository.setCaption(groupId: groupId, photoId: photoId, caption: caption) }
    }

    func deletePhoto(_ photoId: String) {
        Task {
            switch await photoRepository.deletePhoto(groupId: groupId, photoId: photoId) {
            case .success: event = .deleted
            case .failure(let error): event = .failed(error)
            }
        }
    }

    func download(_ photo: Photo) {
        Task {
            switch await photoRepository.downloadToGallery(photo) {
            case .success: event = .saved("Saved to your photos")
            case .failure(let error): event = .failed(error)
            }
        }
    }

    func share(_ photo: Photo) {
        Task {
            switch await photoRepository.prepareForSharing(photo) {
            case .success(let url): event = .shareReady(url, photo.caption)
            case .failure(let error): event = .failed(error)
            }
        }
    }

    func toggleFavorite(_ photo: Photo) {
        Task {
            _ = await photoRepository.setFavorite(groupId: groupId, photoId: photo.id, favorite: !photo.isFavorited(by: state.myUid))
        }
    }

    func report(_ photoId: String, reason: String) {
        Task {
            _ = await photoRepository.reportPhoto(groupId: groupId, photoId: photoId, reason: reason)
            event = .saved("Reported — thanks for flagging it")
        }
    }

    func consumeEvent() { event = nil }

    func canDelete(_ photo: Photo) -> Bool { photo.uploadedBy == state.myUid || state.isAdmin }
}

/// Draft for the caption dialog, so the text field and the Save button share it.
final class CaptionDraft: ObservableObject {
    @Published var text: String
    init(_ text: String) { self.text = text }
}

struct CarouselScreen: View {
    let onClose: () -> Void

    @StateObject private var viewModel: CarouselViewModel
    @EnvironmentObject private var overlays: Overlays

    @State private var currentId: String? = nil
    @State private var controlsVisible = true
    @State private var isZoomed = false
    @State private var dismissOffset: CGFloat = 0
    @State private var slideshowRunning: Bool
    @State private var seeded = false

    init(groupId: String, initialPhotoId: String?, filterRaw: String, startsInSlideshow: Bool, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _viewModel = StateObject(wrappedValue: CarouselViewModel(
            groupId: groupId, initialPhotoId: initialPhotoId, filterRaw: filterRaw, startsInSlideshow: startsInSlideshow))
        _slideshowRunning = State(initialValue: startsInSlideshow)
    }

    var body: some View {
        let state = viewModel.state
        let dismissProgress = min(max(abs(dismissOffset) / Self.dismissThreshold, 0), 1)
        let currentPhoto = state.photos.first { $0.id == currentId } ?? state.photos.first

        ZStack {
            // Fading the backdrop as the photo is dragged away is what makes the
            // gesture feel like dismissal rather than a scroll that went wrong.
            Color.black.opacity(1 - dismissProgress * 0.6).ignoresSafeArea()

            if state.isLoading && state.photos.isEmpty {
                ProgressView().tint(.white)
            }

            pager(state)
                .offset(y: dismissOffset)
                .scaleEffect(1 - dismissProgress * 0.12)
                .simultaneousGesture(dismissGesture, including: isZoomed ? .subviews : .all)

            if controlsVisible, let photo = currentPhoto {
                VStack(spacing: 0) {
                    TopControls(
                        photo: photo,
                        canDelete: viewModel.canDelete(photo),
                        canEditCaption: photo.uploadedBy == state.myUid,
                        isSlideshowRunning: slideshowRunning,
                        onToggleSlideshow: {
                            slideshowRunning.toggle()
                            if slideshowRunning { controlsVisible = false }
                        },
                        onClose: onClose,
                        onDelete: { viewModel.deletePhoto(photo.id) },
                        onEditCaption: { editCaption(photo) },
                        onReport: { viewModel.report(photo.id, reason: "inappropriate") }
                    )
                    Spacer()
                    BottomControls(
                        photo: photo,
                        position: state.photos.firstIndex { $0.id == photo.id } ?? 0,
                        total: state.photos.count,
                        myReaction: viewModel.currentReaction,
                        isFavorite: photo.isFavorited(by: state.myUid),
                        canDelete: viewModel.canDelete(photo),
                        onReact: { viewModel.toggleReaction(photo.id, $0) },
                        onToggleFavorite: { viewModel.toggleFavorite(photo) },
                        onShare: { viewModel.share(photo) },
                        onDownload: { viewModel.download(photo) },
                        onDelete: { viewModel.deletePhoto(photo.id) }
                    )
                }
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: controlsVisible)
        .preferredColorScheme(.dark)
        .statusBarHidden(!controlsVisible)
        // Re-seed once the feed arrives, otherwise the pager opens on page 0 while the
        // photo list is still empty and the tapped photo is never shown.
        .onChange(of: state.photos.isEmpty, initial: true) { _, empty in
            guard !empty, !seeded else { return }
            seeded = true
            let target = viewModel.initialPhotoId.flatMap { id in state.photos.contains { $0.id == id } ? id : nil }
            currentId = target ?? state.photos.first?.id
        }
        .onChange(of: currentId) { _, id in
            guard let id else { return }
            viewModel.onPageChanged(id)
            // Reaching the end of the loaded window pulls in the next page.
            if let index = state.photos.firstIndex(where: { $0.id == id }), index >= state.photos.count - 3 {
                viewModel.loadOlder()
            }
        }
        .onChange(of: viewModel.event) { _, event in
            guard let event else { return }
            switch event {
            case .saved(let message):
                overlays.snack(message)
            case .shareReady(let url, let caption):
                Sharing.sharePhoto(url: url, caption: caption)
            case .failed(let error):
                overlays.snack(error.message ?? "That didn't work")
            case .deleted:
                if state.photos.count <= 1 { onClose() }
            }
            viewModel.consumeEvent()
        }
        // Autoplay. Keeping the screen awake while it runs is the point — a slideshow
        // that blanks after thirty seconds is worse than no slideshow, and this is the
        // one moment the phone is deliberately propped up and not being touched.
        .onChange(of: slideshowRunning, initial: true) { _, running in
            UIApplication.shared.isIdleTimerDisabled = running
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .task(id: slideshowRunning) {
            guard slideshowRunning else { return }
            controlsVisible = false
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.slideInterval * 1_000_000_000))
                if Task.isCancelled { break }
                let photos = viewModel.state.photos
                guard let current = currentId, let index = photos.firstIndex(where: { $0.id == current }) else { continue }
                let next = index + 1
                if next >= photos.count {
                    // Stop at the end rather than looping; a loop hides that it finished.
                    if !viewModel.state.hasMoreToLoad {
                        slideshowRunning = false
                        controlsVisible = true
                        break
                    }
                    viewModel.loadOlder()
                } else {
                    withAnimation(.easeInOut(duration: 0.45)) { currentId = photos[next].id }
                }
            }
        }
    }

    private func pager(_ state: CarouselUiState) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(state.photos) { photo in
                    ZoomableImage(
                        onTap: {
                            if slideshowRunning {
                                slideshowRunning = false
                                controlsVisible = true
                            } else {
                                controlsVisible.toggle()
                            }
                        },
                        onZoomChanged: { isZoomed = $0 }
                    ) {
                        // The cached grid thumbnail fills the frame instantly while the
                        // full-resolution image decodes over it.
                        KFImage(URL(string: photo.imageUrl))
                            .placeholder {
                                KFImage(URL(string: photo.thumbnailUrl))
                                    .resizable()
                                    .aspectRatio(contentMode: .fit)
                            }
                            .fade(duration: 0.4)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .accessibilityLabel(photo.caption ?? "Photo by \(photo.uploaderName)")
                    }
                    .containerRelativeFrame(.horizontal)
                    .id(photo.id)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $currentId)
        .scrollIndicators(.hidden)
        .scrollDisabled(isZoomed)
        .ignoresSafeArea()
    }

    private var dismissGesture: some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { value in
                guard abs(value.translation.height) > abs(value.translation.width) || dismissOffset != 0 else { return }
                dismissOffset = value.translation.height
            }
            .onEnded { _ in
                if abs(dismissOffset) > Self.dismissThreshold {
                    onClose()
                } else {
                    withAnimation(.settle(duration: 0.35)) { dismissOffset = 0 }
                }
            }
    }

    private func editCaption(_ photo: Photo) {
        let draft = CaptionDraft(photo.caption ?? "")
        overlays.show(DialogSpec(
            title: "Caption",
            content: AnyView(CaptionField(draft: draft)),
            confirm: .init(label: "Save") {
                overlays.closeDialog()
                viewModel.setCaption(photo.id, draft.text.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty)
            },
            dismiss: .init(label: "Cancel") { overlays.closeDialog() }
        ))
    }

    /// How far the photo must be dragged before letting go closes the viewer.
    private static let dismissThreshold: CGFloat = 320 / 2.75
    /// Long enough to actually look at a photo, short enough to hold a room's attention.
    private static let slideInterval: Double = 3.5
}

private struct CaptionField: View {
    @ObservedObject var draft: CaptionDraft

    var body: some View {
        RollTextField(
            text: Binding(get: { draft.text }, set: { if $0.count <= Limits.maxCaptionLength { draft.text = $0 } }),
            placeholder: "Bro thought he could drive 😭",
            supportingText: "\(Limits.maxCaptionLength - draft.text.count) left",
            singleLine: false, minLines: 1, maxLines: 3,
            cornerRadius: RollShapes.medium,
            chipBackground: Raised
        )
    }
}

private struct TopControls: View {
    let photo: Photo
    let canDelete: Bool
    let canEditCaption: Bool
    let isSlideshowRunning: Bool
    let onToggleSlideshow: () -> Void
    let onClose: () -> Void
    let onDelete: () -> Void
    let onEditCaption: () -> Void
    let onReport: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            IconButton(icon: RollIcon.back, tint: Ivory, size: 20, label: "Close", action: onClose)
            Spacer()
            HStack(spacing: 10) {
                UserAvatar(name: photo.uploaderName, photoUrl: photo.uploaderPhotoUrl, size: 32, seed: photo.uploadedBy, borderColor: Gold)
                VStack(alignment: .leading, spacing: 2) {
                    Text(photo.uploaderName).rollStyle(RollType.titleSmall, color: Ivory).lineLimit(1)
                    Readout(text: TimeFormat.relative(photo.capturedAt ?? photo.createdAt), color: IvoryMuted)
                }
            }
            Spacer()
            Menu {
                Button(isSlideshowRunning ? "Pause slideshow" : "Play slideshow", action: onToggleSlideshow)
                if canEditCaption {
                    Button((photo.caption ?? "").isEmpty ? "Add a caption" : "Edit caption", action: onEditCaption)
                }
                if canDelete {
                    Button("Delete photo", role: .destructive, action: onDelete)
                }
                Button("Report photo", action: onReport)
            } label: {
                Image(systemName: RollIcon.more)
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Ivory)
                    .frame(width: 48, height: 48)
                    .contentShape(Circle())
            }
            .accessibilityLabel("More")
        }
        .padding(.horizontal, 4).padding(.vertical, 6)
        .background(LinearGradient(colors: [PhotoScrimTop, .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .top))
    }
}

/// Caption, who starred it, reactions, then the four verbs. The brief was explicit
/// that this should not drift into a social feed, so there is no comment thread, no
/// view count and no share-back.
private struct BottomControls: View {
    let photo: Photo
    let position: Int
    let total: Int
    let myReaction: Reaction?
    let isFavorite: Bool
    let canDelete: Bool
    let onReact: (Reaction) -> Void
    let onToggleFavorite: () -> Void
    let onShare: () -> Void
    let onDownload: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let caption = photo.caption, !caption.isEmpty {
                Text(caption).rollStyle(RollType.headlineSmall, color: Ivory).lineLimit(3)
            }

            HStack(spacing: 10) {
                if !photo.favoritedBy.isEmpty {
                    Readout(text: "Starred by \(photo.favoritedBy.count)", color: Gold)
                }
                Spacer()
                Readout(text: "\(position + 1) / \(total)", color: Muted)
            }

            HStack(spacing: 6) {
                ForEach(Reaction.allCases) { reaction in
                    let count = photo.reactionCounts[reaction.key] ?? 0
                    let selected = myReaction == reaction
                    Button { onReact(reaction) } label: {
                        HStack(spacing: 5) {
                            Text(reaction.emoji).font(.system(size: 14))
                            if count > 0 { Readout(text: "\(count)", color: selected ? Gold : Ivory) }
                        }
                        .padding(.horizontal, 11).padding(.vertical, 7)
                        .background(Capsule().fill(selected ? Gold.opacity(0.22) : Ink.opacity(0.55)))
                        .overlay(Capsule().stroke(selected ? Gold : Ivory.opacity(0.15), lineWidth: 1))
                    }
                    .buttonStyle(PlainPressStyle())
                }
            }

            HStack(spacing: 8) {
                Spacer()
                StarAction(isFavorite: isFavorite, onClick: onToggleFavorite)
                ViewerAction(icon: RollIcon.share, label: "Share", onClick: onShare)
                ViewerAction(icon: RollIcon.download, label: "Save", onClick: onDownload)
                if canDelete {
                    ViewerAction(icon: RollIcon.delete, label: "Delete", onClick: onDelete)
                }
                Spacer()
            }
        }
        .padding(.top, 40)
        .padding(.horizontal, 24).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(colors: [.clear, PhotoScrimBottom], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .bottom))
    }
}

private struct ViewerAction: View {
    let icon: String
    let label: String
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 6) {
                ZStack {
                    Circle().stroke(Ivory.opacity(0.2), lineWidth: 1)
                    Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(Ivory)
                }
                .frame(width: 48, height: 48)
                Readout(text: label, color: IvoryMuted)
            }
            .frame(width: 64)
        }
        .buttonStyle(PlainPressStyle())
        .accessibilityLabel(label)
    }
}

/// The star pops and throws a ring when it lights up; unstarring is quiet.
private struct StarAction: View {
    let isFavorite: Bool
    let onClick: () -> Void

    @State private var scale: CGFloat = 1
    @State private var burst: CGFloat = 0
    @State private var wasFavorite: Bool? = nil

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 6) {
                ZStack {
                    if burst > 0 && burst < 1 {
                        Circle()
                            .stroke(Gold.opacity(0.9 * (1 - burst)), lineWidth: 1.5)
                            .scaleEffect(0.4 + 1.8 * burst)
                    }
                    Circle().fill(isFavorite ? Gold.opacity(0.14) : .clear)
                    Circle().stroke(isFavorite ? Gold : Ivory.opacity(0.2), lineWidth: 1)
                    Image(systemName: isFavorite ? RollIcon.star : RollIcon.starBorder)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(isFavorite ? Gold : Ivory)
                        .scaleEffect(scale)
                }
                .frame(width: 48, height: 48)
                Readout(text: isFavorite ? "Starred" : "Star", color: isFavorite ? Gold : IvoryMuted)
            }
            .frame(width: 64)
        }
        .buttonStyle(PlainPressStyle())
        .accessibilityLabel(isFavorite ? "Remove star" : "Star this photo")
        .onChange(of: isFavorite, initial: true) { _, now in
            defer { wasFavorite = now }
            guard let was = wasFavorite, now, !was else { return }
            burst = 0
            withAnimation(.settle(duration: 0.7)) { burst = 1 }
            scale = 0.6
            withAnimation(.settle(duration: 0.22)) { scale = 1.25 }
            withAnimation(.settle(duration: 0.26).delay(0.22)) { scale = 1 }
        }
    }
}

/// Pinch and double-tap zoom for one page of the carousel.
///
/// The pager owns horizontal swiping, so this has to be careful about which gestures
/// it consumes. While zoomed out it consumes nothing horizontally and the pager keeps
/// working; once zoomed in, the pager is disabled and panning moves the image, clamped
/// to its edges — the behaviour that makes zoom and swipe coexist instead of fighting.
///
/// `onZoomChanged` tells the parent whether vertical swipe-to-dismiss should still be
/// armed: dragging a zoomed-in photo means panning, not closing.
struct ZoomableImage<Content: View>: View {
    var maxScale: CGFloat = 4
    var onTap: () -> Void = {}
    var onZoomChanged: (Bool) -> Void = { _ in }
    @ViewBuilder let content: () -> Content

    @State private var scale: CGFloat = 1
    @State private var pinchBase: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var panBase: CGSize = .zero

    private var isZoomed: Bool { scale > 1.01 }

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            content()
                .frame(width: size.width, height: size.height)
                .scaleEffect(scale)
                .offset(offset)
                .contentShape(Rectangle())
                .gesture(
                    SpatialTapGesture(count: 2).onEnded { value in
                        withAnimation(.settle(duration: 0.35)) {
                            if isZoomed {
                                scale = 1; offset = .zero
                            } else {
                                // Zoom toward the point the user tapped, not the centre.
                                let target: CGFloat = 2.5
                                let centre = CGPoint(x: size.width / 2, y: size.height / 2)
                                let candidate = CGSize(width: (centre.x - value.location.x) * (target - 1),
                                                       height: (centre.y - value.location.y) * (target - 1))
                                offset = clamp(candidate, scale: target, in: size)
                                scale = target
                            }
                        }
                        panBase = offset; pinchBase = scale
                    }
                    .exclusively(before: TapGesture().onEnded { onTap() })
                )
                .simultaneousGesture(
                    MagnificationGesture()
                        .onChanged { value in
                            let next = min(max(pinchBase * value, 1), maxScale)
                            scale = next
                            offset = next <= 1.01 ? .zero : clamp(offset, scale: next, in: size)
                        }
                        .onEnded { _ in pinchBase = scale; panBase = offset }
                )
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            offset = clamp(CGSize(width: panBase.width + value.translation.width,
                                                  height: panBase.height + value.translation.height),
                                           scale: scale, in: size)
                        }
                        .onEnded { _ in panBase = offset },
                    including: isZoomed ? .all : .subviews
                )
                .onChange(of: isZoomed) { _, zoomed in onZoomChanged(zoomed) }
        }
    }

    /// Keeps the image from being dragged off into empty space.
    private func clamp(_ candidate: CGSize, scale: CGFloat, in size: CGSize) -> CGSize {
        let maxX = max(size.width * (scale - 1) / 2, 0)
        let maxY = max(size.height * (scale - 1) / 2, 0)
        return CGSize(width: min(max(candidate.width, -maxX), maxX), height: min(max(candidate.height, -maxY), maxY))
    }
}
