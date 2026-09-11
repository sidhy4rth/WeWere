# Roll

**A shared camera roll for a group of friends.**

One group, one private collection. Anyone in it can shoot straight from the app or
add from their gallery, and everyone else sees the photo within seconds. Built for
trips, parties, weddings and the week afterwards when nobody wants to chase eight
people for the good photos.

Native Android — Kotlin, Jetpack Compose, Material 3, CameraX, Firebase.

```bash
git clone https://github.com/YOUR_USERNAME/roll.git
cd roll
cp app/google-services.json.example app/google-services.json   # compiles; talks to nothing
cp local.properties.example local.properties                   # then set sdk.dir
./gradlew assembleDebug
```

That gets you a building project. To make it *work* — real accounts, real photos, real
sync — follow [Setup](#setup) and swap in your own Firebase project.

---

## Status

Verified on this machine:

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | passes — `app/build/outputs/apk/debug/app-debug.apk`, 25 MB |
| `./gradlew testDebugUnitTest` | **26 passed**, 0 failed |
| `./gradlew lintDebug` | **0 errors**, 88 warnings (all "a newer version is available") |
| Firestore rules suite (emulator) | **40 passed**, 0 failed |

All four run in CI on every push — see `.github/workflows/android.yml`.

It has **not been run against a live Firebase project or on a physical device** —
that needs your own Firebase credentials, which is the setup below. Everything up to
that point is done.

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

### 4. Create Storage

**Storage → Get started**, same region. Note the bucket name.

### 5. Deploy the rules and indexes

```bash
npm install -g firebase-tools
firebase login
firebase use --add          # pick the project you just made
firebase deploy --only firestore:rules,firestore:indexes,storage
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

This adds push notifications, membership custom claims (see the privacy note below),
and server-side cleanup of deleted images.

Everything else in Roll runs on the **free Spark plan**. If you skip this step you
lose push notifications and stay on Tier 1 storage rules.

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
cd rules-tests && npm install && npm test   # 40 tests: every rule, against the real emulator
```

The rules suite is the one worth keeping. It drives the **batched** commits the app
actually performs — creating a group and joining one both write four or five
documents in a single commit — because Firestore evaluates each write in a batch
against the state *before* the batch. Rules written with `exists()` look correct and
deny both flows outright; the suite caught exactly that, and `existsAfter()` /
`getAfter()` are why they now pass. Reverting those two helpers turns 40 green into
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

**Storage is a weaker story, for a platform reason.** Cloud Storage security rules
cannot query Firestore. There is no `exists()` available to them, so they physically
cannot ask "is this user a member of this group". The rules ship in two tiers:

- **Tier 1 (active, works on Spark):** `allow read: if request.auth != null`. Object
  names are 20-character Firestore auto-ids, clients are not granted listing, and the
  only place a path is ever published is inside the membership-gated Firestore
  document. Reaching another group's photo means already having been handed its exact
  path.
- **Tier 2 (stronger, needs Blaze):** deploy `functions/` and the
  `syncMembershipClaims` function stamps each user's group list onto their auth token.
  Then uncomment the `inGroupClaim(groupId)` line in `storage.rules` and delete the
  Tier 1 line above it. Storage now enforces real membership.

**The remaining caveat, in either tier:** photo documents store Firebase *download
URLs*, which carry an access token in the query string. Anyone holding that full URL
can fetch the image without signing in — that is how download URLs work, and it is
what makes Coil caching and the Android share sheet straightforward. The URL only
ever lives inside the membership-gated Firestore document, so it does not leak on its
own, but it is not equivalent to server-enforced authorisation.

To close that gap: stop calling `getDownloadUrl()` in `UploadWorker`, store only
`storagePath` (already on every photo document), and load images through a Coil
fetcher backed by `StorageReference` so every fetch carries the user's credentials.
That makes the Storage rules the real gate. It is maybe 60 lines and costs you the
simplest path to sharing.

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
compression and EXIF stripping, Storage upload, shared feed, full-screen carousel with
pinch/double-tap zoom and swipe-to-dismiss, uploader attribution, realtime sync,
profile, delete your own photo.

**Phase 2 — complete.** Reactions, captions, FCM notifications with coalescing,
offline upload queue with retry and cancel, admin controls (rename, cover, remove
member, regenerate/revoke invite, delete group), download to gallery, share sheet,
activity feed.

**Phase 3 — not built.** Shared albums, trip detection, AI highlights, memory videos,
comments, expiring groups, QR joining.

## Permissions, and the ones deliberately absent

| Permission | Asked when |
|---|---|
| `CAMERA` | the first time you tap the camera, with the reason on screen first |
| `POST_NOTIFICATIONS` | after you are signed in and have a group, not at launch |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28 only) | the first time you save a photo to your gallery |
| `FOREGROUND_SERVICE`, `..._DATA_SYNC` | not user-facing; keeps an upload alive when you leave the app |

There is **no read-storage permission**. Every gallery entry point uses the system
Photo Picker, which hands over exactly the images you selected. Declaring
`READ_MEDIA_IMAGES` would give Roll your entire library in order to read four photos.

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
