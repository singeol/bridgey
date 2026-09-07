# Bridgey v0.5.0-alpha.12

This patch preview fixes repeated Android Share launches and improves the
documentation experience on phones and tablets.

## Changes since alpha.11

- Reuse the existing Bridgey task when files, photos, or text are shared from
  another Android application instead of accumulating multiple Bridgey windows
  in Recents.
- Deliver every subsequent Share request to the active `MainActivity` so the
  newest file or text still reaches the confirmation dialog when Bridgey is
  already open or running in the background.
- Explicitly reject Android document-task launch flags for Bridgey's main
  activity and add a regression test for the required manifest configuration.
- Replace the documentation grid with a stable single-column layout on phones
  and tablets.
- Improve mobile documentation typography, spacing, cards, permission rows,
  buttons, and wrapping of long filenames, code, and reference links.
- Verify that the documentation has no horizontal overflow at a 390 px mobile
  viewport.

## Test focus

- Share several photos or files to Bridgey one after another and confirm that
  Android Recents contains only one Bridgey window.
- Repeat while Bridgey is foregrounded, backgrounded, and absent from Recents.
- Confirm that every Share request opens the correct confirmation dialog and
  transfers the selected item after approval.
- Check [the documentation](https://bridgey.ai/docs/) on a narrow phone screen,
  including Files, Permissions, Build from source, and the final download card.
- Smoke-test reconnect, clipboard, regular file selection, notifications,
  Find Device, battery status, and call controls.

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
