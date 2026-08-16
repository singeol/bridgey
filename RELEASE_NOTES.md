# Bridgey v0.2.1

Bridgey v0.2.1 is a reliability and integration release. It keeps the existing
trusted-device data from v0.2.0 and is intended to be installed on both Android
and macOS so that feature availability stays synchronized.

## Highlights

- Share text, one file, or several files directly to Bridgey from Android's
  system Share sheet.
- Android and macOS now exchange their effective feature settings over the
  encrypted session. Unavailable controls disappear or become disabled on the
  other device, including battery status.
- A disabled remote action is rejected explicitly instead of leaving the Mac
  stuck on `Sending…` or a file transfer waiting indefinitely.
- Clipboard and file-transfer UI state is cleared after remote rejection,
  timeout, cancellation, or disconnect.

## Quality and security

- Added shared Android–macOS protocol tests with deterministic identities and
  encrypted cross-platform test vectors.
- Added Android lint and unit tests, macOS tests, CodeQL, dependency review,
  Dependabot, and status badges.
- Refreshed GitHub Actions and audited build dependencies.
- Migrated Android to AGP 9.3.1 with built-in Kotlin and Gradle 9.7.
- No new runtime permissions are requested by this release.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## macOS installation note

The macOS build remains ad-hoc signed because the project does not yet have a
paid Apple Developer ID certificate. Copy Bridgey to Applications, then
Control-click it and choose **Open**. If macOS still blocks it, use **System
Settings → Privacy & Security → Open Anyway**.

## Known limitations

- Both devices must be reachable on the same local network.
- Android requires an explicit user action to read and send its clipboard.
- Android received files remain in `Download/Bridgey`; macOS received files use
  the directory selected in Settings.
- The macOS build is not notarized.
- Linux support, notification replies, SMS, calls, media control, remote input,
  and screen sharing are planned for later versions.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
