import SwiftUI
import Combine

/// Routes as typed values on a navigation stack. Full-bleed screens (camera, review,
/// the photo viewer, the QR scanner) are presented as covers by the screen that
/// opens them, so the stack only ever holds chrome-bearing screens.
enum Route: Hashable {
    case createGroup
    case joinGroup(code: String?)
    case group(id: String)
    case groupSettings(id: String)
    case members(id: String)
}

enum Tab: Hashable, CaseIterable {
    case home, camera, activity, profile

    var label: String {
        switch self {
        case .home: return "Home"
        case .camera: return "Camera"
        case .activity: return "Activity"
        case .profile: return "You"
        }
    }

    var icon: String {
        switch self {
        case .home: return RollIcon.home
        case .camera: return RollIcon.camera
        case .activity: return RollIcon.notifications
        case .profile: return RollIcon.person
        }
    }
}

@MainActor
final class AppNavigator: ObservableObject {
    @Published var path: [Route] = []
    @Published var tab: Tab = .home

    func push(_ route: Route) { path.append(route) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path.removeAll() }

    /// `navigate(group) { popUpTo(HOME) }`: the group becomes the only pushed screen.
    func openGroupFromRoot(_ id: String) { path = [.group(id: id)] }

    /// `navigate(group) { popUpTo(CREATE_GROUP) { inclusive = true } }`.
    func replaceTop(with route: Route) {
        if path.isEmpty { path = [route] } else { path[path.count - 1] = route }
    }
}

/// The signed-in shell: four tabs and one navigation stack.
struct MainShell: View {
    @EnvironmentObject private var root: RootViewModel
    @StateObject private var nav = AppNavigator()

    var body: some View {
        NavigationStack(path: $nav.path) {
            tabRoot
                // The bar belongs to the tab roots only; pushed screens own the whole height.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    RollTabBar(selected: $nav.tab)
                }
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                        .toolbar(.hidden, for: .navigationBar)
                }
                .toolbar(.hidden, for: .navigationBar)
        }
        .environmentObject(nav)
        .background(Ink.ignoresSafeArea())
        // An invite arriving from a link while the app is already open.
        .onChange(of: root.pendingInviteCode, initial: true) { _, code in
            guard let code else { return }
            nav.push(.joinGroup(code: code))
            root.pendingInviteCode = nil
        }
        .onChange(of: root.pendingGroupId, initial: true) { _, id in
            guard let id else { return }
            nav.push(.group(id: id))
            root.pendingGroupId = nil
        }
    }

    @ViewBuilder private var tabRoot: some View {
        switch nav.tab {
        case .home:
            HomeScreen(
                onOpenGroup: { nav.push(.group(id: $0)) },
                onCreateGroup: { nav.push(.createGroup) },
                onJoinGroup: { nav.push(.joinGroup(code: nil)) },
                onOpenProfile: { nav.tab = .profile },
                cameraFirst: false
            )
            .id("home")
        case .camera:
            // The camera tab needs a group to publish into, so it routes through the
            // group list rather than opening a camera with nowhere to send the photo.
            HomeScreen(
                onOpenGroup: { nav.push(.group(id: $0)) },
                onCreateGroup: { nav.push(.createGroup) },
                onJoinGroup: { nav.push(.joinGroup(code: nil)) },
                onOpenProfile: { nav.tab = .profile },
                cameraFirst: true
            )
            .id("camera-tab")
        case .activity:
            ActivityScreen(onOpenGroup: { nav.push(.group(id: $0)) })
        case .profile:
            ProfileScreen(onSignedOut: { nav.popToRoot(); nav.tab = .home })
        }
    }

    @ViewBuilder private func destination(_ route: Route) -> some View {
        switch route {
        case .createGroup:
            CreateGroupScreen(
                onBack: { nav.pop() },
                onGroupReady: { id in nav.replaceTop(with: .group(id: id)) }
            )
        case .joinGroup(let code):
            JoinGroupScreen(
                initialCode: code,
                onBack: { nav.pop() },
                onJoined: { id in nav.openGroupFromRoot(id) }
            )
        case .group(let id):
            GroupScreen(
                groupId: id,
                onBack: { nav.pop() },
                onOpenSettings: { nav.push(.groupSettings(id: $0)) },
                onOpenMembers: { nav.push(.members(id: $0)) }
            )
        case .groupSettings(let id):
            GroupSettingsScreen(
                groupId: id,
                onBack: { nav.pop() },
                onOpenMembers: { nav.push(.members(id: $0)) },
                onLeftGroup: { nav.popToRoot() }
            )
        case .members(let id):
            MembersScreen(groupId: id, onBack: { nav.pop() })
        }
    }
}

/// M3 NavigationBar, in the app's voice: ink, a hairline on top, gold for the
/// selected tab with a soft pill behind its icon, upper-case mono labels.
struct RollTabBar: View {
    @Binding var selected: Tab

    var body: some View {
        VStack(spacing: 0) {
            HairlineRule()
            HStack(spacing: 0) {
                ForEach(Tab.allCases, id: \.self) { tab in
                    let isSelected = tab == selected
                    Button {
                        selected = tab
                    } label: {
                        VStack(spacing: 4) {
                            ZStack {
                                Capsule().fill(Gold.opacity(isSelected ? 0.12 : 0)).frame(width: 64, height: 32)
                                Image(systemName: tab.icon)
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundStyle(isSelected ? Gold : Muted)
                            }
                            Text(tab.label.uppercased())
                                .rollStyle(RollType.labelMedium, color: isSelected ? Gold : Muted)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 12)
                        .padding(.bottom, 16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PlainPressStyle())
                    .accessibilityLabel(tab.label)
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
            .background(Ink)
        }
        .background(Ink)
    }
}
