# Architecture

## Decisions

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
  `-- notifications
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
  filtered. macOS posts through `UserNotifications`. Actions are reserved for a
  later capability.

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
