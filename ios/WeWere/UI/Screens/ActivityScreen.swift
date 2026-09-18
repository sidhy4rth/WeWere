import SwiftUI
import Combine

@MainActor
final class ActivityViewModel: ObservableObject {
    @Published private(set) var events: [ActivityEvent] = []
    private var cancellables = Set<AnyCancellable>()

    init(container: AppContainer = .shared) {
        container.groupRepository.observeActivity(limit: 60)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.events = $0 }
            .store(in: &cancellables)
    }
}

struct ActivityScreen: View {
    let onOpenGroup: (String) -> Void

    @StateObject private var viewModel = ActivityViewModel()

    var body: some View {
        VStack(spacing: 0) {
            RollTopBar(title: "Activity")
            if viewModel.events.isEmpty {
                EmptyState(
                    icon: RollIcon.notifications,
                    title: "Nothing yet",
                    body_: "New photos, reactions and people joining your rolls show up here."
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(viewModel.events, id: \.self) { event in
                            ActivityRow(event: event) { onOpenGroup(event.groupId) }
                        }
                    }
                }
            }
        }
        .background(Ink.ignoresSafeArea())
    }
}

private struct ActivityRow: View {
    let event: ActivityEvent
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 0) {
                UserAvatar(name: event.actorName, photoUrl: event.actorPhotoUrl, size: 40, seed: event.actorId)
                Spacer().frame(width: 14)
                VStack(alignment: .leading, spacing: 3) {
                    Text(describe(event)).rollStyle(RollType.bodyLarge, color: Ivory).multilineTextAlignment(.leading)
                    Readout(text: TimeFormat.relative(event.createdAt))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let preview = event.previewPhotoUrl, !preview.isEmpty {
                    Spacer().frame(width: 12)
                    RemoteImage(url: URL(string: preview), contentMode: .fill, downsample: 200)
                        .frame(width: 46, height: 46)
                        .clipShape(RoundedRectangle(cornerRadius: RollShapes.small))
                        .overlay(RoundedRectangle(cornerRadius: RollShapes.small).stroke(Gold.opacity(0.4), lineWidth: 1))
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(PlainPressStyle())
    }
}

private func describe(_ event: ActivityEvent) -> String {
    switch event.type {
    case .photosAdded:
        let count = max(event.photoCount, 1)
        let noun = count == 1 ? "a photo" : "\(count) photos"
        return "\(event.actorName) added \(noun) to \(event.groupName)"
    case .memberJoined: return "\(event.actorName) joined \(event.groupName)"
    case .memberLeft: return "\(event.actorName) left \(event.groupName)"
    case .reaction: return "\(event.actorName) reacted \(event.reactionKey ?? "") to your photo"
    case .groupCreated: return "\(event.actorName) created \(event.groupName)"
    case .unknown: return "\(event.actorName) did something in \(event.groupName)"
    }
}
