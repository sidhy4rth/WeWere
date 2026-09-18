import SwiftUI

struct ReviewUiState: Equatable {
    var caption: String = ""
    var isSubmitting: Bool = false
    var error: AppError? = nil
    var shared: Bool = false

    var captionRemaining: Int { Limits.maxCaptionLength - caption.count }
}

@MainActor
final class ReviewViewModel: ObservableObject {
    @Published var state = ReviewUiState()

    let groupId: String
    let photoUrl: URL
    private let capturedAt: Millis
    private let photoRepository: PhotoRepository

    init(groupId: String, photoUrl: URL, capturedAt: Millis, container: AppContainer = .shared) {
        self.groupId = groupId
        self.photoUrl = photoUrl
        self.capturedAt = capturedAt
        photoRepository = container.photoRepository
    }

    func onCaptionChange(_ value: String) {
        if value.count > Limits.maxCaptionLength { return }
        state.caption = value
    }

    /// Queues rather than uploads. The user gets back to the group immediately and the
    /// worker delivers the photo whenever the network allows — which is the difference
    /// between the app working on a train and not.
    func shareToGroup() {
        if state.isSubmitting { return }
        Task {
            state.isSubmitting = true; state.error = nil
            let outcome = await photoRepository.enqueueUpload(
                groupId: groupId,
                localUrl: photoUrl,
                caption: state.caption.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
                capturedAt: capturedAt
            )
            switch outcome {
            case .success:
                try? FileManager.default.removeItem(at: photoUrl)
                state.isSubmitting = false; state.shared = true
            case .failure(let error):
                state.isSubmitting = false; state.error = error
            }
        }
    }
}

struct ReviewScreen: View {
    let onRetake: () -> Void
    let onShared: () -> Void

    @StateObject private var viewModel: ReviewViewModel

    init(groupId: String, photoUrl: URL, capturedAt: Millis, onRetake: @escaping () -> Void, onShared: @escaping () -> Void) {
        self.onRetake = onRetake
        self.onShared = onShared
        _viewModel = StateObject(wrappedValue: ReviewViewModel(groupId: groupId, photoUrl: photoUrl, capturedAt: capturedAt))
    }

    var body: some View {
        let state = viewModel.state
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()

            LocalImage(url: viewModel.photoUrl, contentMode: .fit, downsample: 2000)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    TextField("", text: Binding(get: { viewModel.state.caption }, set: viewModel.onCaptionChange),
                              prompt: Text("Add a caption…").foregroundStyle(.white.opacity(0.6)), axis: .vertical)
                        .lineLimit(1...3)
                        .rollText(RollType.bodyLarge)
                        .foregroundStyle(.white)
                        .tint(.white)
                        .padding(.horizontal, 16).padding(.vertical, 14)
                        .background(RoundedRectangle(cornerRadius: RollShapes.medium).fill(Color.white.opacity(0.10)))
                    if state.captionRemaining <= 20 {
                        Text("\(state.captionRemaining) left")
                            .rollStyle(RollType.bodySmall, color: .white.opacity(0.6))
                            .padding(.horizontal, 16)
                    }
                }

                if let error = state.error {
                    Spacer().frame(height: 10)
                    InlineError(message: error.message ?? "Couldn't queue that photo")
                }

                Spacer().frame(height: 14)

                HStack(spacing: 12) {
                    OutlinedPillButton(text: "Retake", enabled: !state.isSubmitting, height: 52, cornerRadius: RollShapes.medium, action: onRetake)
                        .frame(maxWidth: .infinity)

                    Button(action: viewModel.shareToGroup) {
                        ZStack {
                            RoundedRectangle(cornerRadius: RollShapes.medium).fill(Gold)
                            if state.isSubmitting {
                                ProgressView().tint(OnGold)
                            } else {
                                Text("Share to roll").rollStyle(RollType.labelLarge, color: OnGold)
                            }
                        }
                        .frame(height: 52)
                    }
                    .buttonStyle(PlainPressStyle())
                    .disabled(state.isSubmitting)
                    .frame(maxWidth: .infinity)
                    .layoutPriority(1)
                    .frame(minWidth: 0)
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(16)
            .background(Color.black.opacity(0.55).ignoresSafeArea(edges: .bottom))
        }
        .onChange(of: state.shared) { _, shared in if shared { onShared() } }
    }
}
