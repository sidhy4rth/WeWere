import SwiftUI
import UIKit
import FirebaseCore
import GoogleSignIn

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        if FirebaseApp.app() == nil { FirebaseApp.configure() }
        // Must be registered before launch finishes, or iOS refuses the identifier.
        AppContainer.shared.uploadScheduler.registerBackgroundTask()
        return true
    }
}

@main
struct WeWereApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var root = RootViewModel(container: AppContainer.shared)

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(root)
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    root.handleLink(url)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                // Anything left in the queue from a previous run resumes on launch,
                // and again every time the app comes back to the front.
                AppContainer.shared.uploadScheduler.ensureRunning()
            case .background:
                AppContainer.shared.uploadScheduler.scheduleBackgroundProcessingIfNeeded()
            default:
                break
            }
        }
    }
}

/// Hiding the navigation bar (every screen draws its own) would normally switch off
/// the interactive swipe-back gesture; this keeps it, which is what iOS users expect.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
