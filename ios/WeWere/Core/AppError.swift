import Foundation

/// Every failure the UI can encounter, already translated out of Firebase's error
/// vocabulary. The UI switches on these; it never sees a raw NSError.
enum AppError: Error, Equatable {
    case offline
    case timeout
    case notAuthenticated
    case permissionDenied
    case groupNotFound
    case invalidInviteCode
    case inviteExpired
    case alreadyMember
    case removedFromGroup
    case photoNotFound
    case storageQuotaExceeded
    case uploadFailed
    case cameraUnavailable
    case emailInUse(String = "That email is already registered")
    case weakPassword(String = "Password must be at least 6 characters")
    case invalidCredentials(String = "Wrong email or password")
    case validation(String)
    case unknown(String?)

    var message: String? {
        switch self {
        case .offline: return "You're offline"
        case .timeout: return "That took too long"
        case .notAuthenticated: return "You need to be signed in"
        case .permissionDenied: return "You don't have access to this"
        case .groupNotFound: return "That group no longer exists"
        case .invalidInviteCode: return "That invite code isn't valid"
        case .inviteExpired: return "That invite link has expired"
        case .alreadyMember: return "You're already in this group"
        case .removedFromGroup: return "You're no longer a member of this group"
        case .photoNotFound: return "That photo was deleted"
        case .storageQuotaExceeded: return "Storage is full"
        case .uploadFailed: return "Upload failed"
        case .cameraUnavailable: return "Camera unavailable"
        case .emailInUse(let m), .weakPassword(let m), .invalidCredentials(let m), .validation(let m): return m
        case .unknown(let m): return m
        }
    }

    /// Whether retrying the same call unchanged could plausibly succeed.
    var isRetryable: Bool {
        switch self {
        case .offline, .timeout, .uploadFailed, .unknown: return true
        default: return false
        }
    }
}

/// Escape hatch for domain failures discovered mid-transaction — an expired invite,
/// a group that vanished between two reads. Throwing keeps the happy path in
/// `firebaseCall` linear instead of threading Result through every step.
struct AppErrorException: Error {
    let appError: AppError
    init(_ appError: AppError) { self.appError = appError }
}

/// Result type for the repository layer. `Outcome<T>` on Android; here it is Swift's
/// own Result, specialised to an error we have already translated into something showable.
typealias Outcome<T> = Result<T, AppError>

extension Result where Failure == AppError {
    var dataOrNull: Success? { if case .success(let v) = self { return v }; return nil }
    var errorOrNull: AppError? { if case .failure(let e) = self { return e }; return nil }
    var isSuccess: Bool { if case .success = self { return true }; return false }
    /// Drops the payload; for "did it work" callers.
    var unit: Outcome<Void> { map { _ in () } }
}
