# Bridgey

[![Build](https://github.com/singeol/bridgey/actions/workflows/ci.yml/badge.svg)](https://github.com/singeol/bridgey/actions/workflows/ci.yml)
[![Security](https://github.com/singeol/bridgey/actions/workflows/security.yml/badge.svg)](https://github.com/singeol/bridgey/actions/workflows/security.yml)
[![Latest release](https://img.shields.io/github/v/release/singeol/bridgey)](https://github.com/singeol/bridgey/releases/latest)
[![License: MIT](https://img.shields.io/github/license/singeol/bridgey)](LICENSE)

Bridgey is an open-source, local-first bridge between Android and macOS. It aims
to provide a small, native and extensible subset of Apple Continuity and KDE
Connect without accounts, telemetry, or a mandatory cloud service. A Linux
client can later implement the same public protocol without Android changes.

> Project status: v0.2.0. Android and macOS clients support
> secure pairing, automatic reconnect, clipboard sharing, file transfer,
> Android notification forwarding, battery status, and Find Device over the
> local network. Discovery data is intentionally treated as untrusted.

## MVP scope

- Explicit two-device pairing with a shared verification code
- Persistent TLS WebSocket connections with public-key pinning
- Text clipboard synchronization with loop prevention
- Streaming file transfer with progress and cancellation
- Android notification forwarding to native macOS notifications
- Android battery status on macOS
- Find Device in both directions

The current release deliberately starts with a small local-first feature set.
Communication, remote-control, screen-sharing, and optional relay features are
planned in later phases below and will require separate security and platform
feasibility reviews.

## Roadmap

The roadmap describes intended direction rather than fixed release dates.

### v0.2.1 — settings and quality

- [x] Synchronize feature availability between connected devices
- [x] Add a native macOS Settings window and clearer unavailable-feature states
- [x] Add lint, unit-test, CodeQL, dependency-review, and Dependabot workflows
- [x] Audit build tools, libraries, and refresh GitHub Actions
- [ ] Migrate Android to AGP 9 and built-in Kotlin in a separately validated change
- [ ] Complete device testing of settings changes and publish the patch release

### v0.3 — reliability

- [ ] Add Android–macOS protocol integration tests with generated identities
- [ ] Add malformed-message, interrupted-transfer, and reconnect test scenarios
- [ ] Improve simultaneous transfer history, retry, and recovery behavior
- [ ] Add structured diagnostics that can be exported without private content

### v0.4 — platform integration

- [ ] Add an Android share target for sending files and text to a trusted device
- [ ] Add drag-and-drop file sending on macOS
- [ ] Add richer clipboard content after text synchronization is fully hardened
- [ ] Improve accessibility, localization readiness, and first-run guidance

### v0.5 — notifications and communication

- [ ] Synchronize notification dismissed/read state between Android and macOS
- [ ] Support notification action buttons and inline replies when Android exposes them
- [ ] Add per-application notification filters and a private local history
- [ ] Research SMS viewing and replies with explicit permissions and platform-policy checks
- [ ] Show incoming-call state and add supported mute/reject actions

### v0.6 — remote interaction

- [ ] Add media playback controls and shared media information
- [ ] Add presentation controls and an opt-in remote keyboard/touchpad
- [ ] Add explicitly confirmed screen sharing between Android and desktop
- [ ] Add scoped remote file browsing without unrestricted filesystem access
- [ ] Define a permission model and audit log for every remote-control capability

### Later / exploratory

- [ ] Linux client using the public Bridgey protocol
- [ ] Multiple simultaneous trusted-device sessions
- [ ] Optional accounts for device recovery without making accounts mandatory
- [ ] Optional end-to-end encrypted cloud relay for devices outside the local network
- [ ] Carefully scoped command/device automation without providing a remote shell
- [ ] Developer ID signing and notarization when an Apple Developer membership is available

## Repository

```text
android/              Kotlin + Jetpack Compose application and core tests
macos/                Swift + SwiftUI menu-bar application
protocol/             Machine-readable, platform-independent schemas
docs/architecture.md  Components, ownership, and delivery sequence
docs/protocol.md      Version 1 wire protocol
SECURITY.md            Threat model and cryptographic design
```

## Build and run

### Android

Requirements: Android Studio with JDK 17 and Android SDK 36. Open `android/`,
let Android Studio use its bundled JDK, then run the `app` configuration on an
Android 8.0+ device. From the command line, use the versioned Gradle Wrapper:

```bash
cd android
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Release APKs are signed by GitHub Actions using repository secrets. Never
commit an Android keystore.

### macOS

Requirements: macOS 13+, Swift 5.10+, and Xcode for producing a signed app.
The discovery/menu-bar target can also be compiled and tested as a Swift
package:

```bash
cd macos
./build-app.sh
open .build/debug/BridgeyMac.app
```

The `.app` bundle is required for Notification Center integration. The raw
SwiftPM executable remains useful for core diagnostics, but macOS does not
register it as a notification-capable application.

Create local release archives with:

```bash
cd macos
./package-release.sh
```

The current pipeline uses an ad-hoc macOS signature. Users may need to approve
the app in Privacy & Security until Developer ID signing and notarization are
configured.

## Automated releases

Pull requests and pushes to `main` build both clients. Tags matching `v*`
attach a signed Android APK, a macOS DMG/ZIP, and SHA-256 checksums to a GitHub
Release.

Configure these GitHub Actions secrets before creating the first tag:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Back up the original Android keystore and passwords permanently. Every future
update must be signed with the same key.

Local-network discovery works best on real devices on the same Wi-Fi network.
The macOS executable may require local-network permission when prompted.

## Permissions and privacy

Bridgey asks for access only when the related feature needs it:

- Android notifications let the foreground connection, transfer progress,
  received files, and Find Device controls remain visible.
- Android notification access is optional and is used only to forward a
  notification's application name, title, and text to the paired Mac.
- macOS local-network access is required for Bonjour discovery and direct
  encrypted connections to Android devices.
- macOS notifications are optional and are requested only when the user enables
  Android notification display on the Mac.
- File access is scoped to a file selected by the user and the Bridgey receive
  directory (`Downloads/Bridgey`).

Bridgey does not request location, contacts, camera, microphone, screen
recording, Accessibility, Input Monitoring, or Full Disk Access. Permission
denial disables only the corresponding optional integration.

## Settings

Open **Settings** on Android or the gear menu on macOS to:

- change the name advertised to nearby Bridgey devices;
- enable Clipboard, File transfer, Notification forwarding, Battery status,
  and Find Device globally;
- override those features for each trusted device or forget its trust record;
- choose the macOS folder used for received files;
- start the macOS app automatically at login.

Connected devices exchange their effective feature state over the encrypted
session. A feature is available only when both devices enable it, so controls
and battery status update immediately on the other device. A file transfer
already accepted is allowed to finish, while disabling Find Device stops a
currently playing alert. Android received files remain in `Download/Bridgey`;
this uses scoped `MediaStore` access and avoids requesting broad storage
permission.

## Development principles

- Core routes envelopes and lifecycle events; it does not understand plugin
  payloads.
- Every plugin declares capabilities and owns its message types.
- Logs may include state transitions, message IDs, and peer IDs, but never
  secrets or clipboard/notification contents.
- No data learned through Bonjour/mDNS is trusted before authenticated pairing.
- Protocol changes are documented in `docs/protocol.md` before implementation.

Suggested debug log categories are `DISCOVERY`, `PAIRING`, `TRANSPORT`, and
`PLUGIN`. See [the architecture](docs/architecture.md), [wire protocol](docs/protocol.md),
the [security model](SECURITY.md), and [security testing](docs/security-testing.md).

## Contributing

Keep changes narrowly scoped and independently testable. New plugin proposals
should define capabilities, message schemas, permissions, privacy impact, and
failure behavior. By contributing, you agree that your contribution is
licensed under the MIT License.

## Credits

Bridgey is designed and developed by Semyon Mikhailov with the assistance of
[OpenAI Codex](https://openai.com/codex/). Product decisions, testing, and
release responsibility remain with the project maintainer.
