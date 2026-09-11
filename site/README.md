# WeWere landing page

`index.html` is the whole site — one file, fonts from Google Fonts, no build step.

It is hosted from the public `releases` bucket of the Supabase project alongside the
APK, so the download button works from a plain link:

- Page: https://ggdsxygrtmosayegongb.supabase.co/storage/v1/object/public/releases/index.html
- APK:  https://ggdsxygrtmosayegongb.supabase.co/storage/v1/object/public/releases/WeWere.apk

To ship a new build: `./gradlew assembleRelease`, upload
`app/build/outputs/apk/release/app-release.apk` to the bucket as `WeWere.apk`
(overwrite), then update the version, size and SHA-256 in `index.html` and re-upload
it. Uploads go through the Supabase dashboard (Storage → releases); the bucket has no
client write policy on purpose.
