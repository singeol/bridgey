# Bridgey v0.5.0-alpha.9

This ninth Bridgey v0.5 preview fixes incoming-call classification on Samsung
devices across heads-up and full-screen call presentations.

## Changes since alpha.8

- Samsung incoming calls are now recognized by their system full-screen call
  intent when the dialer incorrectly reports them as already ongoing.
- Incoming calls remain classified correctly when they open full screen from
  the home screen or after tapping a heads-up call notification over another app.
- The normal ongoing-call state remains available after the incoming-call
  full-screen intent disappears.
- Added privacy-safe diagnostic logging for call-state signals without recording
  the caller number or notification contents.
- Added regression coverage for Samsung's conflicting `CallStyle` state.

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
