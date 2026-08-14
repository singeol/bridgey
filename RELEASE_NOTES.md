# Bridgey v0.2.0

Bridgey v0.2.0 adds persistent, privacy-focused controls for every major
feature on Android and macOS. Existing paired devices remain trusted when the
new version is installed over v0.1.2.

## New settings

- Enable or disable Clipboard, File transfer, Notification forwarding, Battery
  status, and Find Device globally.
- Override every feature independently for each trusted device.
- Change the device name advertised on the local network.
- Review paired devices and remove their trust records.
- Choose the directory used for received files on macOS.
- Start the macOS application automatically at login.
- Settings persist across application and device restarts.

## Behavior and reliability

- Disabled features are blocked for both outgoing commands and incoming side
  effects without disconnecting the secure session.
- Disabled quick actions are clearly shown in the Android and macOS interfaces.
- The Android foreground notification hides its clipboard action while
  clipboard sharing is disabled.
- Disabling Find Device stops an active alert and synchronizes the stopped state
  with the paired device.
- File transfers already accepted are allowed to finish when the Files setting
  changes, preventing stranded partial transfers.
- macOS now reports the actual selected destination after receiving a file.

## Included functionality

- Encrypted pairing and automatic reconnect over the local network.
- Clipboard sharing in both directions.
- Streaming file transfer with progress, speed, ETA, integrity verification,
  and per-file cancellation.
- Android notification forwarding and battery status on macOS.
- Find Device in both directions.
- Android notification actions and the macOS `Control–Option–C` clipboard
  shortcut.

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
- Linux support, notification actions, SMS, media control, and remote input are
  planned for later versions.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
