# WeWere

**One shared camera roll for the people you were with.**

Start a roll for the trip, the wedding, the flat. Everyone in it shoots straight from
the app or adds from their gallery, and everyone else sees the photo within seconds —
so the week afterwards, nobody has to chase eight people for the good ones.

- **Website & download:** https://wewere.vercel.app
- **Platform:** native Android (7.0+) — Kotlin, Jetpack Compose, Material 3, CameraX
- **Backend:** Firebase Auth + Firestore (free Spark plan) and Supabase Storage for
  the image bytes (free tier, no card) — see [Setup](#setup)
- **Look:** black and gold. Cormorant Garamond for titles, Manrope for body,
  JetBrains Mono for camera-style readouts. Film sprockets, gold hairlines, photos
  that develop from blur.

*(The package ID `com.rollapp.shared` and the internal `Roll*` class names are from the
app's earlier name; only what the user sees says WeWere.)*

## Quick start

```bash
git clone https://github.com/sidhy4rth/WeWere.git
cd WeWere
cp app/google-services.json.example app/google-services.json   # compiles; talks to nothing
cp local.properties.example local.properties                   # then set sdk.dir
./gradlew assembleDebug
```

That gets you a building project. To make it *work* — real accounts, real photos, real
sync — follow [Setup](#setup) and point it at your own Firebase and Supabase projects.
Nothing in this repository contains credentials: `google-services.json`,
`local.properties` and the release keystore are git-ignored on purpose.

---

## Status

| Check | Result |
|---|---|
| `./gradlew assembleDebug` / `assembleRelease` | pass — release APK is 26 MB, signed, minified |
| `./gradlew testDebugUnitTest` | **26 passed**, 0 failed |
| `./gradlew lintDebug` | **0 errors** |
| Firestore rules suite (emulator) | **46 passed**, 0 failed |
| On a phone | sign-in, create roll, upload, view, star — verified on a OnePlus (Android 16) against the live backend |

The four automated checks run in CI on every push — see `.github/workflows/android.yml`.

---

## Setup

You need: Android Studio (Ladybug or newer), a Google account, ~15 minutes.

### 1. Create the Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com) →
   **Add project**. Name it whatever you like. Google Analytics is optional.
2. In the project, click the **Android** icon to add an app.
3. **Package name — must be exactly:**

   ```
   com.rollapp.shared
   ```

   If you want a different one, change `namespace` and `applicationId` in
   `app/build.gradle.kts` first, and use that value here instead.

4. Add your debug signing certificate's SHA-1 — **this is required for Google
   Sign-In** and is the single most common reason it silently fails. Get it with:

   ```bash
   ./gradlew signingReport
   ```

   Copy the `SHA1` under `Variant: debug` and paste it into the Firebase console.

5. Download `google-services.json` and put it at `app/google-services.json`,
   replacing the copy you made from `app/google-services.json.example`.

   The real file is **git-ignored on purpose** — it names your Firebase project, and
   committing it invites strangers to point their builds at your quota. Only the
   `.example` template is tracked; it is a syntactically valid stub that compiles and
   reaches nothing.

### 2. Turn on Authentication

**Authentication → Get started → Sign-in method**, and enable:

- **Email/Password**
- **Google** — then expand **Web SDK configuration** and copy the **Web client ID**
- **Anonymous** (for the "continue as guest" path)

Put the Web client ID in `local.properties` (git-ignored — copy it from
`local.properties.example` if you have not already):

```properties
WEB_CLIENT_ID=123456789012-xxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
```

Without this, Google Sign-In shows a clear error rather than failing silently;
email/password and guest sign-in still work.

### 3. Create Firestore

**Firestore Database → Create database →** start in **production mode**, pick a region
near your users (`asia-south1` for India). Production mode denies everything until you
push the rules in step 5 — that is intentional.

### 4. Create the image bucket (Supabase)

Firebase's own Cloud Storage has required the paid Blaze plan for new projects since
October 2024, so image bytes go to a **Supabase Storage** bucket instead — 1 GB free,
no card needed. Auth and Firestore stay on Firebase; the app sends its Firebase ID
token to Supabase and Supabase verifies it directly.

1. [supabase.com](https://supabase.com) → **New project** (any name, any region — pick
   one near your users). Wait for it to finish provisioning.
2. **Authentication → Sign In / Providers → Third-party auth → Add provider →
   Firebase**, and enter your Firebase **project ID** (Firebase console → Project
   settings → General). This is what lets Supabase trust WeWere's sign-in.
3. **SQL Editor → New query**, paste the whole of `supabase/storage-policies.sql`,
   change `roll-3a292` in it to your Firebase project ID, **Run**. This creates the
   private `roll` bucket and its access policies. Read the comment at the top of that
   file before editing anything else in it.
4. **Project Settings → API**: copy the **Project URL** and the **anon public** key
   into `local.properties`:

   ```properties
   SUPABASE_URL=https://xxxxxxxxxxxxxxxxxxxx.supabase.co
   SUPABASE_ANON_KEY=eyJ...
   ```

   The anon key is public by design — it identifies the project, nothing more. Access
   is gated by the user's Firebase token and the bucket policies.

### 5. Deploy the Firestore rules and indexes

```bash
npm install -g firebase-tools
firebase login
firebase use --add          # pick the project you just made
firebase deploy --only firestore:rules,firestore:indexes
```

**Do not skip this.** Until the rules are deployed the app cannot read or write
anything, and every screen will show a permission error.

### 6. Cloud Messaging

Nothing to configure — FCM is enabled with the project. Notifications need the Cloud
Functions in step 7; without them the app works fully but stays silent.

### 7. Cloud Functions (optional, needs the Blaze plan)

```bash
cd functions && npm install && cd ..
firebase deploy --only functions
```

This adds push notifications. Everything else in WeWere runs on the **free Spark plan**
(plus Supabase's free tier); if you skip this step the only thing you lose is
notifications.

### 8. Build and run

```bash
./gradlew assembleDebug
./gradlew installDebug     # with a device connected
```

Or just open the folder in Android Studio and press Run.

---

## Tests

```bash
./gradlew testDebugUnitTest          # 26 tests: timeline sectioning, invite codes, time formatting
cd rules-tests && npm install && npm test   # 46 tests: every rule, against the real emulator
```

The rules suite is the one worth keeping. It drives the **batched** commits the app
actually performs — creating a group and joining one both write four or five
documents in a single commit — because Firestore evaluates each write in a batch
against the state *before* the batch. Rules written with `exists()` look correct and
deny both flows outright; the suite caught exactly that, and `existsAfter()` /
`getAfter()` are why they now pass. Reverting those two helpers turns 46 green into
38 green and 2 red.

The unit tests cover the ordering trap in `BuildTimelineUseCase` (a photo uploaded
today but taken last week must not emit a second, out-of-order date header) and the
invite-code alphabet.

---

## Testing the thing that actually matters

The real requirement is multi-device realtime sync, and it needs two devices (or one
device plus an emulator) signed in as **different** accounts:

1. **Phone A** — sign in, create a group, share the invite code.
2. **Phone B** — sign in as someone else, Join group, enter the code.
3. **Phone A** — take a photo.
4. **Phone B** — with the group open, the photo appears within a second or two. No
   pull-to-refresh, no reopening.
5. **Phone B** — take one back. It shows up on A the same way.

Then test the part people forget: put Phone A in airplane mode, take four photos, and
watch the group screen say *4 photos waiting to upload*. Turn the network back on —
they upload and appear on B without you touching anything.

---

## Privacy: what these rules do and don't guarantee

Worth being precise about, because the honest answer has a caveat.

**Firestore is properly locked down.** Every read of a group, its photos, its members
and its activity requires a `groups/{groupId}/members/{yourUid}` document to exist.
That check runs on Google's servers and the client cannot influence it. A user who is
not in a group cannot read anything about it, cannot list it, and cannot find it.
Invite codes resolve through a separate `invites/{code}` collection that exposes only
a name and two counts, and listing that collection is denied outright — the code
itself is the secret.

**Image storage is a weaker story, for a platform reason.** Photo bytes live in a
Supabase Storage bucket, and its policies cannot query Firestore — exactly as Firebase
Storage rules could not. So the bucket cannot ask "is this user a member of this
group". What it *does* enforce (`supabase/storage-policies.sql`):

- Every read, upload and delete needs a valid **Firebase** ID token for this project.
  A request with only the public anon key gets nothing.
- Uploads may only land in `groups/{id}/{full|thumbs|covers}/…` or the caller's own
  `avatars/{uid}.jpg`; the bucket caps objects at 15 MB and image MIME types.
- Object names are 20-character Firestore auto-ids, clients are not granted listing,
  and the only place a path is ever published is inside the membership-gated
  Firestore document. Reaching another group's photo means already having been handed
  its exact path.

**The remaining caveat:** photo documents store *signed URLs* (ten-year expiry) so
Coil and the Android share sheet can fetch images without extra plumbing. Anyone
holding that full URL can fetch the image without signing in — the same trade Firebase
download URLs make. The URL only ever lives inside the membership-gated Firestore
document, so it does not leak on its own, but it is not equivalent to server-enforced
authorisation.

To close that gap: stop minting signed URLs in `SupabaseImageStore.upload`, store only
`storagePath` (already on every photo document), and load images through a Coil
fetcher that calls `ImageStore.download` so every fetch carries the user's token. It is
maybe 60 lines and costs you the simplest path to sharing.

**EXIF is stripped on upload.** `ImageProcessor` decodes, rotates and re-encodes every
image, and `Bitmap.compress` writes no metadata — so GPS coordinates, device model and
original timestamps never leave the phone. Capture time is read out beforehand and
stored in Firestore instead, where the group can see it and a downloaded file cannot.

---

## Architecture

```
ui/            Compose screens + ViewModels   (knows nothing about Firebase)
  └─ navigation/  routes, NavHost, bottom bar
domain/
  ├─ model/       plain Kotlin data classes
  ├─ repository/  interfaces — the seam
  └─ usecase/     timeline sectioning
data/
  ├─ remote/      Firestore snapshot mapping, invite codes
  ├─ repository/  the Firebase implementations
  ├─ local/       Room: the durable upload queue
  └─ upload/      image processing, WorkManager worker, scheduler
core/          Outcome, AppError, error mapping, time formatting
di/            Hilt modules
```

**Swapping the backend** means writing new classes against the five interfaces in
`domain/repository/` and changing the `@Binds` in `di/RepositoryModule.kt`. No UI file
imports anything from `com.google.firebase`.

A few decisions worth knowing about:

- **Errors** are translated out of Firebase's exception vocabulary into a sealed
  `AppError` at the repository boundary. The UI switches on those and never sees a raw
  exception, which is why every error state has a real sentence instead of a code.
- **Snapshot mapping is hand-written**, not `toObject<T>()`. `serverTimestamp()` reads
  back as null on the local echo of a write, which would sort a photo you just took to
  the bottom of the feed.
- **Pagination is a growing realtime window**, not cursor pages. A shared roll gains
  photos at the top constantly, and cursor pages would duplicate or skip rows every
  time a friend uploaded mid-scroll.
- **The upload queue lives in Room**, not memory. A photo is marked complete only once
  its Firestore document exists, so a crash between the bytes landing and the document
  being written retries safely instead of leaving an image nobody can see.
- **Timeline sections cut on capture time**, not upload time, and the list is re-sorted
  on that same stamp. Everyone empties their camera roll at the hotel that night;
  sectioning by upload would file a whole week under one heading.

---

## What's built

**Phase 1 — complete.** Google / email / guest auth, create group, invite codes and
links, join flow with preview, membership, CameraX capture, gallery multi-select,
compression and EXIF stripping, resumable upload queue, shared feed, full-screen carousel with
pinch/double-tap zoom and swipe-to-dismiss, uploader attribution, realtime sync,
profile, delete your own photo.

**Phase 2 — complete.** Reactions, captions, FCM notifications with coalescing,
offline upload queue with retry and cancel, admin controls (rename, cover, remove
member, regenerate/revoke invite, delete group), download to gallery, share sheet,
activity feed.

**Also built since:** QR joining (show a QR, scan a QR), starring and filtering by
person, multi-select with bulk save and delete, slideshow, the black-and-gold redesign,
the landing page with a direct APK download, and verified invite links.

**Not built.** Shared albums, trip detection, AI highlights, memory videos, comments,
expiring rolls.

## Permissions, and the ones deliberately absent

| Permission | Asked when |
|---|---|
| `CAMERA` | the first time you tap the camera, with the reason on screen first |
| `POST_NOTIFICATIONS` | after you are signed in and have a group, not at launch |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28 only) | the first time you save a photo to your gallery |
| `FOREGROUND_SERVICE`, `..._DATA_SYNC` | not user-facing; keeps an upload alive when you leave the app |

There is **no read-storage permission**. Every gallery entry point uses the system
Photo Picker, which hands over exactly the images you selected. Declaring
`READ_MEDIA_IMAGES` would give WeWere your entire library in order to read four photos.

## Website and releases

`site/` is the landing page — one HTML file plus the photos on it, hosted on Vercel at
https://wewere.vercel.app next to the APK, so the download button is a plain link.
`site/README.md` covers deploying, the invite-link page (`/join/CODE`) and the
`assetlinks.json` that lets Android open invite links directly in the app.

Release builds are signed from a keystore that is **not** in the repo. Generate your
own once (`local.properties.example` has the command), keep it and its passwords
safe, and add its SHA-1 to your Firebase Android app and its SHA-256 to
`site/.well-known/assetlinks.json`. Bump `versionCode` in `app/build.gradle.kts` for
every APK you publish.

## Known gaps

- **Download-URL caveat** — described in full above.
- **Counters are client-maintained.** `photoCount` and `memberCount` are incremented
  by the client under tight field-level rules that cap each change at ±1. A malicious
  member could still nudge a count; they cannot touch anything else. Moving these into
  a Cloud Function trigger is the robust fix.
- **Group deletion is not atomic.** Firestore has no recursive delete from a client, so
  `deleteGroupInternal` walks the subcollections in batches. Interrupt it and you can
  leave a partially deleted group. `firebase firestore:delete --recursive` or a
  function cleans it up.
- **Reaction counts on the grid** show a total, not a breakdown, to keep the tile read
  cheap.
- **No instrumented tests.** The JVM and rules suites above cover the logic and the
  authorisation surface. Nothing exercises Compose UI or a real device; the
  multi-device walkthrough above is still manual.
- **Invite codes exclude `O`, `I`, `S`, `0`, `1` and `5`**, so the brief's example
  code `GOA7X2` can never be generated — it contains an `O`. Examples throughout use
  `GA7X2M` instead. Dropping the confusable characters is worth more than matching
  the sample string, since codes get read off someone else's screen.
