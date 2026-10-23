#!/usr/bin/env bash
# Isolated installer contract tests. Never reaches a real adb executable.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEST=$(mktemp -d)
trap 'rm -rf "$TEST"' EXIT
mkdir "$TEST/bin"
export LOG="$TEST/commands"
cat > "$TEST/bin/adb" <<'MOCK'
#!/usr/bin/env bash
echo "$*" >> "$LOG"
[[ "$1" == -s ]] && shift 2
case "$*" in
  connect*) echo connected ;;
  get-state) echo device ;;
  'shell getprop ro.build.version.sdk') echo "${SDK:-31}" ;;
  'shell getprop ro.product.manufacturer') echo Sony ;;
  'shell cmd package resolve-activity'*) echo com.vendor.home/.Home ;;
  'shell settings get secure screensaver_components') echo "${SAVER:-com.vendor.saver/.Dream}" ;;
  'shell settings get secure enabled_notification_listeners') echo com.other/.Listener ;;
  'shell settings get secure enabled_accessibility_services') echo com.other/.Service ;;
  install*) [[ "${FAIL_INSTALL:-0}" == 0 ]] || { echo INSTALL_FAILED_UPDATE_INCOMPATIBLE >&2; exit 1; }; echo Success ;;
  'shell dumpsys window') echo 'mCurrentFocus=com.vendor.home/.Home' ;;
esac
MOCK
printf '#!/usr/bin/env bash\nexit 0\n' > "$TEST/bin/sleep"
chmod +x "$TEST/bin/adb" "$TEST/bin/sleep"
# Git Bash may see the Windows Store python3 alias rather than installed Python.
if ! python3 -c 'import sys' >/dev/null 2>&1; then
  printf '#!/usr/bin/env bash\nexec python "$@"\n' > "$TEST/bin/python3"
  chmod +x "$TEST/bin/python3"
fi
export PATH="$TEST/bin:$PATH" TV=192.168.1.20 APK="$TEST/local.apk" STATE_DIR="$TEST/state"
printf fake-apk > "$APK"
bash "$ROOT/install.sh" > "$TEST/output"
[[ "$(cat "$STATE_DIR/home")" == com.vendor.home/.Home ]]
grep -q 'install -r' "$LOG"
! grep -Eq 'uninstall|disable-user|keyevent KEYCODE_POWER' "$LOG"
echo 'PASS install snapshots original launcher and never removes apps'
printf com.saved.home/.Home > "$STATE_DIR/home"
bash "$ROOT/install.sh" > "$TEST/output"
[[ "$(cat "$STATE_DIR/home")" == com.saved.home/.Home ]]
echo 'PASS upgrade retains initial snapshot'
: > "$LOG"
if FAIL_INSTALL=1 bash "$ROOT/install.sh" > "$TEST/output" 2>&1; then exit 1; fi
! grep -Eq 'uninstall|pm grant|set-home-activity|am broadcast' "$LOG"
echo 'PASS signature mismatch stops before configuration without uninstall'
: > "$LOG"
SAVER=com.leslie.tvhome/.PhotoFrame bash "$ROOT/install.sh" restore > "$TEST/output"
grep -q 'set-home-activity com.saved.home/.Home' "$LOG"
grep -q 'settings put secure screensaver_components com.vendor.saver/.Dream' "$LOG"
! grep -Eq 'uninstall|settings put secure enabled_accessibility_services' "$LOG"
echo 'PASS restore retains app and other accessibility services'
: > "$LOG"
bash "$ROOT/install.sh" restore --uninstall > "$TEST/output"
grep -q 'uninstall com.leslie.tvhome' "$LOG"
echo 'PASS explicit uninstall supported'
: > "$LOG"
if STATE_DIR="$TEST/missing" bash "$ROOT/install.sh" restore > "$TEST/output" 2>&1; then exit 1; fi
! grep -Eq 'am broadcast|set-home-activity|uninstall' "$LOG"
echo 'PASS missing snapshot stops without changing TV'
: > "$LOG"
if SDK=26 bash "$ROOT/install.sh" > "$TEST/output" 2>&1; then exit 1; fi
! grep -q 'install -r' "$LOG"
echo 'PASS unsupported Android blocked'
cat > "$TEST/bin/curl" <<'MOCK'
#!/usr/bin/env bash
url=$2
out=$4
if [[ "$url" == *SHA256SUMS.txt ]]; then
  hash=$(python3 -c 'import hashlib; print(hashlib.sha256(b"downloaded-apk").hexdigest())')
  [[ "${BAD_HASH:-0}" == 0 ]] || hash=0000
  printf '%s  Tigris.apk\n' "$hash" > "$out"
else printf downloaded-apk > "$out"
fi
MOCK
chmod +x "$TEST/bin/curl"
: > "$LOG"
APK='' bash "$ROOT/install.sh" > "$TEST/output"
grep -q 'install -r' "$LOG"
echo 'PASS downloaded APK checksum accepted'
: > "$LOG"
if BAD_HASH=1 APK='' bash "$ROOT/install.sh" > "$TEST/output" 2>&1; then exit 1; fi
! grep -q 'install -r' "$LOG"
echo 'PASS checksum mismatch blocks installation'
