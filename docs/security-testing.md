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

Future device-level dynamic coverage should extend the interoperability harness
to run both UI applications, mutate authenticated protocol frames, and interrupt
live file streams. It should run in isolated Android emulator and macOS runner
instances with generated identities and no production trust data.
