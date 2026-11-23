#!/usr/bin/env zsh
# End-to-end test of the phone <-> watch link, with both devices on ADB.
#   1. A notification posted on the phone reaches the watch and opens a card.
#   2. Dismissing it on the watch clears it on the phone.
# Usage: PHONE=<serial> WATCH=<serial> ./test-e2e.sh [out-dir]
# Serials default to the first device whose model matches.

OUT=${1:-build/e2e}
mkdir -p "$OUT"
fails=0

pick() { adb devices -l | awk -v m="$1" '$0 ~ m {print $1; exit}'; }
WATCH=${WATCH:-$(pick 'model:Amazfit')}
PHONE=${PHONE:-$(pick 'model:SM_')}
[[ -n "$WATCH" && -n "$PHONE" ]] || { echo "Need both the watch and the phone on adb."; exit 2; }

W()     { adb -s $WATCH shell "$@" | tr -d '\r'; }
P()     { adb -s $PHONE shell "$@" | tr -d '\r'; }
focus() { W dumpsys window | grep -m1 mCurrentFocus | grep -oE 'u0 [^ }]+' | cut -c4-; }
check() { if [[ "$2" == "$3" ]]; then echo "PASS  $1"; else echo "FAIL  $1 (got '$2', want '$3')"; fails=$((fails+1)); fi; }
on_phone() { P dumpsys notification --noredact | grep -cE "NotificationRecord.*pkg=com.android.shell.*tag=$1 "; }
wlog()  { adb -s $WATCH logcat -d -v time -s PaceLink:I | tr -d '\r' | awk -v t="$1" '$2>=t'; }

# Bring both ends up.
W input keyevent 3
P am start -n com.ayushwing.pace.companion/.MainActivity >/dev/null
sleep 3

echo "== phone -> watch"
tag="pace-e2e-$RANDOM"
mark=$(W date +%H:%M:%S)
P "cmd notification post -S bigtext -t 'Dinner plans' $tag 'Are you home by 8? Grabbing food on the way.'" >/dev/null
got=""
for i in {1..10}; do
  wlog "$mark" | grep -q 'notification from Shell$' && { got=yes; break; }
  sleep 1
done
check "notification reaches watch" "$got" "yes"
check "notification on phone" "$(on_phone $tag)" "1"

# The card should be in front. On the cradle Huami's charging screen can
# cover it, so fall back to the grid's Alerts bubble.
sleep 2
if [[ "$(focus)" != *NotifyActivity ]]; then
  W input keyevent 3; sleep 1.5
  W input swipe 160 260 160 80 120; sleep 1.2
  xy=($(adb -s $WATCH logcat -d -s PaceLauncher:D | tr -d '\r' | grep ' grid ' | tail -1 | grep -oE 'Alerts@[0-9]+,[0-9]+' | cut -d@ -f2 | tr ',' ' '))
  [[ -n "$xy" ]] && W input tap $xy[1] $xy[2]
  sleep 2
fi
check "card is showing" "$(focus | grep -o NotifyActivity)" "NotifyActivity"
adb -s $WATCH exec-out screencap -p > "$OUT/watch-card.png"

echo "== dismiss on watch -> cleared on phone"
W input tap 116 245
gone=""
for i in {1..8}; do
  [[ "$(on_phone $tag)" == "0" ]] && { gone=yes; break; }
  sleep 1
done
check "dismissed on phone" "$gone" "yes"
adb -s $WATCH exec-out screencap -p > "$OUT/watch-after.png"

echo
echo "$fails failure(s); screenshots in $OUT"
exit $fails
