#!/usr/bin/env bash
# Hide Huami's network-facing packages on an Amazfit Pace, one at a time.
#
# Android 5.1 does not let the ADB shell run `pm disable-user`, but the shell
# holds MANAGE_USERS, so `pm hide` works without root. A hidden package does not
# run and cannot be launched; `pm unhide <pkg>` restores it.
#
# After each package the watch reboots and a health check runs: boot, Huami's
# health and launcher processes, and today's step count. The script stops at
# the first failed check.
#
# Usage: ./watch-debloat.sh [--no-reboot]

set -euo pipefail

PACKAGES=(
  com.huami.watch.wifiuploaddata   # uploads data over Wi-Fi
  com.huami.wififtp                # Wi-Fi FTP server
  com.huami.watch.otawatch         # firmware update checker
  com.huami.watch.wearlogger       # system logger
  com.huami.watch.hmlab            # Huami lab features
)
# Do not hide com.huami.watch.health, sensorservice, wearservices or launcher:
# step counting and the always-on face run inside them.

REBOOT=1
[[ "${1:-}" == "--no-reboot" ]] && REBOOT=0

A() { adb shell "$@" | tr -d '\r'; }

[[ "$(adb get-state 2>/dev/null)" == "device" ]] || { echo "No watch on ADB. Dock it and retry."; exit 1; }
echo "Watch: $(A getprop ro.build.display.id)"

check() {
  local procs steps
  [[ "$(A getprop sys.boot_completed)" == "1" ]] || { echo "  FAIL boot"; return 1; }
  procs=$(A ps | grep -cE 'com\.huami\.watch\.(health|launcher)$' || true)
  [[ "$procs" -ge 2 ]] || { echo "  FAIL Huami health/launcher not running"; return 1; }
  steps=$(A content query --uri content://com.huami.watch.health.historydata | tail -1 | grep -oE '"ttl":[0-9]+' || true)
  [[ -n "$steps" ]] || { echo "  FAIL step history unreadable"; return 1; }
  echo "  ok: boot, health+launcher running, steps $steps"
}

wait_boot() {
  sleep 10
  for _ in $(seq 1 60); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
    sleep 3
  done
  sleep 15
}

for pkg in "${PACKAGES[@]}"; do
  if ! A pm list packages -u | grep -qx "package:$pkg"; then
    echo "$pkg: not installed, skipping"; continue
  fi
  if ! A pm list packages | grep -qx "package:$pkg"; then
    echo "$pkg: already hidden"; continue
  fi
  echo "$pkg: hiding"
  A pm hide "$pkg"
  if [[ $REBOOT == 1 ]]; then
    adb reboot
    wait_boot
  fi
  check || { echo "Stopping. Undo with: adb shell pm unhide $pkg"; exit 1; }
done

echo "Done. Hidden: $(comm -23 <(A pm list packages -u | sort) <(A pm list packages | sort) | sed 's/package://' | tr '\n' ' ')"
