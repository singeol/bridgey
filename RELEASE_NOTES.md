# Bridgey v0.5.0-alpha.6

This sixth Bridgey v0.5 preview makes browser phone-number links open Bridgey
and fixes inconsistent macOS application naming during installation.

## Changes since alpha.5

- macOS Settings now has **Use Bridgey for phone links**. Activating it makes
  Bridgey the default handler for `tel:` URLs through the supported macOS API.
- Clicking a phone number in Google, a browser, or another Mac application can
  now show **Open Bridgey?** before the validated number is sent to Android.
- Registration is explicit: installing Bridgey alone does not silently replace
  the user's current phone-link handler.
- `tel:` input uses the same strict validation as clipboard and Services calls;
  USSD commands, letters, extensions, malformed escapes, and oversized numbers
  are rejected.
- macOS build, DMG, and ZIP contents are now consistently named `Bridgey.app`.
  This prevents a new `BridgeyMac.app` from sitting beside an older
  `Bridgey.app` and leaving the old build running.
- Added regression coverage for ordinary, formatted, encoded, and malicious
  `tel:` links.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- The browser controls the wording and placement of its external-application
  confirmation dialog.
- Direct calls require Android telephony hardware and explicit Phone permission.
- Bridgey does not read contacts, SMS history, or call logs.
- Both devices must be reachable on the same local network.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
