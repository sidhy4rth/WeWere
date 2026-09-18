import SwiftUI
import Combine

/// Decides the first screen and keeps the launch colour up until it knows.
@MainActor
final class RootViewModel: ObservableObject {

    enum Phase: Equatable { case loading, onboarding, auth, home }

    @Published private(set) var phase: Phase = .loading
    /// An invite that arrived from a link, waiting for the home screen to be up.
    @Published var pendingInviteCode: String? = nil
    /// A `roll://group/{id}` link, kept for parity with the notification tap on Android.
    @Published var pendingGroupId: String? = nil

    private let container: AppContainer
    private var cancellables = Set<AnyCancellable>()

    init(container: AppContainer) {
        self.container = container
        container.authRepository.currentUser
            .receive(on: DispatchQueue.main)
            .sink { [weak self] user in
                guard let self else { return }
                switch (phase, user) {
                case (.loading, nil): phase = .onboarding
                case (_, nil): if phase != .onboarding { phase = .auth }
                case (_, .some):
                    phase = .home
                    // Repairs a profile whose creation was interrupted on a previous run.
                    Task { _ = await container.authRepository.ensureProfile() }
                }
            }
            .store(in: &cancellables)
    }

    func finishOnboarding() { phase = .auth }

    /// Two link shapes arrive here: an invite (`https://wewere.vercel.app/join/XXXXXX` or
    /// `roll://join/XXXXXX`) and `roll://group/{groupId}`.
    func handleLink(_ url: URL) {
        if url.scheme == "roll" && url.host == "group" {
            if let id = url.pathComponents.dropFirst().first { pendingGroupId = id }
            return
        }
        if let code = InviteCodes.fromLink(url.absoluteString) { pendingInviteCode = code }
    }
}

struct RootView: View {
    @EnvironmentObject private var root: RootViewModel
    @StateObject private var overlays = Overlays()

    var body: some View {
        ZStack {
            Ink.ignoresSafeArea()
            switch root.phase {
            case .loading:
                Color.clear
            case .onboarding:
                OnboardingScreen(onFinished: root.finishOnboarding)
                    .transition(.opacity)
            case .auth:
                AuthScreen(onSignedIn: {})
                    .transition(.opacity)
            case .home:
                MainShell()
                    .transition(.opacity)
            }
            OverlayHost()
        }
        .environmentObject(overlays)
        .animation(.easeInOut(duration: 0.25), value: root.phase)
    }
}
