# WeWere landing page

Live at **https://wewere.vercel.app** (Vercel project `wewere`, team `wi-fi-bandits`).

`index.html` is the whole site — one file, fonts from Google Fonts, no build step.
The APK is served from the same folder, so the download button is a relative link.

## Deploy (Vercel)

1. `./gradlew assembleRelease`
2. Copy `app/build/outputs/apk/release/app-release.apk` here as `WeWere.apk`
3. Update the version, size and SHA-256 in `index.html`
4. `npx vercel --prod` from this folder (first time: `npx vercel login`)

`vercel.json` sets the APK's content type so phones treat it as an installable
package. The APK itself is git-ignored in this folder.

## The phone

The phone pinned on the right is the app drawn in HTML, running on `photos/1.jpg` …
`4.jpg`. At the top of the page it shows the roll's grid; as each section scrolls into
view a photo opens in the viewer (`.view` blocks in `index.html`, one per section).
Captions, the roll name and the starred counts are plain text there — edit in place.
Replace a photo by overwriting the file (JPEG, ~1300–1900 px wide, quality ~75) and
redeploying. They're real people — only use shots your friends are happy to have on
the open web.
