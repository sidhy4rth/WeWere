import Foundation
import FirebaseAuth

/// `ImageStore` backed by a private Supabase Storage bucket.
///
/// This talks to the Storage REST API directly rather than through supabase-swift:
/// the four calls we need are one request each.
///
/// Authentication is the user's **Firebase** ID token. Supabase is configured to trust
/// `securetoken.google.com/<firebase-project>` as a third-party issuer, so the same
/// sign-in that gates Firestore gates the bucket, and the bucket's row-level policies
/// (see `supabase/storage-policies.sql`) can read the Firebase uid from the token.
///
/// Reads go through long-lived signed URLs minted at upload time and stored on the
/// photo document — the equivalent of Firebase's tokenised download URLs, with the
/// same caveat: whoever holds the URL can fetch the image.
final class SupabaseImageStore: ImageStore {

    private let storageRoot: URL
    private let anonKey: String
    private let bucket: String
    private let auth: Auth
    private let session: URLSession

    init(baseUrl: String, anonKey: String, bucket: String, auth: Auth) {
        var root = baseUrl
        while root.hasSuffix("/") { root.removeLast() }
        guard let url = URL(string: root + "/storage/v1") else {
            preconditionFailure("Bad Supabase URL: \(baseUrl)")
        }
        self.storageRoot = url
        self.anonKey = anonKey
        self.bucket = bucket
        self.auth = auth

        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        // A 15 MB photo on a slow uplink can legitimately take a while.
        config.timeoutIntervalForResource = 180
        config.waitsForConnectivity = false
        self.session = URLSession(configuration: config)
    }

    func upload(path: String, bytes: Data, contentType: String, onProgress: @escaping (Float) -> Void) async throws -> String {
        let token = try await idToken()
        var request = authed(token, url: objectUrl("object", path))
        request.httpMethod = "POST"
        // Overwrite rather than 409: a retried upload must reuse its object.
        request.setValue("true", forHTTPHeaderField: "x-upsert")
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let progress = ProgressDelegate(onProgress: onProgress)
        let (data, response) = try await session.upload(for: request, from: bytes, delegate: progress)
        try requireSuccess(response, data, what: "upload")
        onProgress(1)

        return try await signedUrl(token: token, path: path)
    }

    func download(path: String, maxBytes: Int64) async throws -> Data {
        let request = authed(try await idToken(), url: objectUrl("object/authenticated", path))
        let (data, response) = try await session.data(for: request)
        try requireSuccess(response, data, what: "download")
        if let http = response as? HTTPURLResponse, http.expectedContentLength > maxBytes {
            throw ImageStoreError(.tooLarge)
        }
        if Int64(data.count) > maxBytes { throw ImageStoreError(.tooLarge) }
        return data
    }

    func delete(path: String) async throws {
        var request = authed(try await idToken(), url: objectUrl("object", path))
        request.httpMethod = "DELETE"
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ImageStoreError(.other, "No response") }
        if (200..<300).contains(http.statusCode) { return }
        let (status, detail) = errorStatus(http, data)
        if status != 404 { throw storeError("delete", status, detail) }
    }

    // ---------------------------------------------------------------------------------

    private func signedUrl(token: String, path: String) async throws -> String {
        var request = authed(token, url: objectUrl("object/sign", path))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["expiresIn": Self.signedUrlTtlSeconds])

        let (data, response) = try await session.data(for: request)
        try requireSuccess(response, data, what: "sign")
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard let relative = json?["signedURL"] as? String, !relative.isEmpty else {
            throw ImageStoreError(.other, "No signedURL in response")
        }
        // The API returns a path relative to /storage/v1, e.g. "/object/sign/bucket/…?token=…".
        var root = storageRoot.absoluteString
        while root.hasSuffix("/") { root.removeLast() }
        var rel = relative
        while rel.hasPrefix("/") { rel.removeFirst() }
        return root + "/" + rel
    }

    private func idToken() async throws -> String {
        guard let user = auth.currentUser else { throw ImageStoreError(.notAuthenticated) }
        let token = try await user.getIDToken()
        if token.isEmpty { throw ImageStoreError(.notAuthenticated) }
        return token
    }

    private func authed(_ token: String, url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func objectUrl(_ operation: String, _ path: String) -> URL {
        var url = storageRoot
        for segment in operation.split(separator: "/") { url.appendPathComponent(String(segment)) }
        url.appendPathComponent(bucket)
        var trimmed = path
        while trimmed.hasPrefix("/") { trimmed.removeFirst() }
        for segment in trimmed.split(separator: "/") { url.appendPathComponent(String(segment)) }
        return url
    }

    private func requireSuccess(_ response: URLResponse, _ data: Data, what: String) throws {
        guard let http = response as? HTTPURLResponse else { throw ImageStoreError(.other, "No response") }
        if (200..<300).contains(http.statusCode) { return }
        let (status, detail) = errorStatus(http, data)
        throw storeError(what, status, detail)
    }

    /// Storage reports most failures as HTTP 400 with the real status in the JSON body
    /// (`{"statusCode":"403",...}`), so that field wins over the transport code.
    private func errorStatus(_ http: HTTPURLResponse, _ data: Data) -> (Int, String?) {
        let raw = String(data: data, encoding: .utf8)
        var embedded: Int? = nil
        if let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            if let s = json["statusCode"] as? String { embedded = Int(s) }
            else if let n = json["statusCode"] as? NSNumber { embedded = n.intValue }
        }
        return (embedded ?? http.statusCode, raw.map { String($0.prefix(300)) })
    }

    private func storeError(_ what: String, _ status: Int, _ detail: String?) -> ImageStoreError {
        let kind: ImageStoreError.Kind
        switch status {
        case 401: kind = .notAuthenticated
        case 403: kind = .notAuthorized
        case 404: kind = .notFound
        case 413: kind = .tooLarge
        // The bucket's file_size_limit trips as a 400 whose message says "exceeded".
        case 400: kind = (detail?.lowercased().contains("exceeded") == true) ? .tooLarge : .other
        case 507: kind = .quota
        default: kind = .other
        }
        return ImageStoreError(kind, "Supabase \(what) failed: HTTP \(status) \(detail ?? "")")
    }

    /// Ten years. The URL is stored on the photo document, so it has to outlive the photo.
    private static let signedUrlTtlSeconds = 10 * 365 * 24 * 60 * 60

    /// Reports bytes written to the wire as a 0..1 fraction.
    private final class ProgressDelegate: NSObject, URLSessionTaskDelegate {
        let onProgress: (Float) -> Void
        init(onProgress: @escaping (Float) -> Void) { self.onProgress = onProgress }

        func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                        totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
            guard totalBytesExpectedToSend > 0 else { return }
            onProgress(min(1, max(0, Float(totalBytesSent) / Float(totalBytesExpectedToSend))))
        }
    }
}
