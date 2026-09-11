#!/usr/bin/env bash
# Drives phase A: the same window, at each frame rate, plus the not-visible floor.
#
#   tools/battery-phase-a.sh [seconds-per-condition]
#
# The frame rate lives behind the settings UI and there is no way in without root,
# so the buttons are found by their label in a uiautomator dump and tapped. Finding
# them by text rather than by fixed coordinates is what survives a scroll position.
set -u
PKG=io.github.massimonastasi.proflw
SECS=${1:-600}
export MSYS_NO_PATHCONV=1
PATH="$PATH:/c/Users/massi/AppData/Local/Android/Sdk/platform-tools"
HERE=$(dirname "$0")

tap_label() {
  local want=$1 xml
  adb shell uiautomator dump /sdcard/p.xml >/dev/null 2>&1
  xml=$(adb exec-out cat /sdcard/p.xml)
  local xy
  xy=$(printf '%s' "$xml" | python -c "
import sys,re
s=sys.stdin.read(); want=sys.argv[1]
for n in re.findall(r'<node[^>]*>',s):
    t=re.search(r'text=\"([^\"]*)\"',n); b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n)
    if t and b and t.group(1)==want:
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
" "$want")
  [ -n "$xy" ] || { echo "   label '$want' non trovata"; return 1; }
  adb shell input tap $xy
}

set_fps() {
  adb shell am start -n $PKG/.SettingsActivity --activity-clear-top >/dev/null 2>&1
  sleep 2
  # The rate buttons sit near the top, so come back up before looking.
  for i in 1 2 3 4 5 6; do adb shell input swipe 558 600 558 1900 120; done
  sleep 1
  tap_label "$1 fps" || return 1
  sleep 1
  adb shell input keyevent KEYCODE_HOME
  sleep 3   # let the wallpaper become visible and settle
}

echo "# Fase A - $SECS s per condizione - $(date '+%Y-%m-%d %H:%M')"
echo "# dispositivo: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo

for f in 20 15 10; do
  echo "-- imposto $f fps"
  set_fps $f || { echo "   salto $f fps"; continue; }
  "$HERE/battery-probe.sh" "A: ${f} fps" "$SECS"
done

# The floor: the wallpaper is still the active wallpaper, but hidden behind a
# fullscreen app. onVisibilityChanged should have stopped the handler, so this
# is the one number that must come out at zero.
echo "-- copro il wallpaper con un'app a schermo intero"
adb shell am start -a android.intent.action.VIEW -d "https://example.invalid" >/dev/null 2>&1 \
  || adb shell am start -n com.android.settings/.Settings >/dev/null 2>&1
sleep 5
"$HERE/battery-probe.sh" "A0: non visibile" "$SECS"

echo "-- ripristino: 20 fps e home"
adb shell input keyevent KEYCODE_HOME; sleep 2
set_fps 20 >/dev/null 2>&1
echo "# fine $(date '+%H:%M')"
