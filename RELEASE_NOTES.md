# Bridgey v0.5.0-alpha.1

This is the first preview of Bridgey v0.5. Install the alpha on both Android
and macOS to test synchronized notification state, action buttons, and inline
replies. Keep v0.4.3 available if you need the current stable release.

## Notification improvements

- Dismissing a mirrored notification on macOS now dismisses its active source
  notification on Android.
- Removing the original notification on Android removes its mirrored macOS
  notification.
- Up to four actions exposed by an Android notification can appear as native
  macOS notification actions.
- Android inline-reply actions become native macOS text-input actions and send
  the reply through the originating Android `PendingIntent`.
- Notification and action identifiers are opaque SHA-256 tokens. Android keeps
  the underlying system keys and `PendingIntent` objects local and accepts an
  action only while the source notification remains active.
- Commands are encrypted, scoped to the authenticated device session, bounded,
  and replay-protected.
- This release requests no new permissions.

## What to test

1. Receive a dismissible Android notification and dismiss it from macOS.
2. Receive another notification and dismiss it on Android.
3. Test a regular action such as **Mark as read** when an application exposes it.
4. Test **Reply** from a messaging notification and confirm that the response
   reaches the conversation.

Action availability varies by Android application. On macOS, notification
actions may appear only after expanding or hovering over the notification.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Per-application notification filters and private notification history are not
  included in this first alpha.
- Android does not expose a universal cross-application "read" operation;
  Bridgey forwards only actions explicitly supplied by the source application.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
