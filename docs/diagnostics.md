# Diagnostics

Bridgey has two sources of troubleshooting data: a diagnostics export that you
create from Settings, and the system log. Neither leaves your device unless you
share it. Bridgey has no telemetry.

## Export diagnostics

### Android

1. Open Bridgey and tap **Settings**.
2. Scroll to **Diagnostics** and tap **Share diagnostics file**.
3. Choose where to send `Bridgey-Diagnostics.json` in the Android share sheet,
   for example a file manager, an email draft, or a chat with yourself.

The file is written to Bridgey's private cache directory and shared through
Bridgey's `FileProvider` with read permission for the receiving application
only.

### macOS

1. Click the Bridgey menu-bar icon and choose **Settings**.
2. In the **Diagnostics** section, click **Export Diagnostics…**.
3. Choose a location in the save dialog. The default name is
   `Bridgey-Diagnostics.json`.

Export one file from each device when you report a problem between them.

## What the file contains

The export is a JSON object with `schemaVersion` 1:

| Field | Android | macOS | Meaning |
| --- | --- | --- | --- |
| `schemaVersion` | yes | yes | Report format version, currently `1` |
| `generatedAt` | yes | yes | Export time, ISO 8601 in UTC |
| `appVersion` | yes | yes | Bridgey version name, such as `0.6.0-rc.2` |
| `platform` | yes | yes | `android` or `macos` |
| `osApi` | yes | no | Android API level, such as `34` |
| `osVersion` | no | yes | macOS version string, such as `Version 15.5 (Build 24F74)` |
| `connectionState` | yes | yes | `idle`, `connecting`, `verification`, `connected` or `failed` |
| `activeTransferCount` | yes | yes | Number of file transfers in progress |
| `historyCount` | yes | yes | Number of finished, failed or interrupted transfers still listed |
| `localFeatures` | yes | yes | Each feature key with this device's global switch (`true` or `false`) |
| `remoteFeatures` | yes | yes | Each feature key with the state the connected peer last reported |
| `events` | yes | yes | Recent events, oldest first |

Feature keys are `clipboard`, `files`, `notifications`, `battery`,
`find_device`, `ping`, `links`, `media` and `calls`. `localFeatures` shows the
global switches only, not per-device overrides. When no peer is connected,
`remoteFeatures` shows the defaults Bridgey assumes for a peer that has not
reported its state yet, so `ping`, `links`, `media` and `calls` appear as
`false`.

Each event has four string fields:

```json
{
  "timestamp": "2026-10-28T09:41:12Z",
  "category": "transport",
  "event": "disconnected",
  "outcome": "reconnecting"
}
```

### Limits and retention

- Each client keeps at most 200 events. When a new event arrives, the oldest is
  dropped.
- `category`, `event` and `outcome` are normalized before they are stored:
  lowercased, every character other than ASCII letters, digits, `_`, `.` and
  `-` replaced with `_`, and cut to 64 characters.
- Events are kept in memory only. Quitting the Mac app or stopping the Android
  process clears them. Nothing is written to disk until you export.
- The export is a snapshot. It is not updated after it is written.

### What it never contains

Every value in the report is a fixed string chosen in the source code, a
count, a version, or a feature switch. The report never contains clipboard
text, notification titles or text, reply text, phone numbers, file names,
device names, network addresses, or device, session or transfer identifiers.
`BridgeyDiagnosticsTest` (Android) and `BridgeyDiagnosticsTests` (macOS) check
the event bounds and normalization, and that file names and transfer
identifiers stay out of the report.

The file still tells a reader your app and OS versions, which features you
use, and when events happened. Share it only where you are comfortable with
that.

## Reading events

Only the events below are recorded. The two clients record slightly different
sets.

| Category | Event | Outcome | Recorded on | Meaning |
| --- | --- | --- | --- | --- |
| `transport` | `listener_started` | `ok` | both | The local listener for incoming connections started |
| `transport` | `disconnected` | `reconnecting` | both | An established session ended; Bridgey will look for the peer again |
| `transport` | `local_network_denied` | `permission_required` | macOS | macOS refused the connection because Local Network access is off |
| `pairing` | `connection_started` | `ok` | both | A connection or pairing attempt to a discovered device started |
| `pairing` | `connected` | `ok` | both | The session was verified and is connected |
| `protocol` | `session_failed` | `rejected` | Android | The session was closed because of an error, such as an invalid or unexpected message or a file that could not be received. The on-screen error says which |
| `protocol` | `message_rejected` | `session_closed` | macOS | A message could not be handled and the session was closed |
| `transfer` | `send_started` | `ok` | both | An outgoing file transfer started |
| `transfer` | `send_completed` | `ok` | Android | An outgoing file transfer finished |
| `transfer` | `cancelled` | `ok` | Android | A file transfer was cancelled |
| `transfer` | `retry_started` | `ok` | both | A failed or interrupted outgoing transfer was retried |
| `transfer` | `interrupted` | `retry_available` | both | The session ended while a transfer was active |
| `clipboard` | `text_received` | `ok` | both | Plain-text clipboard content was received and applied |
| `clipboard` | `rich_received` | `ok` | both | Clipboard content with HTML was received and applied |
| `notification` | `dismiss_sent` | `ok` | macOS | A mirrored notification was dismissed on the Mac and the phone was told |
| `notification` | `dismiss_requested` | `ok` | Android | The Mac asked the phone to dismiss a notification |
| `notification` | `action_sent`, `reply_sent` | `ok` | macOS | A notification action or inline reply was sent to the phone |
| `notification` | `action_requested`, `reply_requested` | `ok` | Android | The Mac asked the phone to run a notification action or send a reply |
| `notification` | `removed_remotely` | `ok` | macOS | The phone removed a notification and the Mac removed its copy |
| `calls` | `request` | `started`, `confirmation_required` or `rejected` | Android | The Mac asked the phone to call a number. `started` means the call was placed directly, `confirmation_required` means a notification asks you to confirm in the dialer, `rejected` means it was refused |

Common patterns:

- `transport disconnected` without a later `pairing connected` means the
  devices did not find each other again. Check that both are on the same
  network and that Bridgey is running on both.
- `transport local_network_denied` on the Mac means Local Network access must be
  enabled in System Settings → Privacy & Security → Local Network.
- `transfer interrupted` followed by nothing means the transfer can be retried
  from the transfer list after the devices reconnect.
- `protocol session_failed` or `protocol message_rejected` right after an
  action points to a malformed message or a client version mismatch. Include
  the versions of both clients in your report.

## System logs

Both clients write short log lines prefixed with a category: `DISCOVERY`,
`PAIRING`, `TRANSPORT` or `PLUGIN`. They describe state changes, such as
`TRANSPORT disconnected` or `PLUGIN clipboard received`.

Log lines never contain clipboard text, notification titles or text, reply
text, verification codes or keys. They can contain device names, the package
names of Android applications whose notifications are forwarded, the names of
transferred files, battery levels, error descriptions, and the first eight
characters of device and transfer identifiers. Read a log before you post it
and remove anything you do not want to share.

### Android

All Android log lines use the tag `Bridgey`. With USB debugging enabled and the
phone connected:

```bash
adb logcat -s Bridgey
```

Add `-d` to print the current buffer and exit, for example
`adb logcat -d -s Bridgey > bridgey-android.log`.

### macOS

The Mac app logs through the unified logging system. Its executable, and so its
process name, is `BridgeyMac`. Stream live lines in Terminal:

```bash
log stream --style compact --predicate 'process == "BridgeyMac"'
```

To collect the last hour:

```bash
log show --last 1h --style compact --predicate 'process == "BridgeyMac"' > bridgey-mac.log
```

In the Console app, start streaming and filter by the process `BridgeyMac`.

## Attach diagnostics to an issue safely

1. Export diagnostics from each device involved and note the time the problem
   happened.
2. Open each file in a text editor and read it. It is short and plain JSON.
3. If you also collected logs, keep only the lines around the problem and
   remove device names or file names you do not want to publish.
4. Attach the files to the GitHub issue, together with the client versions, OS
   versions, what you did and what you saw.
5. Never paste clipboard text, notification content, phone numbers, signing
   keys or passwords into a public issue. Report security problems privately as
   described in [SECURITY.md](../SECURITY.md).
