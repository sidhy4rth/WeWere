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

## Backdrop photos

`photos/1.jpg` … `4.jpg` sit behind the page under a dark wash and crossfade as you
scroll; each section's `data-bg` picks one. To swap a photo, replace the file (JPEG,
~1300–1900 px wide, quality ~75) and redeploy. They're real people — only use shots
your friends are happy to have on the open web.
