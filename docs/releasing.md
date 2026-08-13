# Releasing Bridgey

Bridgey publishes a signed Android APK and a Developer ID-signed, notarized
macOS DMG from tags matching `v*`.

## Android signing

Create one long-lived release keystore and keep two offline backups. The same
key must sign every direct-download APK update.

Add these repository secrets under **Settings → Secrets and variables →
Actions**:

- `ANDROID_KEYSTORE_BASE64`: base64 text of the `.jks` file
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Google Play is optional for GitHub releases. If Bridgey is later published on
Google Play, enroll in Play App Signing and keep a separate upload key.

## macOS signing and notarization

Enroll in the Apple Developer Program and create a **Developer ID Application**
certificate. Export the certificate and private key from Keychain Access as a
password-protected `.p12` file.

Create an App Store Connect API key with access suitable for notarization, then
add these repository secrets:

- `MACOS_CERTIFICATE_BASE64`: base64 text of the `.p12`
- `MACOS_CERTIFICATE_PASSWORD`
- `MACOS_SIGNING_IDENTITY`: full `Developer ID Application: … (TEAMID)` name
- `APPLE_API_KEY_BASE64`: base64 text of the `AuthKey_….p8` file
- `APPLE_API_KEY_ID`
- `APPLE_API_ISSUER_ID`

The workflow imports the certificate into a temporary keychain, signs Bridgey
with Hardened Runtime and a secure timestamp, submits the DMG through
`notarytool`, staples the accepted ticket, and verifies it with Gatekeeper.

## Publish

1. Update Android `versionName`/`versionCode` and macOS bundle versions.
2. Commit and push `main`; wait for the Build workflow to pass.
3. Create and push an annotated tag such as `v0.1.0`.
4. The Release workflow creates the GitHub Release and attaches installers and
   checksums.

Release notes should retain the acknowledgement: “Built with the assistance of
OpenAI Codex.”
