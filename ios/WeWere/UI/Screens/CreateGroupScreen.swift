import SwiftUI
import PhotosUI

struct CreateGroupUiState: Equatable {
    var name: String = ""
    var description: String = ""
    var coverUrl: URL? = nil
    var isCreating: Bool = false
    var error: AppError? = nil
    var created: Group? = nil

    var canCreate: Bool { !isCreating && !name.trimmingCharacters(in: .whitespaces).isEmpty }
    var nameRemaining: Int { Limits.maxGroupNameLength - name.count }
}

@MainActor
final class CreateGroupViewModel: ObservableObject {
    @Published var state = CreateGroupUiState()

    private let groupRepository: GroupRepository
    let capturesDir: URL

    init(container: AppContainer = .shared) {
        groupRepository = container.groupRepository
        capturesDir = container.capturesDir
    }

    func onNameChange(_ value: String) {
        if value.count > Limits.maxGroupNameLength { return }
        state.name = value; state.error = nil
    }

    func onDescriptionChange(_ value: String) {
        if value.count > Limits.maxGroupDescriptionLength { return }
        state.description = value
    }

    func onCoverSelected(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            let urls = await PhotoPicking.stage([item], into: capturesDir)
            state.coverUrl = urls.first
        }
    }

    func dismissError() { state.error = nil }

    func create() {
        let current = state
        guard current.canCreate else { return }
        Task {
            state.isCreating = true; state.error = nil
            switch await groupRepository.createGroup(
                name: current.name,
                description: current.description.trimmingCharacters(in: .whitespaces).nonEmpty,
                coverUrl: current.coverUrl
            ) {
            case .success(let group): state.isCreating = false; state.created = group
            case .failure(let error): state.isCreating = false; state.error = error
            }
        }
    }
}

struct CreateGroupScreen: View {
    let onBack: () -> Void
    let onGroupReady: (String) -> Void

    @StateObject private var viewModel = CreateGroupViewModel()
    @EnvironmentObject private var overlays: Overlays
    @State private var coverPick: PhotosPickerItem? = nil

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 0) {
            RollTopBar(title: "New roll", onBack: onBack)
            ScrollView {
                VStack(spacing: 0) {
                    // The photo picker needs no library permission on any iOS version.
                    PhotosPicker(selection: $coverPick, matching: .images) {
                        ZStack {
                            if let cover = state.coverUrl {
                                LocalImage(url: cover, contentMode: .fill)
                            } else {
                                VStack(spacing: 6) {
                                    Image(systemName: RollIcon.addPhoto)
                                        .font(.system(size: 26, weight: .medium))
                                        .foregroundStyle(IvoryMuted)
                                    Text("Add a cover photo").rollStyle(RollType.bodyMedium, color: IvoryMuted)
                                    Text("Optional — the first photo works too")
                                        .rollStyle(RollType.bodySmall, color: IvoryMuted)
                                        .multilineTextAlignment(.center)
                                }
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .background(Raised)
                        .clipShape(RoundedRectangle(cornerRadius: RollShapes.large))
                        .overlay(RoundedRectangle(cornerRadius: RollShapes.large).stroke(Gold.opacity(0.35), lineWidth: 1))
                    }
                    .buttonStyle(PlainPressStyle())
                    .onChange(of: coverPick) { _, item in viewModel.onCoverSelected(item) }

                    Spacer().frame(height: 24)

                    RollTextField(
                        label: "Roll name",
                        text: Binding(get: { viewModel.state.name }, set: viewModel.onNameChange),
                        placeholder: "Goa Trip 2026",
                        capitalization: .words,
                        supportingText: state.nameRemaining <= 10 ? "\(state.nameRemaining) left" : nil,
                        cornerRadius: RollShapes.medium
                    )

                    Spacer().frame(height: 12)

                    RollTextField(
                        label: "Description",
                        text: Binding(get: { viewModel.state.description }, set: viewModel.onDescriptionChange),
                        placeholder: "Optional",
                        supportingText: "\(state.description.count)/\(Limits.maxGroupDescriptionLength)",
                        singleLine: false, minLines: 2, maxLines: 4,
                        cornerRadius: RollShapes.medium
                    )

                    if let error = state.error {
                        Spacer().frame(height: 12)
                        InlineError(message: error.message ?? "Couldn't create the roll")
                    }

                    Spacer().frame(height: 28)

                    GoldButton(text: "Create roll", enabled: state.canCreate, loading: state.isCreating) {
                        viewModel.create()
                    }

                    Spacer().frame(height: 32)
                }
                .padding(.horizontal, 20)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(Ink.ignoresSafeArea())
        .onChange(of: state.created) { _, group in
            guard let group else { return }
            showReady(group)
        }
    }

    private func showReady(_ group: Group) {
        let finish = { overlays.closeDialog(); onGroupReady(group.id) }
        overlays.show(DialogSpec(
            title: "\"\(group.name)\" is ready",
            content: AnyView(
                VStack(spacing: 16) {
                    Text("Hold this up and your friends can scan it, or send them the code.")
                        .rollStyle(RollType.bodyMedium, color: IvoryMuted)
                        .multilineTextAlignment(.center)
                    QrCode(content: group.inviteLink, size: 170)
                    Button {
                        Sharing.copyToClipboard(group.inviteCode)
                    } label: {
                        HStack(spacing: 10) {
                            Text(group.inviteCode).rollStyle(RollType.headlineMedium, color: Ivory)
                            Image(systemName: RollIcon.copy).font(.system(size: 16, weight: .semibold)).foregroundStyle(Ivory)
                        }
                        .padding(.horizontal, 28).padding(.vertical, 16)
                        .background(RoundedRectangle(cornerRadius: RollShapes.medium).fill(GoldDeep))
                    }
                    .buttonStyle(PlainPressStyle())
                }
                .frame(maxWidth: .infinity)
            ),
            confirm: .init(label: "Invite friends", filled: true, icon: RollIcon.share) {
                Sharing.shareInvite(groupName: group.name, code: group.inviteCode, link: group.inviteLink)
            },
            dismiss: .init(label: "Later", perform: finish),
            onDismissRequest: finish
        ))
    }
}
