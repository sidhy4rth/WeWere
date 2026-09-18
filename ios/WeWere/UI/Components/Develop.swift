import SwiftUI
import Kingfisher

/// An image that *develops*: it arrives blurred, desaturated and dim, and resolves to
/// the real photograph over about a second — the way a print comes up in the tray.
///
/// The animation starts when the bitmap is ready, not when the view appears, so a
/// slow network shows the placeholder, not a half-developed nothing.
struct DevelopingImage: View {
    let url: String?
    var contentMode: SwiftUI.ContentMode = .fill
    var duration: Double = 1.1
    var delay: Double = 0
    /// Longest edge to decode at, for tiles that never need the full 2560px image.
    var downsample: CGFloat? = nil

    @State private var p: CGFloat = 0

    var body: some View {
        let source = url.flatMap(URL.init(string:))
        RemoteImage(url: source, contentMode: contentMode, downsample: downsample, onReady: {
            guard p == 0 else { return }
            withAnimation(.settle(duration: duration, delay: delay)) { p = 1 }
        })
        .saturation(0.2 + 0.8 * p)
        .brightness(-0.22 * (1 - p))
        .opacity(0.4 + 0.6 * p)
        .blur(radius: (1 - p) * 10)
        .id(url)
    }
}

/// Coil's `AsyncImage`: a cached network image that fills or fits its frame.
struct RemoteImage: View {
    let url: URL?
    var contentMode: SwiftUI.ContentMode = .fill
    var downsample: CGFloat? = nil
    var fade: Bool = false
    var onReady: (() -> Void)? = nil

    var body: some View {
        Color.clear.overlay(
            KFImage(url)
                .setProcessors(downsample.map { [DownsamplingImageProcessor(size: CGSize(width: $0, height: $0))] } ?? [])
                .cacheOriginalImage()
                .fade(duration: fade ? 0.25 : 0)
                .onSuccess { _ in onReady?() }
                .resizable()
                .aspectRatio(contentMode: contentMode)
        )
        .clipped()
    }
}

/// A local file (a capture or a staged upload), decoded at a sane size.
struct LocalImage: View {
    let url: URL?
    var contentMode: SwiftUI.ContentMode = .fill
    var downsample: CGFloat = 800

    var body: some View {
        Color.clear.overlay(
            KFImage(url)
                .setProcessor(DownsamplingImageProcessor(size: CGSize(width: downsample, height: downsample)))
                .cacheMemoryOnly()
                .resizable()
                .aspectRatio(contentMode: contentMode)
        )
        .clipped()
    }
}
