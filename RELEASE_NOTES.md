# Bridgey v0.5.0-alpha.5

This fifth Bridgey v0.5 preview adds a permission-conscious way to start a
phone call from macOS through a paired Android phone.

## Changes since alpha.4

- Copy a phone number and click **Call** in the Bridgey menu, or press
  `Control-Option-P`, to send the request to Android.
- In macOS applications that support Services, select a number and choose
  **Services → Call with Bridgey** without first opening the menu-bar panel.
- Android uses safe confirmation mode by default: a call-request notification
  opens the number in the system dialer, where the user starts the call.
- Android Settings offers an optional **Start calls without confirmation**
  switch. Only this direct mode requests the Phone permission.
- Requests use the authenticated encrypted session, strict 3–15 digit number
  validation, replay protection, a three-second rate limit, per-device feature
  policy, delivery status, and a timeout instead of an indefinite Sending state.
- USSD commands, extensions, arbitrary dial strings, and direct emergency calls
  are rejected. Phone numbers are excluded from diagnostics.
- Older Bridgey builds treat the new capability as unavailable instead of
  disconnecting or showing a control that cannot work.
- Added Android and macOS normalization regression tests and an Android
  permission-manifest regression check.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- macOS Services availability depends on the source application; the clipboard
  button and global shortcut remain available everywhere.
- Direct calls require Android telephony hardware and explicit Phone permission.
- Bridgey does not read contacts, SMS history, or call logs.
- Full SMS history and arbitrary SMS composition remain excluded because they
  require restricted permissions and distribution-policy approval.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
