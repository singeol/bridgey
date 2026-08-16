# Bridgey v0.4.0

Bridgey v0.4.0 improves platform integration and the first-run experience.
Install this version on both Android and macOS to use the new clipboard format.

## Platform integration

- Files can now be dragged from Finder onto the connected-device card on macOS.
- Android's existing system share target sends selected text or files to a
  connected, trusted Mac.
- Clipboard transfer preserves HTML formatting when both platforms expose it,
  while always including a plain-text fallback.
- Clipboard content is limited to 32 KiB before encryption so oversized data is
  rejected locally instead of exceeding the transport frame limit.

## First run and accessibility

- Both apps now show a concise first-run guide for discovery, code verification,
  and optional permissions.
- Welcome guidance is available in English and Russian, establishing localized
  resource bundles for future interface translation.
- Quick actions and the macOS file drop area include clearer accessibility
  labels and hints.
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
- Rich clipboard support currently preserves text and HTML, not images or files.
- Drag-and-drop accepts one regular file at a time; folders are not accepted.
- The macOS build is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
