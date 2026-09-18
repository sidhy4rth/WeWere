import SwiftUI
import Combine

struct HomeUiState: Equatable {
    var user: User? = nil
    var groups: [Group] = []
    var pendingCount: Int = 0
    var failedCount: Int = 0
    var isLoading: Bool = true

    var isEmpty: Bool { !isLoading && groups.isEmpty }
}

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var state = HomeUiState()

    private let uploadQueueRepository: UploadQueueRepository
    private var cancellables = Set<AnyCancellable>()

    init(container: AppContainer = .shared) {
        uploadQueueRepository = container.uploadQueueRepository

        // The profile document rather than the auth user: a name or avatar changed on
        // another device should show up here without a re-login.
        let userStream: Stream<User?> = container.authRepository.currentUser.flatMapLatest { authUser -> Stream<User?> in
            guard let authUser else { return Just(nil).eraseToAnyPublisher() }
            return container.userRepository.observeUser(uid: authUser.uid)
        }

        userStream
            .combineLatest(container.groupRepository.observeMyGroups(), uploadQueueRepository.observePending())
            .map { user, groups, pending in
                HomeUiState(
                    user: user,
                    groups: groups,
                    pendingCount: pending.filter { $0.state != .failed }.count,
                    failedCount: pending.filter { $0.state == .failed }.count,
                    isLoading: false
                )
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
    }

    func retryFailedUploads() {
        Task { _ = await uploadQueueRepository.retryAllFailed() }
    }
}

struct HomeScreen: View {
    let onOpenGroup: (String) -> Void
    let onCreateGroup: () -> Void
    let onJoinGroup: () -> Void
    let onOpenProfile: () -> Void
    /// The Camera tab: a tapped roll opens the camera for it instead of the feed.
    var cameraFirst: Bool = false

    @StateObject private var viewModel = HomeViewModel()
    @State private var cameraGroupId: String? = nil

    var body: some View {
        let state = viewModel.state
        ZStack(alignment: .bottom) {
            Ink.ignoresSafeArea()

            if state.isEmpty {
                EmptyHome(onCreateGroup: onCreateGroup, onJoinGroup: onJoinGroup)
            } else {
                ScrollView {
                    LazyVStack(spacing: 16) {
                        HomeHeader(
                            name: state.user?.name ?? "",
                            photoUrl: state.user?.photoUrl,
                            rollCount: state.groups.count,
                            onOpenProfile: onOpenProfile
                        )

                        if state.failedCount > 0 {
                            InlineError(
                                message: "\(state.failedCount) \(plural(state.failedCount, "photo")) couldn't upload",
                                actionLabel: "Retry",
                                onAction: viewModel.retryFailedUploads
                            )
                        } else if state.pendingCount > 0 {
                            PendingBanner(count: state.pendingCount)
                        }

                        ForEach(Array(state.groups.enumerated()), id: \.element.id) { index, group in
                            RiseIn(delay: 0.24 + Double(min(index, 4)) * 0.12, distance: 22) {
                                RollCard(group: group) { open(group.id) }
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 200)
                }
                .scrollIndicators(.hidden)
            }

            if !state.isEmpty {
                // The primary action floats over a fade so the list never hides behind it.
                VStack(spacing: 4) {
                    RiseIn(delay: 0.62) {
                        GoldButton(text: "New roll", icon: RollIcon.add, action: onCreateGroup)
                    }
                    RiseIn(delay: 0.7) {
                        QuietButton(text: "I have an invite code", action: onJoinGroup)
                    }
                }
                .padding(.top, 64).padding(.horizontal, 24).padding(.bottom, 12)
                .background(
                    LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: Ink, location: 0.55)],
                                   startPoint: .top, endPoint: .bottom)
                )
            }
        }
        .fullScreenCover(item: $cameraGroupId) { groupId in
            CameraFlow(groupId: groupId) { finished in
                cameraGroupId = nil
                // Queue what they picked, then show them the group receiving it.
                if finished { onOpenGroup(groupId) }
            }
        }
    }

    private func open(_ groupId: String) {
        if cameraFirst { cameraGroupId = groupId } else { onOpenGroup(groupId) }
    }
}

extension String: @retroactive Identifiable {
    public var id: String { self }
}

private struct HomeHeader: View {
    let name: String
    let photoUrl: String?
    let rollCount: Int
    let onOpenProfile: () -> Void

    private var today: String {
        let f = DateFormatter(); f.locale = .current; f.dateFormat = "EEEE"
        return f.string(from: Date())
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer().frame(height: 16)
            HStack(alignment: .bottom) {
                RiseIn {
                    VStack(alignment: .leading, spacing: 6) {
                        Readout(text: "\(today) · \(rollCount) \(plural(rollCount, "roll"))", color: Gold)
                        Text("Your rolls").rollStyle(RollType.displaySmall, color: Ivory)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RiseIn(delay: 0.12) {
                    Button(action: onOpenProfile) {
                        UserAvatar(name: name, photoUrl: photoUrl, size: 44, borderColor: Gold.opacity(0.6))
                    }
                    .buttonStyle(PlainPressStyle())
                }
            }
            Spacer().frame(height: 18)
            DrawnHairline()
            Spacer().frame(height: 4)
        }
    }
}

/// The header rule draws itself left to right, once.
private struct DrawnHairline: View {
    @State private var progress: CGFloat = 0

    var body: some View {
        HairlineRule()
            .scaleEffect(x: progress, y: 1, anchor: .leading)
            .onAppear {
                guard progress == 0 else { return }
                withAnimation(.settle(duration: 0.9, delay: 0.2)) { progress = 1 }
            }
    }
}

/// A group as a roll of film: the cover fills the frame, sprocket holes run down both
/// edges, and the name sits on the developed strip at the bottom. The photograph is
/// the whole card; everything else is printed on it.
private struct RollCard: View {
    let group: Group
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            ZStack(alignment: .topLeading) {
                if let cover = group.coverPhotoUrl, !cover.isEmpty {
                    RemoteImage(url: URL(string: cover), contentMode: .fill, downsample: 1200)
                } else {
                    ZStack {
                        RadialGradient(colors: [Raised, Surface], center: .topLeading, startRadius: 0, endRadius: 320)
                        Image(systemName: RollIcon.photoLibrary)
                            .font(.system(size: 34, weight: .medium))
                            .foregroundStyle(Gold.opacity(0.35))
                    }
                }

                Sprockets()

                LinearGradient(
                    stops: [.init(color: Ink.opacity(0.05), location: 0),
                            .init(color: Ink.opacity(0.2), location: 0.45),
                            .init(color: Ink.opacity(0.92), location: 1)],
                    startPoint: .top, endPoint: .bottom
                )

                CornerMarks()

                if group.lastActivityAt > 0 {
                    HStack(spacing: 8) {
                        Circle().fill(Gold)
                            .frame(width: 7, height: 7)
                            .shadow(color: Gold, radius: 6)
                        Readout(text: TimeFormat.relative(group.lastActivityAt), color: Ivory)
                    }
                    .padding(.leading, 24).padding(.top, 16)
                }

                VStack {
                    Spacer()
                    HStack(alignment: .bottom) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(group.name)
                                .rollStyle(RollType.headlineMedium, color: Ivory)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Readout(
                                text: "\(group.photoCount) \(plural(group.photoCount, "photo")) · \(group.memberCount) \(plural(group.memberCount, "friend"))",
                                color: IvoryMuted
                            )
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if !group.recentMemberPhotos.isEmpty {
                            Spacer().frame(width: 12)
                            AvatarStack(photoUrls: group.recentMemberPhotos, size: 28, overlap: 8, borderColor: Ink)
                        }
                    }
                    .padding(.leading, 24).padding(.trailing, 24).padding(.bottom, 18)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 214)
            .background(Surface)
            .clipShape(RoundedRectangle(cornerRadius: RollShapes.large))
            .shadow(color: .black.opacity(0.6), radius: 18, y: 10)
            .contentShape(RoundedRectangle(cornerRadius: RollShapes.large))
        }
        .buttonStyle(PlainPressStyle())
    }
}

/// Two columns of film sprocket holes, faint, along the card's edges.
private struct Sprockets: View {
    var body: some View {
        Canvas { context, size in
            let pitch: CGFloat = 16
            let radius: CGFloat = 2.5
            let inset: CGFloat = 7
            let color = Ivory.opacity(0.16)
            var y = pitch / 2
            while y < size.height {
                for x in [inset, size.width - inset] {
                    context.fill(Path(ellipseIn: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)),
                                 with: .color(color))
                }
                y += pitch
            }
        }
        .allowsHitTesting(false)
    }
}

/// Viewfinder corner brackets, top corners only — a hint, not a frame.
private struct CornerMarks: View {
    var body: some View {
        Canvas { context, size in
            let len: CGFloat = 18
            let stroke: CGFloat = 1.5
            let x0: CGFloat = 22, y0: CGFloat = 14
            let x1 = size.width - x0
            var path = Path()
            path.move(to: CGPoint(x: x0, y: y0)); path.addLine(to: CGPoint(x: x0 + len, y: y0))
            path.move(to: CGPoint(x: x0, y: y0)); path.addLine(to: CGPoint(x: x0, y: y0 + len))
            path.move(to: CGPoint(x: x1, y: y0)); path.addLine(to: CGPoint(x: x1 - len, y: y0))
            path.move(to: CGPoint(x: x1, y: y0)); path.addLine(to: CGPoint(x: x1, y: y0 + len))
            context.stroke(path, with: .color(Gold), lineWidth: stroke)
        }
        .allowsHitTesting(false)
    }
}

private struct PendingBanner: View {
    let count: Int

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(Gold).frame(width: 7, height: 7)
            Readout(text: "\(count) \(plural(count, "photo")) waiting to upload", color: IvoryMuted)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: RollShapes.medium).fill(Raised))
    }
}

private struct EmptyHome: View {
    let onCreateGroup: () -> Void
    let onJoinGroup: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            PrintStack().frame(height: 340).frame(maxWidth: .infinity)
            Spacer()
            RiseIn(delay: 0.5) {
                Text("Nothing on the roll yet")
                    .rollStyle(RollType.headlineLarge, color: Ivory)
                    .multilineTextAlignment(.center)
            }
            Spacer().frame(height: 10)
            RiseIn(delay: 0.6) { HairlineRule().frame(width: 120) }
            Spacer().frame(height: 12)
            RiseIn(delay: 0.65) {
                Text("Start one for your next trip, or join a friend's with their code.")
                    .rollStyle(RollType.bodyMedium, color: Muted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
            Spacer()
            RiseIn(delay: 0.78) {
                GoldButton(text: "New roll", icon: RollIcon.add, action: onCreateGroup)
            }
            Spacer().frame(height: 4)
            RiseIn(delay: 0.86) {
                QuietButton(text: "I have an invite code", action: onJoinGroup)
            }
            Spacer().frame(height: 16)
        }
        .padding(.horizontal, 24)
    }
}

func plural(_ count: Int, _ word: String) -> String { count == 1 ? word : "\(word)s" }
