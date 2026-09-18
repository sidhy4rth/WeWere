import SwiftUI

/// Three faces, each with one job.
///
/// - **Cormorant Garamond** carries every title, and its italic carries dates,
///   captions and the wordmark. It is what makes a group name read like the title of
///   an album rather than a list row.
/// - **Manrope** is the body and button face — quiet on purpose, so the serif and
///   the photographs get the room.
/// - **JetBrains Mono** is for readouts: counters, timestamps, "128 exposures". Set
///   small, tracked wide and upper-case, it reads like the display on a camera.
enum Face {
    static let cormorantMedium = "CormorantGaramond-Medium"
    static let cormorantSemiBold = "CormorantGaramond-SemiBold"
    static let cormorantItalic = "CormorantGaramond-MediumItalic"
    static let manrope = "Manrope-Regular"
    static let manropeMedium = "Manrope-Medium"
    static let manropeSemiBold = "Manrope-SemiBold"
    static let manropeBold = "Manrope-Bold"
    static let mono = "JetBrainsMono-Medium"
}

/// One of the app's text styles: a face, a size, the line height and tracking the
/// Material typography on Android specified for it.
struct RollTextStyle {
    let face: String
    let size: CGFloat
    let lineHeight: CGFloat
    let tracking: CGFloat

    var font: Font { .custom(face, size: size) }

    /// SwiftUI has no line-height property; the closest match is extra spacing
    /// between lines, which is the difference from the font's own leading.
    var lineSpacing: CGFloat {
        let natural = UIFont(name: face, size: size)?.lineHeight ?? size * 1.2
        return max(0, lineHeight - natural)
    }
}

enum RollType {
    static let displayLarge = RollTextStyle(face: Face.cormorantItalic, size: 64, lineHeight: 64, tracking: -1.2)
    static let displaySmall = RollTextStyle(face: Face.cormorantSemiBold, size: 44, lineHeight: 46, tracking: -0.4)
    static let headlineLarge = RollTextStyle(face: Face.cormorantSemiBold, size: 40, lineHeight: 42, tracking: -0.4)
    static let headlineMedium = RollTextStyle(face: Face.cormorantSemiBold, size: 30, lineHeight: 32, tracking: -0.2)
    static let headlineSmall = RollTextStyle(face: Face.cormorantItalic, size: 22, lineHeight: 26, tracking: 0)
    static let titleLarge = RollTextStyle(face: Face.cormorantSemiBold, size: 26, lineHeight: 30, tracking: 0)
    static let titleMedium = RollTextStyle(face: Face.manropeSemiBold, size: 16, lineHeight: 22, tracking: 0)
    static let titleSmall = RollTextStyle(face: Face.manropeSemiBold, size: 14, lineHeight: 20, tracking: 0)
    static let bodyLarge = RollTextStyle(face: Face.manrope, size: 16, lineHeight: 24, tracking: 0)
    static let bodyMedium = RollTextStyle(face: Face.manrope, size: 14, lineHeight: 20, tracking: 0)
    static let bodySmall = RollTextStyle(face: Face.manrope, size: 13, lineHeight: 18, tracking: 0)
    static let labelLarge = RollTextStyle(face: Face.manropeBold, size: 16, lineHeight: 20, tracking: 0.1)
    static let labelMedium = RollTextStyle(face: Face.mono, size: 11, lineHeight: 14, tracking: 1)
    static let labelSmall = RollTextStyle(face: Face.mono, size: 10, lineHeight: 12, tracking: 0.9)
}

extension View {
    /// Applies a text style: face, size, tracking and line spacing together.
    func rollText(_ style: RollTextStyle) -> some View {
        self.font(style.font).tracking(style.tracking).lineSpacing(style.lineSpacing)
    }
}

extension Text {
    func rollStyle(_ style: RollTextStyle, color: Color = Ivory) -> some View {
        self.font(style.font).tracking(style.tracking).lineSpacing(style.lineSpacing).foregroundStyle(color)
    }
}
