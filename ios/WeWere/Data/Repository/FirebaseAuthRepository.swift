import Foundation
import Combine
import FirebaseAuth
import FirebaseFirestore

final class FirebaseAuthRepository: AuthRepository {

    private let auth: Auth
    private let firestore: Firestore

    init(auth: Auth, firestore: Firestore) {
        self.auth = auth
        self.firestore = firestore
        currentUser = auth.userStream().map { $0.map(Self.toDomain) }.eraseToAnyPublisher()
    }

    let currentUser: Stream<User?>

    func currentUid() -> String? { auth.currentUser?.uid }

    func signInWithGoogle(idToken: String, accessToken: String) async -> Outcome<User> {
        await firebaseCall {
            let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
            let result = try await auth.signIn(with: credential)
            let profile = try await provisionProfile(result.user)
            // A Google account always has a name; anonymous upgrades may not.
            if profile.name.trimmingCharacters(in: .whitespaces).isEmpty {
                try await updateAuthDisplayName(result.user, "Someone")
            }
            return profile
        }
    }

    func signInWithEmail(email: String, password: String) async -> Outcome<User> {
        if let validation = validateEmailPassword(email, password) { return .failure(validation) }
        return await firebaseCall {
            let result = try await auth.signIn(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
            return try await provisionProfile(result.user)
        }
    }

    func signUpWithEmail(name: String, email: String, password: String) async -> Outcome<User> {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        if trimmedName.isEmpty { return .failure(.validation("What should we call you?")) }
        if let validation = validateEmailPassword(email, password) { return .failure(validation) }
        return await firebaseCall {
            let result = try await auth.createUser(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
            try await updateAuthDisplayName(result.user, trimmedName)
            return try await provisionProfile(result.user, overrideName: trimmedName)
        }
    }

    func signInAnonymously() async -> Outcome<User> {
        await firebaseCall {
            let result = try await auth.signInAnonymously()
            return try await provisionProfile(result.user, overrideName: "Guest")
        }
    }

    func linkAnonymousToEmail(name: String, email: String, password: String) async -> Outcome<User> {
        guard let current = auth.currentUser else { return .failure(.notAuthenticated) }
        if !current.isAnonymous { return .failure(.validation("This account is already permanent")) }
        if let validation = validateEmailPassword(email, password) { return .failure(validation) }
        let trimmedName = name.trimmingCharacters(in: .whitespaces)

        return await firebaseCall {
            let credential = EmailAuthProvider.credential(withEmail: email.trimmingCharacters(in: .whitespaces), password: password)
            let result = try await current.link(with: credential)
            let user = result.user
            try await updateAuthDisplayName(user, trimmedName)
            // The uid is preserved by linking, so every existing membership survives.
            return try await provisionProfile(user, overrideName: trimmedName, markPermanent: true)
        }
    }

    func linkAnonymousToGoogle(idToken: String, accessToken: String) async -> Outcome<User> {
        guard let current = auth.currentUser else { return .failure(.notAuthenticated) }
        if !current.isAnonymous { return .failure(.validation("This account is already permanent")) }
        return await firebaseCall {
            let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: accessToken)
            let result = try await current.link(with: credential)
            return try await provisionProfile(result.user, markPermanent: true)
        }
    }

    func sendPasswordReset(email: String) async -> Outcome<Void> {
        if !email.contains("@") { return .failure(.validation("Enter a valid email")) }
        return await firebaseCall {
            try await auth.sendPasswordReset(withEmail: email.trimmingCharacters(in: .whitespaces))
        }
    }

    func signOut() async -> Outcome<Void> {
        await firebaseCall { try auth.signOut() }
    }

    func deleteAccount() async -> Outcome<Void> {
        guard let user = auth.currentUser else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            // The profile doc goes first: once the auth user is gone the client can no
            // longer satisfy the `request.auth.uid == userId` rule that guards it.
            try await firestore.collection(FirestorePaths.users).document(user.uid).delete()
            try await user.delete()
        }
    }

    /// Makes sure the signed-in user has a profile document, repairing accounts whose
    /// first attempt never landed. Idempotent and safe to call on every launch.
    func ensureProfile() async -> Outcome<Void> {
        guard let user = auth.currentUser else { return .failure(.notAuthenticated) }
        return await firebaseCall {
            let doc = try await firestore.collection(FirestorePaths.users).document(user.uid).getDocument()
            if !doc.exists {
                _ = try await provisionProfile(user, overrideName: user.isAnonymous ? "Guest" : nil)
            }
        }
    }

    /// Runs the profile write in a detached task and waits for it.
    ///
    /// The caller is a ViewModel whose screen is about to go away: signing in flips
    /// the auth-state listener, which navigates away from the sign-in screen. Handing
    /// the work to a task that nobody cancels means a cancellation stops the
    /// *waiting*, not the write.
    private func provisionProfile(_ user: FirebaseAuth.User, overrideName: String? = nil,
                                  markPermanent: Bool = false) async throws -> User {
        let firestore = self.firestore
        return try await Task.detached(priority: .userInitiated) {
            try await Self.upsertProfile(firestore: firestore, user: user, overrideName: overrideName, markPermanent: markPermanent)
        }.value
    }

    /// Creates `users/{uid}` on first sign-in and refreshes the mutable fields on every
    /// later one. `merge` keeps fields the client does not own — notification prefs,
    /// device tokens — intact.
    private static func upsertProfile(firestore: Firestore, user: FirebaseAuth.User,
                                      overrideName: String?, markPermanent: Bool) async throws -> User {
        let doc = firestore.collection(FirestorePaths.users).document(user.uid)
        let name = overrideName ?? Self.fallbackName(for: user)

        var data: [String: Any] = [
            "name": name,
            "email": user.email ?? "",
            "photoUrl": user.photoURL?.absoluteString as Any,
            "isAnonymous": user.isAnonymous && !markPermanent,
            "updatedAt": FieldValue.serverTimestamp()
        ]

        let existing = try await doc.getDocument()
        if !existing.exists {
            data["createdAt"] = FieldValue.serverTimestamp()
        } else if existing.str("photoUrl") != nil && user.photoURL == nil {
            // Don't clear a photo the user uploaded themselves just because the
            // auth provider has none.
            data.removeValue(forKey: "photoUrl")
        }

        try await doc.setData(data, merge: true)

        return User(
            uid: user.uid,
            name: name,
            email: user.email ?? "",
            photoUrl: user.photoURL?.absoluteString ?? existing.str("photoUrl"),
            createdAt: Date.nowMillis,
            isAnonymous: user.isAnonymous && !markPermanent
        )
    }

    private func updateAuthDisplayName(_ user: FirebaseAuth.User, _ name: String) async throws {
        let change = user.createProfileChangeRequest()
        change.displayName = name
        try await change.commitChanges()
    }

    private func validateEmailPassword(_ email: String, _ password: String) -> AppError? {
        let trimmed = email.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || !trimmed.contains("@") { return .validation("Enter a valid email") }
        if password.count < 6 { return .weakPassword() }
        return nil
    }

    /// Display name, else the part of the email before the @, else "Guest".
    private static func fallbackName(for user: FirebaseAuth.User) -> String {
        if let display = user.displayName, !display.trimmingCharacters(in: .whitespaces).isEmpty { return display }
        if let email = user.email, let local = email.split(separator: "@").first { return String(local) }
        return "Guest"
    }

    private static func toDomain(_ user: FirebaseAuth.User) -> User {
        User(
            uid: user.uid,
            name: fallbackName(for: user),
            email: user.email ?? "",
            photoUrl: user.photoURL?.absoluteString,
            createdAt: user.metadata.creationDate?.millis ?? 0,
            isAnonymous: user.isAnonymous
        )
    }
}
