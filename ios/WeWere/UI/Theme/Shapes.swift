import SwiftUI

/// Pills for anything tappable, soft corners for anything that holds a photo.
enum RollShapes {
    static let extraSmall: CGFloat = 6
    static let small: CGFloat = 8
    static let medium: CGFloat = 12
    static let large: CGFloat = 18
    static let extraLarge: CGFloat = 28
}

/// The one easing every entrance in the app shares. Fast out, long settle.
extension Animation {
    static func settle(duration: Double = 0.7, delay: Double = 0) -> Animation {
        .timingCurve(0.2, 0.8, 0.2, 1, duration: duration).delay(delay)
    }
}
