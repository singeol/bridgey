# Bridgey v0.5.0-alpha.3

This third Bridgey v0.5 preview improves mirrored notification identity and
connection recovery. Install alpha.3 on both Android and macOS to test the new
encrypted source-app icons and heartbeat protocol.

## Changes since alpha.2

- Android now renders the source application's icon as a small, bounded PNG and
  sends it inside the existing encrypted notification payload.
- macOS validates the PNG, stores it in a content-addressed cache, and presents
  it as a native notification attachment. Payloads from older clients remain
  compatible and simply omit the attachment.
- Ad-hoc macOS packages now carry an explicit stable designated requirement for
  `dev.bridgey.mac`. This gives Notification Center and Local Network privacy a
  stable application identity across unsigned preview updates.
- Both clients negotiate an encrypted-session heartbeat. Once negotiated, a
  dead connection is detected within about 30 seconds instead of leaving
  Android stuck on a stale `Connected` state.
- macOS now recognizes the system's Local Network policy-denied error, stops
  misleading reconnect attempts, explains the required permission, and offers
  a shortcut to the corresponding System Settings pane.
- Added unit coverage for notification icon validation and naming, heartbeat
  compatibility and timeout behavior, and Local Network error recognition.
- This release requests no new permissions.

## Notification icon behavior

macOS reserves the leading icon slot for Bridgey, the application that creates
the local notification. The originating Android application's icon is supplied
as the notification's native media attachment; expand the notification if the
current macOS presentation style does not show attachments in its compact view.

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
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
