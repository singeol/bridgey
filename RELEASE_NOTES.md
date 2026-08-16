# Bridgey v0.4.2

Bridgey v0.4.2 is a usability patch for file drag-and-drop and oversized
clipboard content. Install it on both Android and macOS; protocol compatibility
with v0.4.x is unchanged.

## Fixes and improvements

- macOS file drag-and-drop now opens in a dedicated floating window that stays
  visible while switching to Finder.
- The drop window clearly reports when no Android device is connected or File
  transfer is disabled.
- Android and macOS now explain that clipboard content over 32 KiB cannot be
  sent as a clipboard message and should be transferred as a file instead.
- Added English and Russian text for the new macOS file-drop workflow.
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
