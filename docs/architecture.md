# Architecture

## Decisions

**Implementation note (0.6):** the WebSocket/TLS transport and generalized plugin
boundaries below describe the target architecture. Current clients use native
TCP sockets with newline JSON and application-layer AES-GCM feature payloads;
the coordinator owns routing. Refer to the current-wire section of
`docs/protocol.md` for implemented behavior, not the planned transport below.

Bridgey is a monorepo with native clients and a platform-neutral protocol. The
first transport is a persistent WebSocket over TLS; mDNS only supplies changing
LAN endpoints. Plugin APIs depend on a small transport abstraction so QUIC can
be introduced later without changing plugin message contracts.

```text
Native UI / lifecycle
        |
Connection manager ---- Device registry / secure storage
        |                         |
Protocol router          Pairing + identity
        |
Transport interface ----- WebSocket/TLS implementation
        |
Plugin registry
  |-- clipboard
  |-- files
  |-- notifications
  `-- calls
```

## Core boundaries

### Discovery

Publishes and browses `_bridgey._tcp.local.`. TXT records contain only discovery
hints: `id`, `name`, `version`, and `platform`; the SRV record supplies the port.
Records are size-bounded when parsed. Discovery does not establish identity,
trust capabilities, or automatically pair. A paired peer is recognized only
after the transport proves possession of its pinned private key.

### Device registry

Owns the stable local device ID and paired peer records. A peer record contains
the public-key fingerprint, display name chosen by the user, negotiated protocol
version, granted plugin permissions, and key metadata. Android stores private
key material in Android Keystore; macOS stores it in Keychain. Discovery caches
are separate, ephemeral data.

### Pairing

Runs a mutually confirmed state machine: invitation, ephemeral ECDH exchange,
transcript-derived six-digit verification code, confirmation on both devices,
and durable trust. Pairing details and abort paths are in `docs/protocol.md`.

### Transport

Exposes `connect`, `disconnect`, `send(envelope)`, and an incoming-envelope
stream. The WebSocket/TLS implementation owns ping/pong, timeouts, reconnect,
certificate pinning, bounded queues, and network-path monitoring. Exactly-once
delivery is not promised; message IDs and acknowledgements provide idempotency.

### Protocol router

Validates envelope size/version/time, rejects duplicates, correlates responses,
and routes `type` prefixes to plugins. It never decodes plugin-specific payloads.

### Plugins

A plugin registers a capability string, message type prefix, lifecycle hooks,
and handler. Plugins are independently enabled per paired device. Unknown
capabilities and message types are ignored or answered with `unsupported_type`.

The current clients persist a global feature policy plus per-device overrides.
The global switch always takes precedence. After authentication, peers exchange
their effective feature state through an encrypted `features.update` message.
A feature is exposed only when both peers enable it; UI actions and dependent
status such as battery data update immediately. Incoming side effects still
check local policy independently. Active accepted file transfers are allowed to
complete so changing a setting cannot strand a partial transfer.

## Connection lifecycle

```text
idle -> discovering -> connecting -> authenticating -> connected
  ^          |             |               |              |
  `----------+-------------+---------------+--- backoff ---'
```

Network-path changes and wake events restart discovery because peer IPs are not
stable. One deterministic dialer is selected by lexicographically comparing the
authenticated device IDs; the lower ID initiates, while either side may accept.
Duplicate connections are closed after authentication. Backoff uses full jitter
with a 1 second base and 60 second cap, and resets after 30 seconds of stability.

## Multiple devices

A client keeps a session registry instead of a single session. Sessions that
are still handshaking or waiting for a pairing code are pending; an
authenticated session is stored under the peer's device ID, at most one per
device. Each session keeps its own key, replay window, quick-request sequence,
handshake timeout and heartbeat, so nothing crosses from one device to another.

When a second session authenticates for a device that already has one, the
duplicate rule decides which survives: keep the session dialled by the device
with the lower ID; if both were dialled by the same side, keep the newer one.
Both ends apply the same rule, so they close the same socket. A new connection
never closes an existing session before it authenticates.

Only one pairing code is shown at a time. A pairing the user starts replaces an
earlier one the user started; an unknown device that asks to pair while another
code is waiting for confirmation receives `pairing.cancel`. Trusted reconnects
skip the code and never disturb the pairing in progress or other devices.

Per-device state (the peer's `features.update`, battery, ringing, reconnect
backoff and endpoint) lives with the device's registry entry. Messages are
routed by these rules:

- **To every connected device where the feature is available:** the local
  feature state (each device gets its own effective per-device settings),
  battery, Mac media state, and Android notifications and their removals.
- **To the session a request came from:** acknowledgements, results, browse
  answers, notification actions, and both sides of an incoming transfer.
- **To the selected device:** actions the user starts (clipboard, files and
  folders, browsing, ping, Find device, calls, links). The UI lets the user
  pick the device; until then it is the most recently connected one.

Each file transfer belongs to the device it was offered to or received from.
The outgoing queue stays sequential across devices; every entry remembers its
device. When a device disconnects, only its transfers are interrupted (and stay
retryable for that device); transfers with other devices continue.

Features with one global surface keep one target. The Mac call panel shows the
latest call from any phone and sends its actions to that phone. Local Find
device ringing starts when any device asks; stopping it also tells every
device that is ringing. The Mac media controller accepts commands from every
phone with Media enabled. Android shows the media state of the selected Mac.

## MVP plugin behavior

- **Battery:** Android publishes percentage and charging state after an
  authenticated connection and on system battery changes. macOS displays the
  latest value only while that peer remains connected.
- **Clipboard:** UTF-8 text plus optional HTML with a mandatory plain-text
  fallback and a 32 KiB content limit. Every encrypted update carries a unique
  message ID and requires a delivery acknowledgement. Android background
  clipboard access is not assumed; reading and sending requires an explicit
  user action.
- **Files:** metadata is announced in JSON, then bytes are streamed in bounded
  chunks. macOS users choose a persistent receive directory; Android writes to
  its scoped `Download/Bridgey` collection. Hash verification,
  progress, cancellation, and partial-file cleanup are mandatory.
- **Notifications:** Android's `NotificationListenerService` emits sanitized
  metadata. Bridgey's own notifications and secret/silent categories are
  filtered. Users can disable forwarding per observed Android package before
  content enters the transport. macOS posts through `UserNotifications` and
  returns only the bounded action tokens supplied by Android. Optional local
  history is off by default, retains at most 200 items for seven days, and is
  stored with owner-only permissions. Ongoing `CATEGORY_CALL` notifications are
  the sole exception to the ongoing-notification filter: their bounded call
  type drives a temporary macOS call card. With the separate call-integration
  opt-in, Android's telephony state corrects ambiguous OEM notification state
  and authenticated action tokens invoke the platform Answer, Decline, and
  Hang Up operations. Without it, Bridgey forwards only actions supplied by
  the phone application.
- **Calls from Mac:** macOS sends only a strictly validated number over the
  authenticated encrypted session. Android defaults to a notification that
  opens the system dialer for user confirmation. Call status and controls are
  a separate opt-in backed by narrow runtime Phone permissions; Bridgey does
  not request contacts or call-log access, and emergency numbers never use the
  immediate path.
- **macOS call presentation:** call notifications use a compact non-activating
  floating panel so Answer, Decline, and Hang Up remain directly visible rather
  than being collapsed into the system notification action menu. Ordinary
  mirrored notifications continue to use `UserNotifications`.

## Platform lifecycle

Android uses a visible foreground service only while the user has enabled a
persistent connection. It listens to connectivity changes and respects Doze;
there are no hidden keep-alive tricks. macOS uses a menu-bar scene and network
path/wake notifications. Both clients persist state before UI teardown.

Android Back navigation closes only the activity UI and intentionally leaves
the enabled connection service running. `Turn off Bridgey`, available both in
the app and its foreground notification, is the explicit lifecycle boundary:
it stops discovery and transport, removes the notification, and persists the
disabled state. Launching the main-profile app explicitly enables it again.

The Android MVP activates networking only for the system/main user. Samsung
Secure Folder and managed-profile copies have isolated app storage and would
otherwise publish different device identities from the same physical phone.
Those copies show an explanatory screen and do not start discovery or the
foreground connection service.

## Delivery sequence

1. Repository, protocol/security specifications, buildable shells, discovery.
2. Identity, pairing transcript, secure storage, and test vectors.
3. pinned-TLS WebSocket transport and reconnection state machine.
4. Clipboard, streaming files, then notification forwarding.
5. Hardening, interoperability tests, accessibility, and release packaging.
