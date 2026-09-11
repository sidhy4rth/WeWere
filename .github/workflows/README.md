# CI

Two jobs run on every push and pull request to `main`:

- **Android** — assembles the debug APK, runs the 26 JVM unit tests, and runs Android
  Lint. The APK and all reports are uploaded as artifacts.
- **Firestore security rules** — boots the Firestore emulator and runs the 40 rules
  tests in `rules-tests/`.

The rules job matters more than it looks. Firestore evaluates each write in a batch
against the state *before* the batch, so rules that read correctly can still deny
group creation and joining outright. That class of bug is invisible without the
emulator, which is why it gates every change.
