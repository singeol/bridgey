# Bridgey v0.6.0-rc.2 — macOS panel sizing

One complete 0.6 feature candidate for end-to-end testing. This is a prerelease;
stable 0.5.0 remains the default download until device acceptance is complete.

## Changes since rc.1

- Constrain the macOS menu-bar panel to its content's ideal height when the
  connection state changes, instead of accepting a stale taller window size.
- Give the File transfers summary button an explicit horizontal layout.
- Add three layout tests for oversized height proposals, repeated grow/shrink
  transitions, and showing/hiding transfer history.
- Keep Android functionality unchanged; bump both clients to build 25.

The original system-menu glitch has not been reproduced in the isolated layout
test. Please confirm this fix on the affected Mac: quit the old Bridgey process,
install rc.2, and reopen the menu repeatedly while disconnecting/reconnecting
the phone, with transfer history present. There should be no tall empty area
above the panel. See the full acceptance checklist below.

## Included 0.6 features

- Control Music or Spotify on your Mac from Android: play/pause, previous/next,
  seek and player volume, with bounded now-playing metadata and optional Music
  artwork. Enable Media controls and choose the player in Mac Settings first.
- Optional pause of that player when a forwarded phone call rings or becomes
  active. Media stays paused after the call; no unexpected automatic resume.
- Send copied HTTP(S) links in both directions and URLs from Android Share.
  Open/Dismiss on the receiving device gives explicit control over navigation.
- Ping either device without starting the Find Device ring.
- See MacBook battery percentage and charging state on Android.
- Add Bridgey clipboard to Android Quick Settings.
- Configure Mac clipboard, call, link and Ping shortcuts, with conflict checks
  and an optional Shift modifier.

## Safety and quality

- Fix the High CodeQL finding in Android call-confirmation PendingIntent by
  targeting a private explicit activity.
- New commands have feature gates, correlated acknowledgements, bounded
  timeouts and disconnect cleanup; encrypted request sequences reject replay.
- Add URL, media-command, artwork, sequence and component-permission tests.
- Merge reviewed dependency updates: setup-java v6 and AGP 9.4.0.
- Correct security documentation: current native clients use TCP with
  application-layer AES-GCM, not the originally planned TLS/WebSocket transport.

## Install and test

Install both clients from this release over the existing versions, quitting the
old Mac process first. Trust and settings should remain intact. Use the
[complete acceptance checklist](https://github.com/singeol/bridgey/blob/main/docs/testing-0.6.md)
for setup, new features, feature-off/reconnect cases, and the 0.5 regressions.

- `Bridgey-Android.apk`: signed Android 8.0+ application.
- `Bridgey-macOS.dmg` / `Bridgey-macOS.zip`: macOS 13+ application.
- `SHA256SUMS-*`: download checksums.

## Limitations

- Both devices must be on a reachable local network. No cloud account or relay.
- Media automation is off by default and needs macOS consent for the selected
  running player. This is not universal Safari/Chrome or system media control.
  Metadata normally refreshes within ten seconds. Music artwork depends on what
  the player exposes; Spotify artwork is not fetched.
- Quick Settings uses a short foreground clipboard-capture activity to respect
  Android's privacy rules; it does not bypass background clipboard restrictions.
- Call pause depends on forwarded call state and granted optional Phone access.
  Multi-OEM call/notification validation remains pending; passing unit tests does
  not certify every phone. Call audio, SMS history and contacts are not accessed.
- macOS remains ad-hoc signed and not notarized; Gatekeeper may warn on first
  launch. No paid Apple Developer credentials have been added.
- Pre-1.0 software: this candidate has not yet completed physical-device acceptance
  or an independent protocol security audit.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
