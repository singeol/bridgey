# Developer guide

This guide covers building, testing and changing Bridgey. Read
[architecture.md](architecture.md), [protocol.md](protocol.md) and
[SECURITY.md](../SECURITY.md) before changing behaviour that crosses the wire.

## Repository layout

```text
android/
  app/                  Kotlin + Jetpack Compose application (dev.bridgey.android)
    src/main/           Activity, connection service, pairing, settings, plugins
    src/test/           JVM unit tests
  core/discovery/       NSD (DNS-SD) discovery module and its tests
macos/
  Sources/BridgeyMac/   Swift + SwiftUI menu-bar application
  Tests/BridgeyMacTests/ XCTest suite
  Resources/            Info.plist, entitlements, icons, en.lproj and ru.lproj strings
  build-app.sh          Builds and signs .build/<configuration>/Bridgey.app
  package-release.sh    Builds the release DMG and ZIP with checksums
protocol/
  schema/               JSON schema for the planned envelope
  test-vectors/         Shared vectors that both clients must decode identically
  messages.json         Machine-readable list of every message kind
testdata/migrations/    Stored data as released versions wrote it, for upgrade tests
ci/                     CodeQL SARIF gate and its unit tests
docs/                   Architecture, protocol, security testing, releasing, guides
website/                Static project website
```

Upstream clients are single-session: `PairingCoordinator` owns one session on
each platform (`PairingCoordinator.kt` on Android, `Pairing.swift` on macOS).
Settings live in `BridgeySettings` on both platforms: global switches per
`BridgeyFeature`, plus per-device overrides.

## Requirements

- **Android:** JDK 17 and Android SDK 36. Android Studio's bundled JDK works.
  Point Gradle at the SDK with `sdk.dir` in `android/local.properties` (not
  committed) or `ANDROID_HOME`. Always use the Gradle Wrapper in `android/`.
- **macOS:** macOS 13 or later, Swift 5.10 or later, and Xcode or the Xcode
  Command Line Tools (`build-app.sh` uses `xcrun`, `codesign` and, when
  available, `actool`). Python 3 runs `create-icns.py`.

## Build and test

These are the commands the Build workflow (`.github/workflows/ci.yml`) runs on
every pull request. Run them before you open one.

Android, on Ubuntu with Temurin JDK 17:

```bash
cd android
./gradlew :app:lintDebug :core:discovery:lintDebug :app:testDebugUnitTest :core:discovery:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

macOS, on a `macos-15` runner:

```bash
cd macos
swift test -Xswiftc -warnings-as-errors
sh -n build-app.sh package-release.sh
python3 -m py_compile create-icns.py
plutil -lint Resources/Info.plist
./build-app.sh
open .build/debug/Bridgey.app
```

Use the `.app` bundle for manual testing: Notification Center integration
needs it. Without `MACOS_SIGNING_IDENTITY`, `build-app.sh` signs the bundle ad
hoc with a stable designated requirement, so macOS privacy settings survive
rebuilds.

The Security workflow (`.github/workflows/security.yml`) runs CodeQL
`security-extended` for Kotlin and Swift and fails on blocking findings. The
gate's own tests run with:

```bash
python3 -m unittest discover -s ci -p 'test_*.py'
```

Write tests next to the existing ones: JVM tests under
`android/app/src/test/java/dev/bridgey/android/` and XCTest under
`macos/Tests/BridgeyMacTests/`. Keep pure logic (parsing, validation, policy)
in small types that can be tested without a device.

## Changing the protocol

Both clients must agree on every message. A change is complete only when the
document, the contract file, the shared vectors and both clients agree.

1. **Document it first.** Describe the message kind, direction, fields, limits,
   encryption, acknowledgement and failure behaviour in
   [protocol.md](protocol.md) before you implement it. This is a project rule
   (see the Development principles in the README).
2. **Add it to `protocol/messages.json`.** Every message kind the sources use is
   listed there. `ProtocolContractTest` (Android) and `ProtocolContractTests`
   (macOS) scan the sources and fail when a kind, a feature key or a transport
   limit in the code does not match the file, or when a listed kind is missing
   from `protocol.md`.
3. **Add shared vectors.** New payloads get golden vectors in
   `protocol/test-vectors/protocol-v1.json`, which `ProtocolGoldenVectorsTest`
   and `ProtocolGoldenVectorsTests` decode with the same expected results, so
   the two implementations cannot drift. Payload decoders also join the fuzz
   properties in `ProtocolFuzzTest` and `ProtocolFuzzTests` (fixed seed; set
   `BRIDGEY_FUZZ_ITERATIONS` and `BRIDGEY_FUZZ_SEED` for longer runs).
   `crypto-v1.properties` covers P-256 ECDH, HKDF session keys, verification
   codes, confirmation proofs and AES-GCM; `ProtocolCryptoIntegrationTest` and
   `ProtocolCryptoIntegrationTests` read it.
4. **Follow the existing payload patterns.** Feature payloads are AES-GCM
   encrypted with the session key, carry a unique `messageId` checked against
   the per-session replay window, and are bounded before they are decoded.
   Malformed input must never crash a client and is never logged with content.
5. **Test the edges** on both platforms: oversized, malformed and replayed
   input, the feature turned off on either side, and disconnects while a
   request is pending.

### New feature keys

A new feature needs a plain key (such as `links` or `media`) in the
`BridgeyFeature` enum on both platforms, with a user-facing title. Older
clients do not send the new key in `features.update`, so a peer that omits it
must be treated as not supporting it. Add the key to the set excluded in
`featureEnabledByLegacyPeer` in `BridgeySettings.kt` and `BridgeySettings.swift`,
so the function returns `false` for it and the feature stays off for such
peers. Keys for which it returns `true` are required, and a `features.update`
without them is rejected. Extend `BridgeySettingsTest` and
`BridgeySettingsTests` to cover the new key. List the key in the `features` section of
`protocol/messages.json` and describe it in `protocol.md`.

The UI must hide or disable the feature unless both devices enable it
(`isFeatureAvailable`), and every incoming handler must check local policy
again before it acts.

## Changing stored data

Users upgrade over existing installs, so stored data must keep working.
Bridgey stores data in these places:

- Android: `SharedPreferences` files `bridgey`, `bridgey.settings`,
  `bridgey.trust` and `bridgey_onboarding`, plus private keys in Android
  Keystore.
- macOS: `UserDefaults` keys (`settings.*`, `shortcuts.*`, `media.*`,
  `deviceID`, `trustedDeviceIDs`), trust records and keys in the Keychain, and the notification history in
  `~/Library/Application Support/Bridgey/notification-history.json`.

A change to the format of any of these needs a migration from the previous
format, a fixture under `testdata/migrations/` written exactly as the previous
release writes it, and a test that loads it (`StorageMigrationTest` and
`StorageMigrationTests`). [upgrade-policy.md](upgrade-policy.md) lists every
stored key and file and the rules for changing them.

## Localization

User-facing strings are English with Russian translations.

- **Android:** string resources in `android/app/src/main/res/values/strings.xml`
  and `values-ru/strings.xml`, read with `stringResource` or
  `getString`.
- **macOS:** `L10n.text("key", fallback: "English text")` from
  `Localization.swift`, with the key in both
  `macos/Resources/en.lproj/Localizable.strings` and
  `macos/Resources/ru.lproj/Localizable.strings`. `build-app.sh` copies every
  `.lproj` folder into the app bundle.

Many existing strings are still hard-coded in English; new strings should use
the localized form on both platforms.

## Logging

Log lines start with a category: `DISCOVERY`, `PAIRING`, `TRANSPORT` or
`PLUGIN`. Android uses `android.util.Log` with the tag `Bridgey`; macOS uses
`NSLog`. Logs may include state transitions, message kinds and shortened IDs,
but never secrets, keys, verification codes, clipboard text, notification
content, reply text or phone numbers. Data learned through discovery is
untrusted and must not be treated as identity.

Diagnostics events (`BridgeyDiagnostics.record`) take fixed category, event and
outcome strings only. Never pass names, file names, addresses, identifiers or
content to them. See [diagnostics.md](diagnostics.md) for what users see.

## Security and releases

- [security-testing.md](security-testing.md) describes the automated security
  checks and what they do not cover. Report vulnerabilities privately as
  described in [SECURITY.md](../SECURITY.md).
- [releasing.md](releasing.md) covers version bumps, signing secrets, tags and
  release artifacts.
- [testing-0.6.md](testing-0.6.md) is the two-device acceptance checklist, and
  [compatibility.md](compatibility.md) records device results.

## Pull requests

Keep changes narrowly scoped and independently testable, and make sure the
Build and Security workflows pass. New plugin proposals should define
capabilities, message schemas, permissions, privacy impact and failure
behaviour. Contributions are licensed under the MIT License.
