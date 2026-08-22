# Bridgey v0.5.0-alpha.10

This final Bridgey v0.5 preview completes reliable cellular call status and
controls between Android and macOS and adds a focused native call experience on
the Mac.

## Changes since alpha.9

- Android now uses the platform telephony state to distinguish ringing,
  active/outgoing, and ended calls when an OEM dialer publishes ambiguous call
  notifications.
- Added real Answer, Decline, and Hang Up controls through Android's protected
  telecom APIs. The optional call-integration switch requests only
  `CALL_PHONE`, `READ_PHONE_STATE`, and `ANSWER_PHONE_CALLS`; Bridgey still does
  not request contacts or call-log access.
- Incoming calls show Answer on the left in green and Decline on the right in
  red in a compact non-activating macOS panel. Active and outgoing calls show a
  full-width red Hang Up control.
- The call panel stays above normal windows and across Spaces without taking
  focus, sounds once for a new incoming call, can be hidden for the current
  call, and disappears automatically when the call ends.
- Suppressed late Samsung dialer updates after the phone returns to idle, which
  previously could briefly recreate an incorrect incoming-call card.
- Updated Gradle Wrapper to 9.7.1 and kotlinx-coroutines to 1.11.0 after clean
  Build, CodeQL, and dependency-review checks.
- Expanded call-state, action-ordering, permission, and OEM regression tests and
  updated the privacy and protocol documentation.

## Test focus

- Incoming call: Answer and Decline, transition to Call in progress, Hang Up,
  and automatic dismissal.
- Outgoing call from clipboard, macOS Services, and a browser `tel:` link.
- Notification buttons, inline reply, dismissal synchronization, application
  filters, and private local history.
- Reconnect after restarting either client, followed by clipboard, file,
  Find Device, and battery smoke tests.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Full call status and controls require explicit Android Phone permissions;
  confirmation mode remains available without that opt-in.
- Bridgey does not read contacts, SMS history, or call logs.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
