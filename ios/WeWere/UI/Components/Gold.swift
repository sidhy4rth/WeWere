import SwiftUI

/// Fades and lifts `content` into place once, the first time it appears.
///
/// Every list, header and button arrives this way, staggered by `delay`, so a
/// screen is built in front of the user rather than snapped on.
struct RiseIn<Content: View>: View {
    var delay: Double = 0
    var distance: CGFloat = 18
    @ViewBuilder let content: () -> Content

    @State private var progress: CGFloat = 0

    var body: some View {
        content()
            .opacity(progress)
            .offset(y: (1 - progress) * distance)
            .onAppear {
                guard progress == 0 else { return }
                withAnimation(.settle(duration: 0.7, delay: delay)) { progress = 1 }
            }
    }
}

/// A fading gold rule.
struct HairlineRule: View {
    var body: some View {
        Rectangle().fill(HairlineBrush).frame(height: 1)
    }
}

/// Mono, upper-case, tracked — the camera-readout voice used for counters,
/// timestamps and section eyebrows. Never for sentences.
struct Readout: View {
    let text: String
    var color: Color = Muted
    var alignment: TextAlignment = .leading

    var body: some View {
        Text(text.uppercased())
            .rollStyle(RollType.labelSmall, color: color)
            .multilineTextAlignment(alignment)
            .lineLimit(1)
    }
}

/// The primary action: a brushed-gold pill with a soft glow. One per screen at most.
struct GoldButton: View {
    let text: String
    var icon: String? = nil
    var enabled: Bool = true
    var loading: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: { if enabled && !loading { action() } }) {
            ZStack {
                Capsule().fill(GoldBrush)
                if loading {
                    ProgressView().tint(OnGold).scaleEffect(0.9)
                } else {
                    HStack(spacing: 10) {
                        if let icon {
                            Image(systemName: icon).font(.system(size: 17, weight: .bold)).foregroundStyle(OnGold)
                        }
                        Text(text).rollStyle(RollType.labelLarge, color: OnGold)
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled || loading)
        .opacity(enabled ? 1 : 0.5)
        .shadow(color: Gold.opacity(enabled ? 0.35 : 0), radius: 18, y: 8)
    }
}

/// The secondary action: same pill, a gold hairline instead of a fill.
struct HairlineButton: View {
    let text: String
    var icon: String? = nil
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let icon {
                    Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(Ivory)
                }
                Text(text).rollStyle(RollType.titleMedium, color: Ivory)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .overlay(Capsule().stroke(Gold.opacity(0.45), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// A quiet tertiary action — plain text, muted, generous hit target.
struct QuietButton: View {
    let text: String
    var enabled: Bool = true
    var color: Color = Muted
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).rollStyle(RollType.bodySmall, color: color)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// M3 `TextButton`: label text in the primary colour, no container.
struct TextButton: View {
    let text: String
    var color: Color = Gold
    var style: RollTextStyle = RollType.labelLarge
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).rollStyle(style, color: color)
                .padding(.horizontal, 12)
                .frame(minHeight: 40)
                .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.38)
    }
}

/// M3 filled `Button`: a gold pill, 40pt tall.
struct FilledButton: View {
    let text: String
    var icon: String? = nil
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon { Image(systemName: icon).font(.system(size: 15, weight: .bold)) }
                Text(text).rollStyle(RollType.labelLarge, color: OnGold)
            }
            .foregroundStyle(OnGold)
            .padding(.horizontal, 24)
            .frame(height: 40)
            .background(Capsule().fill(Gold))
            .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// M3 `OutlinedButton`: pill with a hairline, ivory label.
struct OutlinedPillButton: View {
    let text: String
    var enabled: Bool = true
    var height: CGFloat = 40
    var cornerRadius: CGFloat? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text).rollStyle(RollType.labelLarge, color: Ivory)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius ?? height / 2)
                        .stroke(Outline, lineWidth: 1)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(PlainPressStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// A filter chip: filled gold when on, hairline when off.
struct GoldChip: View {
    let text: String
    let selected: Bool
    var icon: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(selected ? OnGold : Gold)
                }
                Text(text).rollStyle(RollType.titleSmall, color: selected ? OnGold : Ivory)
            }
            .padding(.horizontal, 16)
            .frame(height: 36)
            .background(Capsule().fill(selected ? Gold : .clear))
            .overlay(Capsule().stroke(selected ? .clear : Gold.opacity(0.5), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(PressDimStyle())
    }
}

/// The shutter. A gold ring with a brushed fill and, when `pulsing`, a ring that
/// expands and fades on a loop — the app's one always-moving element, so the eye
/// lands on the verb.
struct Shutter: View {
    var size: CGFloat = 76
    var pulsing: Bool = true
    var label: String = "Take photo"
    let action: () -> Void

    @State private var pulse: CGFloat = 0

    var body: some View {
        Button(action: action) {
            ZStack {
                if pulsing {
                    Circle()
                        .stroke(Gold.opacity(0.7 * (1 - pulse)), lineWidth: 2)
                        .scaleEffect(1 + 0.5 * pulse)
                }
                Circle().stroke(Gold, lineWidth: 3)
                Circle()
                    .fill(GoldBrush)
                    .frame(width: size - 16, height: size - 16)
                    .shadow(color: Gold.opacity(0.5), radius: 12, y: 4)
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(PlainPressStyle())
        .accessibilityLabel(label)
        .onAppear { startPulse() }
        .onChange(of: pulsing) { _, now in if now { startPulse() } }
    }

    private func startPulse() {
        pulse = 0
        withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 2.2).repeatForever(autoreverses: false)) {
            pulse = 1
        }
    }
}

/// The app's top bar: a chevron, a serif title, optional actions. No tonal tint.
struct RollTopBar<Actions: View>: View {
    let title: String
    var onBack: (() -> Void)? = nil
    @ViewBuilder var actions: () -> Actions

    init(title: String, onBack: (() -> Void)? = nil, @ViewBuilder actions: @escaping () -> Actions = { EmptyView() }) {
        self.title = title
        self.onBack = onBack
        self.actions = actions
    }

    var body: some View {
        HStack(spacing: 0) {
            if let onBack {
                IconButton(icon: RollIcon.back, tint: Ivory, size: 20, label: "Back", action: onBack)
            } else {
                Spacer().frame(width: 16)
            }
            Text(title)
                .rollStyle(RollType.titleLarge, color: Ivory)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.leading, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            actions()
        }
        .padding(.leading, 4).padding(.trailing, 4).padding(.top, 4).padding(.bottom, 8)
        .background(Ink)
    }
}

/// Text fields: gold hairline, gold when focused, a whisper of raised surface behind.
struct RollTextField<Trailing: View>: View {
    var label: String? = nil
    @Binding var text: String
    var placeholder: String? = nil
    var isSecure: Bool = false
    var keyboard: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .sentences
    var submitLabel: SubmitLabel = .done
    var supportingText: String? = nil
    var singleLine: Bool = true
    var minLines: Int = 1
    var maxLines: Int = 1
    var cornerRadius: CGFloat = RollShapes.large
    var textStyle: RollTextStyle = RollType.bodyLarge
    var textColor: Color = Ivory
    var textAlignment: TextAlignment = .leading
    /// What sits behind the floating label chip — the screen or dialog surface.
    var chipBackground: Color = Ink
    var onSubmit: (() -> Void)? = nil
    @ViewBuilder var trailing: () -> Trailing

    @FocusState private var focused: Bool

    init(label: String? = nil, text: Binding<String>, placeholder: String? = nil, isSecure: Bool = false,
         keyboard: UIKeyboardType = .default, capitalization: TextInputAutocapitalization = .sentences,
         submitLabel: SubmitLabel = .done, supportingText: String? = nil, singleLine: Bool = true,
         minLines: Int = 1, maxLines: Int = 1, cornerRadius: CGFloat = RollShapes.large,
         textStyle: RollTextStyle = RollType.bodyLarge, textColor: Color = Ivory,
         textAlignment: TextAlignment = .leading, chipBackground: Color = Ink,
         onSubmit: (() -> Void)? = nil, @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }) {
        self.label = label
        self._text = text
        self.placeholder = placeholder
        self.isSecure = isSecure
        self.keyboard = keyboard
        self.capitalization = capitalization
        self.submitLabel = submitLabel
        self.supportingText = supportingText
        self.singleLine = singleLine
        self.minLines = minLines
        self.maxLines = maxLines
        self.cornerRadius = cornerRadius
        self.textStyle = textStyle
        self.textColor = textColor
        self.textAlignment = textAlignment
        self.chipBackground = chipBackground
        self.onSubmit = onSubmit
        self.trailing = trailing
    }

    private var floating: Bool { focused || !text.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .topLeading) {
                HStack(spacing: 8) {
                    field
                        .focused($focused)
                        .rollText(textStyle)
                        .foregroundStyle(textColor)
                        .tint(Gold)
                        .keyboardType(keyboard)
                        .textInputAutocapitalization(capitalization)
                        .autocorrectionDisabled(keyboard == .emailAddress || isSecure)
                        .submitLabel(submitLabel)
                        .onSubmit { onSubmit?() }
                        .multilineTextAlignment(textAlignment)
                    trailing()
                }
                .padding(.leading, 16)
                .padding(.trailing, 12)
                .padding(.vertical, singleLine ? 0 : 16)
                .frame(minHeight: 56)
                .background(RoundedRectangle(cornerRadius: cornerRadius).fill(Raised.opacity(focused ? 0.6 : 0.4)))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .stroke(focused ? Gold : Gold.opacity(0.35), lineWidth: focused ? 2 : 1)
                )

                if let label, floating {
                    Text(label)
                        .font(.custom(Face.manrope, size: 12))
                        .foregroundStyle(focused ? Gold : Muted)
                        .padding(.horizontal, 4)
                        .background(chipBackground)
                        .offset(x: 12, y: -8)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { focused = true }

            if let supportingText {
                Text(supportingText)
                    .rollStyle(RollType.bodySmall, color: Muted)
                    .padding(.horizontal, 16)
            }
        }
    }

    @ViewBuilder private var field: some View {
        let prompt: String? = floating ? placeholder : (label ?? placeholder)
        if isSecure {
            SecureField("", text: $text, prompt: prompt.map { Text($0).foregroundStyle(Muted) })
        } else if singleLine {
            TextField("", text: $text, prompt: prompt.map { Text($0).foregroundStyle(Muted) })
        } else {
            TextField("", text: $text, prompt: prompt.map { Text($0).foregroundStyle(Muted) }, axis: .vertical)
                .lineLimit(minLines...max(minLines, maxLines))
        }
    }
}
