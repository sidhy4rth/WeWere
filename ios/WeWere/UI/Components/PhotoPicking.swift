import SwiftUI
import PhotosUI

/// The system photo picker, which needs no library permission on any iOS version —
/// it runs out of process and hands the app exactly the photos the user chose.
enum PhotoPicking {

    /// Copies each pick to a temporary file the upload queue can stage from.
    static func stage(_ items: [PhotosPickerItem], into directory: URL) async -> [URL] {
        var urls: [URL] = []
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let file = directory.appendingPathComponent("pick_\(UUID().uuidString).img")
            if (try? data.write(to: file, options: .atomic)) != nil { urls.append(file) }
        }
        return urls
    }
}
