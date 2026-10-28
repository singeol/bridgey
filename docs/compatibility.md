# Compatibility

This page records which Android devices and Macs Bridgey has been checked on,
and what differs between platform versions. It is the starting point for the
v1.0 roadmap item "Publish a tested Android/OEM and macOS compatibility
matrix".

- **Verified** means someone ran both clients on that combination and reported
  the result publicly. The row says what was checked and on which build.
- **Unverified** means no result has been reported yet. It does not mean the
  combination is unsupported.

Automated checks (Android lint and JVM unit tests, Swift tests and the app
build on a macOS 15 runner, CodeQL) run on every pull request. They find code
errors, not device or vendor behaviour, so they never make a row verified.
Device acceptance for the current release candidate is tracked in
[testing-0.6.md](testing-0.6.md).

## Supported versions

| Platform | Minimum | Built against | Source |
| --- | --- | --- | --- |
| Android | 8.0 (API 26) | API 36 (`compileSdk` and `targetSdk`) | `android/app/build.gradle.kts` |
| macOS | 13 | Swift tools 5.10 | `macos/Package.swift`, `LSMinimumSystemVersion` in `macos/Resources/Info.plist` |

The published macOS application in the v0.6.0-rc.2 release is an Apple silicon
(arm64) executable only. Intel Macs need a build from source (see the
[developer guide](developer-guide.md)); that configuration is unverified.

## Device results

| Phone | Android skin | Mac | macOS | Bridgey build | Checked | Source |
| --- | --- | --- | --- | --- | --- | --- |
| OnePlus 11 | OxygenOS (ColorOS base) | MacBook | 27 | v0.6.0-rc.2 with pull requests [#21](https://github.com/singeol/bridgey/pull/21) and [#22](https://github.com/singeol/bridgey/pull/22) applied | Call and notification mirroring, plus the earlier 0.6 checks, with no regressions seen | [#19](https://github.com/singeol/bridgey/issues/19) |

The OnePlus result was not on plain v0.6.0-rc.2: it was a test build of rc.2
with the call and notification lifecycle fixes from those two pull requests
applied. The
Android version and OxygenOS build number were not recorded in the report.

[testing-0.6.md](testing-0.6.md) lists a Samsung S25+ with a MacBook as pending
full acceptance of the 0.6 candidate, and records no other device result.

## Android versions

| Android | API | Status | Differences in Bridgey |
| --- | --- | --- | --- |
| 8.0 to 8.1 | 26 to 27 | Unverified | Cannot receive files. Find Device plays the alarm sound once instead of looping. With call status and controls on, Bridgey offers Answer but not Decline or Hang Up. |
| 9 | 28 | Unverified | Cannot receive files. With call status and controls on, Decline and Hang Up become available. |
| 10 to 11 | 29 to 30 | Unverified | Receiving files into `Download/Bridgey` becomes available. |
| 12 to 12L | 31 to 32 | Unverified | Call state follows telephony state changes, and CallStyle Answer, Decline and Hang Up intents are used to classify calls. |
| 13 | 33 | Unverified | Bridgey's own notifications need the runtime notification permission. |
| 14 to 16 | 34 to 36 | Unverified | The connection service is declared with the `connectedDevice` foreground service type that Android 14 and later require. |

Details behind the table, all from the Android sources:

- **Receiving files needs Android 10 (API 29).** Received files are written
  through scoped `MediaStore` Downloads access. On Android 8 and 9, a file
  offer from the Mac ends the session with "Receiving files requires Android 10
  or newer". Sending files from the phone works on every supported version.
- **Find Device looping needs Android 9 (API 28).** On older versions the alarm
  sound is played without looping.
- **Ending or declining a call needs Android 9 (API 28).** With "Call status and
  controls" enabled, Bridgey uses Telecom `endCall()`, which exists from API 28.
  Answer uses `acceptRingingCall()` and is offered from API 26.
- **Call state on Android 12 (API 31) and later.** Bridgey registers a telephony
  call-state callback, reads the CallStyle answer, decline and hang-up intents,
  and uses them to tell ringing, screening and active calls apart. On Android 8
  to 11, the call type sent to the Mac is the type the phone application
  reports in its notification, and no CallStyle fallback actions are added.
  With call status and controls enabled, an idle phone state still removes the
  call from the Mac.
- **Notification permission on Android 13 (API 33) and later.** Bridgey asks for
  `POST_NOTIFICATIONS` only when you tap Enable on the Bridgey notifications
  card. Before Android 13, notifications are allowed by default.
- **Clipboard.** Bridgey never reads the Android clipboard in the background.
  Sending copied text always needs an explicit action: the Clipboard button,
  the Send clipboard action in the connection notification, or the Quick
  Settings tile.
- **Work profiles and secure folders.** Only the main (system) user runs the
  connection. Copies in a managed profile or Samsung Secure Folder show an
  explanation and do not start discovery (see
  [architecture](architecture.md#platform-lifecycle)).

## Android vendors

None of these has a reported result except the OnePlus row above.

| Vendor and skin | Status |
| --- | --- |
| OnePlus, OxygenOS (ColorOS base) | Verified on OnePlus 11 with the build described above |
| Google Pixel and other AOSP-like builds | Unverified |
| Samsung One UI | Unverified |
| Xiaomi HyperOS and MIUI | Unverified |
| Other vendors (Oppo ColorOS, Vivo, Motorola, others) | Unverified |

Phone applications differ between vendors in how they post call
notifications. [#19](https://github.com/singeol/bridgey/issues/19) lists the
call behaviours that still need checks on Samsung and Xiaomi devices.

### Background limits

Bridgey keeps its connection in a visible foreground service and does not use
hidden keep-alive techniques. Some vendors still stop background applications
more aggressively than stock Android. If the connection drops while the phone
is idle:

1. Open Android Settings → Apps → Bridgey → Battery and allow unrestricted or
   background use. The wording differs between vendors and Android versions.
2. If the vendor has an autostart or app launch manager, allow Bridgey there.
3. Keep Bridgey notifications enabled so the foreground connection stays
   visible.

[Don't kill my app!](https://dontkillmyapp.com) collects per-vendor
instructions for these settings. Bridgey does not change them for you.

## macOS versions

| macOS | Status | Notes |
| --- | --- | --- |
| 13 Ventura | Unverified | Lowest supported version |
| 14 Sonoma | Unverified | |
| 15 Sequoia | Unverified | CI builds and tests run on a macOS 15 runner; that is not a device test |
| 26 | Unverified | |
| 27 | Verified with the OnePlus 11 row above | MacBook |

The Mac client has no macOS version checks beyond the minimum of 13. Optional
macOS permissions are the same on every version: Local Network for discovery
and connections, Notifications for mirrored Android notifications, and
Automation for Music or Spotify when media control is enabled.

## Contribute a result

A result is useful whether it passed or failed. Open an issue (or comment on an
existing compatibility issue) with:

1. **Bridgey versions** on both devices. On Android, see Android Settings → Apps
   → Bridgey; on the Mac, Bridgey Settings shows the version. The diagnostics
   export contains both as `appVersion`.
2. **Phone:** maker, model, Android version, and the vendor skin with its build
   number (Android Settings → About phone).
3. **Mac:** model or chip, and the macOS version and build.
4. **What you checked** and what happened. The tables in
   [testing-0.6.md](testing-0.6.md) are a good checklist; name the rows you ran.
5. **Diagnostics** from each device if something failed. See
   [diagnostics.md](diagnostics.md) for how to export them and what they contain.

Do not paste clipboard text, notification content, phone numbers, file names,
signing keys or passwords into a public issue.

A maintainer can then add a row to the device results table in this form:

```text
| <phone> | <skin and build> | <Mac> | <macOS and build> | <Bridgey versions> | <what was checked> | <issue link> |
```
