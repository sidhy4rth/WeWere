# WeWere landing page

Live at **https://wewere.vercel.app**.

`index.html` is the whole site — one file, fonts from Google Fonts, no build step. The
Android APK and the iPhone `.ipa` are served from the same folder (both git-ignored
here), so the download buttons are plain links; `vercel.json` sets their content types
so phones treat them as downloads. The version, size and SHA-256 on each install card
are updated by hand with every release, and the files carry the version in their name
so a browser can never stitch a stale partial download onto a new release.

## The prints

`photos/1.jpg` … `4.jpg` are the prints stacked on the right — ivory borders, captions,
date stamps, the same motif as the app's sign-in screen. The stack is pinned; each
section you scroll into deals the top print off the table, and scrolling back returns
it. On phones there is no stack: the same four prints sit in the page, one just before
each section, and rise in as they scroll into view. Each photo therefore appears twice
in `index.html` (stack + `print-inline`) — keep captions in sync. They're real people —
only use shots your friends are happy to have on the open web.

## Invite links

Invites are `https://wewere.vercel.app/join/CODE`. `vercel.json` rewrites that path to
`join.html`, which shows the code and an "Open in WeWere" button (`roll://join/CODE`,
which both apps register). `.well-known/assetlinks.json` carries the Android app's
certificate fingerprints so Android opens those links directly in the app once it's
installed.
