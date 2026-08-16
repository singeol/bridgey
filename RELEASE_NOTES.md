# Bridgey v0.5.0-alpha.4

This fourth Bridgey v0.5 preview adds notification controls that keep noisy or
sensitive applications out of the transport and an optional private history on
the Mac.

## Changes since alpha.3

- Android Settings now lists applications after Bridgey observes a notification
  from them and lets the user enable or disable forwarding per application.
- Disabling an application takes effect immediately for future notifications
  and removes that application's currently mirrored notifications from macOS
  without dismissing the originals on Android.
- macOS can optionally retain a private local notification history in Settings.
  History is off by default, limited to 200 items and seven days, and written
  with owner-only file permissions.
- Turning history off deletes the stored history; an explicit Clear history
  action is also available.
- Notification filters and history remain local settings and add no protocol
  metadata, network service, account, or permission.
- Added regression coverage for per-package mirrored-notification removal and
  history expiry, replacement, persistence, and size limits.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Android does not expose a universal cross-application "read" operation;
  Bridgey forwards only actions explicitly supplied by the source application.
- SMS and incoming-call integration remain research items because they require
  sensitive permissions and platform-policy validation.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
