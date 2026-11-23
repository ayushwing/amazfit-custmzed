#!/usr/bin/env zsh
# End-to-end flow test for the Pace launcher, run against the real watch over ADB.
# Usage: ./test-flows.sh [out-dir]
# Every check prints PASS/FAIL; screenshots land in out-dir for visual review.
# Exits non-zero if any check fails or any app crashes during the run.

SERIAL=${SERIAL:-$(adb get-serialno 2>/dev/null)}
OUT=${1:-build/flows}
PKG=com.ayushwing.pace.launcher
mkdir -p "$OUT"; rm -f "$OUT"/*.png(N)
fails=0

A()     { adb -s $SERIAL shell "$@" | tr -d '\r'; }
focus() { A dumpsys window | grep -m1 mCurrentFocus | grep -oE 'u0 [^/ ]+' | cut -c4-; }
shot()  { adb -s $SERIAL exec-out screencap -p > "$OUT/$1.png"; }
log()   { adb -s $SERIAL logcat -d -s PaceLauncher:D | tr -d '\r'; }
check() { if [[ "$2" == "$3" ]]; then echo "PASS  $1"; else echo "FAIL  $1 (got '$2', want '$3')"; fails=$((fails+1)); fi; }
dismiss_errors() { for i in 1 2 3; do A dumpsys window windows | grep -q 'Application Error' || return; A input tap 160 212; sleep 1; done; }
home()  { A input keyevent 3; sleep 1.5; }
to_grid() { A input swipe 160 260 160 80 120; sleep 1.2; }
count() { log | grep -c "$1"; }
bubble() { log | grep ' grid ' | tail -1 | grep -oE "$1@[0-9]+,[0-9]+" | cut -d@ -f2 | tr ',' ' '; }

START=$(A date +%m-%d\ %H:%M:%S)
dismiss_errors

echo "== face"
home
check "button/HOME shows launcher" "$(focus)" "$PKG"
shot 01-face

echo "== grid"
b=$(count page=1); to_grid
check "swipe up opens grid" "$(( $(count page=1) - b ))" "1"
shot 02-grid
labels=($(log | grep ' grid ' | tail -1 | grep -oE '[A-Za-z]+@' | tr -d '@'))
echo "      bubbles: ${labels[*]}"

echo "== scroll"
A input swipe 160 230 160 120 400; sleep 1
shot 03-grid-scrolled
A input swipe 160 120 160 230 400; sleep 1

echo "== swipe down returns to face"
b=$(count page=0)
A input swipe 160 60 160 280 120; sleep 1.2
check "swipe down closes grid" "$(( $(count page=0) - b ))" "1"
shot 04-face-again

echo "== back key on grid"
to_grid; b=$(count page=0); A input keyevent 4; sleep 1.2
check "back closes grid" "$(( $(count page=0) - b ))" "1"

typeset -A want=(
  Alarm com.huami.watch.deskclock  Music watch.huami.com.mediaplayer
  History com.huami.watch.newsport Workout com.huami.watch.newsport
  Compass com.huami.watch.compass  Files com.amazmod.service
  Settings com.huami.mobile.watchsettings Huami com.huami.watch.launcher
)
n=5
for label in ${(k)want}; do
  echo "== open $label"
  home; to_grid
  xy=($(bubble $label))
  if [[ -z "$xy" ]]; then
    # Not visible at rest: scroll the grid up and look again.
    A input swipe 160 230 160 110 400; sleep 1; xy=($(bubble $label))
  fi
  if [[ -z "$xy" ]]; then check "$label bubble visible" "no" "yes"; continue; fi
  A input tap $xy[1] $xy[2]; sleep 3
  check "$label opens" "$(focus)" "$want[$label]"
  shot "$(printf %02d $n)-app-$label"; n=$((n+1))
  A input keyevent 3; sleep 2
  got=$(focus)
  if [[ "$got" != "$PKG" ]]; then A input keyevent 26; sleep 2; got=$(focus); fi
  check "$label -> button returns to face" "$got" "$PKG"
  dismiss_errors
done

echo "== sleep and wake"
# The watch is set to stay awake while charging (stay_on_while_plugged_in=3),
# so turn that off for this check and restore it afterwards.
stay=$(A settings get global stay_on_while_plugged_in)
A settings put global stay_on_while_plugged_in 0
# A short button press means "home" on this firmware; the screen sleeps on its timeout.
home; sleep 18
check "display sleeps on timeout" "$(A dumpsys power | grep -m1 -oE 'mWakefulness=[A-Za-z]+')" "mWakefulness=Asleep"
A input keyevent 26; sleep 2
A settings put global stay_on_while_plugged_in "$stay"
check "wake returns to launcher" "$(focus)" "$PKG"
shot 90-after-wake

echo "== crashes since start"
crashes=$(adb -s $SERIAL logcat -b crash -d -v time -T "$START.000" 2>/dev/null | tr -d '\r' | grep -E 'Process: ' | sort -u)
check "no app crashed" "${crashes:-none}" "none"

echo
echo "$fails failure(s); screenshots in $OUT"
exit $fails
