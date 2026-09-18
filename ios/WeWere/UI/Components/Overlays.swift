import SwiftUI
import Combine

/// A dialog, the way M3's AlertDialog draws one: raised card, serif title, plain
/// body, actions at the end. Presented over everything from the root, so it sits
/// above tab bars and pushed screens alike.
struct DialogSpec: Identifiable {
    struct Action {
        let label: String
        var filled: Bool = false
        var enabled: () -> Bool = { true }
        var icon: String? = nil
        let perform: () -> Void
    }

    let id = UUID()
    var title: String
    var message: String? = nil
    var content: AnyView? = nil
    var confirm: Action? = nil
    var dismiss: Action? = nil
    /// Tapping the scrim. Defaults to closing the dialog.
    var onDismissRequest: (() -> Void)? = nil
}

@MainActor
final class Overlays: ObservableObject {
    @Published var dialog: DialogSpec? = nil
    @Published var snackbar: String? = nil

    private var snackbarTask: Task<Void, Never>? = nil

    func show(_ spec: DialogSpec) { dialog = spec }
    func closeDialog() { dialog = nil }

    /// A one-line message at the bottom of the screen that goes away on its own.
    func snack(_ message: String) {
        snackbarTask?.cancel()
        snackbar = message
        snackbarTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_200_000_000)
            if !Task.isCancelled { self?.snackbar = nil }
        }
    }

    // Confirmations are the common case; one call builds the whole dialog.
    func confirm(title: String, message: String, confirmLabel: String, dismissLabel: String = "Cancel",
                 onConfirm: @escaping () -> Void) {
        show(DialogSpec(
            title: title,
            message: message,
            confirm: .init(label: confirmLabel) { [weak self] in self?.closeDialog(); onConfirm() },
            dismiss: .init(label: dismissLabel) { [weak self] in self?.closeDialog() }
        ))
    }
}

/// Draws the current dialog and snackbar over the whole app.
struct OverlayHost: View {
    @EnvironmentObject private var overlays: Overlays

    var body: some View {
        ZStack {
            if let dialog = overlays.dialog {
                Ink.opacity(0.55)
                    .ignoresSafeArea()
                    .onTapGesture { dialog.onDismissRequest?() ?? overlays.closeDialog() }
                    .transition(.opacity)
                DialogCard(spec: dialog)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            }
            if let message = overlays.snackbar {
                VStack {
                    Spacer()
                    Text(message)
                        .rollStyle(RollType.bodyMedium, color: Ink)
                        .padding(.horizontal, 16).padding(.vertical, 14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 4).fill(Ivory))
                        .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
                        .padding(16)
                        .padding(.bottom, 8)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.2), value: overlays.dialog?.id)
        .animation(.easeOut(duration: 0.25), value: overlays.snackbar)
    }
}

private struct DialogCard: View {
    let spec: DialogSpec

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(spec.title).rollStyle(RollType.headlineSmall, color: Ivory)
            if let message = spec.message {
                Text(message).rollStyle(RollType.bodyMedium, color: IvoryMuted)
            }
            if let content = spec.content {
                content
            }
            HStack(spacing: 8) {
                Spacer()
                if let dismiss = spec.dismiss {
                    TextButton(text: dismiss.label, action: dismiss.perform)
                }
                if let confirm = spec.confirm {
                    if confirm.filled {
                        FilledButton(text: confirm.label, icon: confirm.icon, enabled: confirm.enabled(), action: confirm.perform)
                    } else {
                        TextButton(text: confirm.label, enabled: confirm.enabled(), action: confirm.perform)
                    }
                }
            }
            .padding(.top, 8)
        }
        .padding(24)
        .frame(maxWidth: 340)
        .fixedSize(horizontal: false, vertical: true)
        .background(RoundedRectangle(cornerRadius: RollShapes.extraLarge).fill(Raised))
        .shadow(color: .black.opacity(0.5), radius: 30, y: 12)
        .padding(.horizontal, 28)
    }
}
