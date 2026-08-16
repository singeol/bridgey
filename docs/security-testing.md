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
  authentication. Both native implementations must match the same fixture.

Future dynamic coverage should use a purpose-built interoperability harness
that runs both clients, mutates authenticated protocol frames, interrupts file
streams, and verifies that malformed input cannot cause a side effect or leave
partial files. It should run in isolated Android emulator and macOS runner
instances with generated identities and no production trust data.
