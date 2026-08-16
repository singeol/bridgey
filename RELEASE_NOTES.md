# Bridgey v0.4.1

Bridgey v0.4.1 is a security-hardening patch for v0.4.0. Install it on both
Android and macOS; functionality and protocol compatibility are unchanged.

## Security fixes

- Every Android notification action now uses an explicit destination component
  as well as an immutable `PendingIntent`, preventing another application from
  redirecting an action to an unintended Bridgey component.
- macOS trust records — paired device IDs, names, and pinned public identity
  keys — are now stored in the login Keychain instead of `UserDefaults`.
- Existing macOS trust records are migrated to Keychain on first launch and are
  removed from the old preference store only after the Keychain write succeeds.
- Regression coverage verifies successful migration and persistence without
  requiring users to pair their devices again.
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
- The macOS build is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
