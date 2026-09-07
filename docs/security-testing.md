# Security testing

Bridgey is a pair of native LAN applications, not an HTTP service. Traditional
web DAST scanners such as OWASP ZAP would only report that there is no web
endpoint and would not exercise the encrypted Bridgey protocol.

The project therefore uses layered checks:

- Android Lint, Kotlin/JVM unit tests, Swift tests, compiler warnings as errors,
  script syntax checks, and metadata validation on every pull request and push;
- CodeQL `security-extended` analysis for Kotlin/Java and Swift on pull requests,
  pushes to `main`, and a weekly schedule;
- GitHub Dependency Review on pull requests and weekly Dependabot checks for
  Gradle, SwiftPM, and GitHub Actions;
- negative tests for untrusted discovery fields, settings policy, manifest
  permissions, and protocol boundary behavior;
- shared deterministic Android/macOS cryptographic vectors covering P-256 ECDH,
  HKDF session keys, verification codes, confirmation proofs, and AES-GCM
  authentication. Both native implementations must match the same fixture;
- bounded-frame and malformed-message tests, deterministic reconnect-backoff
  tests, and interrupted-transfer recovery tests on both clients;
- diagnostics tests that ensure exported reports contain aggregate state but
  exclude file names and protocol identifiers.

## 0.6 review

- CodeQL SARIF is checked after upload on every analyzed branch/PR: High/Critical
  security findings or error-level quality results fail the job. A successful
  scan alone is no longer enough. Missing/failed reports fail closed. The gate
  has its own unit tests and does not suppress results or disable queries.

- Resolved CodeQL `java/android/implicit-pendingintents`: the immutable call
  confirmation PendingIntent now targets a private, explicit Bridgey activity.
  Only the local tap launches ACTION_DIAL; the fallback does not place a call.
- Added manifest regression coverage for the private confirmation activity and
  permission-protected Quick Settings service. No new Android uses-permission.
- Added URL scheme/credential/length validation tests on both platforms,
  per-session encrypted request-sequence replay tests, and Swift tests for the
  media command allowlist, integer bounds and malformed/oversized artwork.
- Mac automation is opt-in, restricted to locally selected Music/Spotify,
  serialized off the UI thread and time/output bounded. No Accessibility,
  remote shell, browser scripting, artwork URL fetch or media-library scanning.
- Dependency PRs #12, #13 and #14 were reviewed with green Build, CodeQL and
  Dependency Review checks: setup-java v6 and matching AGP 9.4.0 plugins.

Green CodeQL is not a full protocol audit. In particular, application-layer
encryption is not the proposed TLS transport, and not all legacy envelope
headers/acknowledgements are authenticated. A transport-wide authenticated
envelope/sequence design and independent security review remain hardening work.
Device/OEM acceptance is recorded separately in `testing-0.6.md`.

Future device-level dynamic coverage should extend the interoperability harness
to run both UI applications, mutate authenticated protocol frames, and interrupt
live file streams. It should run in isolated Android emulator and macOS runner
instances with generated identities and no production trust data.
