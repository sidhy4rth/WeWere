# WeWere for iPhone

The native iOS app: Swift, SwiftUI, AVFoundation, Firebase iOS SDK, Kingfisher.
Same backend and same design as the Android app one directory up — an account, a roll
and an invite code work on both.

## Build

Requirements: Xcode 26 (iOS 17 deployment target), nothing else. Packages resolve
through Swift Package Manager on first build.

```bash
cd ios
cp WeWere/Resources/GoogleService-Info.example.plist WeWere/Resources/GoogleService-Info.plist
cp WeWere/Resources/Config.example.plist WeWere/Resources/Config.plist
open WeWere.xcodeproj
```

The two copied files are git-ignored, like `google-services.json` and
`local.properties` on Android:

- `GoogleService-Info.plist` — from Firebase Console → Project settings → Your apps →
  the iOS app (bundle id `com.rollapp.shared`), or `firebase apps:sdkconfig IOS <app id>`.
  Google Sign-In needs the iOS OAuth client this file names; a build phase copies its
  `REVERSED_CLIENT_ID` into the app's URL schemes so the sign-in sheet can return.
- `Config.plist` — `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_BUCKET`, the same
  values as `local.properties` on Android.

The templates compile; they just talk to nothing. `WeWere.xcodeproj` is generated from
`project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`xcodegen
generate`) but is committed, so plain `xcodebuild` works without it.

From the command line:

```bash
# Simulator
xcodebuild -project WeWere.xcodeproj -scheme WeWere -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17' build

# Unsigned release build for sideloading (see below)
xcodebuild -project WeWere.xcodeproj -scheme WeWere -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY="" build
mkdir -p build/ipa/Payload
cp -R DerivedData/Build/Products/Release-iphoneos/WeWere.app build/ipa/Payload/
(cd build/ipa && zip -qr -X ../WeWere-<version>.ipa Payload)
```

## Layout

```
WeWere/
  App/        entry point, AppContainer (the one place Firebase is wired), navigation, root
  Core/       AppError, Outcome, constants, error mapping, time formatting, invite codes
  Domain/     models, repository protocols, timeline sectioning
  Data/       Firestore repositories, Supabase image store, the upload queue and worker
  UI/         theme (colours, the three faces), components, one file per screen
  Resources/  fonts, asset catalog (icon, launch mark), config plists
```

It mirrors the Android app layer for layer. Where the platforms differ:

| Android | iOS |
|---|---|
| Kotlin Flows | Combine publishers (`Stream<T>`), same `combine` / `flatMapLatest` shapes |
| Hilt | one `AppContainer` singleton, view models take it in `init` |
| Room upload queue | `UploadQueueStore` — a JSON file rewritten atomically, same rows and states |
| WorkManager | `UploadScheduler` — one draining task, backoff, restarted by connectivity and foreground; a `BGProcessingTask` when iOS allows it |
| CameraX + ML Kit | AVFoundation for capture and QR detection |
| Photo Picker | `PhotosPicker` — no library permission, same 100-photo cap |
| Coil | Kingfisher, tuned to the same cache sizes |
| App Links | the `roll://` scheme only — Universal Links need a paid team id |
| FCM | nothing yet; push needs an APNs key and a paid account either way |

The image pipeline re-encodes through ImageIO with no metadata dictionary, so EXIF
(GPS, device, timestamps) is stripped exactly as on Android; capture time is read out
first and stored on the photo document.

## Distribution

There is no App Store listing: Apple's developer program is a paid account, and
tap-to-install from a website (TestFlight, ad-hoc `itms-services://` links) needs it
too. The free path — the one every sideloaded iOS app uses — is an **unsigned `.ipa`**
that the person installs with [AltStore](https://altstore.io) or
[Sideloadly](https://sideloadly.io) and their own Apple ID. A free Apple ID's signature
lasts seven days; AltStore refreshes it in the background.

If a developer account ever appears: sign the same build with it, upload with
`xcodebuild -exportArchive` or Transporter, and TestFlight gives a public link that
installs with one tap. Nothing in the code needs to change; Universal Links for
`wewere.vercel.app/join/CODE` become possible then too (an `apple-app-site-association`
with the team id).

## Not verified on hardware

Everything above was exercised in the iOS Simulator against the live backend except the
parts the simulator cannot do: the camera itself (capture, flash, zoom, tap to focus —
the simulator has no camera and shows the "camera unavailable" fallback), QR scanning,
Google Sign-In's sheet, haptics, and background upload time. The code paths are
standard AVFoundation and the flows around them are tested; a run on a real iPhone is
the remaining step.
