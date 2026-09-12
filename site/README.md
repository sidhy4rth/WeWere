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

## The prints

`photos/1.jpg` … `4.jpg` are the prints stacked on the right — ivory borders, captions,
date stamps, the same motif as the app's sign-in screen. The stack is pinned; each
section you scroll into deals the top print off the table, and scrolling back returns
it. Order and captions are the `<figure class="print">` blocks in `index.html` (they
appear twice: once for the pinned stack, once for the swipeable row on phones — keep
both in sync). Replace a photo by overwriting the file (JPEG, ~1300–1900 px wide,
quality ~75) and redeploying. They're real people — only use shots your friends are
happy to have on the open web.
