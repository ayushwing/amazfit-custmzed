#!/usr/bin/env bash
# ==============================================================================
# Amazfit Pace (A1612 / huangheUS) Zero-Cloud Debloat & Hardening Script
# Author: Ayush Soni (ayushwing.com)
# ==============================================================================

set -euo pipefail

SERIAL="<watch-serial>"

echo "=== Checking ADB connection to Amazfit Pace ($SERIAL) ==="
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "Error: Device $SERIAL not found or unauthorized. Make sure watch is docked."
    exit 1
fi

echo "Connected to: $(adb -s "$SERIAL" shell getprop ro.build.display.id)"

# Telemetry, Wi-Fi upload, OTA, and cloud services to disable/freeze
HUAMI_BLOAT=(
    "com.huami.watch.wifiuploaddata"       # Background Wi-Fi telemetry sync
    "com.huami.watch.otawatch"             # Automatic OTA updater
    "com.huami.wififtp"                    # Open Wi-Fi FTP server
    "com.huami.watch.wearlogger"           # Local system telemetry logger
    "com.huami.watch.hmlab"                # Experimental telemetry
)

echo "=== Disabling Huami Telemetry & Tracking Packages ==="
for pkg in "${HUAMI_BLOAT[@]}"; do
    echo "Disabling package: $pkg..."
    adb -s "$SERIAL" shell pm hide "$pkg" 2>/dev/null || \
    adb -s "$SERIAL" shell pm disable-user --user 0 "$pkg" 2>/dev/null || \
    echo " - Notice: Could not disable $pkg (may already be inactive)"
done

echo "=== Cleaning temporary caches ==="
adb -s "$SERIAL" shell rm -f /sdcard/*.png /sdcard/*.log /sdcard/*.tmp 2>/dev/null || true

echo "=== Current active storage & RAM ==="
adb -s "$SERIAL" shell "df /data && free -m"

echo "=== Done! Watch is hardened and stripped of background cloud daemons. ==="
