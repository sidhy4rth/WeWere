import SwiftUI

private struct OnboardingPage {
    let icon: String
    let title: String
    let body: String
}

private let pages: [OnboardingPage] = [
    .init(icon: RollIcon.camera, title: "Capture together",
          body: "Take photos straight from WeWere and they land in the roll instantly."),
    .init(icon: RollIcon.photoLibrary, title: "One shared camera roll",
          body: "Everyone's photos gather in one place, so nobody has to ask for the good ones."),
    .init(icon: RollIcon.autoAwesome, title: "Keep the memories",
          body: "Your roll is a private collection of the whole trip. Nobody else can see it.")
]

struct OnboardingScreen: View {
    let onFinished: () -> Void

    @State private var page = 0

    private var isLast: Bool { page == pages.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                TextButton(text: "Skip", action: onFinished)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 8)

            TabView(selection: $page) {
                ForEach(Array(pages.enumerated()), id: \.offset) { index, item in
                    VStack(spacing: 0) {
                        ZStack {
                            Circle().fill(Raised)
                            Circle().stroke(Gold.opacity(0.6), lineWidth: 1)
                            Image(systemName: item.icon)
                                .font(.system(size: 40, weight: .medium))
                                .foregroundStyle(Gold)
                        }
                        .frame(width: 112, height: 112)
                        Spacer().frame(height: 36)
                        Text(item.title)
                            .rollStyle(RollType.headlineLarge, color: Ivory)
                            .multilineTextAlignment(.center)
                        Spacer().frame(height: 12)
                        HairlineRule().frame(width: 120)
                        Spacer().frame(height: 12)
                        Text(item.body)
                            .rollStyle(RollType.bodyLarge, color: IvoryMuted)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 36)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            HStack(spacing: 6) {
                ForEach(0..<pages.count, id: \.self) { index in
                    Capsule()
                        .fill(page == index ? Gold : OutlineVariant)
                        .frame(width: page == index ? 22 : 8, height: 8)
                        .animation(.easeOut(duration: 0.25), value: page)
                }
            }
            .padding(.bottom, 24)

            GoldButton(text: isLast ? "Get started" : "Next") {
                if isLast { onFinished() } else { withAnimation { page += 1 } }
            }
            .padding(.horizontal, 28)

            Spacer().frame(height: 32)
        }
        .background(Ink.ignoresSafeArea())
    }
}
