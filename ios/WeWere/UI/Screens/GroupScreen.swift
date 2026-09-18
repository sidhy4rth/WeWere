import SwiftUI
import PhotosUI

/// What the photo viewer opens on: a tapped photo, or the roll as a slideshow.
struct ViewerRequest: Identifiable {
    let id = UUID()
    let photoId: String?
    let filter: String
    let slideshow: Bool
}

struct GroupScreen: View {
    let groupId: String
    let onBack: () -> Void
    let onOpenSettings: (String) -> Void
    let onOpenMembers: (String) -> Void

    @StateObject private var viewModel: GroupViewModel
    @EnvironmentObject private var overlays: Overlays
    @State private var picks: [PhotosPickerItem] = []
    @State private var cameraOpen = false
    @State private var viewer: ViewerRequest? = nil
    @State private var revokedShown = false

    init(groupId: String, onBack: @escaping () -> Void, onOpenSettings: @escaping (String) -> Void,
         onOpenMembers: @escaping (String) -> Void) {
        self.groupId = groupId
        self.onBack = onBack
        self.onOpenSettings = onOpenSettings
        self.onOpenMembers = onOpenMembers
        _viewModel = StateObject(wrappedValue: GroupViewModel(groupId: groupId))
    }

    var body: some View {
        let state = viewModel.state
        // Each day's hero is its most-starred photo (ties go to the newest), so the
        // section opens on the shot the group itself voted for.
        let heroIds = pickHeroes(state.timeline)
        let rows = buildRows(state: state, heroIds: heroIds)

        ZStack(alignment: .bottom) {
            Ink.ignoresSafeArea()

            VStack(spacing: 0) {
                if state.isSelecting {
                    SelectionTopBar(
                        count: state.selectedIds.count,
                        deletableCount: state.deletableCount(),
                        onClear: viewModel.clearSelection,
                        onSelectAll: viewModel.selectAll,
                        onSave: {
                            viewModel.saveSelectionToDevice { count in
                                overlays.snack("Saved \(count) \(count == 1 ? "photo" : "photos") to your photos")
                            }
                        },
                        onDelete: confirmBulkDelete
                    )
                }

                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(rows) { row in
                            rowView(row, state: state)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 170)
                }
                .scrollIndicators(.hidden)
            }

            // The shutter bar. The camera has no place in selection mode; the action
            // bar owns the screen then.
            if !state.isSelecting {
                HStack(spacing: 40) {
                    PhotosPicker(selection: $picks, maxSelectionCount: Limits.maxGallerySelection, matching: .images) {
                        RingGlyph(icon: RollIcon.photoLibrary)
                    }
                    .buttonStyle(PlainPressStyle())
                    .accessibilityLabel("Upload from gallery")
                    Shutter { cameraOpen = true }
                    Button { onOpenMembers(groupId) } label: { RingGlyph(icon: RollIcon.people) }
                        .buttonStyle(PlainPressStyle())
                        .accessibilityLabel("Members")
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 56).padding(.bottom, 16)
                .background(
                    LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: Ink, location: 0.7)],
                                   startPoint: .top, endPoint: .bottom)
                )
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.isSelecting)
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            Task {
                let urls = await PhotoPicking.stage(items, into: AppContainer.shared.capturesDir)
                picks = []
                viewModel.uploadFromGallery(urls)
            }
        }
        .onChange(of: state.accessRevoked) { _, revoked in
            guard revoked, !revokedShown else { return }
            revokedShown = true
            overlays.show(DialogSpec(
                title: "You're no longer in this roll",
                message: "An admin removed you, so its photos aren't available any more.",
                confirm: .init(label: "OK") { overlays.closeDialog(); onBack() },
                onDismissRequest: { overlays.closeDialog(); onBack() }
            ))
        }
        .onChange(of: state.transientError) { _, error in
            guard let error else { return }
            overlays.snack(error.message ?? "That didn't work")
            viewModel.dismissError()
        }
        .fullScreenCover(isPresented: $cameraOpen) {
            CameraFlow(groupId: groupId) { _ in cameraOpen = false }
        }
        .fullScreenCover(item: $viewer) { request in
            CarouselScreen(groupId: groupId, initialPhotoId: request.photoId, filterRaw: request.filter,
                           startsInSlideshow: request.slideshow, onClose: { viewer = nil })
        }
    }

    // MARK: rows

    private enum GridRow: Identifiable {
        case header
        case bulk(BulkProgress)
        case failed(Int)
        case emptyFiltered(PhotoFilter)
        case empty
        case uploadingHeader
        case pending([PendingUpload], String)
        case section(label: String, key: String)
        case hero(Photo)
        case tiles([Photo], String)
        case loadingMore

        var id: String {
            switch self {
            case .header: return "header"
            case .bulk: return "bulk"
            case .failed: return "failed"
            case .emptyFiltered, .empty: return "empty"
            case .uploadingHeader: return "uploading-header"
            case .pending(_, let key): return "pending-\(key)"
            case .section(_, let key): return "header-\(key)"
            case .hero(let photo): return "hero-\(photo.id)"
            case .tiles(_, let key): return "tiles-\(key)"
            case .loadingMore: return "loading-more"
            }
        }
    }

    private func buildRows(state: GroupUiState, heroIds: Set<String>) -> [GridRow] {
        var rows: [GridRow] = []
        if !state.isSelecting { rows.append(.header) }
        if let progress = state.bulkProgress { rows.append(.bulk(progress)) }
        if state.failedCount > 0 { rows.append(.failed(state.failedCount)) }

        if state.isEmpty {
            rows.append(state.filter.isActive ? .emptyFiltered(state.filter) : .empty)
            return rows
        }

        if !state.pendingUploads.isEmpty && !state.filter.isActive {
            rows.append(.uploadingHeader)
            for chunk in state.pendingUploads.chunked(3) {
                rows.append(.pending(chunk, chunk.map(\.id).joined(separator: "|")))
            }
        }

        var run: [Photo] = []
        func flush() {
            if !run.isEmpty { rows.append(.tiles(run, run.map(\.id).joined(separator: "|"))); run = [] }
        }
        for entry in state.timeline {
            switch entry {
            case .header(let label, let key):
                flush()
                rows.append(.section(label: label, key: key))
            case .item(let photo):
                if heroIds.contains(photo.id) && !state.isSelecting {
                    flush()
                    rows.append(.hero(photo))
                } else {
                    run.append(photo)
                    if run.count == 3 { flush() }
                }
            }
        }
        flush()

        if state.hasMore { rows.append(.loadingMore) }
        return rows
    }

    @ViewBuilder private func rowView(_ row: GridRow, state: GroupUiState) -> some View {
        switch row {
        case .header:
            GroupHeader(
                state: state,
                onBack: onBack,
                onOpenMembers: { onOpenMembers(groupId) },
                onOpenSettings: { onOpenSettings(groupId) },
                onOpenSlideshow: {
                    viewer = ViewerRequest(photoId: nil, filter: PhotoFilterCodec.encode(state.filter), slideshow: true)
                },
                onFilter: viewModel.setFilter
            )
        case .bulk(let progress):
            BulkProgressBar(progress: progress)
        case .failed(let count):
            InlineError(message: "\(count) \(count == 1 ? "photo" : "photos") didn't upload",
                        actionLabel: "Retry", onAction: viewModel.retryFailed)
        case .emptyFiltered(let filter):
            EmptyRoll(title: emptyFilterTitle(filter),
                      body_: "Nothing here yet. Clear the filter to see the whole roll.",
                      actionLabel: "Show all photos", onAction: viewModel.clearFilter)
        case .empty:
            EmptyRoll(title: "Nothing developed yet",
                      body_: "Take the first one — everyone on the roll sees it straight away.")
        case .uploadingHeader:
            SectionHeader(label: "Uploading")
        case .pending(let uploads, _):
            tileRow(count: uploads.count) { index in
                let upload = uploads[index]
                PendingTile(progress: upload.progress, isFailed: upload.state == .failed,
                            localUri: upload.localUri, onCancel: { viewModel.cancelUpload(upload.id) })
            }
        case .section(let label, _):
            SectionHeader(label: label)
        case .hero(let photo):
            HeroTile(photo: photo, onClick: { open(photo, state: state) },
                     onLongClick: { Haptics.longPress(); viewModel.toggleSelection(photo.id) })
        case .tiles(let photos, _):
            tileRow(count: photos.count) { index in
                let photo = photos[index]
                PhotoTile(
                    photo: photo,
                    isSelected: state.selectedIds.contains(photo.id),
                    isSelecting: state.isSelecting,
                    isFavorite: photo.isFavorited(by: state.myUid),
                    onClick: { open(photo, state: state) },
                    onLongClick: { Haptics.longPress(); viewModel.toggleSelection(photo.id) }
                )
            }
        case .loadingMore:
            HStack { Spacer(); GoldRing(size: 24, lineWidth: 2); Spacer() }
                .padding(24)
                .onAppear { viewModel.loadOlder() }
        }
    }

    /// Three columns, 6pt gutters; a short last row keeps its tiles square.
    @ViewBuilder private func tileRow<Tile: View>(count: Int, @ViewBuilder tile: @escaping (Int) -> Tile) -> some View {
        HStack(spacing: 6) {
            ForEach(0..<3, id: \.self) { index in
                if index < count {
                    tile(index)
                } else {
                    Color.clear.aspectRatio(1, contentMode: .fit)
                }
            }
        }
    }

    private func open(_ photo: Photo, state: GroupUiState) {
        if state.isSelecting {
            viewModel.toggleSelection(photo.id)
        } else {
            viewer = ViewerRequest(photoId: photo.id, filter: PhotoFilterCodec.encode(state.filter), slideshow: false)
        }
    }

    private func confirmBulkDelete() {
        let state = viewModel.state
        let deletable = state.deletableCount()
        let skipped = state.selectedIds.count - deletable
        var message = "They're removed for everyone in the roll and can't be recovered."
        // Be explicit rather than silently dropping part of the selection.
        if skipped > 0 {
            message += "\n\n\(skipped) of your selection \(skipped == 1 ? "was" : "were") uploaded by someone else and will be left alone."
        }
        overlays.confirm(
            title: "Delete \(deletable) \(deletable == 1 ? "photo" : "photos")?",
            message: message,
            confirmLabel: "Delete"
        ) {
            viewModel.deleteSelection { count in
                overlays.snack("Deleted \(count) \(count == 1 ? "photo" : "photos")")
            }
        }
    }
}

/// Title block: nav row, name, readout, the people, then the filters.
private struct GroupHeader: View {
    let state: GroupUiState
    let onBack: () -> Void
    let onOpenMembers: () -> Void
    let onOpenSettings: () -> Void
    let onOpenSlideshow: () -> Void
    let onFilter: (PhotoFilter) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                IconButton(icon: RollIcon.back, tint: Ivory, size: 20, label: "Back", action: onBack)
                Spacer()
                if !state.photos.isEmpty {
                    IconButton(icon: RollIcon.play, tint: Ivory, label: "Play slideshow", action: onOpenSlideshow)
                }
                IconButton(icon: RollIcon.settings, tint: Ivory, label: "Group settings", action: onOpenSettings)
            }
            .padding(.top, 4)
            .padding(.horizontal, -12)

            RiseIn {
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.group?.name ?? "")
                        .rollStyle(RollType.headlineLarge, color: Ivory)
                        .lineLimit(2)
                        .truncationMode(.tail)
                    if let group = state.group {
                        Readout(
                            text: "\(group.photoCount) \(group.photoCount == 1 ? "exposure" : "exposures") · \(group.memberCount) \(group.memberCount == 1 ? "friend" : "friends")",
                            color: Gold
                        )
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Spacer().frame(height: 16)

            // People double as the uploader filter: tap a face to see only their shots.
            RiseIn(delay: 0.08) {
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(state.members) { member in
                            let selected: Bool = {
                                if case .byUploader(let uid, _) = state.filter { return uid == member.uid }
                                return false
                            }()
                            Button {
                                onFilter(selected ? .all : .byUploader(uid: member.uid, name: member.name))
                            } label: {
                                UserAvatar(name: member.name, photoUrl: member.photoUrl, size: 34, seed: member.uid,
                                           borderColor: selected ? Gold : nil)
                            }
                            .buttonStyle(PlainPressStyle())
                        }
                        Button(action: onOpenMembers) {
                            ZStack {
                                Circle().stroke(Gold.opacity(0.6), lineWidth: 1)
                                Image(systemName: RollIcon.people).font(.system(size: 13, weight: .bold)).foregroundStyle(Gold)
                            }
                            .frame(width: 34, height: 34)
                        }
                        .buttonStyle(PlainPressStyle())
                        .accessibilityLabel("Members")
                    }
                }
                .scrollIndicators(.hidden)
            }

            Spacer().frame(height: 16)

            RiseIn(delay: 0.14) {
                HStack(spacing: 8) {
                    GoldChip(text: "All", selected: state.filter == .all) { onFilter(.all) }
                    GoldChip(text: "Starred", selected: state.filter == .favorites, icon: RollIcon.star) { onFilter(.favorites) }
                    if case .byUploader(let uid, let name) = state.filter {
                        GoldChip(text: uid == state.myUid ? "You" : firstWord(name), selected: true) { onFilter(.all) }
                    }
                    Spacer()
                }
            }

            Spacer().frame(height: 8)
        }
    }
}

private struct RingGlyph: View {
    let icon: String
    var body: some View {
        ZStack {
            Circle().fill(Ink.opacity(0.6))
            Circle().stroke(Ivory.opacity(0.2), lineWidth: 1)
            Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(Ivory)
        }
        .frame(width: 48, height: 48)
    }
}

private struct SelectionTopBar: View {
    let count: Int
    let deletableCount: Int
    let onClear: () -> Void
    let onSelectAll: () -> Void
    let onSave: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                IconButton(icon: RollIcon.close, tint: Ivory, label: "Cancel selection", action: onClear)
                Text("\(count) selected")
                    .rollStyle(RollType.headlineSmall, color: Ivory)
                    .padding(.leading, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                IconButton(icon: RollIcon.selectAll, tint: Gold, label: "Select all", action: onSelectAll)
                IconButton(icon: RollIcon.download, tint: Gold, label: "Save to device", action: onSave)
                // Hidden rather than disabled when none of the selection is yours: a
                // greyed-out bin invites a tap that can never work.
                if deletableCount > 0 {
                    IconButton(icon: RollIcon.delete, tint: Gold, label: "Delete", action: onDelete)
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 4)
            HairlineRule()
        }
        .background(Ink)
    }
}

private struct BulkProgressBar: View {
    let progress: BulkProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Readout(
                text: progress.action == .saving
                    ? "Saving \(progress.done) of \(progress.total) to your photos"
                    : "Deleting \(progress.total) photos",
                color: IvoryMuted
            )
            GoldBar(fraction: progress.fraction)
        }
        .padding(.vertical, 8)
    }
}

private struct SectionHeader: View {
    let label: String
    var body: some View {
        Text(label)
            .rollStyle(RollType.headlineSmall, color: Ivory)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 18).padding(.bottom, 6)
    }
}

private struct EmptyRoll: View {
    let title: String
    let body_: String
    var actionLabel: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            RiseIn(delay: 0.2) {
                Text(title).rollStyle(RollType.headlineMedium, color: Ivory).multilineTextAlignment(.center)
            }
            RiseIn(delay: 0.26) { HairlineRule().frame(width: 120) }
            RiseIn(delay: 0.32) {
                Text(body_).rollStyle(RollType.bodyMedium, color: Muted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            if let actionLabel, let onAction {
                Spacer().frame(height: 8)
                RiseIn(delay: 0.4) { GoldButton(text: actionLabel, action: onAction) }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48).padding(.bottom, 24)
    }
}

/// The day's opening shot, full width.
private struct HeroTile: View {
    let photo: Photo
    let onClick: () -> Void
    let onLongClick: () -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            DevelopingImage(url: photo.imageUrl, downsample: 1400)
            LinearGradient(stops: [.init(color: .clear, location: 0.5), .init(color: Ink.opacity(0.85), location: 1)],
                           startPoint: .top, endPoint: .bottom)
            HStack(spacing: 8) {
                Image(systemName: RollIcon.star).font(.system(size: 12, weight: .bold)).foregroundStyle(Gold)
                Readout(text: (photo.favoritedBy.isEmpty ? "Latest · " : "Most starred · ") + firstWord(photo.uploaderName), color: Ivory)
            }
            .padding(.leading, 14).padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(342 / 200, contentMode: .fit)
        .background(Surface)
        .clipShape(RoundedRectangle(cornerRadius: RollShapes.medium))
        .contentShape(RoundedRectangle(cornerRadius: RollShapes.medium))
        .onTapGesture(perform: onClick)
        .onLongPressGesture(perform: onLongClick)
        .accessibilityLabel(photo.caption ?? "Photo by \(photo.uploaderName)")
    }
}

/// Grid tiles load the thumbnail, never the full image. A group of a few hundred
/// photos would otherwise pull tens of megabytes to render a screen of small squares.
private struct PhotoTile: View {
    let photo: Photo
    let isSelected: Bool
    let isSelecting: Bool
    let isFavorite: Bool
    let onClick: () -> Void
    let onLongClick: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Surface
            DevelopingImage(url: photo.thumbnailUrl)
                // Shrinking the selected tile shows the ring without hiding the photo.
                .padding(isSelected ? 6 : 0)
                .clipShape(RoundedRectangle(cornerRadius: RollShapes.extraSmall))

            if isSelecting {
                ZStack {
                    Circle().fill(isSelected ? Gold : Ink.opacity(0.55))
                    if !isSelected { Circle().stroke(Ivory.opacity(0.5), lineWidth: 1) }
                    if isSelected {
                        Image(systemName: RollIcon.check).font(.system(size: 11, weight: .bold)).foregroundStyle(OnGold)
                    }
                }
                .frame(width: 22, height: 22)
                .padding(6)
            } else if isFavorite {
                ZStack {
                    Circle().fill(Ink.opacity(0.7))
                    Image(systemName: RollIcon.star).font(.system(size: 10, weight: .bold)).foregroundStyle(Gold)
                }
                .frame(width: 22, height: 22)
                .padding(6)
            }

            if photo.totalReactions > 0 && !isSelecting {
                VStack {
                    Spacer()
                    HStack {
                        Readout(text: "\(photo.totalReactions)", color: Ivory)
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Capsule().fill(Ink.opacity(0.7)))
                        Spacer()
                    }
                }
                .padding(6)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: RollShapes.small))
        .overlay(
            RoundedRectangle(cornerRadius: RollShapes.small).stroke(Gold, lineWidth: isSelected ? 2 : 0)
        )
        .contentShape(RoundedRectangle(cornerRadius: RollShapes.small))
        .onTapGesture(perform: onClick)
        .onLongPressGesture(perform: onLongClick)
        .accessibilityLabel(photo.caption ?? "Photo by \(photo.uploaderName)")
    }
}

private struct PendingTile: View {
    let progress: Float
    let isFailed: Bool
    let localUri: String
    let onCancel: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Surface
            LocalImage(url: URL(string: localUri), contentMode: .fill, downsample: 400)
            Ink.opacity(0.55)
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    if isFailed {
                        Readout(text: "Failed", color: Ivory)
                    } else {
                        GoldRing(progress: Double(min(max(progress, 0), 1)), size: 34)
                    }
                    Spacer()
                }
                Spacer()
            }
            Button(action: onCancel) {
                Image(systemName: RollIcon.close).font(.system(size: 13, weight: .bold)).foregroundStyle(Ivory)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(PlainPressStyle())
            .accessibilityLabel("Cancel upload")
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: RollShapes.small))
    }
}

/// One hero per section: the most-starred photo, newest first on a tie.
private func pickHeroes(_ timeline: [TimelineItem]) -> Set<String> {
    var heroes = Set<String>()
    var best: Photo? = nil
    func close() { if let b = best { heroes.insert(b.id) }; best = nil }
    for entry in timeline {
        switch entry {
        case .header: close()
        case .item(let photo):
            if let current = best {
                if photo.favoritedBy.count > current.favoritedBy.count { best = photo }
            } else {
                best = photo
            }
        }
    }
    close()
    return heroes
}

private func emptyFilterTitle(_ filter: PhotoFilter) -> String {
    switch filter {
    case .favorites: return "Nothing starred yet"
    case .byUploader(_, let name): return "No photos from \(firstWord(name))"
    case .all: return "No photos yet"
    }
}

func firstWord(_ s: String) -> String { String(s.split(separator: " ").first ?? Substring(s)) }

extension Array {
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}
