# CI

Three jobs run on pushes and pull requests to `main`:

- **Android** — assembles the debug APK, runs the 26 JVM unit tests, and runs Android
  Lint. The APK and all reports are uploaded as artifacts.
- **Firestore security rules** — boots the Firestore emulator and runs the 40 rules
  tests in `rules-tests/`.
- **iOS** — builds the SwiftUI app in `ios/` for the simulator on a macOS runner
  (only when something under `ios/` changes). It uses the committed config templates,
  so it proves the project compiles on a clean machine, not that it can sign in.

The rules job matters more than it looks. Firestore evaluates each write in a batch
against the state *before* the batch, so rules that read correctly can still deny
group creation and joining outright. That class of bug is invisible without the
emulator, which is why it gates every change.
