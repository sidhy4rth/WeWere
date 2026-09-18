import SwiftUI

/// Three prints fanning out of a stack, the app's opening image. The photos are
/// abstract on purpose — nobody has signed in yet, so there is nothing real to show,
/// and a stock photo of strangers would say the wrong thing.
struct PrintStack: View {
    var body: some View {
        GeometryReader { geo in
            // Positions are relative to the centre so the cluster holds together on any width.
            let cx = geo.size.width / 2
            let cy = geo.size.height / 2
            ZStack(alignment: .topLeading) {
                Print(
                    gradient: RadialGradient(
                        stops: [.init(color: Color(hex: 0x9AC6E8), location: 0),
                                .init(color: Color(hex: 0x2F5F8A), location: 0.4),
                                .init(color: Color(hex: 0x0D1B2A), location: 1)],
                        center: .topLeading, startRadius: 0, endRadius: 178
                    ),
                    rotation: -11,
                    x: cx - PrintSize.width / 2 - 82, y: cy - PrintSize.height / 2 - 20,
                    delay: 0.08
                )
                Print(
                    gradient: RadialGradient(
                        stops: [.init(color: Color(hex: 0xF8E2A8), location: 0),
                                .init(color: Color(hex: 0xD59A3F), location: 0.3),
                                .init(color: Color(hex: 0x5A2D0C), location: 0.7),
                                .init(color: Color(hex: 0x1A0C05), location: 1)],
                        center: .topLeading, startRadius: 0, endRadius: 178
                    ),
                    rotation: 9,
                    x: cx - PrintSize.width / 2 + 70, y: cy - PrintSize.height / 2 - 44,
                    delay: 0.22
                )
                Print(
                    gradient: RadialGradient(
                        stops: [.init(color: Color(hex: 0xF2C27B), location: 0),
                                .init(color: Color(hex: 0xB9642C), location: 0.35),
                                .init(color: Color(hex: 0x2C1810), location: 1)],
                        center: .topLeading, startRadius: 0, endRadius: 185
                    ),
                    rotation: -2,
                    x: cx - PrintSize.width / 2 - 6, y: cy - PrintSize.height / 2 + 24,
                    delay: 0.38
                )
                VStack {
                    Spacer()
                    LinearGradient(colors: [.clear, Ink], startPoint: .top, endPoint: .bottom)
                        .frame(height: 120)
                }
            }
        }
    }
}

private let PrintSize = CGSize(width: 150, height: 190)

private struct Print: View {
    let gradient: RadialGradient
    let rotation: Double
    let x: CGFloat
    let y: CGFloat
    let delay: Double

    @State private var p: CGFloat = 0

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            RoundedRectangle(cornerRadius: 6).fill(Ivory)
            RoundedRectangle(cornerRadius: 2)
                .fill(gradient)
                .padding(.init(top: 7, leading: 7, bottom: 28, trailing: 7))
            Readout(text: "06 09 26", color: GoldDeep)
                .padding(.trailing, 11)
                .padding(.bottom, 9)
        }
        .frame(width: PrintSize.width, height: PrintSize.height)
        .shadow(color: .black.opacity(0.55), radius: 24, y: 12)
        .rotationEffect(.degrees(rotation * p))
        .scaleEffect(0.92 + 0.08 * p)
        .opacity(p)
        .offset(x: x, y: y + (1 - p) * 40)
        .onAppear {
            guard p == 0 else { return }
            withAnimation(.settle(duration: 0.9, delay: delay)) { p = 1 }
        }
    }
}
