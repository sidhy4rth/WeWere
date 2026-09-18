import SwiftUI

/// Black and gold, always. There is no light theme: the app is a darkroom and the
/// photos are the only thing allowed to be bright. Chrome is warm near-black so it
/// sits behind skin tones and sunsets without turning them blue; gold is the single
/// accent and it is used sparingly — a hairline, a counter, one primary button.

let Ink = Color(hex: 0x0B0A08)
let Surface = Color(hex: 0x141210)
let Raised = Color(hex: 0x1C1916)
let Hairline = Color(hex: 0xD4AF37, alpha: 0x40 / 255.0)

let Gold = Color(hex: 0xD4AF37)
let GoldLight = Color(hex: 0xE5C158)
let GoldDeep = Color(hex: 0xA8842A)
let OnGold = Color(hex: 0x14110A)

let Ivory = Color(hex: 0xF3EDE0)
let IvoryMuted = Color(hex: 0xB9B0A0)
let Muted = Color(hex: 0x8E8578)

let ErrorColor = Color(hex: 0xFFB4AB)
let OnError = Color(hex: 0x690005)
/// M3 dark error container / on-error-container, what InlineError draws with.
let ErrorContainer = Color(hex: 0x93000A)
let OnErrorContainer = Color(hex: 0xFFDAD6)

/// Scrims for text laid over photographs.
let PhotoScrimTop = Color.black.opacity(0x99 / 255.0)
let PhotoScrimBottom = Color.black.opacity(0xCC / 255.0)

/// The primary-button and shutter fill: brushed gold, lit from above.
let GoldBrush = LinearGradient(
    stops: [.init(color: GoldLight, location: 0), .init(color: Gold, location: 0.55), .init(color: GoldDeep, location: 1)],
    startPoint: .top, endPoint: .bottom
)

/// A fading gold rule — used under headers and as a divider.
let HairlineBrush = LinearGradient(
    stops: [.init(color: .clear, location: 0), .init(color: Gold.opacity(0.55), location: 0.5), .init(color: .clear, location: 1)],
    startPoint: .leading, endPoint: .trailing
)

/// M3 outline / outlineVariant as the theme defined them.
let Outline = Gold.opacity(0.45)
let OutlineVariant = Ivory.opacity(0.12)

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}
