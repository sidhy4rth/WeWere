import SwiftUI

/// The Material Rounded icons the Android app uses, mapped to SF Symbols.
enum RollIcon {
    static let home = "house.fill"
    static let camera = "camera.fill"
    static let notifications = "bell.fill"
    static let person = "person.fill"
    static let back = "chevron.left"
    static let add = "plus"
    static let photoLibrary = "photo.on.rectangle"
    static let autoAwesome = "sparkles"
    static let visibility = "eye"
    static let visibilityOff = "eye.slash"
    static let addPhoto = "photo.badge.plus"
    static let copy = "doc.on.doc"
    static let share = "square.and.arrow.up"
    static let groups = "person.3.fill"
    static let qrScanner = "qrcode.viewfinder"
    static let more = "ellipsis"
    static let personAdd = "person.badge.plus"
    static let check = "checkmark"
    static let close = "xmark"
    static let delete = "trash"
    static let download = "arrow.down.to.line"
    static let people = "person.2.fill"
    static let play = "play.fill"
    static let pause = "pause.fill"
    static let selectAll = "checkmark.circle"
    static let settings = "gearshape.fill"
    static let star = "star.fill"
    static let starBorder = "star"
    static let chevronRight = "chevron.right"
    static let deleteForever = "trash.fill"
    static let image = "photo"
    static let link = "link"
    static let linkOff = "link.badge.plus"
    static let logout = "rectangle.portrait.and.arrow.right"
    static let refresh = "arrow.clockwise"
    static let edit = "pencil"
    static let cloudOff = "icloud.slash"
    static let cameraSwitch = "arrow.triangle.2.circlepath.camera"
    static let flashAuto = "bolt.badge.automatic"
    static let flashOff = "bolt.slash"
    static let flashOn = "bolt.fill"
    static let grid = "grid"
    static let timerOff = "timer"
}

/// M3 `IconButton`: a 48pt touch target around a 24pt glyph.
struct IconButton: View {
    let icon: String
    var tint: Color = Ivory
    var size: CGFloat = 24
    var label: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: size * 0.8, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(RippleButtonStyle(color: tint))
        .accessibilityLabel(label ?? icon)
    }
}

/// A press state that dims like a Material ripple, without the spread.
struct RippleButtonStyle: ButtonStyle {
    var color: Color = .black
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Circle().fill(color.opacity(configuration.isPressed ? 0.12 : 0)))
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct PressDimStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(Capsule().fill(Color.black.opacity(configuration.isPressed ? 0.12 : 0)))
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// A plain pressable area: no chrome, a light dim while pressed.
struct PlainPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.82 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

enum Haptics {
    static func longPress() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func light() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}
