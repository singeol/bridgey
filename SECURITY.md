# Security policy and model

## Supported versions

Bridgey is pre-release software. Security fixes are applied to the latest main
branch only. Do not use it yet for sensitive production workflows.

## Reporting a vulnerability

Until a private project security address is published, use GitHub's private
security advisory feature rather than a public issue. Include affected commit,
reproduction steps, impact, and any suggested mitigation. Do not include real
clipboard or notification content.

## Threat model

The LAN is hostile. Attackers may advertise arbitrary mDNS records, observe and
modify traffic, replay frames, race connections, and exhaust resources. A
previously paired device may later be compromised. The operating system and its
secure key store are trusted; denial of radio/network service and a fully
compromised local OS are outside the model.

Security goals are mutual peer authentication, confidentiality and integrity in
transit, visible first-pairing consent, replay-resistant side effects, least
plugin privilege, and complete revocation of local trust.

## Current transport and planned hardening

The native 0.5/0.6 clients use bounded newline-delimited JSON over TCP and
application-layer AES-GCM for feature payloads. They do **not** currently use
TLS or WebSockets. Ephemeral P-256 ECDH/HKDF establishes a session key; HMAC
confirmation and signed transcripts bind the stored long-term peer identity.
Routing headers and some acknowledgement/heartbeat messages are not encrypted.
Replay caches are bounded and session-local. This is not a claim of independent
cryptographic audit or a complete active-network-attacker defense.

The following items describe the target security design; in particular TLS,
persistent replay handling, authenticated key rotation, and comprehensive
resource/rate limits are not all implemented. See the current-wire section of
`docs/protocol.md` and automated coverage in `docs/security-testing.md`.

## Target design

- Generate a long-term P-256 signing/authentication key per installation using
  Android Keystore or macOS Keychain/Secure Enclave where available. Private
  keys are non-exportable when the platform supports it.
- Pair with ephemeral P-256 ECDH, HKDF-SHA-256, transcript binding, and a
  six-digit code confirmed on both screens. Use platform cryptographic APIs and
  constant-time comparisons; never implement primitives locally.
- After pairing, use TLS 1.3 where available and pin the peer's long-term public
  key (SPKI SHA-256), rather than trusting mDNS names or the public Web PKI.
- Authenticate the full pairing transcript before persisting trust. TLS session
  resumption must remain bound to the same pinned identity.
- Generate unpredictable message IDs. Keep a bounded replay cache per peer and
  make state-changing handlers idempotent. Reject expired pairing messages and
  impossible protocol transitions.
- Apply frame, string, queue, transfer-size, rate, and connection limits before
  expensive decoding or allocation. Stream file bytes and verify their declared
  SHA-256 before completion.
- Treat clipboard, filenames, and notification data as sensitive. Production
  logs contain only redacted IDs and state transitions; no payload bodies,
  derived keys, verification codes, tokens, or full fingerprints.

The verification code protects first pairing from an active MITM only when the
user actually compares both displays. The UI must not offer a one-sided or
automatic confirmation path.

## Revocation and rotation

"Forget device" deletes its pinned key, permissions, replay state, resumable
sessions, and pending transfers, then closes the connection. Re-pairing is
required afterward. A compromised paired peer can access only capabilities the
user granted until it is revoked.

Key rotation is an authenticated protocol operation signed by both the old and
new keys and explicitly surfaced to the user. If the old key is unavailable,
rotation is not allowed: forget and pair again. Local identity reset invalidates
all pairings.

## Privacy defaults

There is no telemetry, advertising identifier, account, cloud relay, or remote
analytics. LAN features communicate directly between paired devices. Sensitive
OS access requires explicit permission. Global and per-device switches gate
feature execution; permission-light features default on. Mac media control
defaults off and requires a locally selected player and Automation consent. Bridgey
does not weaken platform background/privacy controls to obtain clipboard or
notification data.

Remote call requests are accepted only from an authenticated paired session,
are encrypted and replay-protected, and are subject to per-peer feature policy,
strict phone-number validation, and rate limiting. Android requires local
confirmation by default. The higher-risk direct-call path is a separate local
opt-in and never handles a platform-recognized emergency number directly.
