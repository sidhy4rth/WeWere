import SwiftUI
import AVFoundation
import PhotosUI

/// Backs the gallery shortcut inside the camera.
///
/// Deliberately tiny and separate from GroupViewModel: the camera needs somewhere to
/// send photos, not the group's photo feed, member list and realtime listeners.
@MainActor
final class CameraViewModel: ObservableObject {
    let groupId: String
    private let photoRepository: PhotoRepository

    init(groupId: String, container: AppContainer = .shared) {
        self.groupId = groupId
        photoRepository = container.photoRepository
    }

    func uploadFromGallery(_ urls: [URL]) {
        if urls.isEmpty { return }
        Task {
            for url in urls {
                _ = await photoRepository.enqueueUpload(groupId: groupId, localUrl: url, caption: nil, capturedAt: Date.nowMillis)
                try? FileManager.default.removeItem(at: url)
            }
        }
    }
}

/// Camera → review → queued. Presented full-screen over whichever screen opened it.
/// `onFinish(true)` means photos were sent to the roll; `false` that it was closed.
struct CameraFlow: View {
    let groupId: String
    let onFinish: (Bool) -> Void

    @StateObject private var viewModel: CameraViewModel
    @State private var captured: Capture? = nil

    private struct Capture: Identifiable {
        let id = UUID()
        let url: URL
        let capturedAt: Millis
    }

    init(groupId: String, onFinish: @escaping (Bool) -> Void) {
        self.groupId = groupId
        self.onFinish = onFinish
        _viewModel = StateObject(wrappedValue: CameraViewModel(groupId: groupId))
    }

    var body: some View {
        ZStack {
            CameraScreen(
                onClose: { onFinish(false) },
                onPhotoCaptured: { url, at in captured = Capture(url: url, capturedAt: at) },
                onPickFromGallery: { urls in
                    viewModel.uploadFromGallery(urls)
                    onFinish(true)
                }
            )
            if let capture = captured {
                ReviewScreen(
                    groupId: groupId,
                    photoUrl: capture.url,
                    capturedAt: capture.capturedAt,
                    onRetake: { captured = nil },
                    onShared: { onFinish(true) }
                )
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: captured?.id)
        .preferredColorScheme(.dark)
    }
}

struct CameraScreen: View {
    let onClose: () -> Void
    let onPhotoCaptured: (URL, Millis) -> Void
    let onPickFromGallery: ([URL]) -> Void

    @StateObject private var permission = CameraPermission()

    var body: some View {
        if permission.isGranted {
            CameraContent(onClose: onClose, onPhotoCaptured: onPhotoCaptured, onPickFromGallery: onPickFromGallery)
        } else {
            CameraPermissionGate(
                shouldExplain: permission.isDeniedForever,
                onRequest: { permission.request() },
                onClose: onClose
            )
        }
    }
}

/// Permission is asked for here — at the moment the user taps the camera — rather than
/// at launch, with the reason stated before the system dialog appears.
private struct CameraPermissionGate: View {
    let shouldExplain: Bool
    let onRequest: () -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                Text("WeWere needs your camera")
                    .rollStyle(RollType.headlineMedium, color: Ivory)
                    .multilineTextAlignment(.center)
                Spacer().frame(height: 12)
                HairlineRule().frame(width: 120)
                Spacer().frame(height: 12)
                Text(shouldExplain
                     ? "Without it, you can still upload from your gallery — but taking a photo straight into the roll needs camera access. Turn it on in Settings."
                     : "So you can take photos straight into the roll. Nothing is captured until you press the shutter.")
                    .rollStyle(RollType.bodyMedium, color: Muted)
                    .multilineTextAlignment(.center)
                Spacer().frame(height: 28)
                GoldButton(text: shouldExplain ? "Open Settings" : "Allow camera", action: onRequest)
            }
            .padding(.horizontal, 36)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            IconButton(icon: RollIcon.close, tint: .white, label: "Close", action: onClose).padding(4)
        }
    }
}

private struct CameraContent: View {
    let onClose: () -> Void
    let onPhotoCaptured: (URL, Millis) -> Void
    let onPickFromGallery: ([URL]) -> Void

    @StateObject private var camera = CameraController(capturesDir: AppContainer.shared.capturesDir)
    @State private var lensBack = true
    @State private var flashMode: AVCaptureDevice.FlashMode = .off
    @State private var isCapturing = false
    @State private var showGrid = false
    @State private var timerSeconds = 0
    @State private var countdown = 0
    @State private var zoomRatio: CGFloat = 1
    @State private var pinchStart: CGFloat = 1
    @State private var focusPoint: CGPoint? = nil
    @State private var picks: [PhotosPickerItem] = []
    @State private var staging = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()

                if camera.isAvailable {
                    CameraPreview(session: camera.session) { layer in camera.previewLayer = layer }
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .gesture(
                            MagnificationGesture()
                                .onChanged { value in
                                    let next = min(max(pinchStart * value, camera.minZoom), camera.maxZoom)
                                    zoomRatio = next
                                    camera.setZoom(next)
                                }
                                .onEnded { _ in pinchStart = zoomRatio }
                        )
                        .simultaneousGesture(
                            SpatialTapGesture().onEnded { value in
                                focusPoint = value.location
                                camera.focus(at: value.location)
                            }
                        )
                } else {
                    VStack(spacing: 8) {
                        Text("Camera unavailable").rollStyle(RollType.headlineSmall, color: Ivory)
                        Text("You can still add photos from your library.").rollStyle(RollType.bodySmall, color: Muted)
                    }
                }

                if showGrid {
                    // Rule-of-thirds guides. Hairline and low-contrast so they help composition
                    // without competing with the scene.
                    Canvas { context, size in
                        var path = Path()
                        for i in 1...2 {
                            let x = size.width * CGFloat(i) / 3
                            let y = size.height * CGFloat(i) / 3
                            path.move(to: CGPoint(x: x, y: 0)); path.addLine(to: CGPoint(x: x, y: size.height))
                            path.move(to: CGPoint(x: 0, y: y)); path.addLine(to: CGPoint(x: size.width, y: y))
                        }
                        context.stroke(path, with: .color(.white.opacity(0.35)), lineWidth: 1)
                    }
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                }

                ViewfinderBrackets().ignoresSafeArea().allowsHitTesting(false)

                if let point = focusPoint {
                    Circle()
                        .stroke(Gold, lineWidth: 1.5)
                        .frame(width: 76, height: 76)
                        .position(point)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }

                if zoomRatio > 1.05 {
                    Readout(text: String(format: "%.1f×", zoomRatio), color: Gold)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Capsule().fill(Ink.opacity(0.55)))
                        .offset(y: 120)
                        .allowsHitTesting(false)
                }

                if countdown > 0 {
                    ZStack {
                        Color.black.opacity(0.25).ignoresSafeArea()
                        Text("\(countdown)")
                            .font(.custom(Face.cormorantItalic, size: 120))
                            .foregroundStyle(Gold)
                    }
                    .allowsHitTesting(false)
                }

                VStack {
                    HStack(spacing: 0) {
                        IconButton(icon: RollIcon.close, tint: Ivory, label: "Close", action: onClose)
                        Spacer()
                        IconButton(icon: RollIcon.grid, tint: showGrid ? Gold : Ivory, label: showGrid ? "Hide grid" : "Show grid") {
                            showGrid.toggle()
                        }
                        Button {
                            // Off -> 3s -> 10s -> off. Two useful delays beat a picker.
                            timerSeconds = timerSeconds == 0 ? 3 : (timerSeconds == 3 ? 10 : 0)
                        } label: {
                            ZStack {
                                if timerSeconds == 0 {
                                    Image(systemName: RollIcon.timerOff)
                                        .font(.system(size: 19, weight: .semibold)).foregroundStyle(Ivory)
                                } else {
                                    Readout(text: "\(timerSeconds)s", color: Gold)
                                }
                            }
                            .frame(width: 48, height: 48)
                        }
                        .buttonStyle(RippleButtonStyle(color: Ivory))
                        .accessibilityLabel("Self-timer")
                        IconButton(
                            icon: flashMode == .on ? RollIcon.flashOn : (flashMode == .auto ? RollIcon.flashAuto : RollIcon.flashOff),
                            tint: flashMode == .off ? Ivory : Gold,
                            label: "Flash"
                        ) {
                            flashMode = flashMode == .off ? .on : (flashMode == .on ? .auto : .off)
                        }
                    }
                    .padding(.horizontal, 4)

                    Spacer()

                    HStack {
                        PhotosPicker(selection: $picks, maxSelectionCount: Limits.maxGallerySelection, matching: .images) {
                            ZStack {
                                Circle().fill(Ink.opacity(0.55))
                                Circle().stroke(Ivory.opacity(0.2), lineWidth: 1)
                                if staging {
                                    ProgressView().tint(Ivory)
                                } else {
                                    Image(systemName: RollIcon.photoLibrary)
                                        .font(.system(size: 19, weight: .semibold)).foregroundStyle(Ivory)
                                }
                            }
                            .frame(width: 52, height: 52)
                        }
                        .buttonStyle(PlainPressStyle())
                        .disabled(staging)

                        Spacer()

                        let shutterEnabled = !isCapturing && countdown == 0 && camera.isAvailable
                        Shutter(size: 84, pulsing: shutterEnabled) { if shutterEnabled { shutterPressed() } }
                            .opacity(shutterEnabled ? 1 : 0.5)

                        Spacer()

                        Button {
                            lensBack.toggle()
                        } label: {
                            ZStack {
                                Circle().fill(Ink.opacity(0.55))
                                Circle().stroke(Ivory.opacity(0.2), lineWidth: 1)
                                Image(systemName: RollIcon.cameraSwitch)
                                    .font(.system(size: 19, weight: .semibold)).foregroundStyle(Ivory)
                            }
                            .frame(width: 52, height: 52)
                        }
                        .buttonStyle(PlainPressStyle())
                        .accessibilityLabel("Flip camera")
                    }
                    .padding(.horizontal, 32).padding(.vertical, 28)
                }
            }
        }
        .onAppear { camera.configure(position: lensBack ? .back : .front) }
        .onDisappear { camera.stop() }
        // Rebind whenever the lens changes. Flipping the lens resets optics; carrying
        // the old zoom across is wrong.
        .onChange(of: lensBack) { _, back in
            zoomRatio = 1; pinchStart = 1
            camera.configure(position: back ? .back : .front)
        }
        // A tapped focus reticle should fade rather than sit there forever.
        .onChange(of: focusPoint) { _, point in
            guard point != nil else { return }
            Task {
                try? await Task.sleep(nanoseconds: 900_000_000)
                withAnimation(.easeOut(duration: 0.2)) { focusPoint = nil }
            }
        }
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            staging = true
            Task {
                let urls = await PhotoPicking.stage(items, into: AppContainer.shared.capturesDir)
                picks = []
                staging = false
                if !urls.isEmpty { onPickFromGallery(urls) }
            }
        }
    }

    private func capture() {
        isCapturing = true
        camera.capture(flash: flashMode) { result in
            isCapturing = false
            if case .success(let url) = result { onPhotoCaptured(url, Date.nowMillis) }
        }
    }

    private func shutterPressed() {
        if timerSeconds == 0 { capture(); return }
        Task {
            countdown = timerSeconds
            while countdown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                countdown -= 1
            }
            capture()
        }
    }
}

/// Gold corner brackets that breathe slowly — the viewfinder is alive without any
/// of it getting in the way of the scene.
private struct ViewfinderBrackets: View {
    @State private var breathe: CGFloat = 0

    var body: some View {
        Canvas { context, size in
            let inset: CGFloat = 34
            let top = size.height * 0.2
            let bottom = size.height * 0.72
            let len: CGFloat = 26
            let stroke: CGFloat = 2
            let grow = 3 * breathe
            let color = Gold.opacity(0.7 + 0.3 * breathe)
            let l = inset - grow, r = size.width - inset + grow
            let t = top - grow, b = bottom + grow
            var path = Path()
            path.move(to: .init(x: l, y: t)); path.addLine(to: .init(x: l + len, y: t))
            path.move(to: .init(x: l, y: t)); path.addLine(to: .init(x: l, y: t + len))
            path.move(to: .init(x: r, y: t)); path.addLine(to: .init(x: r - len, y: t))
            path.move(to: .init(x: r, y: t)); path.addLine(to: .init(x: r, y: t + len))
            path.move(to: .init(x: l, y: b)); path.addLine(to: .init(x: l + len, y: b))
            path.move(to: .init(x: l, y: b)); path.addLine(to: .init(x: l, y: b - len))
            path.move(to: .init(x: r, y: b)); path.addLine(to: .init(x: r - len, y: b))
            path.move(to: .init(x: r, y: b)); path.addLine(to: .init(x: r, y: b - len))
            context.stroke(path, with: .color(color), lineWidth: stroke)
            let c = CGPoint(x: size.width / 2, y: (top + bottom) / 2)
            context.fill(Path(ellipseIn: CGRect(x: c.x - 3, y: c.y - 3, width: 6, height: 6)), with: .color(Gold))
        }
        .onAppear {
            withAnimation(.timingCurve(0.2, 0.8, 0.2, 1, duration: 3.2).repeatForever(autoreverses: true)) {
                breathe = 1
            }
        }
    }
}
