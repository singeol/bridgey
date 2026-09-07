# Bridgey v0.5.0

Bridgey 0.5 is the first complete notifications and communication milestone for
the local-first Android and macOS companion. It promotes the tested 0.5 alpha
series to a regular release and includes the final mobile and Android Share
fixes from alpha.12.

## Highlights

- Mirror Android notifications as native macOS notifications over the encrypted
  local connection.
- Use exposed notification action buttons and inline replies from the Mac.
- Synchronize notification dismissal, filter forwarding per Android app, and
  review a private local notification history.
- Start a validated cellular call from selected macOS text, the clipboard, the
  Bridgey panel, or a browser `tel:` link.
- See live incoming, outgoing, and active call state on macOS with explicit
  Answer, Decline, and Hang Up controls when optional Phone access is enabled.
- Share text, photos, and files through Android's Share menu without accumulating
  multiple Bridgey windows in Recents.
- Transfer files in either direction with progress, speed, ETA, verification,
  per-transfer notifications, and synchronized cancellation.
- Synchronize the clipboard, battery state, feature availability, and Find
  Device controls between trusted devices.

## Reliability, privacy, and experience

- Pair with a verification code and reconnect through TLS WebSockets with
  public-key pinning; discovery metadata is always treated as untrusted.
- Keep sensitive functionality opt-in with synchronized global and per-device
  feature controls.
- Export bounded diagnostics without clipboard text, notification contents,
  filenames, network addresses, or persistent identifiers.
- Add regression coverage for pairing, protocol crypto, reconnects, malformed
  messages, file cancellation, notification actions, call lifecycles, Android
  permissions, and Share task reuse.
- Adopt a unified Bridgey `B` identity across Android, macOS, notifications, and
  the web.
- Publish the project website, mobile-friendly documentation, security material,
  release instructions, and the roadmap through 1.0 at
  [bridgey.ai](https://bridgey.ai/).

## Upgrade and test focus

- Install over an existing 0.5 alpha and confirm that device trust and settings
  remain intact.
- Restart either client and verify automatic reconnect, then test clipboard and
  one file in each direction.
- Share several photos to Bridgey consecutively and confirm Android Recents
  contains a single Bridgey window.
- Test notification display, an action or inline reply, dismissal sync, and an
  application filter.
- Test incoming and outgoing calls, Answer, Decline, Hang Up, and automatic call
  panel dismissal.
- Test battery status and Find Device in both directions.

## Downloads

- `Bridgey-Android.apk` — signed Android 8.0+ application.
- `Bridgey-macOS.dmg` — macOS 13+ disk image.
- `Bridgey-macOS.zip` — alternative macOS archive.
- `SHA256SUMS-*` — checksums for verifying downloads.

## Known limitations

- Bridgey currently requires both devices to be reachable on the same local
  network; accounts and an optional relay are planned for later evaluation.
- Call status and controls can vary between Android vendors. Direct controls
  require explicit Android Phone permissions; confirmation mode remains
  available without that opt-in.
- Bridgey does not read contacts, SMS history, or call logs.
- The macOS build remains ad-hoc signed and is not notarized until Developer ID
  credentials are configured, so macOS can show an unidentified-developer
  warning on first launch.
- Bridgey remains pre-1.0 software; the protocol compatibility freeze and full
  supported-device matrix are planned for 1.0.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
