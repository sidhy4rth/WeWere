import SwiftUI
import Combine

struct MembersUiState: Equatable {
    var group: Group? = nil
    var members: [Member] = []
    var myUid: String? = nil
    var isAdmin: Bool = false
    var error: AppError? = nil
    var isLoading: Bool = true
}

@MainActor
final class MembersViewModel: ObservableObject {
    @Published private(set) var state = MembersUiState()

    let groupId: String
    private let groupRepository: GroupRepository
    private let errorFlow = CurrentValueSubject<AppError?, Never>(nil)
    private var cancellables = Set<AnyCancellable>()

    init(groupId: String, container: AppContainer = .shared) {
        self.groupId = groupId
        groupRepository = container.groupRepository
        let authRepository = container.authRepository

        groupRepository.observeGroup(groupId: groupId)
            .combineLatest(groupRepository.observeMembers(groupId: groupId),
                           groupRepository.observeMembership(groupId: groupId), errorFlow)
            .map { group, members, membership, error in
                MembersUiState(
                    group: group,
                    // Most photos first — on a trip this is genuinely the interesting ranking.
                    members: members.sorted { $0.photoCount > $1.photoCount },
                    myUid: authRepository.currentUid(),
                    isAdmin: membership?.isAdmin == true,
                    error: error,
                    isLoading: false
                )
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.state = $0 }
            .store(in: &cancellables)
    }

    func removeMember(_ uid: String) {
        Task {
            if case .failure(let error) = await groupRepository.removeMember(groupId: groupId, uid: uid) {
                errorFlow.send(error)
            }
        }
    }

    func regenerateInvite() {
        Task {
            if case .failure(let error) = await groupRepository.regenerateInviteCode(groupId: groupId, expiresAt: nil) {
                errorFlow.send(error)
            }
        }
    }

    func dismissError() { errorFlow.send(nil) }
}

struct MembersScreen: View {
    let onBack: () -> Void

    @StateObject private var viewModel: MembersViewModel
    @EnvironmentObject private var overlays: Overlays

    init(groupId: String, onBack: @escaping () -> Void) {
        self.onBack = onBack
        _viewModel = StateObject(wrappedValue: MembersViewModel(groupId: groupId))
    }

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 0) {
            RollTopBar(title: "Members", onBack: onBack)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let error = state.error {
                        InlineError(message: error.message ?? "Something went wrong",
                                    actionLabel: "Dismiss", onAction: viewModel.dismissError)
                            .padding(16)
                    }

                    if let group = state.group, group.isInviteActive {
                        VStack(spacing: 0) {
                            // Someone across the table scans this instead of typing.
                            QrCode(content: group.inviteLink, size: 180)
                            Spacer().frame(height: 10)
                            Text(group.inviteCode).rollStyle(RollType.headlineSmall, color: Gold)
                            Text("Scan this, or type the code").rollStyle(RollType.bodySmall, color: IvoryMuted)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 16)

                        HairlineButton(text: "Invite friends · \(group.inviteCode)", icon: RollIcon.personAdd) {
                            Sharing.shareInvite(groupName: group.name, code: group.inviteCode, link: group.inviteLink)
                        }
                        .padding(.horizontal, 20).padding(.vertical, 12)
                    }

                    ForEach(state.members) { member in
                        MemberRow(
                            member: member,
                            isMe: member.uid == state.myUid,
                            canManage: state.isAdmin && member.uid != state.myUid,
                            onRemove: { confirmRemoval(member) }
                        )
                    }
                }
                .padding(.bottom, 32)
            }
        }
        .background(Ink.ignoresSafeArea())
    }

    private func confirmRemoval(_ member: Member) {
        overlays.confirm(
            title: "Remove \(member.name)?",
            message: "They'll lose access to this roll's photos. Photos they already uploaded stay in the roll.",
            confirmLabel: "Remove"
        ) {
            viewModel.removeMember(member.uid)
        }
    }
}

private struct MemberRow: View {
    let member: Member
    let isMe: Bool
    let canManage: Bool
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            UserAvatar(name: member.name, photoUrl: member.photoUrl, size: 44, seed: member.uid)
            Spacer().frame(width: 14)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(isMe ? "\(member.name) (you)" : member.name).rollStyle(RollType.titleMedium, color: Ivory)
                    if member.isAdmin {
                        Text("Admin")
                            .rollStyle(RollType.labelSmall, color: Ivory)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: RollShapes.extraSmall).fill(GoldDeep))
                    }
                }
                Text("\(member.photoCount) \(member.photoCount == 1 ? "photo" : "photos")")
                    .rollStyle(RollType.bodyMedium, color: IvoryMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if canManage {
                Menu {
                    Button("Remove from roll", role: .destructive, action: onRemove)
                } label: {
                    Image(systemName: RollIcon.more)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Ivory)
                        .frame(width: 48, height: 48)
                        .contentShape(Circle())
                }
                .accessibilityLabel("Manage \(member.name)")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }
}
