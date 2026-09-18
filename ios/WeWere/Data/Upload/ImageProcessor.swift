import Foundation
import ImageIO
import UniformTypeIdentifiers
import CoreGraphics

struct ProcessedImage {
    let bytes: Data
    let width: Int
    let height: Int
}

struct ProcessedUpload {
    let full: ProcessedImage
    let thumbnail: ProcessedImage
    /// From EXIF, when the shot was actually taken. Nil if the file had none.
    let capturedAt: Millis?
}

/// Turns whatever the camera or photo library hands us into two JPEGs: one sized for
/// full-screen viewing and one for the grid.
///
/// Re-encoding is also how EXIF gets stripped. The JPEG is written from a bare
/// CGImage with no metadata dictionary, so GPS coordinates, the device model and the
/// original timestamp never leave the phone — orientation is baked into the pixels
/// first so the image still appears the right way up. Capture time is read out before
/// that and carried in the Firestore document instead, where it is group-visible
/// rather than embedded in a file anyone could download.
final class ImageProcessor {

    func prepare(url: URL) throws -> ProcessedUpload {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            throw AppErrorException(.validation("Could not read that image"))
        }
        let capturedAt = readCaptureTime(source)
        let full = try decodeScaled(source, maxEdge: Limits.fullImageMaxEdge)
        let thumb = try decodeScaled(source, maxEdge: Limits.thumbnailMaxEdge)
        return ProcessedUpload(
            full: try encodeJpeg(full, quality: Limits.fullImageQuality),
            thumbnail: try encodeJpeg(thumb, quality: Limits.thumbnailQuality),
            capturedAt: capturedAt
        )
    }

    func prepareCover(url: URL) throws -> ProcessedImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            throw AppErrorException(.validation("Could not read that image"))
        }
        return try encodeJpeg(try decodeScaled(source, maxEdge: Self.coverMaxEdge), quality: Limits.fullImageQuality)
    }

    func prepareAvatar(url: URL) throws -> ProcessedImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            throw AppErrorException(.validation("Could not read that image"))
        }
        return try encodeJpeg(try decodeScaled(source, maxEdge: Self.avatarMaxEdge), quality: 85)
    }

    /// Decodes straight to a bounded size, upright. ImageIO's thumbnail path never
    /// materialises the full 48MP bitmap — decoding one of those directly is ~190MB
    /// and an immediate memory kill on a mid-range phone.
    private func decodeScaled(_ source: CGImageSource, maxEdge: Int) throws -> CGImage {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxEdge
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw AppErrorException(.validation("Could not read that image"))
        }
        return image
    }

    private func encodeJpeg(_ image: CGImage, quality: Int) throws -> ProcessedImage {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw AppErrorException(.unknown("Could not encode image"))
        }
        let properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: Double(quality) / 100.0]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            throw AppErrorException(.unknown("Could not encode image"))
        }
        return ProcessedImage(bytes: data as Data, width: image.width, height: image.height)
    }

    /// EXIF timestamps carry no timezone, so they are parsed in the device's zone —
    /// which is the right guess for a photo the user took on this phone, and the only
    /// guess available for one they were sent.
    private func readCaptureTime(_ source: CGImageSource) -> Millis? {
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else { return nil }
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        let raw = (exif?[kCGImagePropertyExifDateTimeOriginal] as? String)
            ?? (tiff?[kCGImagePropertyTIFFDateTime] as? String)
        guard let raw else { return nil }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
        guard let date = formatter.date(from: raw) else { return nil }
        let millis = date.millis
        // A camera with an unset clock reports 1980; that is worse than no value.
        return millis > Self.minPlausibleCaptureMillis ? millis : nil
    }

    private static let coverMaxEdge = 1280
    private static let avatarMaxEdge = 512

    /// 2000-01-01. Anything older is a camera with a dead clock battery.
    private static let minPlausibleCaptureMillis: Millis = 946_684_800_000
}
