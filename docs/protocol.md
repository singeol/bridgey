# Bridgey Protocol v1

Status: mixed implementation reference and future design draft. The native
clients currently use newline-delimited JSON over TCP, **not TLS/WebSockets**.
The proposed WebSocket envelope and `core.hello` negotiation below are future
design, not the wire format shipped in 0.5/0.6. JSON is UTF-8 encoded.

## Current native transport (0.5/0.6)

`PairingMessage` / Android `Message` use `kind`, `sessionId`, optional
`messageId`, and message-specific fields. Encrypted payloads use base64 `nonce`
and `ciphertext` (AES-GCM ciphertext followed by its authentication tag).
Native frames have a 65,536-byte limit. The session ID salts HKDF-SHA-256 for
ephemeral P-256 ECDH; the shared info is `bridgey-pairing-v1`. The displayed code
is derived from the first four key bytes modulo 1,000,000. Confirmation HMACs
bind identity keys; P-256 signatures bind session ID, ordered device IDs and
ephemeral keys. Trusted reconnects verify the stored peer signing identity.

Payload encryption is application-layer protection, not TLS. Routing headers
and some acknowledgements remain outside the encrypted payload; traffic
metadata is visible. Replay caches are bounded and session-local, not persistent.
The future transport design must not be advertised as an implemented guarantee.

## Discovery

Browse and publish DNS-SD service type `_bridgey._tcp.local.`. TXT values are
UTF-8 and advisory:

| Key | Meaning | Limit |
| --- | --- | --- |
| `id` | Stable random UUID, used only as a hint before authentication | 36 bytes |
| `name` | User-visible device name | 64 bytes |
| `version` | Highest supported envelope major version | 8 bytes |
| `platform` | `android`, `macos`, or a future identifier | 16 bytes |

The SRV port identifies the TLS WebSocket listener. TXT, hostnames, addresses,
and ports are attacker-controlled until the peer authenticates. Implementations
must deduplicate discoveries by service instance and refresh endpoints on every
network change.

## Framing and envelope

After the TLS WebSocket opens, each text frame contains exactly one JSON object:

```json
{
  "version": 1,
  "id": "018f5228-76c7-7b37-a42c-3cfe6f78219a",
  "type": "clipboard.update",
  "timestamp": 1786550000000,
  "replyTo": null,
  "expectsReply": false,
  "payload": {}
}
```

`id` is unique per sender (UUIDv7 recommended). `timestamp` is Unix epoch
milliseconds and is used for diagnostics/expiry, not message ordering. `replyTo`
correlates a response or acknowledgement. Receivers keep a bounded, persistent
window of recently accepted IDs per peer; a repeated ID is acknowledged if
needed but its side effect is not executed again.

Limits before negotiation for the planned capability envelope are 256 KiB per
JSON frame, depth 32, string length 128 KiB, and 1,024 keys. The current native
newline transport intentionally applies a stricter 65,536-byte frame limit
before UTF-8/JSON decoding. A malformed, oversized, or unterminated frame closes
the session before plugin dispatch. File chunks remain bounded independently.

## Session negotiation

The first authenticated application message is `core.hello`:

```json
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceName": "Semyon's MacBook Pro",
  "platform": "macos",
  "protocolVersions": [1],
  "capabilities": ["battery.send.v1", "clipboard.v1", "files.v1", "notifications.receive.v1"]
}
```

The selected version is the highest intersection. Capability negotiation is the
set intersection; absence means disabled. Device ID must match the identity
bound to the pinned key. No common version closes the connection with
`unsupported_version`.

Core types are `core.hello`, `core.ack`, `core.error`, `core.ping`, and
`core.pong`. An acknowledgement payload contains `status` (`accepted`,
`completed`, or `rejected`). Errors contain a stable `code`, safe `message`, and
optional details. Error text must not disclose secrets.

The current native clients also exchange an authenticated, encrypted
`features.update` message after pairing and whenever local policy changes. Its
version 1 payload contains a complete boolean map for `clipboard`, `files`,
`notifications`, `battery`, `find_device`, `ping`, `calls`, `links`, `media`, `folders`, and
`files_browse`. A UI action is
available only when both peers report the corresponding feature as enabled. Clients
predating this message are treated as enabling the original v1 features for
compatibility; the later `calls`, `ping`, `links`, `media`, `folders`, and `files_browse` features are disabled unless a peer
advertises them explicitly. A receiver ignores keys it does not know.

## Pairing flow

Pairing runs only after a user selects a discovered peer:

1. Both peers create ephemeral P-256 ECDH key pairs and exchange
   `pairing.offer`/`pairing.answer` containing nonces and public keys.
2. Each validates all fields and derives the same secret with platform crypto.
3. HKDF-SHA-256 derives independent verification and session keys, binding the
   ordered public keys, nonces, device IDs, and protocol version into `info`.
4. A six-digit code derived from the verification key is displayed on both
   devices. It is never sent over the network.
5. Each user explicitly confirms; peers exchange authenticated
   `pairing.confirm` records containing their long-term public keys.
6. Trust is stored only after both confirmations verify. Any timeout, mismatch,
   rejection, or disconnect erases ephemeral state.

Pairing offers expire after two minutes and cannot be silently retried. The
short code is a human MITM check, not a password. Detailed primitive and storage
requirements are in `SECURITY.md`.

## Plugin messages

### Quick actions (native 0.6)

`quick.request`, `quick.result` and `media.state` are AES-GCM-encrypted payloads
accepted only in the current connected session. Requests/results are limited
to 8 KiB plaintext; media state to 32 KiB. The normal 65,536-byte frame bound
still applies. Unknown versions, malformed values and stale sessions are ignored.

Requests contain `version: 1`, UUID `requestId`, `feature`, `action`, string
`value`, and positive integer `sequence` (at most 2^53−1). Receivers retain a
per-feature sequence high-water mark for the entire session, independent of
the outer message ID/replay cache; old encrypted requests cannot re-execute by
changing that ID. Sender sequences increase and requests are not auto-retried.
An authenticated result echoes the inner requestId and feature with boolean
`accepted`. Pending operations time out after eight seconds and are cleared
on disconnect or feature disable. Timeout means “not confirmed”, not an assertion
that an operation definitely never reached the other peer.

- `links` / `offer`: 4,096 UTF-8 bytes maximum, HTTP(S) only, nonempty host,
  no user credentials, whitespace/control characters or backslashes, valid
  optional port. One pending received link per device; a second is declined.
  Acceptance means queued for explicit local Open/Dismiss, **not** opened.
- `media`: Android → Mac, only `toggle`, `pause`, `next`, `previous`, `seek`
  (integer seconds 0…604800), `volume` (integer 0…100). The Mac must locally
  opt in and select running Music or Spotify. Commands map to fixed player
  AppleScript instructions, never received source code or a remote shell.
  Automation failures and a busy player are reported as rejected. Accepted
  means the player command completed, not that a particular track must exist.
- `media.state`: Mac → Android; `version`, `player`, `title`/`artist` (256
  characters each), boolean `playing`, integer `position`/`duration`
  (0…604800), `volume` (0…100), bounded `detail`. Optional base64 `artwork`
  contains at most 16 KiB JPEG, at most 128×128 pixels. The receiver validates
  both encoded size and decoded dimensions before allocating a bitmap.

Music artwork comes from player automation, not filesystem browsing; it is
optional and may be unavailable. Spotify artwork is not downloaded. Metadata
refreshes after commands/settings changes and on the ten-second heartbeat.

### Battery (`battery.send.v1`)

Either battery-powered peer sends `battery.update` after a secure session is
established and when its battery state changes. Android uses system battery
broadcasts; macOS checks its public IOKit power-source state on connection and
while the heartbeat is active:

```json
{
  "level": 79,
  "isCharging": true
}
```

`level` is an integer from 0 through 100. Receivers reject out-of-range or
malformed values. Battery updates contain no device identifier because the
authenticated session already binds them to the paired sender.

### Clipboard (`clipboard.v1`)

`clipboard.update` remains the compatible plain-text form: its decrypted payload
is UTF-8 text. `clipboard.rich` carries versioned UTF-8 JSON:

```json
{
  "version": 1,
  "text": "example",
  "html": "<p><strong>example</strong></p>"
}
```

The combined text and HTML content is limited to 32 KiB before encryption. The
text field is mandatory and is always written as a fallback; supported clients
also write the HTML representation. Receivers reject malformed, unsupported,
empty, or oversized payloads. Each encrypted update has a unique message ID and
the current transport replies with `clipboard.ack` after the write. If local
policy disabled clipboard handling while a peer still had stale capability
state, it replies with `clipboard.rejected` and immediately resends the
encrypted `features.update`; senders time out rather than displaying an
unbounded sending state.

### Files (`files.v1`)

`files.offer` carries transfer ID, display filename, MIME type, unsigned byte
size, and SHA-256. `files.accept` selects a binary stream; `files.progress` is
advisory; `files.cancel` is idempotent; `files.complete` confirms the final hash.
Paths from a sender are never accepted. Implementations stream through bounded
buffers, enforce negotiated size limits, and delete or clearly mark partials.

The current JSON transport streams encrypted 24 KiB chunks rather than a raw
binary substream. An encrypted `files.offer` contains `transferId`, `name`,
`mimeType`, `size`, and the base64-encoded SHA-256. After `files.accept`, each
`files.chunk` carries the transfer ID, a monotonically increasing sequence
number, and an independently AES-GCM-encrypted chunk. An encrypted
`files.complete` repeats the transfer ID and hash; the receiver replies with
`files.complete.ack` only after byte count and hash verification and the atomic
rename of the partial file. The macOS v1 receiver saves collision-safe names in
a user-selected directory (default `~/Downloads/Bridgey`) and removes partial
files when a transfer is interrupted.

Sending several files does not add messages. The sender keeps a local queue and
offers one file at a time; the next `files.offer` follows only after the
previous transfer completes, fails, or is cancelled. Each queued file has its
own transfer ID, so cancelling or retrying one file does not affect the others.
Waiting files never reach the receiver, which therefore applies its limits and
feature policy to every file separately.

Receivers resolve name conflicts deterministically and never overwrite an
existing file. macOS uses Finder-style numbering (`photo.jpg`, `photo 2.jpg`,
`photo 3.jpg`) and also treats a name as taken while its `.bridgey-part` file
exists. Android lets `MediaStore` choose a unique name in `Download/Bridgey`,
which appends a number such as `photo (1).jpg`.

Version 1 does not resume a partial file stream. If a connection is interrupted,
the receiver deletes its pending partial file and both clients mark the transfer
as interrupted. A user-initiated retry creates a new transfer identifier,
recomputes the digest, and sends the file again from byte zero.

The Android v1 receiver writes through `MediaStore` to `Download/Bridgey` with
`IS_PENDING` set until verification, so partial files are hidden and deleted on
failure without requiring broad storage access.
Either peer can send `files.cancel` with the transfer ID. The sender stops
reading and sending chunks, while the receiver closes and deletes its partial
file. Cancellation is idempotent and leaves the authenticated session usable.
For Mac-to-Android transfers, Android sends cumulative `files.chunk.ack`
messages and macOS keeps at most 64 chunks (1.5 MiB) unacknowledged. This bounds
memory and cancellation latency without limiting throughput to one network
round trip per chunk.
An offer repeats its transfer ID in the outer session message so a receiver
whose local file policy is disabled can return `files.rejected` without
decrypting or accepting the offer. It then resends `features.update` to repair
stale UI state on the sender.

### Folders (`folders`)

A folder transfer sends a directory tree as one unit and reuses the file
stream above for its contents. It is offered only while both peers report
`files` and `folders`. The messages are direction-neutral; the current clients
send folders from macOS to Android.

Folder payloads are AES-GCM encrypted, carry `version: 1` and a lowercase UUID
`folderId`, and are accepted only in the connected session after the replay
check. Plaintext is limited to 32 KiB. `folders.offer`, `folders.rejected`,
`folders.cancel` and `folders.cancel.ack` repeat the folder ID in the outer
`transferId`. As with files, a receiver whose local policy disables files or
folders answers an outer-only `folders.rejected` without decrypting the offer
and resends `features.update`; `folders.cancel` and its acknowledgement are
outer-only like `files.cancel`.

1. The sender sends `folders.offer` `{version, folderId, name, fileCount,
   folderCount, totalSize, pageCount}`, then `pageCount` (1 to 64)
   `folders.manifest` pages `{version, folderId, page, entries}` in order
   without waiting. A page holds at most 256 entries, each `{type: "dir",
   path}` or `{type: "file", path, size}`. An entry's index is its position
   across all pages. Senders list a directory before its contents.
2. The receiver validates the complete manifest before writing anything:
   `name` and every path component are 1 to 255 UTF-8 bytes, are not `.` or
   `..`, and contain no `/`, NUL or other control character (U+0000 to U+001F,
   U+007F); paths are relative, joined with `/`, at most 32 components and
   1,024 UTF-8 bytes; every entry's parent is the folder itself or a `dir`
   entry listed earlier; no path repeats; there are 1 to 10,000 files and at
   most 2,000 folders, each file is at most 10 GB, at most 1 TiB in total, and
   the counts and total match the offer. Only one incoming folder is active per
   session.
3. Within 15 seconds of the last page the receiver answers `folders.accept`
   `{version, folderId, name}` with the local folder name, or an encrypted
   `folders.rejected` `{version, folderId, code}` with `policy`, `unsupported`,
   `busy`, `invalid_manifest`, `limit_exceeded`, `insufficient_space` or
   `io_error`. A rejection leaves the session open; pages for a folder that is
   not being collected are ignored.
4. Each file entry is then sent as a normal file transfer, one at a time in
   manifest order. The encrypted `files.offer` adds `folderId` and `entry`. The
   receiver accepts it only while that folder is active, when `entry` is a
   `file` entry after the previous one, `name` equals the entry's last path
   component and `size` equals its manifest size. A sender may skip a file it
   cannot read. Paths are never taken from `files.offer` itself.
5. The sender ends with `folders.complete` `{version, folderId, sentFiles}`.
   The receiver answers `folders.result` `{version, folderId, savedFiles}` and
   forgets the folder.

Either peer may send `folders.cancel`; the sender also cancels the file in
progress with `files.cancel`. Files already saved stay and the partial file is
deleted. A disconnect ends the folder transfer. Version 1 does not resume: a
retry sends the whole folder again as a new folder.

Conflict handling is deterministic and never overwrites or merges. A received
folder is always saved as a new folder in the receive directory; when its name
is taken, the receiver uses the first free numbered name (`Photos (1)`).
Inside the new folder each name is made safe for local storage (NFC, `"`, `*`,
`:`, `<`, `>`, `?`, `\`, `|` and control characters become `_`, leading spaces
and trailing spaces and dots are removed, at most 200 UTF-8 bytes with the
extension kept). When two siblings then match ignoring case, the later one in
manifest order gets the first free number (`photo (1).jpg`, `Album (1)`).

Android saves received folders through `MediaStore` under
`Download/Bridgey/<folder>/` with `IS_PENDING` set on each file until it is
verified, so no storage permission is needed. MediaStore stores files only, so
empty folders are not created on Android. The macOS sender skips hidden items,
symbolic links, special files and partial `.bridgey-part` files, and anything
outside the limits above, and does not send a folder without files.

### Browsing shared folders (`files_browse`)

The Android user can share folders with paired devices by granting them
through the system Storage Access Framework folder picker (a persisted
`ACTION_OPEN_DOCUMENT_TREE` grant). Bridgey requests no broad storage
permission and can see nothing outside those trees. A peer may browse the
shared folders, download files from them and, when the Android user allows it,
upload files into them. The phone serves the requests below while both peers
report `files` and `files_browse`; the current macOS client browses and serves
nothing.

Requests and answers are AES-GCM encrypted, accepted only in the connected
session after the replay check, carry `version: 1` and a lowercase UUID
`requestId`, and are limited to 8 KiB of plaintext (answers 32 KiB). The asker
gives up after 8 seconds. The phone answers at most 4 requests at a time and
20 per 10 seconds, upload checks included; others are answered `busy`. Failures answer `browse.error`
`{version, requestId, code}` with `policy`, `busy`, `invalid_request`,
`not_found`, `unavailable` (the grant was revoked or the storage removed),
`read_only` or `io_error`. Names, sizes and dates stay inside the encrypted
session and are not logged. Content URIs, document IDs and file system paths
never cross the wire.

A location is a `rootId` and a `path`. `rootId` is a random identifier the
phone created when the folder was shared. `path` is `""` for the shared
folder itself or names joined with `/` under the folder rules above (each
name 1 to 255 UTF-8 bytes, not `.` or `..`, no `/`, NUL or control
characters; at most 32 names and 1,024 bytes). The phone resolves each name by
an exact match among the children of the previous folder, so a request can
never leave the shared tree. Hidden items (names starting with `.`) are not
listed and cannot be resolved.

- `browse.roots` `{version, requestId}` answers `browse.roots.result`
  `{version, requestId, roots, uploads}`: at most 16 `{rootId, name}` entries
  and whether uploads are allowed.
- `browse.list` `{version, requestId, rootId, path, offset}` answers
  `browse.list.result` `{version, requestId, rootId, path, offset, total,
  entries, nextOffset}`. Entries are `{name, dir, size, modified}` (`size` and
  `modified`, Unix milliseconds, are omitted when unknown or for folders),
  folders first, then files, by name ignoring case. A page holds at most 200
  entries and stays within the answer limit; `nextOffset` is omitted on the
  last page.
- `browse.download` `{version, requestId, rootId, path}` names a file. The
  phone answers `browse.download.result` `{version, requestId, transferId}`
  and queues the file as a normal outgoing transfer with that transfer ID, so
  the receiver applies its usual policy, verification and naming. Folders are
  not downloaded in version 1.
- Upload: the asker sends a normal file transfer whose encrypted `files.offer`
  adds `browseRootId` and `browsePath` (a folder). The phone accepts it only
  while uploads are allowed (a local switch, off by default) and the folder
  resolves; otherwise it answers `browse.error` with the transfer ID as
  `requestId` and does not accept the offer. A leading `.` in the name becomes
  `_`, and the phone picks the first free name ignoring case
  (`photo (1).jpg`), so nothing is overwritten or hidden. It writes the
  file through the document provider, and deletes it if verification fails or
  the transfer is interrupted.

### Notifications (`notifications.send.v1`, `notifications.dismiss.v1`)

`notifications.post` carries package, application name, opaque notification ID,
title, text, timestamp, and an optional size-bounded base64 PNG icon. Content is
sensitive and must not be logged. `source=bridgey` messages are never forwarded.
`notifications.dismiss` carries the opaque notification ID back to Android when
the user dismisses its mirrored macOS notification. `notifications.remove`
carries the same ID to macOS when the original notification disappears on
Android. Both reference payloads are encrypted, replay-protected, limited to a
512-character opaque ID, and valid only for the authenticated device session.
Clients that do not recognize these messages ignore them. Future
`notification-actions.v1` messages reference the same opaque ID and a separately
scoped, 64-character action token. A `notifications.post` may contain up to four
actions with a 64-character title and `allowsReply` flag. macOS returns
`notifications.action` with the notification ID, action token, and an optional
reply of at most 4,096 characters. Android executes only tokens retained for the
still-active source notification; arbitrary `PendingIntent` data never crosses
the transport.

Per-application forwarding filters are enforced on Android before payload
construction and are intentionally local settings rather than protocol state.
The optional macOS notification history is also local-only, off by default,
bounded to 200 items and seven days, and never synchronized to a peer.

In the current JSON transport, the notification payload is AES-GCM encrypted
and contains `packageName`, `applicationName`, `notificationId`, `title`, `text`,
and the Android post time in Unix milliseconds. Android excludes Bridgey's own
foreground notification, ongoing items, group summaries, secret notifications,
and empty content before encryption. A `CATEGORY_CALL` notification may include
`callType` with one of `incoming`, `ongoing`, `screening`, or `unknown`; calls
are the only ongoing notifications forwarded. Call controls reuse the same
scoped notification action tokens and therefore exist only when the Android
phone application supplies the corresponding `PendingIntent`.

### Find device (`find-device.v1`)

Either connected peer can send an encrypted `find.start` payload containing an
opaque `alertId`. The receiver plays a repeating local alert until either user
stops it. An encrypted `find.stop` with the same logical alert scope stops the
local sound. The receiver answers with encrypted `find.started` or
`find.stopped`, and only that acknowledgement changes the sender's displayed
state. All messages use unique message IDs and are accepted only inside an
authenticated paired session.

Android presents a separate ongoing find-device notification with a Stop
action while its alert is active. Stopping from that action, from either app,
or disconnecting stops the local alert; find-device state is never persisted
across sessions.

### Ping (`ping.v1`)

`ping.request` is a lightweight, one-shot presence alert and is intentionally
separate from Find Device. It carries an encrypted `{ "version": 1 }` payload,
uses a unique replay-protected message ID, and causes the receiving device to
play one short system sound. Android also shows a short local toast. The
receiver returns `ping.ack` with the request message ID, so the sender reports
delivery or a bounded five-second timeout instead of leaving an indefinite
status. Ping never starts a repeating sound and has no state to restore after a
disconnect.

### Calls from Mac (`calls.v1`)

macOS sends `calls.request` only when both peers advertise the `calls` feature.
Its AES-GCM encrypted JSON payload contains a single `number` string. The
receiver accepts only 3–15 decimal digits with an optional leading `+`; spaces,
parentheses, dots, and hyphens may be present for display formatting and are
removed before use. Letters, extensions, USSD strings, and other dial commands
are rejected. Each request has a unique replay-protected message ID and Android
accepts at most one request every three seconds.

Android answers with the same message ID using `calls.started`,
`calls.confirmation_required`, or `calls.rejected`. Confirmation mode is the
default and posts a local notification whose explicit action opens the system
dialer with `ACTION_DIAL`. Direct mode is a separate local opt-in that requires
`CALL_PHONE`; the same local call-integration opt-in requests
`READ_PHONE_STATE` and `ANSWER_PHONE_CALLS` to identify call state and execute
Answer, Decline, and Hang Up commands sent through authenticated notification
action tokens. Bridgey uses the platform telecom service and never starts a
number the platform identifies as an emergency number. It does not request
contacts or call-log access. Local feature policy is checked again immediately
before any side effect. Number content is not logged.

## Compatibility

Adding optional fields or message types is backward compatible. Changing field
meaning, authentication, or required behavior requires a new capability or
major version. JSON is a codec behind the envelope model; a future protobuf
codec must preserve IDs, types, correlation, and negotiated semantics.
