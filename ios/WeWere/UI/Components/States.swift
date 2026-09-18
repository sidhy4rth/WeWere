import SwiftUI

/// Empty and error states share one shape: a mark, a plain sentence, and — when there
/// is something the user can actually do — exactly one button. An error that offers no
/// action gets no button rather than a dead "OK".
struct EmptyState: View {
    let icon: String
    let title: String
    let body_: String
    var actionLabel: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(Raised)
                Image(systemName: icon)
                    .font(.system(size: 28, weight: .medium))
                    .foregroundStyle(IvoryMuted)
            }
            .frame(width: 72, height: 72)
            Spacer().frame(height: 20)
            Text(title).rollStyle(RollType.titleLarge, color: Ivory).multilineTextAlignment(.center)
            Spacer().frame(height: 8)
            Text(body_).rollStyle(RollType.bodyMedium, color: IvoryMuted).multilineTextAlignment(.center)
            if let actionLabel, let onAction {
                Spacer().frame(height: 24)
                FilledButton(text: actionLabel, action: onAction)
            }
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ErrorState: View {
    let error: AppError
    var onRetry: (() -> Void)? = nil

    var body: some View {
        EmptyState(
            icon: RollIcon.cloudOff,
            title: error.message ?? "Something went wrong",
            body_: errorGuidance(error),
            actionLabel: error.isRetryable && onRetry != nil ? "Try again" : nil,
            onAction: error.isRetryable ? onRetry : nil
        )
    }
}

/// The second line: what the user should understand, not a restatement of the error.
private func errorGuidance(_ error: AppError) -> String {
    switch error {
    case .offline: return "WeWere will catch up as soon as you're back on a network."
    case .timeout: return "The connection is slow right now."
    case .permissionDenied: return "Ask an admin to invite you again."
    case .groupNotFound: return "It may have been deleted by an admin."
    case .removedFromGroup: return "An admin removed you from this roll."
    case .invalidInviteCode: return "Double-check the code — they're six characters."
    case .inviteExpired: return "Ask whoever invited you for a fresh link."
    case .storageQuotaExceeded: return "This roll has hit its storage limit."
    case .notAuthenticated: return "Sign in to keep going."
    default: return "Give it another go in a moment."
    }
}

/// A one-line, dismissible failure inside an otherwise working screen.
struct InlineError: View {
    let message: String
    var actionLabel: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            Text(message)
                .rollStyle(RollType.bodyMedium, color: OnErrorContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionLabel, let onAction {
                TextButton(text: actionLabel, color: OnErrorContainer, action: onAction)
            }
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: RollShapes.medium).fill(ErrorContainer))
    }
}

struct LoadingState: View {
    var body: some View {
        ProgressView().tint(Gold)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// M3 circular progress: an indeterminate spinner, or a determinate gold arc.
struct GoldRing: View {
    var progress: Double? = nil
    var size: CGFloat = 34
    var lineWidth: CGFloat = 3
    var track: Color = Ivory.opacity(0.2)

    var body: some View {
        if let progress {
            ZStack {
                Circle().stroke(track, lineWidth: lineWidth)
                Circle()
                    .trim(from: 0, to: min(max(progress, 0), 1))
                    .stroke(Gold, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.2), value: progress)
            }
            .frame(width: size, height: size)
        } else {
            ProgressView().tint(Gold).frame(width: size, height: size)
        }
    }
}

/// M3 linear progress: a thin gold bar on a raised track.
struct GoldBar: View {
    let fraction: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Raised)
                Capsule().fill(Gold).frame(width: geo.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: 4)
        .animation(.easeOut(duration: 0.2), value: fraction)
    }
}
