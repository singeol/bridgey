# Bridgey v0.5.0-alpha.7

This seventh Bridgey v0.5 preview fixes mirrored incoming-call controls and
keeps the call state on macOS synchronized with Android.

## Changes since alpha.6

- Incoming Android calls now expose **Answer** and **Decline** on macOS even
  when the phone app keeps the required `CallStyle` actions outside the regular
  notification action list.
- Accepted calls transition to **Call in progress** with the appropriate
  **Hang Up** action instead of retaining a stale **Incoming call** label.
- Ending a call, disabling notification forwarding, disconnecting, or replacing
  the active call now removes its stale macOS notification and menu-bar card.
- Outbound call-request messages such as **Android rejected the call request**
  are cleared when an actual call arrives and otherwise expire automatically.
- Added Android and macOS regression coverage for required call actions and
  incoming-to-ongoing state changes.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Call controls depend on the safe `PendingIntent` actions exposed by the
  Android phone application; Bridgey does not request call-log access.
- Direct calls require Android telephony hardware and explicit Phone permission.
- Bridgey does not read contacts, SMS history, or call logs.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
