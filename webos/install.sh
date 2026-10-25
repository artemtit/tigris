#!/bin/sh
# LG webOS Developer Mode only. Does not use ADB or attempt Root/Home interception.
set -eu
ROOT_URL="https://github.com/lesnolie/Tigris/archive/refs/heads/main.tar.gz"
DEVICE=""
MODE=preflight
SOURCE_DIR=""
usage() {
  cat <<'HELP'
Tigris webOS developer-preview installer
Usage: sh webos/install.sh --device <ares-device-alias> [--preflight|--install|--uninstall] [--source <repo-directory>]
Requirements: Node.js, @webos-tools/cli, LG Developer Mode + Key Server.
--preflight  read-only connection and device capability inventory (default)
--install    package current source as IPK, install and launch; no HOME takeover
--uninstall  remove ONLY org.tigris.webos after explicit confirmation
TV alias is configured through: ares-setup-device
This script does not root TVs, intercept HOME, alter stock LG Home or install a Helper.
HELP
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --device) [ "$#" -gt 1 ] || { usage; exit 2; }; DEVICE=$2; shift 2 ;;
    --source) [ "$#" -gt 1 ] || { usage; exit 2; }; SOURCE_DIR=$2; shift 2 ;;
    --preflight) MODE=preflight; shift ;;
    --install) MODE=install; shift ;;
    --uninstall) MODE=uninstall; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done
[ -n "$DEVICE" ] || { echo "Supply --device with your configured LG alias." >&2; usage; exit 2; }
case "$DEVICE" in *[!a-zA-Z0-9._-]*|'') echo "Invalid device alias." >&2; exit 2;; esac
for cmd in ares-setup-device ares-device ares-install ares-launch; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "Missing $cmd. Install @webos-tools/cli and pair with ares-setup-device." >&2; exit 3; }
done
echo "[webOS] Platform=LG webOS; transport=webOS CLI/Developer Mode SSH (not Android ADB)"
echo "[webOS] Registered devices:"
ares-setup-device --list
ares-device --system-info --device "$DEVICE"
echo "[webOS] Preflight device=$DEVICE; checking reachable installed-app inventory:"
ares-install --device "$DEVICE" --list >/dev/null
echo "[webOS] SSH/installer channel reachable. Renew Developer Mode before expiry; expiry may remove developer-installed apps."
echo "[webOS] Native Home Helper: UNVERIFIED (no Tigris input-capture probe installed)."
echo "[webOS] Root status: NOT CHECKED; no rooting or root service installation is performed."
if [ "$MODE" = preflight ]; then
  echo "[webOS] Read-only preflight complete. Do not mark HOME interception as supported."
  exit 0
fi
if [ "$MODE" = uninstall ]; then
  printf 'Remove only Tigris webOS (org.tigris.webos) from %s? Type YES: ' "$DEVICE"
  read answer
  [ "$answer" = YES ] || { echo "Cancelled."; exit 0; }
  ares-install --device "$DEVICE" --remove org.tigris.webos
  echo "[webOS] Removed Tigris. LG Home was never modified."
  exit 0
fi
command -v ares-package >/dev/null 2>&1 || { echo "Missing ares-package." >&2; exit 3; }
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT HUP INT TERM
if [ -n "$SOURCE_DIR" ]; then
  APP_DIR="$(cd "$SOURCE_DIR/webos/app" && pwd)"
elif [ -f webos/app/appinfo.json ]; then
  APP_DIR="$(pwd)/webos/app"
else
  command -v curl >/dev/null 2>&1 || { echo "Missing curl." >&2; exit 3; }
  command -v tar >/dev/null 2>&1 || { echo "Missing tar." >&2; exit 3; }
  echo "[webOS] Downloading official source snapshot (developer preview, NOT a published signed release)..."
  curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 --output "$TEMP/source.tar.gz" "$ROOT_URL"
  tar -xzf "$TEMP/source.tar.gz" -C "$TEMP"
  APP_DIR="$TEMP/Tigris-main/webos/app"
fi
[ -f "$APP_DIR/appinfo.json" ] || { echo "Tigris webOS source not available in $APP_DIR." >&2; exit 4; }
echo "[webOS] Packaging from $APP_DIR"
(cd "$TEMP" && ares-package "$APP_DIR")
IPK=$(find "$TEMP" -maxdepth 1 -name 'org.tigris.webos_*_all.ipk' -type f | head -n 1)
[ -n "$IPK" ] || { echo "No Tigris IPK produced. Packaging failed." >&2; exit 4; }
echo "[webOS] Installing the standard webOS application (not a Home replacement)."
ares-install --device "$DEVICE" "$IPK"
echo "[webOS] Launching Tigris..."
ares-launch --device "$DEVICE" org.tigris.webos
echo "[webOS] Installed normal Tigris webOS application. Actual Home key takeover is NOT active."
echo "[webOS] Test D-pad, OK, Back, installed apps and optional weather on the TV."
echo "[webOS] For rapid launch, bind Tigris using LG's Quick Access UI on the remote."
