import Foundation
import FirebaseAuth
import FirebaseFirestore

/// Single place where Firebase's error zoo becomes an `AppError`.
enum FirebaseErrorMapper {

    static func map(_ error: Error) -> AppError {
        // Repositories raise this to report a reason the SDK has no code for.
        if let raised = error as? AppErrorException { return raised.appError }

        if let store = error as? ImageStoreError {
            switch store.kind {
            case .notAuthenticated: return .notAuthenticated
            case .notAuthorized: return .permissionDenied
            case .notFound: return .photoNotFound
            case .tooLarge: return .validation("That image is too large")
            case .quota: return .storageQuotaExceeded
            case .other: return .uploadFailed
            }
        }

        if let url = error as? URLError {
            switch url.code {
            case .timedOut: return .timeout
            default: return .offline
            }
        }

        let ns = error as NSError

        if ns.domain == AuthErrorDomain, let code = AuthErrorCode(rawValue: ns.code) {
            switch code {
            case .networkError: return .offline
            case .weakPassword: return .weakPassword()
            case .emailAlreadyInUse, .credentialAlreadyInUse, .accountExistsWithDifferentCredential: return .emailInUse()
            case .invalidCredential, .wrongPassword, .invalidEmail, .userNotFound, .userDisabled,
                 .invalidUserToken, .userTokenExpired:
                return .invalidCredentials()
            default:
                return .unknown(ns.localizedDescription)
            }
        }

        if ns.domain == FirestoreErrorDomain, let code = FirestoreErrorCode.Code(rawValue: ns.code) {
            switch code {
            case .permissionDenied: return .permissionDenied
            case .notFound: return .groupNotFound
            case .unauthenticated: return .notAuthenticated
            case .unavailable: return .offline
            case .deadlineExceeded: return .timeout
            case .resourceExhausted: return .storageQuotaExceeded
            default: return .unknown(ns.localizedDescription)
            }
        }

        if ns.domain == NSURLErrorDomain {
            return ns.code == NSURLErrorTimedOut ? .timeout : .offline
        }
        if ns.domain == NSPOSIXErrorDomain {
            return .offline
        }

        return .unknown(ns.localizedDescription)
    }
}

/// Runs `block`, translating anything thrown into an `Outcome.failure`.
func firebaseCall<T>(_ block: () async throws -> T) async -> Outcome<T> {
    do {
        return .success(try await block())
    } catch {
        return .failure(FirebaseErrorMapper.map(error))
    }
}
