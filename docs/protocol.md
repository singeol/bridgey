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
`notifications`, `battery`, `find_device`, `ping`, `calls`, `links`, `media`, and `screen`. A UI
action is available only when both peers report the corresponding feature as enabled. Clients
predating this message are treated as enabling the original v1 features for
compatibility; the later `calls`, `ping`, `links`, `media`, and `screen` features are disabled unless
a peer advertises them explicitly.

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

### Screen sharing, phone to Mac (`screen`, experimental)

Status: experimental prototype for the v0.9 roadmap item. View only, and only from the phone to
the Mac. The Mac never sends input to the phone, and Mac to phone sharing is not part of this
version.

**Negotiation and consent.** `features.update` carries the boolean `screen`. It is off by default on
both platforms, uses the normal global and per-device switches, and is disabled for legacy peers.
On Android it means "this phone may share its screen with this Mac"; on the Mac it means "this Mac
shows the screen of this phone". `screen.*` messages are sent only while both the local setting
and the peer's latest map are `true`. Only the phone starts a stream: the person taps Share screen
in Bridgey and then confirms Android's own screen capture (MediaProjection) dialog. That consent is
requested again for every session and is never stored or reused. The Mac cannot ask the phone to
start sharing in this version.

**Encryption and ordering.** Every `screen.*` message is AES-GCM encrypted with the session key and
carries only the outer `kind`, `sessionId`, `nonce` and `ciphertext`. These messages have no
`messageId` and do not enter the shared replay window, because 30 frames a second would push every
other feature's IDs out of it. Instead, each peer numbers the `screen.*` messages it sends in a
session 1, 2, 3, ... (JSON messages and frames alike, across streams, at most 2^53-1), and a
receiver drops any message whose `sequence` is not higher than the last one it accepted from that
peer in the current session. A new session has a new key and starts again at 1, so a message cannot
be replayed into another session either. Malformed, out-of-sequence or undecryptable `screen.*`
messages are dropped, never answered, and never close the transport session.

JSON plaintexts are UTF-8 objects with `version: 1`, the `streamId` (a UUID the phone chooses for
each stream and never reuses) and the sender's `sequence`; `screen.start` is at most 1,024 bytes and
the others at most 512.

| Message | Direction | Fields besides `version`, `streamId`, `sequence` | Meaning |
| --- | --- | --- | --- |
| `screen.start` | phone to Mac | `codec`: `h264`; `width`, `height` (even integers, 16 to 4,096); `fps` (1 to 60); `bitrate` (bits a second, 100,000 to 50,000,000) | a new stream; its first frame is a key frame |
| `screen.started` | Mac to phone | none | the viewer window is open and frames may follow; the phone gives up after 5 seconds without it |
| `screen.frame` | phone to Mac | binary, below | one part of one encoded frame |
| `screen.ack` | Mac to phone | `frame` (1 to 2^32-1), `decodeMs` (0 to 60,000), optional `keyframe`: `true` | the newest frame the Mac has finished with: decoded, or dropped (lost, undecodable, or waiting for a key frame) with `decodeMs` 0; `keyframe` asks for a key frame after a lost or undecodable frame, at most once a second |
| `screen.stats` | phone to Mac | `latencyMs`, `rttMs` (0 to 60,000), `bitrate`, `sentBitrate` (0 to 50,000,000), `fps` (0 to 120), `dropped` (0 to 1,000) | once a second: the phone's latency and round-trip estimates, target and measured bit rate, frames sent and dropped in the last second; shown in the Mac window and doubling as a keepalive on a still screen |
| `screen.stop` | either | `reason` | ends the stream |

| `reason` | Sent by | Meaning |
| --- | --- | --- |
| `user` | phone | the person tapped Stop in the notification or the app |
| `closed` | Mac | the person closed the viewer window or chose Stop |
| `ended` | phone | Android ended the capture (the system Stop, another app capturing) |
| `screen_off` | phone | the phone's screen turned off |
| `disabled` | either | the feature is off on the sending side |
| `invalid` | Mac | the start was malformed or asked for something the Mac cannot decode |
| `timeout` | either | the phone got no `screen.started` within 5 seconds, or no `screen.ack` for 6 seconds while frames were outstanding; the Mac got no frame or `screen.stats` for 6 seconds |
| `failed` | either | the encoder, the capture or the decoder failed |

An unknown `reason` reads as `failed`. A Mac whose setting is off answers `screen.start` with
`screen.stop` `disabled` and drops every other `screen.*` message. When the transport session
closes, both sides end the stream locally and say nothing. There is at most one stream per session;
a new `screen.start` replaces the current stream on the Mac.

**Frames.** The plaintext of `screen.frame` is a 40-byte header followed by 1 to 32,768 bytes of
H.264 Annex B data, so at most 32,808 bytes. Encrypted, base64-encoded and wrapped in the JSON
envelope, a frame message stays below the 65,536-byte frame limit. Integers are big-endian.

| Bytes | Field | Rule |
| --- | --- | --- |
| 0 | version | `1` |
| 1 | flags | bit 0 key frame (IDR), bit 1 the frame starts with its SPS and PPS, bit 2 last part of the frame; other bits are sent as 0 and ignored |
| 2 to 3 | part | 0 to 63, the index of this part within the frame |
| 4 to 19 | stream ID | the 16 bytes of the `streamId` UUID |
| 20 to 27 | sequence | 1 to 2^53-1, the phone's session sequence above |
| 28 to 31 | frame | 1 to 2^32-1, increasing from one frame to the next |
| 32 to 39 | capture time | 0 to 2^63-1 microseconds on the phone's monotonic clock; only differences mean anything |

A frame is one H.264 access unit split into parts sent in order 0, 1, 2, ...; part 0 starts with an
Annex B start code. Every key frame carries the current SPS and PPS at its start, so it can be
decoded on its own and a size change (rotation) needs no separate message. B-frames are not used,
so frames are shown in the order they arrive. The Mac keeps at most one unfinished frame, drops it
when a part of a later frame arrives, and drops frames until the next key frame after a lost or
undecodable one. A frame over 64 parts (2 MiB) is never sent.

**Pacing and backpressure (phone).** The phone sends a frame only while at most 6 frames and at most
max(64 KiB, 250 ms at the current bit rate) are sent and not yet acknowledged. A frame that does not
fit is dropped, along with every following frame up to the next key frame, which the phone asks the
encoder for at once, and the bit rate is lowered. When nothing is outstanding, the next key frame is
always sent whatever its size, so a stream cannot stall. The Mac acknowledges every complete frame,
including frames it loses or drops while waiting for a key frame, so the count drains. This keeps at most
about a quarter of a second of video queued in the socket: clipboard, notifications, file chunks and
acknowledgements wait behind one 32 KiB part at most plus that small queue. The bit rate goes down
by a quarter when frames are dropped or the round trip grows 80 ms over its minimum (at most every
500 ms), and up by 15 percent after 2 seconds without either, between 300 kb/s and 8 Mb/s.

**Why the existing session.** Frames travel on the same encrypted, newline-delimited TCP session as
every other feature. That needs no second listener, port, handshake or authentication path: the
session key already encrypts the frames and the pinned peer identity already authenticates them. The
costs are about a third more bytes for base64 and JSON, and that a part being written delays a
message queued behind it, which the pacing above keeps short. A future binary or datagram transport
could carry the same frame format unchanged, because the header holds everything a receiver needs.

**Security review.**

| Threat | Mitigations |
| --- | --- |
| The Mac watches the phone without the person knowing | Off by default on both devices, with per-device switches. Every session needs a tap in Bridgey and Android's own consent dialog, every time. While a stream runs the phone shows an ongoing notification naming the Mac with a Stop action (Bridgey asks for notification permission before the first share), and the Mac shows the viewer window with a Live label and Stop. The phone stops when its screen turns off |
| Sensitive content on the screen | Android shows black for secure windows (`FLAG_SECURE`), and recent Android versions hide sensitive notification content during capture. The Mac decodes in memory and shows frames at once; it never saves, records or forwards them, and logs carry only stream ID prefixes, sizes, rates and stop reasons |
| Another device on the network watches or injects frames | Frames are AES-GCM encrypted with the key of the authenticated session with the pinned peer. The session sequence rejects replays and reordering within a session, the session key rejects them across sessions, and the stream ID in each header keeps parts of different streams apart |
| A flood of frames or oversized frames | Parts are at most 32 KiB, frames at most 64 parts, and the Mac keeps one unfinished frame. Frames go only to a Mac that advertises `screen`; a Mac whose setting is off decrypts nothing beyond the start it refuses. The Mac ends a stream with no traffic for 6 seconds |
| A paired Mac turns into a remote control | No `screen.*` message causes input, opens an app or reads anything on the phone besides the frames the person agreed to share. Control is out of scope and would need its own review |
| Sharing left running unnoticed | The notification stays until the stream ends. Stop on either side, the screen turning off, a disconnect, or turning the feature off on either device ends it |

## Compatibility

Adding optional fields or message types is backward compatible. Changing field
meaning, authentication, or required behavior requires a new capability or
major version. JSON is a codec behind the envelope model; a future protobuf
codec must preserve IDs, types, correlation, and negotiated semantics.
