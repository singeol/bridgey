#!/bin/sh
set -eu

CONFIGURATION=release ./build-app.sh

OUTPUT_DIR="${OUTPUT_DIR:-dist}"
APP_PATH=".build/release/BridgeyMac.app"
DMG_ROOT="${TMPDIR:-/private/tmp}/bridgey-dmg"

mkdir -p "$OUTPUT_DIR"
rm -rf "$DMG_ROOT"
mkdir -p "$DMG_ROOT"
ditto "$APP_PATH" "$DMG_ROOT/Bridgey.app"
ln -s /Applications "$DMG_ROOT/Applications"
hdiutil create -volname "Bridgey" -srcfolder "$DMG_ROOT" -ov -format UDZO "$OUTPUT_DIR/Bridgey-macOS.dmg"

if [ -n "${APPLE_API_KEY_PATH:-}" ] && [ -n "${APPLE_API_KEY_ID:-}" ] && [ -n "${APPLE_API_ISSUER_ID:-}" ]; then
  xcrun notarytool submit "$OUTPUT_DIR/Bridgey-macOS.dmg" \
    --key "$APPLE_API_KEY_PATH" \
    --key-id "$APPLE_API_KEY_ID" \
    --issuer "$APPLE_API_ISSUER_ID" \
    --wait
  xcrun stapler staple "$OUTPUT_DIR/Bridgey-macOS.dmg"
  xcrun stapler validate "$OUTPUT_DIR/Bridgey-macOS.dmg"
fi

ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$OUTPUT_DIR/Bridgey-macOS.zip"
(
  cd "$OUTPUT_DIR"
  shasum -a 256 Bridgey-macOS.dmg Bridgey-macOS.zip > SHA256SUMS-macOS.txt
  shasum -a 256 -c SHA256SUMS-macOS.txt
)
