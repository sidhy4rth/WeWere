import Foundation

/// Where image bytes live.
///
/// Firestore holds every fact about a photo except the pixels. Those go through this
/// interface so the blob backend is swappable: production talks to Supabase Storage
/// (Firebase's own Cloud Storage needs the Blaze plan since late 2024).
///
/// Paths are bucket-relative and never start with a slash, e.g.
/// `groups/{groupId}/full/{photoId}.jpg`. See `StoragePaths`.
protocol ImageStore: AnyObject {

    /// Uploads `bytes` to `path`, replacing anything already there, and returns a URL
    /// that an image loader can fetch with no further credentials. Retries of a failed
    /// upload therefore land on the same object instead of orphaning the first attempt.
    ///
    /// `onProgress` is called with a 0..1 fraction from whichever thread does the I/O;
    /// it must not block.
    func upload(path: String, bytes: Data, contentType: String, onProgress: @escaping (Float) -> Void) async throws -> String

    /// Fetches the object at `path`, failing if it is larger than `maxBytes`.
    func download(path: String, maxBytes: Int64) async throws -> Data

    /// Removes the object at `path`. Deleting something already gone is not an error.
    func delete(path: String) async throws
}

extension ImageStore {
    func upload(path: String, bytes: Data) async throws -> String {
        try await upload(path: path, bytes: bytes, contentType: "image/jpeg", onProgress: { _ in })
    }
}

/// Raised by an `ImageStore` so the error mapper can produce a sensible `AppError`.
struct ImageStoreError: Error, CustomStringConvertible {
    enum Kind { case notAuthenticated, notAuthorized, notFound, tooLarge, quota, other }
    let kind: Kind
    let message: String?

    init(_ kind: Kind, _ message: String? = nil) {
        self.kind = kind
        self.message = message
    }

    var description: String { message ?? "\(kind)" }
}
