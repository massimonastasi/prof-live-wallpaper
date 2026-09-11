#!/usr/bin/env bash
# Measures what the wallpaper costs over a window, and prints one row of the table.
#
#   tools/battery-probe.sh <label> <seconds>
#
# CPU time comes from /proc/<pid>/stat, which is readable without root and does not
# care whether the phone is charging - so this half of the campaign runs on the cable.
# Frame counts come from gfxinfo, reset at the start of the window.
#
# ponytail: no averaging, no repeats, no JSON. One window, one row. Run it again if
# you want a second sample; the label is yours to make meaningful.
set -u

PKG=io.github.massimonastasi.proflw
LABEL=${1:?usage: battery-probe.sh <label> <seconds>}
SECS=${2:?usage: battery-probe.sh <label> <seconds>}

export MSYS_NO_PATHCONV=1   # or adb's /proc paths become C:/Program Files/Git/proc
PATH="$PATH:/c/Users/massi/AppData/Local/Android/Sdk/platform-tools"

# utime+stime in clock ticks. Fields 14 and 15, counting from 1, after the comm field -
# which is parenthesised and may contain spaces, so everything before ')' is dropped.
cpu_ticks() {
  local pid=$1
  adb shell "cat /proc/$pid/stat 2>/dev/null" \
    | sed 's/.*) //' | awk '{print $12 + $13}'
}

pid=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
if [ -z "$pid" ]; then
  echo "$LABEL: the wallpaper process is not running - 0 CPU by definition."
  exit 0
fi

hz=$(adb shell getconf CLK_TCK | tr -d '\r'); hz=${hz:-100}
adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
t0=$(cpu_ticks "$pid")
temp0=$(adb shell dumpsys battery | awk '/temperature/{print $2}' | tr -d '\r')
start=$(date +%s)

sleep "$SECS"

t1=$(cpu_ticks "$pid")
elapsed=$(( $(date +%s) - start ))
gfx=$(adb shell dumpsys gfxinfo $PKG 2>/dev/null)
temp1=$(adb shell dumpsys battery | awk '/temperature/{print $2}' | tr -d '\r')

# The process dying mid-window would make the delta meaningless rather than small.
now=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
[ "$now" = "$pid" ] || { echo "$LABEL: process changed ($pid -> ${now:-gone}) - window void."; exit 1; }

frames=$(echo "$gfx" | awk '/Total frames rendered/{print $NF}')
p50=$(echo "$gfx"    | awk '/50th percentile/{print $NF}')
p90=$(echo "$gfx"    | awk '/90th percentile/{print $NF}')
janky=$(echo "$gfx"  | awk '/^ *Janky frames:/{print $3}')

awk -v l="$LABEL" -v d="$(( t1 - t0 ))" -v hz="$hz" -v s="$elapsed" -v f="${frames:-0}" \
    -v p50="${p50:-?}" -v p90="${p90:-?}" -v j="${janky:-?}" \
    -v c0="${temp0:-0}" -v c1="${temp1:-0}" 'BEGIN{
  cpu = d / hz
  printf "%-22s %6.1fs window  %7.2fs CPU  %6.2f%% of a core  %5.1f fps  %s/%s  jank %s  %.1f->%.1f C\n",
         l, s, cpu, cpu/s*100, f/s, p50, p90, j, c0/10, c1/10
}'
