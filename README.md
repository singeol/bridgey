# Bridgey

[![Build](https://github.com/singeol/bridgey/actions/workflows/ci.yml/badge.svg)](https://github.com/singeol/bridgey/actions/workflows/ci.yml)
[![Security](https://github.com/singeol/bridgey/actions/workflows/security.yml/badge.svg)](https://github.com/singeol/bridgey/actions/workflows/security.yml)
[![Latest release](https://img.shields.io/github/v/release/singeol/bridgey)](https://github.com/singeol/bridgey/releases/latest)
[![License: MIT](https://img.shields.io/github/license/singeol/bridgey)](LICENSE)

[Website and documentation](https://bridgey.ai/) ·
[Download the latest release](https://github.com/singeol/bridgey/releases/latest)

Bridgey is an open-source, local-first bridge between Android and macOS. It aims
to provide a small, native and extensible subset of Apple Continuity and KDE
Connect without accounts, telemetry, or a mandatory cloud service. A Linux
client can later implement the same public protocol without Android changes.

> Current release: v0.5.0. Android and macOS clients support
> secure pairing, automatic reconnect, clipboard sharing, file transfer,
> Android notification forwarding, battery status, Find Device, and starting a
> phone call from macOS over the local network. Discovery data is intentionally
> treated as untrusted.

## MVP scope

- Explicit two-device pairing with a shared verification code
- Persistent TLS WebSocket connections with public-key pinning
- Plain-text and HTML clipboard synchronization with a safe payload limit
- Streaming file transfer with progress and cancellation
- Android notification forwarding to native macOS notifications
- Android battery status on macOS
- Find Device in both directions
- Start a phone call from selected text or the macOS clipboard

The current release deliberately starts with a small local-first feature set.
Communication, remote-control, screen-sharing, and optional relay features are
planned in later phases below and will require separate security and platform
feasibility reviews.

## Roadmap

The roadmap describes intended direction rather than fixed release dates.
Bridgey 1.0 is defined as a stable, secure local Android–macOS companion, not
as complete feature parity with KDE Connect. Experimental plugins do not block
the stable release.

### v0.2.1 — settings and quality

- [x] Synchronize feature availability between connected devices
- [x] Add a native macOS Settings window and clearer unavailable-feature states
- [x] Add lint, unit-test, CodeQL, dependency-review, and Dependabot workflows
- [x] Audit build tools, libraries, and refresh GitHub Actions
- [x] Migrate Android to AGP 9 and built-in Kotlin in a separately validated change
- [x] Complete device testing of settings changes and publish the patch release

### v0.3.0 — reliability

- [x] Add Android–macOS protocol integration tests with deterministic generated identities
- [x] Add malformed-message, interrupted-transfer, and reconnect test scenarios
- [x] Improve simultaneous transfer history, retry, and recovery behavior
- [x] Add structured diagnostics that can be exported without private content

### v0.4.0 — platform integration

- [x] Add an Android share target for sending files and text to a trusted device
- [x] Add drag-and-drop file sending on macOS
- [x] Add richer clipboard content after text synchronization is fully hardened
- [x] Improve accessibility, localization readiness, and first-run guidance

### v0.5.0 — notifications and communication

- [x] Synchronize notification dismissal/removal state between Android and macOS
- [x] Support notification action buttons and inline replies when Android exposes them
- [x] Add per-application notification filters and a private local history
- [x] Research SMS viewing/replies and document the permission-safe notification-action scope
- [x] Show live call state with explicit Answer, Decline, and Hang Up controls
- [x] Start a validated phone call from selected macOS text or the clipboard

### v0.6.0 — media and quick actions

- [ ] Control macOS media from Android: play/pause, previous/next, seek, and volume
- [ ] Show bounded now-playing metadata and artwork where public platform APIs allow it
- [ ] Optionally pause or mute media while a phone call is ringing or active
- [ ] Send and explicitly open web links in either direction
- [x] Add a lightweight Ping action separate from Find Device
- [x] Show Mac battery status on Android
- [ ] Add an Android Quick Settings tile and configurable macOS shortcuts
- [ ] Complete multi-OEM stabilization of the v0.5 call and notification lifecycle

### v0.7.0 — remote input and presentations

- [ ] Use Android as an opt-in macOS touchpad and keyboard
- [ ] Support pointer movement, clicks, scrolling, dragging, and bounded text input
- [ ] Add presentation actions for previous/next, start/full screen, escape, and laser pointer
- [ ] Request macOS Accessibility access only when remote input is explicitly enabled
- [ ] Require a visible active-session indicator and an immediate local stop control
- [ ] Rate-limit remote input and keep a private audit log of remote-control sessions
- [ ] Grant remote-control capabilities independently for every trusted device

### v0.8.0 — Files 2.0 and multiple devices

- [ ] Send multiple files and folders with a visible queue and deterministic conflict handling
- [ ] Resume verified transfers after network interruption or application restart
- [ ] Browse only Android folders explicitly granted through the system Storage Access Framework
- [ ] Upload to and download from granted folders without broad storage permissions
- [ ] Improve Finder, Quick Look, Downloads, and transfer-history integration
- [ ] Support multiple simultaneous trusted-device sessions with per-device settings
- [ ] Stress-test cancellation, reconnection, and multi-gigabyte transfers in both directions

### v0.9.0 — automation and experimental communication

- [ ] Add locally configured actions for lock, sleep, screensaver, volume, and launching an app
- [ ] Permit custom automation only through a local allowlist; never expose a remote shell
- [ ] Add Android Device Controls/widgets and browser integrations for link handoff
- [ ] Add a privacy-preserving connection health and diagnostics view
- [ ] Prototype explicitly confirmed, view-only screen sharing in both directions
- [ ] Evaluate full SMS conversations, new-message sending, and optional contact names as a
      separately consented plugin and distribution flavor
- [ ] Keep notification-based message replies as the permission-light fallback

Screen sharing must display a persistent local indicator and require explicit
confirmation for each session. Full SMS access depends on distribution-policy
approval, a privacy policy, prominent disclosure, retention controls, and a
separate permission review. Neither experimental capability blocks v1.0.

### v1.0.0 — stable local companion

- [ ] Freeze and document the Bridgey Protocol v1 compatibility contract
- [ ] Preserve trust, settings, and local history through supported upgrades and migrations
- [ ] Recover reliably after sleep, Wi-Fi changes, process restarts, and interrupted transfers
- [ ] Run long-lived, malformed-input, fuzz, and cross-platform integration tests
- [ ] Publish a tested Android/OEM and macOS compatibility matrix
- [ ] Complete security, privacy, accessibility, and English/Russian localization reviews
- [ ] Explain every optional permission in onboarding and provide local-data reset controls
- [ ] Publish checksums, SBOM/provenance, diagnostics guidance, and user/developer documentation
- [ ] Provide a clear update path and repeatable Android and macOS release packaging

Developer ID signing and notarization remain desirable when an Apple Developer
membership is available, but do not prevent a documented open-source v1.0.

### Beyond KDE Connect

Bridgey should keep its local-first scope while developing capabilities that
are more explicit and auditable than a simple feature-for-feature clone:

- [ ] One-time remote permissions, including grants limited to a single session or time window
- [ ] A private per-device audit log for remote input, automation, files, and sensitive actions
- [ ] Resumable content-addressed transfers with end-to-end integrity verification
- [ ] Image and file clipboard handoff with explicit user interaction on Android
- [ ] QR-assisted pairing and temporary guest pairing without a permanent trust record
- [ ] Home, Work, and Public Network profiles with conservative defaults on unfamiliar networks
- [ ] A documented plugin SDK so future clients can extend the public protocol safely
- [ ] Optional end-to-end encrypted relay without making accounts or cloud service mandatory

### After v1.0 / exploratory

- [ ] Linux client using the public Bridgey protocol
- [ ] Windows and iOS clients where platform restrictions permit useful background behavior
- [ ] Bluetooth transport fallback when local-network discovery is unavailable
- [ ] Optional accounts for recovery without making accounts mandatory
- [ ] End-to-end encrypted off-LAN relay with direct connections preferred
- [ ] Full Android screen control only after a separate accessibility and abuse review
- [ ] Contacts and call-history synchronization only as separately consented plugins
- [ ] Developer ID signing, notarization, and a polished macOS updater

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
open .build/debug/Bridgey.app
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
- Android Phone access is optional and requested only when the user enables
  call status and controls. `CALL_PHONE` starts validated calls,
  `READ_PHONE_STATE` distinguishes ringing from active calls, and
  `ANSWER_PHONE_CALLS` enables Answer, Decline, and Hang Up. Bridgey does not
  request contacts or call-log access. Without this opt-in, a request from Mac
  opens the system dialer through an Android notification for confirmation.
- macOS local-network access is required for Bonjour discovery and direct
  encrypted connections to Android devices.
- macOS notifications are optional and are requested only when the user enables
  Android notification display on the Mac.
- File access is scoped to a file selected by the user and the Bridgey receive
  directory (`Downloads/Bridgey`).

Bridgey does not request location, contacts, SMS, call-log, camera, microphone,
screen recording, Accessibility, Input Monitoring, or Full Disk Access.
Permission denial disables only the corresponding optional integration.

## Settings

Open **Settings** on Android or the gear menu on macOS to:

- change the name advertised to nearby Bridgey devices;
- enable Clipboard, File transfer, Notification forwarding, Battery status,
  Find Device, and Calls from Mac globally;
- override those features for each trusted device or forget its trust record;
- choose the macOS folder used for received files;
- start the macOS app automatically at login.

To call from macOS, copy a phone number and press **Call** in Bridgey or
`Control-Option-P`. In applications that expose macOS Services, select a phone
number and choose **Services → Call with Bridgey**. Android uses confirmation
mode by default: tap the call-request notification to review the number in the
system dialer. An explicit Android Settings switch enables call status and
controls. It asks for `CALL_PHONE`, `READ_PHONE_STATE`, and
`ANSWER_PHONE_CALLS` so Bridgey can start a validated call, distinguish
ringing from an active call, and answer, decline, or hang up from the paired
Mac. It does not request contacts or call history, and emergency numbers are
never dialled directly by Bridgey.

Bridgey can also handle `tel:` links from browsers. In macOS Settings, press
**Use Bridgey for phone links** once; this explicit action makes Bridgey the
default phone-link handler. Browsers still show their external-application
confirmation before opening Bridgey and sending the validated number.

Incoming and active calls appear in a compact floating macOS call panel with
explicit controls. It stays available across Spaces without activating Bridgey
or taking focus from the current app; hiding it leaves the same controls in the
Bridgey menu-bar panel.

Connected devices exchange their effective feature state over the encrypted
session. A feature is available only when both devices enable it, so controls
and battery status update immediately on the other device. A file transfer
already accepted is allowed to finish, while disabling Find Device stops a
currently playing alert. Android received files remain in `Download/Bridgey`;
this uses scoped `MediaStore` access and avoids requesting broad storage
permission.

Recent file transfers remain visible until cleared. An outgoing transfer that
fails or is interrupted can be retried after the devices reconnect; retrying
creates a new transfer and starts from byte zero so that integrity verification
remains simple and deterministic. Each client keeps at most 20 completed or
interrupted entries in memory.

Settings can export a structured diagnostics JSON file. It contains bounded
event metadata, application/platform versions, feature states, connection
state, and transfer counts. It never includes clipboard text, notification
content, file names, network addresses, or device/session/transfer identifiers.

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
