# Bridgey v0.5.0-alpha.11

This Bridgey preview introduces a unified visual identity for Android, macOS,
and the web, and publishes the project's first public website and documentation.

## Changes since alpha.10

- Replaced the Android and macOS application icons with the new Bridgey `B`
  mark in a shared navy, cyan, and blue visual style.
- Added matching Android adaptive and monochrome icons so the mark remains
  recognizable with themed launcher icons and in system notifications.
- Added the Bridgey website and documentation at
  [bridgey.ai](https://bridgey.ai/), deployed automatically with GitHub Pages.
- Added search metadata, canonical URLs, Open Graph and Twitter cards,
  structured data, `robots.txt`, and a sitemap for search-engine indexing.
- Expanded the public roadmap from v0.6 through v1.0, including media controls,
  remote input, presentation controls, Files 2.0, multiple devices, bounded
  automation, experimental screen sharing, and the stable-release criteria.
- Documented additional post-1.0 ideas that extend beyond KDE Connect while
  retaining Bridgey's local-first and permission-minimizing design.

## Test focus

- Confirm the new `B` icon on the Android launcher, Android notifications,
  the macOS app, menu-bar notifications, and the mounted DMG.
- Upgrade over alpha.10 and confirm that pairing and trusted-device settings
  are retained.
- Smoke-test reconnect, clipboard sharing, file transfer, notification actions,
  Find Device, battery status, and call controls in both directions.
- Open the website and documentation on desktop and mobile browsers.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Full call status and controls require explicit Android Phone permissions;
  confirmation mode remains available without that opt-in.
- Bridgey does not read contacts, SMS history, or call logs.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.
- A newly configured custom domain can require DNS propagation and certificate
  issuance before GitHub Pages can enforce HTTPS.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
