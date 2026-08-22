# Bridgey v0.5.0-alpha.8

This eighth Bridgey v0.5 preview improves Samsung call-state handling and makes
browser phone links reliable during a cold Bridgey launch or reconnect.

## Changes since alpha.7

- Call state is derived from the actual Android `CallStyle` answer, decline,
  and hang-up intents instead of trusting an inconsistent OEM call-type value.
- Short terminal `ongoing` updates emitted by Samsung while a call is ending
  are settled before forwarding, preventing a false **Call in progress** banner.
- `tel:` URLs are now received by the early macOS application delegate, so a
  phone number is retained when the browser launches Bridgey from a stopped state.
- Browser and macOS Services call requests wait up to 30 seconds for the secure
  Android connection and feature negotiation instead of failing during startup.
- Pending call requests are bounded, expire safely, and are cleared when Bridgey
  or the Calls feature is turned off.
- Added regression coverage for Samsung call-state conflicts, terminal call
  updates, cold-launch URL queuing, and reconnect dispatch readiness.

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
