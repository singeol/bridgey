# Bridgey v0.3.0

Bridgey v0.3.0 focuses on reliability, recovery, and privacy-safe support
information. Install this version on both Android and macOS.

## Transfer recovery

- Recent active, completed, cancelled, failed, and interrupted transfers now
  remain visible in a bounded history instead of disappearing after a few
  seconds.
- Each client keeps up to 20 completed or interrupted entries and provides a
  clear-history action.
- Interrupted or failed outgoing files can be retried after reconnecting. A
  retry creates a fresh transfer, recomputes its SHA-256, and starts from byte
  zero.
- A disconnect now marks every active row as interrupted and removes partial
  incoming files instead of leaving progress stuck on sending or verifying.
- Simultaneous transfers are ordered consistently by their start time.

## Protocol hardening and tests

- The native transport rejects malformed UTF-8 and protocol frames larger than
  65,536 bytes before plugin dispatch.
- Added malformed-frame, interrupted-transfer, bounded-history, reconnect
  backoff, and diagnostics privacy tests for Android and macOS.
- Existing shared Android–macOS cryptographic vectors continue to verify P-256,
  HKDF, confirmation proofs, signatures, and AES-GCM interoperability.

## Privacy-safe diagnostics

- Settings on both platforms can export a structured JSON diagnostics report.
- Reports contain only bounded event metadata, versions, feature states,
  connection state, and aggregate transfer counts.
- Reports exclude clipboard text, notification content, file names, network
  addresses, and device, session, or transfer identifiers.
- This release requests no new runtime permissions.

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
- File retry is user initiated and restarts from byte zero; partial-stream
  resume is not part of protocol v1.
- Transfer history is kept for the current application run and is not a record
  of file contents.
- Android requires an explicit user action to read and send its clipboard.
- The macOS build is not notarized.

Built with the assistance of [OpenAI Codex](https://openai.com/codex/).
