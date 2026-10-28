# Upgrade policy

Bridgey keeps trust, settings, and local history when a user installs a newer
release over an older one. This page lists everything each client stores, the
release that introduced each format, and the rules for changing a format. It
serves the v1.0 roadmap item "Preserve trust, settings, and local history
through supported upgrades and migrations".

## Supported upgrades

- Any release from v0.2.0 onwards can be upgraded directly to the current
  release on the same platform. Older stored values are read as they are; no
  intermediate release is needed. Releases 0.1.x stored trust and identity in
  the same formats but had no settings yet.
- Android updates must be signed with the same key (see
  [releasing](releasing.md)); otherwise Android refuses the update and the
  user has to uninstall, which deletes all local data.
- Downgrades are not supported. An older release ignores keys it does not
  know, so a downgrade usually keeps working, but it is not tested.
- Pairing works across versions as described in the protocol compatibility
  contract in [protocol.md](protocol.md). Upgrading one device does not require
  pairing again.

## What is stored

### Android (`dev.bridgey.android`)

All values are in app-private storage and are removed when the app is
uninstalled. The file names are SharedPreferences names.

| Storage | Keys | Since | Contents |
| --- | --- | --- | --- |
| `bridgey` | `device_id` | 0.1.0 | Stable random device UUID used in discovery and pairing |
| `bridgey` | `bridgey_enabled` | 0.1.0 | Whether the user turned Bridgey on |
| `bridgey.settings` | `device_name` | 0.2.0 | Name advertised to nearby devices |
| `bridgey.settings` | `global.<feature>` | 0.2.0 | Global feature switches (missing means on) |
| `bridgey.settings` | `device.<deviceId>.<feature>` | 0.2.0 | Per-device overrides |
| `bridgey.settings` | `notification.application.<package>` | 0.5.0 | Application names seen in forwarded notifications |
| `bridgey.settings` | `notification.disabled_packages` | 0.5.0 | String set of packages not forwarded |
| `bridgey.settings` | `calls.direct_enabled` | 0.5.0 | Direct call mode opt-in |
| `bridgey.trust` | `peer.<deviceId>.name`, `peer.<deviceId>.identityKey` | 0.1.0 | Trusted Macs and their pinned P-256 identity keys |
| `bridgey_onboarding` | `completed` | 0.4.0 | First-run guidance dismissed |
| Android Keystore | alias `bridgey.identity.p256.v1` | 0.1.0 | This phone's P-256 signing key (not exportable) |
| `Download/Bridgey` | files | 0.1.0 | Received files, in shared storage; kept on uninstall |

Feature keys are the `features.update` keys: `clipboard`, `files`,
`notifications`, `battery`, `find_device`, `ping`, `links`, `media`, and
`calls`. Transfer history and the diagnostics log are kept in memory only.

### macOS (`dev.bridgey.mac`)

| Storage | Keys | Since | Contents |
| --- | --- | --- | --- |
| UserDefaults | `deviceID` | 0.1.0 | Stable random device UUID |
| UserDefaults | `legacyPreferencesMigrated` | 0.1.0 | Values from the earlier `BridgeyMac` domain were copied once |
| UserDefaults | `settings.deviceName` | 0.2.0 | Name advertised to nearby devices |
| UserDefaults | `settings.global.<feature>` | 0.2.0 | Global feature switches (missing means on, except `media`) |
| UserDefaults | `settings.device.<deviceId>.<feature>` | 0.2.0 | Per-device overrides |
| UserDefaults | `settings.receiveFolderPath` | 0.2.0 | Folder for received files |
| UserDefaults | `settings.receiveFolderBookmark` | 0.3.0 | Security-scoped bookmark for that folder |
| UserDefaults | `settings.onboarding.completed` | 0.4.0 | First-run guidance dismissed |
| UserDefaults | `settings.notificationHistory.enabled` | 0.5.0 | Local notification history opt-in |
| UserDefaults | `shortcuts.<action>`, `shortcuts.shift` | 0.6.0-rc.1 | Keyboard shortcut keys (`clipboard`, `call`, `link`, `ping`) |
| UserDefaults | `media.player` | 0.6.0-rc.1 | `off`, `music`, or `spotify` |
| UserDefaults | `media.pauseForCalls` | 0.6.0-rc.1 | Pause the player during calls |
| UserDefaults | `trustedDeviceIDs`, `trusted.<deviceId>.name`, `trusted.<deviceId>.identityKey` | 0.1.0 to 0.4.0 | Trusted phones; moved to the Keychain by 0.4.1 and later, then deleted |
| Keychain | service `dev.bridgey.mac.trust`, account `trusted-devices-v1` | 0.4.1 | JSON object of device ID to `{id, name, identityKey}` |
| Keychain | service `dev.bridgey.identity`, account `p256-signing-v1` | 0.1.0 | This Mac's P-256 signing key, raw representation |
| `~/Library/Application Support/Bridgey/notification-history.json` | | 0.5.0 | Optional history: at most 200 items and seven days, file mode 0600 |
| Receive folder (default `~/Downloads/Bridgey`) | files | 0.1.0 | Received files |

Login item registration is held by macOS (`SMAppService`), not by Bridgey.
Transfer history and the diagnostics log are kept in memory only.

## Local data reset

Settings on each platform has a reset control that asks for confirmation
first.

- Android, Reset Bridgey on this phone: deletes `bridgey.settings`,
  `bridgey.trust`, and `bridgey_onboarding`, ends the current session, and
  clears the recent transfer list. It keeps `bridgey` (device ID and on/off
  state), the Keystore signing key, received files, and permissions granted in
  Android Settings.
- macOS, Reset Bridgey on this Mac: deletes the trust Keychain item's entries,
  every `settings.`, `shortcuts.`, `media.`, and legacy `trusted.` key,
  `notification-history.json`, mirrored notifications in Notification Center,
  and the transfer list, and ends the current session. It keeps `deviceID`,
  `legacyPreferencesMigrated`, the Keychain signing key, received files, and
  launch at login.

Paired devices keep their own record of the reset device until it is forgotten
there too. A store added later must be added to the reset, or listed here as
kept, in the same pull request.

## Rules for changing a stored format

1. Never rename or reuse a key or file. Add a new key and keep reading the old
   one, or migrate it once and delete the old value only after the new one has
   been written successfully, as the 0.4.1 trust migration does.
2. A missing value means the default of the release that reads it. Choose
   defaults so that a value missing because it predates the feature behaves
   sensibly.
3. Ignore what you do not understand. Unknown keys, unknown enum values, and
   entries that fail validation are skipped one by one; they never discard the
   rest of a store or crash the app.
4. Every new format or change of format adds a fixture under
   `testdata/migrations/<platform>/<release>/` written exactly as the release
   writes it, plus a test that loads it with the current code. Fixtures use
   throwaway keys and made-up names only.
5. Security material stays where it is. Identity keys stay in the Android
   Keystore and the macOS Keychain; a migration never moves them to plain
   preferences or files.
6. Document the change in this page in the same pull request.

## Tests

`StorageMigrationTest` (Android) and `StorageMigrationTests` (macOS) load the
fixtures in `testdata/migrations/` with the current code:

- Android: `bridgey.settings` and `bridgey.trust` from 0.2.0, 0.5.0, and
  0.6.0-rc.2 through `BridgeySettings` and `AndroidTrustRegistry`, and
  `device_id` in `bridgey`.
- macOS: UserDefaults domains from 0.2.0, 0.4.0, 0.5.0, and 0.6.0-rc.2 through
  `BridgeySettings`, `ShortcutSettings` and `MediaController`, the pre-0.4.1 trust keys that
  `MacTrustRegistry` migrates, the Keychain trust JSON from 0.5.0 and
  0.6.0-rc.2, and `notification-history.json` through
  `NotificationHistoryStore`.

They do not exercise the Android Keystore, the macOS Keychain itself, or the
one-time copy from the `BridgeyMac` UserDefaults domain; those need a real
device or Mac.
