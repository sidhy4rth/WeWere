# WeWere for iPhone

The native iOS app: Swift, SwiftUI, AVFoundation, Firebase, Kingfisher. Same backend
and same design as the Android app one directory up — an account, a roll and an invite
code work on both.

```
WeWere/
  App/        entry point, the one place the backend is wired, navigation, root
  Core/       AppError, Outcome, constants, error mapping, time formatting, invite codes
  Domain/     models, repository protocols, timeline sectioning
  Data/       Firestore repositories, the image store, the upload queue and worker
  UI/         theme (colours, the three faces), components, one file per screen
  Resources/  fonts, asset catalog (icon, launch mark)
```

It mirrors the Android app layer for layer. Where the platforms differ:

| Android | iOS |
|---|---|
| Kotlin Flows | Combine publishers, same `combine` / `flatMapLatest` shapes |
| Hilt | one `AppContainer`, view models take it in `init` |
| Room upload queue | a JSON store rewritten atomically, same rows and states |
| WorkManager | one draining task with backoff, restarted by connectivity and foreground; a background processing task when iOS allows it |
| CameraX + ML Kit | AVFoundation for capture and QR detection |
| Photo Picker | `PhotosPicker` — no library permission, same 100-photo cap |
| Coil | Kingfisher, tuned to the same cache sizes |
| App Links | the `roll://` scheme only — Universal Links need an Apple developer team |
| FCM | nothing yet |

The image pipeline re-encodes through ImageIO with no metadata dictionary, so EXIF
(GPS, device, timestamps) is stripped exactly as on Android; capture time is read out
first and stored on the photo document.

The project file is generated from `project.yml` with XcodeGen and committed. The
Firebase and Supabase configuration is git-ignored; the `.example.plist` files next to
it are what CI compiles against.

## Distribution

There is no App Store listing yet. The build on the website is an unsigned `.ipa` that
people install with AltStore or Sideloadly and their own Apple ID — Apple only allows
tap-to-install links (TestFlight, the App Store) through a paid developer account.
Nothing in the code changes when that happens.

## Not yet verified on hardware

Everything was exercised in the iOS Simulator against the live backend except what the
simulator cannot do: the camera itself (capture, flash, zoom, tap to focus), QR
scanning, Google Sign-In's sheet, haptics, and background upload time.
