import SwiftUI
import AVFoundation

/// Camera permission, asked at the moment it is needed rather than at launch.
@MainActor
final class CameraPermission: ObservableObject {
    @Published var status: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    var isGranted: Bool { status == .authorized }
    /// Denied once already: the system dialog will not show again, only Settings can fix it.
    var isDeniedForever: Bool { status == .denied || status == .restricted }

    func request() {
        if isDeniedForever {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            return
        }
        Task {
            _ = await AVCaptureDevice.requestAccess(for: .video)
            status = AVCaptureDevice.authorizationStatus(for: .video)
        }
    }
}

/// The live preview layer, filling its bounds.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    var onLayer: ((AVCaptureVideoPreviewLayer) -> Void)? = nil

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        if let connection = view.previewLayer.connection, connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
        onLayer?(view.previewLayer)
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

/// Owns the capture session for the in-app camera: lens, flash, zoom, focus, shutter.
final class CameraController: NSObject, ObservableObject {

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "com.rollapp.shared.camera")
    private let photoOutput = AVCapturePhotoOutput()
    private var device: AVCaptureDevice?
    private var input: AVCaptureDeviceInput?
    private var pending: [Int64: (Result<URL, Error>) -> Void] = [:]
    private var refocusWork: DispatchWorkItem?
    var previewLayer: AVCaptureVideoPreviewLayer?

    @Published private(set) var isAvailable = true
    @Published private(set) var hasFlash = false
    @Published private(set) var maxZoom: CGFloat = 1
    @Published private(set) var minZoom: CGFloat = 1

    let capturesDir: URL

    init(capturesDir: URL) {
        self.capturesDir = capturesDir
        super.init()
    }

    /// (Re)binds the session to the given lens. Called whenever the lens flips.
    func configure(position: AVCaptureDevice.Position) {
        queue.async { [self] in
            session.beginConfiguration()
            session.sessionPreset = .photo
            if let input { session.removeInput(input) }

            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                DispatchQueue.main.async { self.isAvailable = false }
                return
            }
            session.addInput(input)
            self.input = input
            self.device = device

            if !session.outputs.contains(photoOutput), session.canAddOutput(photoOutput) {
                session.addOutput(photoOutput)
            }
            photoOutput.maxPhotoQualityPrioritization = .balanced
            session.commitConfiguration()

            let maxZoom = min(device.activeFormat.videoMaxZoomFactor, 10)
            let minZoom = device.minAvailableVideoZoomFactor
            let hasFlash = device.hasFlash
            DispatchQueue.main.async {
                self.isAvailable = true
                self.maxZoom = maxZoom
                self.minZoom = minZoom
                self.hasFlash = hasFlash
            }
            if !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        queue.async { [self] in if session.isRunning { session.stopRunning() } }
    }

    /// Pinch zoom; the factor is clamped to what the lens actually supports.
    func setZoom(_ factor: CGFloat) {
        guard let device else { return }
        let clamped = min(max(factor, minZoom), maxZoom)
        queue.async {
            guard (try? device.lockForConfiguration()) != nil else { return }
            device.videoZoomFactor = clamped
            device.unlockForConfiguration()
        }
    }

    /// Tap to focus at a point in the preview's coordinate space. Hands control back to
    /// continuous AF after a moment, so one tap does not lock focus for the whole session.
    func focus(at layerPoint: CGPoint) {
        guard let device, let previewLayer else { return }
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        refocusWork?.cancel()
        queue.async {
            guard (try? device.lockForConfiguration()) != nil else { return }
            if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.autoFocus) {
                device.focusPointOfInterest = devicePoint
                device.focusMode = .autoFocus
            }
            if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.autoExpose) {
                device.exposurePointOfInterest = devicePoint
                device.exposureMode = .autoExpose
            }
            device.unlockForConfiguration()
        }
        let work = DispatchWorkItem { [weak self] in
            guard let self, let device = self.device else { return }
            self.queue.async {
                guard (try? device.lockForConfiguration()) != nil else { return }
                if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
                if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
                device.unlockForConfiguration()
            }
        }
        refocusWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    /// Captures to a file in the app cache. Writing to a private file rather than the
    /// photo library keeps a photo out of the user's own gallery until they choose to
    /// share it — a shot they retake should leave no trace.
    func capture(flash: AVCaptureDevice.FlashMode, completion: @escaping (Result<URL, Error>) -> Void) {
        queue.async { [self] in
            let settings: AVCapturePhotoSettings
            if photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
                settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            } else {
                settings = AVCapturePhotoSettings()
            }
            if photoOutput.supportedFlashModes.contains(flash) { settings.flashMode = flash }
            if let connection = photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
            pending[settings.uniqueID] = completion
            photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let completion = pending.removeValue(forKey: photo.resolvedSettings.uniqueID)
        if let error {
            DispatchQueue.main.async { completion?(.failure(error)) }
            return
        }
        guard let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async { completion?(.failure(AppErrorException(.cameraUnavailable))) }
            return
        }
        do {
            try FileManager.default.createDirectory(at: capturesDir, withIntermediateDirectories: true)
            let file = capturesDir.appendingPathComponent("capture_\(Date.nowMillis).jpg")
            try data.write(to: file, options: .atomic)
            DispatchQueue.main.async { completion?(.success(file)) }
        } catch {
            DispatchQueue.main.async { completion?(.failure(error)) }
        }
    }
}
