# Bridgey v0.1.2

Bridgey v0.1.2 makes platform permissions smaller, clearer, and explicitly
controlled by the user. No account, cloud service, or internet connection is
required for the core features.

## Privacy and permissions

- Removed unused Android network-state, Wi-Fi control, and vibration
  permissions.
- Bridgey no longer requests Android or macOS notification permission
  automatically at startup.
- Added separate, contextual controls for Bridgey system notifications and the
  optional Android notification-forwarding access.
- Added plain-language explanations before opening sensitive system permission
  dialogs.
- Added the required macOS local-network usage description and Bonjour service
  declaration.
- Documented every requested capability and added a test that prevents unused
  Android permissions from returning unnoticed.

## Included

- Automatic discovery of Android and macOS devices on the local network.
- Explicit encrypted pairing with matching six-digit verification codes.
- Automatic reconnect for previously trusted devices.
- Text clipboard sharing in both directions.
- Streaming file transfer in both directions with progress, speed, ETA,
  integrity verification, and per-file cancellation.
- Android notifications forwarded to native macOS notifications.
- Android battery level and charging status displayed on macOS.
- Find Device: ring Android from macOS or ring the Mac from Android, with
  synchronized stopping.
- Native Android foreground actions and a macOS global clipboard shortcut
  (`Control–Option–C`).

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## macOS installation note

This release is ad-hoc signed because the project does not yet have a
paid Apple Developer ID certificate. macOS may block the first launch.

1. Copy Bridgey to Applications.
2. Control-click Bridgey and choose **Open**.
3. If macOS still blocks it, open **System Settings → Privacy & Security** and
   choose **Open Anyway** for Bridgey.

The source code and reproducible GitHub Actions build are public. Developer ID
signing and notarization can be added to a future release without changing the
application protocol.

## Known limitations

- Both devices must currently be reachable on the same local network.
- Android background clipboard restrictions require an explicit user action to
  send the phone clipboard.
- The macOS build is not notarized in v0.1.2.
- Linux support, notification actions, SMS, media control, and remote input are
  planned for later versions.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
