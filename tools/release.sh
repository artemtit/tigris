#!/usr/bin/env bash
# Usage: tools/release.sh <versionName> [--dry-run]
# Bumps version, builds+tests, verifies signer, publishes to artemtit/tigris-releases.
set -euo pipefail
VER=${1:?usage: release.sh <versionName> [--dry-run]}; DRY=${2:-}
[[ "$VER" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || { echo "bad version: $VER" >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
G=app/build.gradle.kts
EXPECTED=729ea3cfc623690140aeefc7e495e41850fd1f3d7f572c63f1cc92162b6b3ba6
REPO=artemtit/tigris-releases; TAG="v$VER"; ASSET="Tigris-$TAG.apk"
[ -n "${JAVA_HOME:-}" ] || export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot"

CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' $G | head -1)
sed -i "s/versionCode = $CODE/versionCode = $((CODE+1))/; s/versionName = \"[^\"]*\"/versionName = \"$VER\"/" $G
echo "versionCode $CODE -> $((CODE+1)), versionName $VER"

./gradlew --no-daemon -q testStandardDebugUnitTest assembleStandardRelease
APK=app/build/outputs/apk/standard/release/app-standard-release.apk

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties | tr -d '\r' | sed 's/\\/\//g; s/\:/:/')}}
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner.bat "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
[ -n "$APKSIGNER" ] || { echo "apksigner not found in $SDK" >&2; exit 1; }
ACTUAL=$("$APKSIGNER" verify --print-certs "$APK" | tr -d '\r' | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
[ "$ACTUAL" = "$EXPECTED" ] || { echo "SIGNER MISMATCH: $ACTUAL != $EXPECTED" >&2; exit 1; }
echo "signer OK ($ACTUAL)"

OUT=$(mktemp -d); cp "$APK" "$OUT/$ASSET"; echo "asset: $OUT/$ASSET"
if [ "$DRY" = "--dry-run" ]; then echo "dry run: skipping commit and gh release"; exit 0; fi

git add $G && git commit -m "chore(release): $TAG"
gh release create "$TAG" "$OUT/$ASSET" --repo "$REPO" --title "Tigris $VER" --notes "Tigris $VER"
