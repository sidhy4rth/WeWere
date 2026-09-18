import SwiftUI
import UIKit

/// The system share sheet, in the two shapes this app needs.
enum Sharing {

    static func shareInvite(groupName: String, code: String, link: String) {
        let text = "Join our \"\(groupName)\" roll on WeWere 📸\n\n\(link)\n\nOr enter the code in WeWere: \(code)"
        present(items: [text], subject: "Join \(groupName) on WeWere")
    }

    static func sharePhoto(url: URL, caption: String?) {
        var items: [Any] = [url]
        if let caption, !caption.trimmingCharacters(in: .whitespaces).isEmpty { items.append(caption) }
        present(items: items, subject: nil)
    }

    static func copyToClipboard(_ text: String) {
        UIPasteboard.general.string = text
    }

    private static func present(items: [Any], subject: String?) {
        guard let presenter = UIApplication.topViewController() else { return }
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let subject { controller.setValue(subject, forKey: "subject") }
        controller.popoverPresentationController?.sourceView = presenter.view
        presenter.present(controller, animated: true)
    }
}

extension UIApplication {
    /// The view controller a system sheet should be presented from.
    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
