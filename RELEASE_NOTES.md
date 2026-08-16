# Bridgey v0.4.3

Bridgey v0.4.3 fixes Android diagnostic report sharing. Install this update on
Android to send diagnostics to a connected Mac through Bridgey's file-transfer
channel. Protocol compatibility with v0.4.x is unchanged.

## Fixes and improvements

- Android now exports diagnostics as a real `Bridgey-Diagnostics.json` file
  instead of placing the complete report in `EXTRA_TEXT`.
- Choosing Bridgey in the Android share sheet now treats the diagnostic report
  as a file, so it is not rejected by the 32 KiB clipboard limit.
- Diagnostic files are shared from a private cache directory through a
  non-exported `FileProvider` with temporary read access only.
- The Settings action is now labelled **Share diagnostics file** to make the
  behavior explicit.
- This release requests no new permissions.

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
- Clipboard payloads are limited to 32 KiB; larger text and diagnostics should
  be sent as files.
- Rich clipboard support currently preserves text and HTML, not images or files.
- The macOS build is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
