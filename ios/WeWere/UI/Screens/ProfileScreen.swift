import SwiftUI
import Combine
import PhotosUI

struct ProfileUiState: Equatable {
    var user: User? = nil
    var prefs: NotificationPrefs = NotificationPrefs()
    var isSaving: Bool = false
    var error: AppError? = nil
    var signedOut: Bool = false
}

@MainActor
final class ProfileViewModel: ObservableObject {
    @Published private(set) var state = ProfileUiState()

    private let authRepository: AuthRepository
    private let userRepository: UserRepository
    private let saving = CurrentValueSubject<Bool, Never>(false)
    private let errorFlow = CurrentValueSubject<AppError?, Never>(nil)
    private let signedOut = CurrentValueSubject<Bool, Never>(false)
    private var cancellables = Set<AnyCancellable>()

    init(container: AppContainer = .shared) {
        authRepository = container.authRepository
        userRepository = container.userRepository

        let user: Stream<User?> = authRepository.currentUser.flatMapLatest { [userRepository] auth -> Stream<User?> in
            guard let auth else { return Just(nil).eraseToAnyPublisher() }
            return userRepository.observeUser(uid: auth.uid)
        }

        user.combineLatest(userRepository.observeNotificationPrefs(), saving, errorFlow)
            .combineLatest(signedOut)
            .map { first, out in
                let (user, prefs, isSaving, error) = first
                return ProfileUiState(user: user, prefs: prefs, isSaving: isSaving, error: error, signedOut: out)
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
    }

    func updateName(_ name: String) { run { await self.userRepository.updateDisplayName(name) } }
    func updatePhoto(_ url: URL) {
        run {
            let outcome = await self.userRepository.updateProfilePhoto(url: url).unit
            try? FileManager.default.removeItem(at: url)
            return outcome
        }
    }
    func updatePrefs(_ prefs: NotificationPrefs) { run { await self.userRepository.updateNotificationPrefs(prefs) } }

    /// Upgrades a guest account in place. The uid is preserved by Firebase's credential
    /// linking, so every group the guest already joined comes with them — which is the
    /// only reason guest onboarding is worth offering at all.
    func convertGuestToAccount(name: String, email: String, password: String) {
        run { await self.authRepository.linkAnonymousToEmail(name: name, email: email, password: password).unit }
    }

    func signOut() {
        Task {
            _ = await authRepository.signOut()
            signedOut.send(true)
        }
    }

    func deleteAccount() {
        Task {
            saving.send(true)
            switch await authRepository.deleteAccount() {
            case .success: signedOut.send(true)
            case .failure(let error): errorFlow.send(error)
            }
            saving.send(false)
        }
    }

    func dismissError() { errorFlow.send(nil) }

    private func run(_ block: @escaping () async -> Outcome<Void>) {
        if saving.value { return }
        Task {
            saving.send(true)
            errorFlow.send(nil)
            if case .failure(let error) = await block() { errorFlow.send(error) }
            saving.send(false)
        }
    }
}

final class NameDraft: ObservableObject {
    @Published var name: String
    init(_ name: String) { self.name = name }
}

final class GuestDraft: ObservableObject {
    @Published var name = ""
    @Published var email = ""
    @Published var password = ""
    var canSubmit: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && email.contains("@") && password.count >= 6 }
}

struct ProfileScreen: View {
    let onSignedOut: () -> Void

    @StateObject private var viewModel = ProfileViewModel()
    @EnvironmentObject private var overlays: Overlays
    @State private var photoPick: PhotosPickerItem? = nil

    var body: some View {
        let state = viewModel.state
        let user = state.user
        VStack(spacing: 0) {
            RollTopBar(title: "You")
            ScrollView {
                VStack(spacing: 0) {
                    if let error = state.error {
                        InlineError(message: error.message ?? "Something went wrong",
                                    actionLabel: "Dismiss", onAction: viewModel.dismissError)
                            .padding(16)
                    }

                    VStack(spacing: 0) {
                        // The photo is the profile: a gold camera badge says "this is yours to
                        // change" without a label, and the button below says it out loud.
                        PhotosPicker(selection: $photoPick, matching: .images) {
                            ZStack(alignment: .bottomTrailing) {
                                UserAvatar(name: user?.name ?? "", photoUrl: user?.photoUrl, size: 112,
                                           seed: user?.uid ?? "", borderColor: Gold)
                                ZStack {
                                    Circle().fill(Gold)
                                    Circle().stroke(Ink, lineWidth: 3)
                                    Image(systemName: RollIcon.camera).font(.system(size: 13, weight: .bold)).foregroundStyle(OnGold)
                                }
                                .frame(width: 34, height: 34)
                            }
                        }
                        .buttonStyle(PlainPressStyle())
                        .accessibilityLabel("Change profile photo")
                        Spacer().frame(height: 10)
                        PhotosPicker(selection: $photoPick, matching: .images) {
                            Text((user?.photoUrl ?? "").isEmpty ? "Add a photo" : "Change photo")
                                .rollStyle(RollType.bodySmall, color: Gold)
                                .padding(.horizontal, 12).frame(minHeight: 40)
                        }
                        .buttonStyle(PlainPressStyle())
                        Spacer().frame(height: 4)
                        Button { editName(user?.name ?? "") } label: {
                            HStack(spacing: 6) {
                                Text(user?.name ?? "").rollStyle(RollType.headlineMedium, color: Ivory)
                                Image(systemName: RollIcon.edit).font(.system(size: 15, weight: .medium)).foregroundStyle(IvoryMuted)
                            }
                        }
                        .buttonStyle(PlainPressStyle())
                        if let email = user?.email, !email.isEmpty {
                            Text(email).rollStyle(RollType.bodyMedium, color: IvoryMuted)
                        }
                        if let created = user?.createdAt, created > 0 {
                            Spacer().frame(height: 6)
                            Readout(text: "On WeWere since \(TimeFormat.absoluteDate(created))", color: Gold)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                    .onChange(of: photoPick) { _, item in
                        guard let item else { return }
                        Task {
                            let urls = await PhotoPicking.stage([item], into: AppContainer.shared.capturesDir)
                            photoPick = nil
                            if let url = urls.first { viewModel.updatePhoto(url) }
                        }
                    }

                    if user?.isAnonymous == true {
                        GoldButton(text: "Save your account") { upgradeGuest() }
                            .padding(.horizontal, 20)
                        Text("You're signed in as a guest. Add an email so you don't lose your rolls if you change phones.")
                            .rollStyle(RollType.bodySmall, color: IvoryMuted)
                            .padding(.horizontal, 24).padding(.vertical, 8)
                    }

                    HairlineRule().padding(.horizontal, 20).padding(.vertical, 16)

                    Readout(text: "Notifications", color: Gold)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20).padding(.vertical, 6)

                    PrefSwitch(label: "New photos", subtitle: "When friends add photos to your rolls",
                               checked: state.prefs.newPhotos) { var p = state.prefs; p.newPhotos = $0; viewModel.updatePrefs(p) }
                    PrefSwitch(label: "Reactions", subtitle: "When someone reacts to your photo",
                               checked: state.prefs.reactions) { var p = state.prefs; p.reactions = $0; viewModel.updatePrefs(p) }
                    PrefSwitch(label: "People joining", subtitle: "When someone new joins a roll",
                               checked: state.prefs.memberJoined) { var p = state.prefs; p.memberJoined = $0; viewModel.updatePrefs(p) }

                    HairlineRule().padding(.horizontal, 20).padding(.vertical, 16)

                    TextButton(text: "Sign out") { viewModel.signOut() }
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 12)

                    TextButton(text: "Delete account", color: ErrorColor) {
                        overlays.confirm(
                            title: "Delete your account?",
                            message: "Your profile is deleted and you're removed from your rolls. Photos you've already shared stay with those rolls.",
                            confirmLabel: "Delete account"
                        ) { viewModel.deleteAccount() }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 12)

                    Spacer().frame(height: 28)

                    Text("Made with ❤️ by sidhY4rth")
                        .rollStyle(RollType.bodySmall, color: Muted)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)

                    Spacer().frame(height: 40)
                }
            }
        }
        .background(Ink.ignoresSafeArea())
        .onChange(of: state.signedOut) { _, out in if out { onSignedOut() } }
    }

    private func editName(_ current: String) {
        let draft = NameDraft(current)
        overlays.show(DialogSpec(
            title: "Your name",
            content: AnyView(NameField(draft: draft)),
            confirm: .init(label: "Save", enabled: { !draft.name.trimmingCharacters(in: .whitespaces).isEmpty }) {
                overlays.closeDialog()
                viewModel.updateName(draft.name)
            },
            dismiss: .init(label: "Cancel") { overlays.closeDialog() }
        ))
    }

    private func upgradeGuest() {
        let draft = GuestDraft()
        overlays.show(DialogSpec(
            title: "Save your account",
            message: "Your rolls and photos stay exactly as they are.",
            content: AnyView(GuestFields(draft: draft)),
            confirm: .init(label: "Save account", enabled: { draft.canSubmit }) {
                overlays.closeDialog()
                viewModel.convertGuestToAccount(name: draft.name, email: draft.email, password: draft.password)
            },
            dismiss: .init(label: "Not now") { overlays.closeDialog() }
        ))
    }
}

private struct NameField: View {
    @ObservedObject var draft: NameDraft
    var body: some View {
        RollTextField(text: $draft.name, capitalization: .words,
                      supportingText: "This is what your friends see on your photos",
                      cornerRadius: RollShapes.medium, chipBackground: Raised)
    }
}

private struct GuestFields: View {
    @ObservedObject var draft: GuestDraft
    var body: some View {
        VStack(spacing: 8) {
            RollTextField(label: "Your name", text: $draft.name, capitalization: .words, chipBackground: Raised)
            RollTextField(label: "Email", text: $draft.email, keyboard: .emailAddress, capitalization: .never, chipBackground: Raised)
            RollTextField(label: "Password", text: $draft.password, isSecure: true, capitalization: .never, chipBackground: Raised)
        }
    }
}

private struct PrefSwitch: View {
    let label: String
    let subtitle: String
    let checked: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).rollStyle(RollType.bodyLarge, color: Ivory)
                Text(subtitle).rollStyle(RollType.bodySmall, color: IvoryMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Toggle("", isOn: Binding(get: { checked }, set: onChange))
                .labelsHidden()
                .tint(Gold)
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
        .contentShape(Rectangle())
        .onTapGesture { onChange(!checked) }
    }
}
