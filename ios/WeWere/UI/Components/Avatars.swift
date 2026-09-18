import SwiftUI

/// Avatar with a deterministic colour fallback.
///
/// The colour is derived from the user id, not picked at random, so the same person is
/// the same colour on every device and in every group — which is what makes an initials
/// bubble readable as a specific person rather than decoration.
struct UserAvatar: View {
    let name: String
    let photoUrl: String?
    var size: CGFloat = 40
    var seed: String? = nil
    var borderColor: Color? = nil

    var body: some View {
        let background = avatarColor(for: seed ?? name)
        ZStack {
            Circle().fill(background)
            if let photoUrl, !photoUrl.trimmingCharacters(in: .whitespaces).isEmpty {
                RemoteImage(url: URL(string: photoUrl), contentMode: .fill, downsample: max(size * 3, 160))
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else {
                Text(User.initials(of: name))
                    .font(.custom(Face.manropeBold, size: size * 0.36))
                    .foregroundStyle(Color(hex: 0x14110A))
            }
            if let borderColor {
                Circle().stroke(borderColor, lineWidth: 2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityLabel(name)
    }
}

/// The overlapping avatar row on a group card.
struct AvatarStack: View {
    let photoUrls: [String]
    var names: [String] = []
    var size: CGFloat = 26
    var maxVisible: Int = 4
    var overlap: CGFloat = 9
    var borderColor: Color = Ink

    var body: some View {
        let visible = Array(photoUrls.prefix(maxVisible))
        HStack(spacing: -overlap) {
            ForEach(Array(visible.enumerated()), id: \.offset) { index, url in
                UserAvatar(name: names.indices.contains(index) ? names[index] : "", photoUrl: url,
                           size: size, seed: url, borderColor: borderColor)
                    .zIndex(Double(maxVisible - index))
            }
        }
    }
}

/// Muted pastels: they have to sit next to gold without competing with it.
private let AvatarColors: [Color] = [
    Color(hex: 0xF2C27B), Color(hex: 0xE8B4C9), Color(hex: 0x9AC6E8),
    Color(hex: 0x6F8F6A), Color(hex: 0xCFD7E2), Color(hex: 0xE8A87C),
    Color(hex: 0xB8A6D9), Color(hex: 0x8FBFB4)
]

func avatarColor(for seed: String) -> Color {
    let hash = seed.javaHashCode
    let index = Int(hash.magnitude % UInt32(AvatarColors.count))
    return AvatarColors[index]
}

extension String {
    /// Kotlin/Java `String.hashCode()`, so a person is the same colour on both platforms.
    var javaHashCode: Int32 {
        var h: Int32 = 0
        for unit in utf16 { h = h &* 31 &+ Int32(unit) }
        return h
    }
}
