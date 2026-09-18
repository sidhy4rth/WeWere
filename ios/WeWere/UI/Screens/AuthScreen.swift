import SwiftUI
import Combine
import FirebaseCore
import GoogleSignIn

enum AuthMode { case signIn, signUp }

struct AuthUiState: Equatable {
    var mode: AuthMode = .signIn
    var name: String = ""
    var email: String = ""
    var password: String = ""
    var isSubmitting: Bool = false
    var error: AppError? = nil
    var passwordResetSent: Bool = false
    var signedIn: Bool = false

    var canSubmit: Bool {
        !isSubmitting && email.contains("@") && password.count >= 6 &&
            (mode == .signIn || !name.trimmingCharacters(in: .whitespaces).isEmpty)
    }
}

@MainActor
final class AuthViewModel: ObservableObject {
    @Published var state = AuthUiState()

    private let authRepository: AuthRepository

    init(container: AppContainer = .shared) {
        authRepository = container.authRepository
    }

    func setMode(_ mode: AuthMode) { state.mode = mode; state.error = nil }
    func onNameChange(_ value: String) { state.name = value; state.error = nil }
    func onEmailChange(_ value: String) { state.email = value; state.error = nil }
    func onPasswordChange(_ value: String) { state.password = value; state.error = nil }
    func dismissError() { state.error = nil }

    func submit() {
        let current = state
        guard current.canSubmit else { return }
        Task {
            state.isSubmitting = true; state.error = nil
            let outcome: Outcome<User>
            switch current.mode {
            case .signIn: outcome = await authRepository.signInWithEmail(email: current.email, password: current.password)
            case .signUp: outcome = await authRepository.signUpWithEmail(name: current.name, email: current.email, password: current.password)
            }
            apply(outcome.unit)
        }
    }

    func signInWithGoogle() {
        Task {
            state.isSubmitting = true; state.error = nil
            switch await GoogleSignInHelper.getTokens() {
            case .failure(let error):
                state.isSubmitting = false; state.error = error
            case .success(let tokens):
                apply(await authRepository.signInWithGoogle(idToken: tokens.idToken, accessToken: tokens.accessToken).unit)
            }
        }
    }

    func continueAsGuest() {
        Task {
            state.isSubmitting = true; state.error = nil
            apply(await authRepository.signInAnonymously().unit)
        }
    }

    func sendPasswordReset() {
        let email = state.email
        Task {
            state.isSubmitting = true; state.error = nil
            switch await authRepository.sendPasswordReset(email: email) {
            case .success: state.isSubmitting = false; state.passwordResetSent = true
            case .failure(let error): state.isSubmitting = false; state.error = error
            }
        }
    }

    private func apply(_ outcome: Outcome<Void>) {
        switch outcome {
        case .success: state.isSubmitting = false; state.signedIn = true
        case .failure(let error): state.isSubmitting = false; state.error = error
        }
    }
}

/// Google sign-in through the Google Sign-In SDK, presented over the current screen.
@MainActor
enum GoogleSignInHelper {
    static func getTokens() async -> Outcome<(idToken: String, accessToken: String)> {
        guard let clientID = FirebaseApp.app()?.options.clientID, !clientID.isEmpty else {
            return .failure(.validation("Google Sign-In isn't configured — add GoogleService-Info.plist"))
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        guard let presenter = UIApplication.topViewController() else {
            return .failure(.unknown("Nothing to present sign-in from"))
        }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else {
                return .failure(.unknown("Google sign-in returned no token"))
            }
            return .success((idToken, result.user.accessToken.tokenString))
        } catch {
            let ns = error as NSError
            if ns.domain == kGIDSignInErrorDomain && ns.code == GIDSignInError.Code.canceled.rawValue {
                return .failure(.validation("Sign-in cancelled"))
            }
            return .failure(.unknown(ns.localizedDescription))
        }
    }
}

struct AuthScreen: View {
    let onSignedIn: () -> Void

    @StateObject private var viewModel = AuthViewModel()
    @EnvironmentObject private var overlays: Overlays
    @State private var passwordVisible = false
    // Google is the front door; the email form only unfolds when asked for.
    @State private var emailFormOpen = false

    var body: some View {
        let state = viewModel.state
        GeometryReader { geo in
        ScrollView {
            VStack(spacing: 0) {
                if !emailFormOpen {
                    PrintStack()
                        .frame(height: 360)
                        .frame(maxWidth: .infinity)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    Spacer().frame(height: 72)
                }

                RiseIn(delay: 0.5) {
                    Text("WeWere").rollStyle(RollType.displayLarge, color: Gold)
                }
                Spacer().frame(height: 10)
                RiseIn(delay: 0.6) { HairlineRule().frame(width: 120) }
                Spacer().frame(height: 10)
                RiseIn(delay: 0.65) {
                    Text("One shared camera roll for the people you were with.")
                        .rollStyle(RollType.bodyMedium, color: IvoryMuted)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 56)
                }

                Spacer(minLength: 32)

                VStack(spacing: 12) {
                    if let error = state.error {
                        InlineError(message: error.message ?? "Something went wrong")
                    }

                    if emailFormOpen {
                        EmailForm(passwordVisible: $passwordVisible, viewModel: viewModel)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                        QuietButton(text: "Back to Google sign-in", enabled: !state.isSubmitting) {
                            withAnimation(.settle(duration: 0.4)) { emailFormOpen = false }
                        }
                    } else {
                        RiseIn(delay: 0.78) {
                            GoldButton(text: "Continue with Google", enabled: !state.isSubmitting, loading: state.isSubmitting) {
                                viewModel.signInWithGoogle()
                            }
                        }
                        RiseIn(delay: 0.86) {
                            HairlineButton(text: "Use email instead", enabled: !state.isSubmitting) {
                                withAnimation(.settle(duration: 0.4)) { emailFormOpen = true }
                            }
                        }
                        RiseIn(delay: 0.94) {
                            QuietButton(text: "Just looking — continue as guest", enabled: !state.isSubmitting) {
                                viewModel.continueAsGuest()
                            }
                        }
                    }
                }
                .padding(.horizontal, 24)

                Spacer().frame(height: 16)
            }
            .frame(minHeight: geo.size.height)
        }
        .scrollBounceBehavior(.basedOnSize)
        }
        .background(
            ZStack {
                Ink
                RadialGradient(colors: [Color(hex: 0x1A1712), Ink], center: .topLeading, startRadius: 0, endRadius: 500)
            }
            .ignoresSafeArea()
        )
        .onChange(of: state.signedIn) { _, signedIn in if signedIn { onSignedIn() } }
        .onChange(of: state.passwordResetSent) { _, sent in
            if sent { overlays.snack("Check your inbox for a reset link") }
        }
    }
}

private struct EmailForm: View {
    @Binding var passwordVisible: Bool
    @ObservedObject var viewModel: AuthViewModel

    var body: some View {
        let state = viewModel.state
        VStack(spacing: 12) {
            if state.mode == .signUp {
                RollTextField(label: "Your name",
                              text: Binding(get: { viewModel.state.name }, set: viewModel.onNameChange),
                              capitalization: .words, submitLabel: .next)
            }

            RollTextField(label: "Email",
                          text: Binding(get: { viewModel.state.email }, set: viewModel.onEmailChange),
                          keyboard: .emailAddress, capitalization: .never, submitLabel: .next)

            RollTextField(label: "Password",
                          text: Binding(get: { viewModel.state.password }, set: viewModel.onPasswordChange),
                          isSecure: !passwordVisible, capitalization: .never, submitLabel: .done,
                          onSubmit: { viewModel.submit() }) {
                IconButton(icon: passwordVisible ? RollIcon.visibilityOff : RollIcon.visibility, tint: Muted, size: 22,
                           label: passwordVisible ? "Hide password" : "Show password") {
                    passwordVisible.toggle()
                }
            }

            if state.mode == .signIn {
                HStack {
                    Spacer()
                    TextButton(text: "Forgot password?", color: Gold, style: RollType.bodySmall,
                               enabled: state.email.contains("@")) {
                        viewModel.sendPasswordReset()
                    }
                }
            }

            GoldButton(text: state.mode == .signIn ? "Sign in" : "Create account",
                       enabled: state.canSubmit, loading: state.isSubmitting) {
                viewModel.submit()
            }

            HStack(spacing: 0) {
                Text(state.mode == .signIn ? "New here?" : "Already have an account?")
                    .rollStyle(RollType.bodySmall, color: Muted)
                TextButton(text: state.mode == .signIn ? "Create an account" : "Sign in",
                           color: Gold, style: RollType.bodySmall) {
                    withAnimation(.easeOut(duration: 0.2)) {
                        viewModel.setMode(state.mode == .signIn ? .signUp : .signIn)
                    }
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}
