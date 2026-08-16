# Bridgey v0.5.0-alpha.2

This second Bridgey v0.5 preview fixes the generic icon shown on forwarded
macOS notifications. Install the alpha on both Android and macOS to continue
testing synchronized notification state, action buttons, and inline replies.

## Changes since alpha.1

- The macOS package now compiles the Bridgey app icon into `Assets.car`, which
  current macOS versions use in Notification Center and other system UI.
- `Bridgey.icns` remains bundled for compatibility with macOS 13–15.
- The bundle now declares both the modern `CFBundleIconName` and compatible
  `CFBundleIconFile` metadata.
- The build fails on full-Xcode runners if the modern icon catalog is not
  produced, while machines with Command Line Tools only retain the compatible
  ICNS fallback.
- This release requests no new permissions.

## Notification features under test

- Dismissing a mirrored notification on macOS dismisses its active source
  notification on Android.
- Removing the original notification on Android removes its mirrored macOS
  notification.
- Up to four actions exposed by an Android notification can appear as native
  macOS notification actions.
- Android inline-reply actions become native macOS text-input actions and send
  the reply through the originating Android `PendingIntent`.
- Notification and action identifiers are encrypted, scoped, bounded,
  replay-protected opaque tokens.

macOS always uses Bridgey's own bundle icon in the leading notification-icon
position. The public notification API does not allow Bridgey to replace it with
the source Android application's icon.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Per-application notification filters and private notification history are not
  included in this alpha.
- Android does not expose a universal cross-application "read" operation;
  Bridgey forwards only actions explicitly supplied by the source application.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
