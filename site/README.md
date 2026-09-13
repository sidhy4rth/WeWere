# WeWere landing page

Live at **https://wewere.vercel.app** (Vercel project `wewere`, team `wi-fi-bandits`).

`index.html` is the whole site — one file, fonts from Google Fonts, no build step.
The APK is served from the same folder, so the download button is a relative link.

## Deploy (Vercel)

1. `./gradlew assembleRelease`
2. Copy `app/build/outputs/apk/release/app-release.apk` here as `WeWere-<version>.apk` and point both download buttons in `index.html` at it. The name carries the version on purpose: a browser that resumes a stalled download of `WeWere.apk` would stitch the old release's first chunk onto the new one and produce a file that is the right size but will not install.
3. Update the version, size and SHA-256 in `index.html`
4. `npx vercel --prod` from this folder (first time: `npx vercel login`)

`vercel.json` sets the APK's content type so phones treat it as an installable
package. The APK itself is git-ignored in this folder.

## The prints

`photos/1.jpg` … `4.jpg` are the prints stacked on the right — ivory borders, captions,
date stamps, the same motif as the app's sign-in screen. The stack is pinned; each
section you scroll into deals the top print off the table, and scrolling back returns
it. On phones there is no stack: the same four prints sit in the page, one just before
each section, and rise in as they scroll into view. Each photo therefore appears twice
in `index.html` (stack + `print-inline`) — keep captions in sync. Replace a photo by
overwriting the file (JPEG, ~1300–1900 px wide, quality ~75) and redeploying. They're
real people — only use shots your friends are happy to have on the open web.

## Invite links

Invites are `https://wewere.vercel.app/join/CODE`. `vercel.json` rewrites that path
to `join.html`, which shows the code and an "Open in WeWere" button (`roll://join/CODE`).
`.well-known/assetlinks.json` carries the app's release and debug certificate
fingerprints so Android opens those links directly in the app once it's installed.
If the signing key ever changes, regenerate it:
`apksigner verify --print-certs app-release.apk` → SHA-256.
