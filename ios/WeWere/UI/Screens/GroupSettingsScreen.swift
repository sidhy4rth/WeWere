import SwiftUI
import Combine
import PhotosUI

struct GroupSettingsUiState: Equatable {
    var group: Group? = nil
    var isAdmin: Bool = false
    var isSaving: Bool = false
    var error: AppError? = nil
    var finished: Bool = false
}

@MainActor
final class GroupSettingsViewModel: ObservableObject {
    @Published private(set) var state = GroupSettingsUiState()

    let groupId: String
    private let groupRepository: GroupRepository
    private let saving = CurrentValueSubject<Bool, Never>(false)
    private let errorFlow = CurrentValueSubject<AppError?, Never>(nil)
    private let finished = CurrentValueSubject<Bool, Never>(false)
    private var cancellables = Set<AnyCancellable>()

    init(groupId: String, container: AppContainer = .shared) {
        self.groupId = groupId
        groupRepository = container.groupRepository

        groupRepository.observeGroup(groupId: groupId)
            .combineLatest(groupRepository.observeMembership(groupId: groupId), saving, errorFlow)
            .combineLatest(finished)
            .map { first, done in
                let (group, membership, isSaving, error) = first
                return GroupSettingsUiState(group: group, isAdmin: membership?.isAdmin == true,
                                            isSaving: isSaving, error: error, finished: done)
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
    }

    func rename(_ name: String) { run { await self.groupRepository.updateGroup(groupId: self.groupId, name: name, description: nil).unit } }
    func updateDescription(_ description: String) { run { await self.groupRepository.updateGroup(groupId: self.groupId, name: nil, description: description).unit } }
    func updateCover(_ url: URL) {
        run {
            let outcome = await self.groupRepository.updateCoverPhoto(groupId: self.groupId, url: url).unit
            try? FileManager.default.removeItem(at: url)
            return outcome
        }
    }
    func regenerateInvite(_ expiresAt: Millis?) { run { await self.groupRepository.regenerateInviteCode(groupId: self.groupId, expiresAt: expiresAt).unit } }
    func revokeInvite() { run { await self.groupRepository.revokeInvite(groupId: self.groupId) } }
    func leaveGroup() { run(finishOnSuccess: true) { await self.groupRepository.leaveGroup(groupId: self.groupId) } }
    func deleteGroup() { run(finishOnSuccess: true) { await self.groupRepository.deleteGroup(groupId: self.groupId) } }

    func dismissError() { errorFlow.send(nil) }

    private func run(finishOnSuccess: Bool = false, _ block: @escaping () async -> Outcome<Void>) {
        if saving.value { return }
        Task {
            saving.send(true)
            errorFlow.send(nil)
            switch await block() {
            case .success: if finishOnSuccess { finished.send(true) }
            case .failure(let error): errorFlow.send(error)
            }
            saving.send(false)
        }
    }
}

/// Drafts for the rename dialog, shared between its fields and its Save button.
final class GroupDraft: ObservableObject {
    @Published var name: String
    @Published var description: String
    init(name: String, description: String) { self.name = name; self.description = description }
}

struct GroupSettingsScreen: View {
    let onBack: () -> Void
    let onOpenMembers: (String) -> Void
    let onLeftGroup: () -> Void

    @StateObject private var viewModel: GroupSettingsViewModel
    @EnvironmentObject private var overlays: Overlays
    @State private var coverPick: PhotosPickerItem? = nil

    init(groupId: String, onBack: @escaping () -> Void, onOpenMembers: @escaping (String) -> Void, onLeftGroup: @escaping () -> Void) {
        self.onBack = onBack
        self.onOpenMembers = onOpenMembers
        self.onLeftGroup = onLeftGroup
        _viewModel = StateObject(wrappedValue: GroupSettingsViewModel(groupId: groupId))
    }

    var body: some View {
        let state = viewModel.state
        let group = state.group
        VStack(spacing: 0) {
            RollTopBar(title: "Roll settings", onBack: onBack)
            ScrollView {
                VStack(spacing: 0) {
                    if let error = state.error {
                        InlineError(message: error.message ?? "Something went wrong",
                                    actionLabel: "Dismiss", onAction: viewModel.dismissError)
                            .padding(16)
                    }

                    SettingsRow(icon: RollIcon.people, title: "Members",
                                subtitle: "\(group?.memberCount ?? 0) in this roll") {
                        onOpenMembers(viewModel.groupId)
                    }

                    if let group, group.isInviteActive {
                        SettingsRow(icon: RollIcon.link, title: "Invite code", subtitle: group.inviteCode, trailing: RollIcon.copy) {
                            Sharing.copyToClipboard(group.inviteCode)
                            Sharing.shareInvite(groupName: group.name, code: group.inviteCode, link: group.inviteLink)
                        }
                    }

                    if state.isAdmin {
                        Divider().overlay(OutlineVariant).padding(.vertical, 8)

                        PhotosPicker(selection: $coverPick, matching: .images) {
                            SettingsRowLabel(icon: RollIcon.image, title: "Change cover photo")
                        }
                        .buttonStyle(PlainPressStyle())
                        .onChange(of: coverPick) { _, item in
                            guard let item else { return }
                            Task {
                                let urls = await PhotoPicking.stage([item], into: AppContainer.shared.capturesDir)
                                coverPick = nil
                                if let url = urls.first { viewModel.updateCover(url) }
                            }
                        }
                        SettingsRow(icon: RollIcon.refresh, title: "Name and description", subtitle: group?.name) {
                            editDetails(group)
                        }
                        SettingsRow(icon: RollIcon.refresh, title: "Generate a new code",
                                    subtitle: "The old link stops working immediately") {
                            viewModel.regenerateInvite(nil)
                        }
                        if group?.isInviteActive == true {
                            SettingsRow(icon: RollIcon.linkOff, title: "Turn off invites",
                                        subtitle: "Nobody new can join until you make a new code") {
                                viewModel.revokeInvite()
                            }
                        }
                    }

                    Divider().overlay(OutlineVariant).padding(.vertical, 8)

                    SettingsRow(icon: RollIcon.logout, title: "Leave roll", destructive: true) {
                        overlays.confirm(
                            title: "Leave this roll?",
                            message: "You'll lose access to its photos. Photos you uploaded stay with the roll for everyone else.",
                            confirmLabel: "Leave"
                        ) { viewModel.leaveGroup() }
                    }

                    if state.isAdmin {
                        SettingsRow(icon: RollIcon.deleteForever, title: "Delete roll",
                                    subtitle: "Removes every photo for everyone", destructive: true) {
                            overlays.confirm(
                                title: "Delete \"\(group?.name ?? "")\"?",
                                message: "Every photo in this roll is permanently deleted for all \(group?.memberCount ?? 0) members. This cannot be undone.",
                                confirmLabel: "Delete everything"
                            ) { viewModel.deleteGroup() }
                        }
                    }

                    Spacer().frame(height: 40)
                }
            }
        }
        .background(Ink.ignoresSafeArea())
        .onChange(of: state.finished) { _, finished in if finished { onLeftGroup() } }
    }

    private func editDetails(_ group: Group?) {
        let draft = GroupDraft(name: group?.name ?? "", description: group?.description ?? "")
        overlays.show(DialogSpec(
            title: "Edit roll",
            content: AnyView(GroupDraftFields(draft: draft)),
            confirm: .init(label: "Save", enabled: { !draft.name.trimmingCharacters(in: .whitespaces).isEmpty }) {
                overlays.closeDialog()
                viewModel.rename(draft.name)
                viewModel.updateDescription(draft.description)
            },
            dismiss: .init(label: "Cancel") { overlays.closeDialog() }
        ))
    }
}

private struct GroupDraftFields: View {
    @ObservedObject var draft: GroupDraft

    var body: some View {
        VStack(spacing: 12) {
            RollTextField(label: "Roll name", text: $draft.name, capitalization: .words,
                          cornerRadius: RollShapes.medium, chipBackground: Raised)
            RollTextField(label: "Description", text: $draft.description, placeholder: "Optional",
                          singleLine: false, minLines: 1, maxLines: 3,
                          cornerRadius: RollShapes.medium, chipBackground: Raised)
        }
    }
}

struct SettingsRowLabel: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    var trailing: String = RollIcon.chevronRight
    var destructive: Bool = false

    var body: some View {
        let tint = destructive ? ErrorColor : Ivory
        HStack(spacing: 0) {
            Image(systemName: icon).font(.system(size: 19, weight: .medium)).foregroundStyle(tint)
                .frame(width: 22)
            Spacer().frame(width: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).rollStyle(RollType.titleMedium, color: tint)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).rollStyle(RollType.bodyMedium, color: IvoryMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: trailing).font(.system(size: 16, weight: .semibold)).foregroundStyle(IvoryMuted)
        }
        .padding(.horizontal, 20).padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

struct SettingsRow: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    var trailing: String = RollIcon.chevronRight
    var destructive: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            SettingsRowLabel(icon: icon, title: title, subtitle: subtitle, trailing: trailing, destructive: destructive)
        }
        .buttonStyle(PlainPressStyle())
    }
}
