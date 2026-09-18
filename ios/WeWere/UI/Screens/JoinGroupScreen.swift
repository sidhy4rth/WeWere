import SwiftUI

struct JoinGroupUiState: Equatable {
    var code: String = ""
    var preview: GroupPreview? = nil
    var isLookingUp: Bool = false
    var isJoining: Bool = false
    var error: AppError? = nil
    var joinedGroupId: String? = nil

    var canLookUp: Bool { !isLookingUp && !isJoining && code.count >= Limits.inviteCodeLength }
}

@MainActor
final class JoinGroupViewModel: ObservableObject {
    @Published var state = JoinGroupUiState()

    private let groupRepository: GroupRepository

    init(initialCode: String?, container: AppContainer = .shared) {
        groupRepository = container.groupRepository
        // Arriving from an invite link: fill the field and look it up straight away,
        // so the user lands on "You've been invited to…" rather than an empty form.
        if let prefilled = initialCode.map(InviteCodes.normalise), !prefilled.isEmpty {
            state.code = prefilled
            lookUp()
        }
    }

    func onCodeChange(_ value: String) {
        let normalised = String(InviteCodes.normalise(value).prefix(Limits.inviteCodeLength + 3))
        state.code = normalised; state.error = nil; state.preview = nil
    }

    /// A scanned code skips the keyboard entirely and goes straight to the preview.
    func onCodeScanned(_ code: String) {
        state.code = InviteCodes.normalise(code); state.error = nil; state.preview = nil
        lookUp()
    }

    func dismissError() { state.error = nil }
    func clearPreview() { state.preview = nil }

    func lookUp() {
        let code = state.code
        if code.isEmpty { return }
        Task {
            state.isLookingUp = true; state.error = nil
            switch await groupRepository.previewByInviteCode(code) {
            case .success(let preview): state.isLookingUp = false; state.preview = preview
            case .failure(let error): state.isLookingUp = false; state.error = error
            }
        }
    }

    func join() {
        let code = state.code
        if code.isEmpty || state.isJoining { return }
        Task {
            state.isJoining = true; state.error = nil
            switch await groupRepository.joinByInviteCode(code) {
            case .success(let group): state.isJoining = false; state.joinedGroupId = group.id
            case .failure(let error): state.isJoining = false; state.error = error
            }
        }
    }
}

struct JoinGroupScreen: View {
    let initialCode: String?
    let onBack: () -> Void
    let onJoined: (String) -> Void

    @StateObject private var viewModel: JoinGroupViewModel
    @State private var scanning = false
    /// The field's own text. SwiftUI will not redraw a TextField whose binding setter
    /// rewrote the value mid-edit, so the upper-casing goes through this and back.
    @State private var codeText = ""

    init(initialCode: String?, onBack: @escaping () -> Void, onJoined: @escaping (String) -> Void) {
        self.initialCode = initialCode
        self.onBack = onBack
        self.onJoined = onJoined
        _viewModel = StateObject(wrappedValue: JoinGroupViewModel(initialCode: initialCode))
    }

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 0) {
            RollTopBar(title: "Join a roll", onBack: onBack)
            ScrollView {
                VStack(spacing: 0) {
                    if let preview = state.preview {
                        previewSection(preview, state: state)
                    } else {
                        codeSection(state)
                    }
                }
                .padding(.horizontal, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Ink.ignoresSafeArea())
        .onAppear { codeText = viewModel.state.code }
        .onChange(of: codeText) { _, typed in
            let normalised = String(InviteCodes.normalise(typed).prefix(Limits.inviteCodeLength + 3))
            if normalised != typed { codeText = normalised; return }
            if normalised != viewModel.state.code { viewModel.onCodeChange(normalised) }
        }
        .onChange(of: state.code) { _, code in if code != codeText { codeText = code } }
        .onChange(of: state.joinedGroupId) { _, id in if let id { onJoined(id) } }
        .fullScreenCover(isPresented: $scanning) {
            ScanQrScreen(onClose: { scanning = false }, onCodeScanned: { code in
                scanning = false
                viewModel.onCodeScanned(code)
            })
        }
    }

    @ViewBuilder private func codeSection(_ state: JoinGroupUiState) -> some View {
        Spacer().frame(height: 40)
        Text("Enter the invite code")
            .rollStyle(RollType.headlineMedium, color: Ivory)
            .multilineTextAlignment(.center)
        Spacer().frame(height: 8)
        Text("Six characters, from whoever invited you.")
            .rollStyle(RollType.bodyMedium, color: IvoryMuted)
            .multilineTextAlignment(.center)

        Spacer().frame(height: 32)

        // Monospace and wide tracking: a code is read character by character, not as a word.
        RollTextField(
            text: $codeText,
            placeholder: "GA7X2M",
            capitalization: .characters,
            submitLabel: .go,
            cornerRadius: RollShapes.medium,
            textStyle: RollTextStyle(face: Face.mono, size: 28, lineHeight: 36, tracking: 8),
            textColor: Gold,
            textAlignment: .center,
            onSubmit: { if state.canLookUp { viewModel.lookUp() } }
        )

        if let error = state.error {
            Spacer().frame(height: 16)
            InlineError(message: error.message ?? "That code didn't work")
        }

        Spacer().frame(height: 16)

        HairlineButton(text: "Scan their QR code", icon: RollIcon.qrScanner) { scanning = true }

        Spacer().frame(height: 16)

        GoldButton(text: "Find roll", enabled: state.canLookUp, loading: state.isLookingUp) {
            viewModel.lookUp()
        }
    }

    @ViewBuilder private func previewSection(_ preview: GroupPreview, state: JoinGroupUiState) -> some View {
        Spacer().frame(height: 28)

        ZStack {
            if let cover = preview.coverPhotoUrl, !cover.isEmpty {
                RemoteImage(url: URL(string: cover), contentMode: .fill, downsample: 1200)
            } else {
                Image(systemName: RollIcon.groups)
                    .font(.system(size: 38, weight: .medium))
                    .foregroundStyle(IvoryMuted)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(16 / 9, contentMode: .fit)
        .background(Raised)
        .clipShape(RoundedRectangle(cornerRadius: RollShapes.large))

        Spacer().frame(height: 24)

        Text("You've been invited to").rollStyle(RollType.bodyMedium, color: IvoryMuted)
        Spacer().frame(height: 4)
        Text(preview.name).rollStyle(RollType.headlineLarge, color: Ivory).multilineTextAlignment(.center)
        Spacer().frame(height: 8)
        Text("\(preview.memberCount) \(preview.memberCount == 1 ? "member" : "members") · \(preview.photoCount) \(preview.photoCount == 1 ? "photo" : "photos")")
            .rollStyle(RollType.bodyMedium, color: IvoryMuted)

        if let description = preview.description, !description.isEmpty {
            Spacer().frame(height: 12)
            Text(description).rollStyle(RollType.bodyMedium, color: IvoryMuted).multilineTextAlignment(.center)
        }

        if let error = state.error {
            Spacer().frame(height: 16)
            InlineError(message: error.message ?? "Couldn't join")
        }

        Spacer().frame(height: 32)

        GoldButton(text: preview.alreadyMember ? "Open roll" : "Join roll", enabled: !state.isJoining, loading: state.isJoining) {
            if preview.alreadyMember { onJoined(preview.id) } else { viewModel.join() }
        }

        TextButton(text: "Use a different code") { viewModel.clearPreview() }
    }
}
