import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

/// Renders an invite link as a QR code.
///
/// Always drawn on a white card regardless of theme — scanners need the light modules
/// lighter than the dark ones, and an inverted code in dark mode simply will not read
/// on many phones. Error correction is set high so the code still scans when someone
/// photographs it off another screen at an angle, which is how these actually get used.
struct QrCode: View {
    let content: String
    var size: CGFloat = 220

    @State private var image: UIImage? = nil

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
                    .accessibilityLabel("QR code for this group's invite")
            } else {
                Color.clear.frame(width: size, height: size)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.white))
        .task(id: content) {
            // Encoding is cheap but not free, and this runs during a dialog animation.
            let scale = UIScreen.main.scale
            let rendered = await Task.detached(priority: .userInitiated) {
                Self.encode(content, pixels: Int(size * scale))
            }.value
            image = rendered
        }
    }

    private static func encode(_ content: String, pixels: Int) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(content.utf8)
        filter.correctionLevel = "H"
        guard let output = filter.outputImage else { return nil }
        let scale = CGFloat(pixels) / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
