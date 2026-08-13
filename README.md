# Bridgey

Bridgey is an open-source, local-first bridge between Android and macOS. It aims
to provide a small, native and extensible subset of Apple Continuity and KDE
Connect without accounts, telemetry, or a mandatory cloud service. A Linux
client can later implement the same public protocol without Android changes.

> Project status: v0.1 release candidate. Android and macOS clients support
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

Cloud relay, accounts, SMS, calls, media control, remote input, screen sharing,
and filesystem browsing are outside v0.1.

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
let Android Studio use its bundled Gradle, then run the `app` configuration on
an Android 8.0+ device. From a machine with Gradle installed:

```bash
cd android
gradle :app:assembleDebug :app:testDebugUnitTest
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
and [security model](SECURITY.md).

## Contributing

Keep changes narrowly scoped and independently testable. New plugin proposals
should define capabilities, message schemas, permissions, privacy impact, and
failure behavior. By contributing, you agree that your contribution is
licensed under the MIT License.

## Credits

Bridgey is designed and developed by Semyon Mikhailov with the assistance of
[OpenAI Codex](https://openai.com/codex/). Product decisions, testing, and
release responsibility remain with the project maintainer.
