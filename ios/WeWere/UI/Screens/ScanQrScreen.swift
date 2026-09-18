import SwiftUI
import AVFoundation

/// Scans a group's QR code.
///
/// Typing a six-character code is the slowest part of joining, and the one people get
/// wrong. Pointing a camera at a friend's screen is the natural gesture for an app
/// that is already a camera. Detection is on-device, so it works with no network —
/// often at exactly the moment a group is being formed, on hotel wifi.
struct ScanQrScreen: View {
    let onClose: () -> Void
    let onCodeScanned: (String) -> Void

    @StateObject private var permission = CameraPermission()

    var body: some View {
        SwiftUI.Group {
            if permission.isGranted {
                ScannerContent(onClose: onClose, onCodeScanned: onCodeScanned)
            } else {
                ZStack(alignment: .topLeading) {
                    Color.black.ignoresSafeArea()
                    VStack(spacing: 0) {
                        Text("Point your camera at the code")
                            .rollStyle(RollType.headlineSmall, color: .white)
                            .multilineTextAlignment(.center)
                        Spacer().frame(height: 10)
                        Text("WeWere needs the camera to read a roll's QR code. Nothing is recorded — it only looks for a code.")
                            .rollStyle(RollType.bodyMedium, color: .white.opacity(0.75))
                            .multilineTextAlignment(.center)
                        Spacer().frame(height: 28)
                        FilledButton(text: permission.isDeniedForever ? "Open Settings" : "Allow camera") { permission.request() }
                        TextButton(text: "Enter the code instead", color: .white, action: onClose)
                    }
                    .padding(.horizontal, 36)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    IconButton(icon: RollIcon.close, tint: .white, label: "Close", action: onClose)
                        .padding(4)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

private struct ScannerContent: View {
    let onClose: () -> Void
    let onCodeScanned: (String) -> Void

    @StateObject private var scanner = QrScanner()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: scanner.session).ignoresSafeArea()

            // Dimmed surround with a clear window, so it is obvious where to aim.
            Color.black.opacity(0.45).ignoresSafeArea()
            RoundedRectangle(cornerRadius: 24)
                .stroke(Color.white, lineWidth: 3)
                .frame(width: 250, height: 250)

            VStack {
                HStack {
                    IconButton(icon: RollIcon.close, tint: .white, label: "Close", action: onClose).padding(4)
                    Spacer()
                }
                Spacer()
                VStack(spacing: 0) {
                    Text("Point at a roll's QR code").rollStyle(RollType.titleMedium, color: .white)
                    TextButton(text: "Type the code instead", color: .white.opacity(0.85), action: onClose)
                }
                .padding(32)
            }
        }
        .onAppear { scanner.start { code in onCodeScanned(code) } }
        .onDisappear { scanner.stop() }
    }
}

private final class QrScanner: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.rollapp.shared.qr")
    private var onCode: ((String) -> Void)?
    // Guards against firing repeatedly: the camera sees the same code in every frame
    // for as long as it stays in view.
    private var handled = false

    func start(onCode: @escaping (String) -> Void) {
        self.onCode = onCode
        handled = false
        queue.async { [self] in
            if session.inputs.isEmpty {
                session.beginConfiguration()
                if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                   let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
                    session.addInput(input)
                }
                let output = AVCaptureMetadataOutput()
                if session.canAddOutput(output) {
                    session.addOutput(output)
                    output.setMetadataObjectsDelegate(self, queue: queue)
                    if output.availableMetadataObjectTypes.contains(.qr) { output.metadataObjectTypes = [.qr] }
                }
                session.commitConfiguration()
            }
            if !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        queue.async { [self] in if session.isRunning { session.stopRunning() } }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !handled else { return }
        // Accept a full invite link or a bare code; a QR from another app should
        // simply not match rather than throw.
        let code = metadataObjects
            .compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
            .compactMap { raw -> String? in
                if let fromLink = InviteCodes.fromLink(raw) { return fromLink }
                let normalised = InviteCodes.normalise(raw)
                return InviteCodes.isPlausible(normalised) ? normalised : nil
            }
            .first
        guard let code else { return }
        handled = true
        DispatchQueue.main.async { self.onCode?(code) }
    }
}
