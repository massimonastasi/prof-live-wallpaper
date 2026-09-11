#!/usr/bin/env bash
# Measures one unplugged window: what actually left the battery, and what the
# wallpaper was doing while it left.
#
#   tools/battery-drain.sh start <label>    # on the cable, just before unplugging
#   tools/battery-drain.sh stop  <label>    # the moment you plug back in
#
# Charge counters only fall while the device discharges, so the phone has to be
# physically unplugged for the window. Reading happens on the cable, at both ends.
#
# The primary number is the difference between the two charge-counter readings,
# subtracted here. batterystats is kept only as a cross-check, because plugging
# back in starts a charge session and rolls its "since charged" totals.
#
# ponytail: state in one file, arithmetic in one awk. No database, no CSV.
set -u

CMD=${1:?usage: battery-drain.sh start|stop <label>}
LABEL=${2:?usage: battery-drain.sh start|stop <label>}
PKG=io.github.massimonastasi.proflw
CAP_MAH=4300                      # Fairphone 6, from dumpsys batterystats
STATE="${TMPDIR:-/tmp}/drain-$(echo "$LABEL" | tr -c 'A-Za-z0-9' _).state"

export MSYS_NO_PATHCONV=1
PATH="$PATH:/c/Users/massi/AppData/Local/Android/Sdk/platform-tools"

cpu_ticks() {
  local pid; pid=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
  [ -n "$pid" ] || { echo 0; return; }
  adb shell "cat /proc/$pid/stat 2>/dev/null" | sed 's/.*) //' | awk '{print $12 + $13}'
}

read_all() {
  local b; b=$(adb shell dumpsys battery)
  printf '%s %s %s %s %s\n' \
    "$(date +%s)" \
    "$(echo "$b" | awk '/Charge counter/{print $3}')" \
    "$(echo "$b" | awk '/^  level:/{print $2}')" \
    "$(echo "$b" | awk '/temperature/{print $2}')" \
    "$(cpu_ticks)"
}

case "$CMD" in
  start)
    adb shell dumpsys batterystats --reset >/dev/null 2>&1
    read_all > "$STATE"
    read -r _ c l t _ < "$STATE"
    echo "$LABEL: pronto. Carica ${c} uAh, livello ${l}%, $(awk -v x="$t" 'BEGIN{printf "%.1f", x/10}') C."
    echo "  Ora: stacca il cavo, non toccare il telefono, torna fra 30 minuti."
    ;;
  stop)
    [ -f "$STATE" ] || { echo "$LABEL: nessuno start registrato."; exit 1; }
    adb devices | grep -qw device || {
      echo "$LABEL: nessun dispositivo collegato. Ricollega il cavo, poi rilancia stop."
      echo "  Il riferimento di partenza resta salvato: non si e' perso nulla."
      exit 1
    }
    now=$(read_all)
    set -- $now
    [ -n "${2:-}" ] && [ "${2:-0}" -gt 0 ] || {
      echo "$LABEL: lettura della carica vuota. Finestra non chiusa, riferimento conservato."
      exit 1
    }
    screen=$(adb shell dumpsys batterystats 2>/dev/null | grep -m1 "Screen on:")
    awk -v l="$LABEL" -v cap="$CAP_MAH" -v s="$(cat "$STATE")" -v e="$now" -v scr="$screen" 'BEGIN{
      split(s, a, " "); split(e, b, " ")
      mins  = (b[1] - a[1]) / 60
      duAh  = a[2] - b[2]                       # positive while draining
      mAh   = duAh / 1000
      perH  = (mins > 0) ? mAh * 60 / mins : 0
      cpu   = (b[5] - a[5]) / 100               # CLK_TCK is 100 on this device
      printf "%-16s %5.1f min  %7.2f mAh  ->  %6.1f mAh/h  = %.2f%% batteria/ora   CPU %6.1fs (%.2f%% di un core)\n",
             l, mins, mAh, perH, perH/cap*100, cpu, (mins>0)? cpu/(mins*60)*100 : 0
      printf "                 livello %d%% -> %d%%, %.1f -> %.1f C\n", a[3], b[3], a[4]/10, b[4]/10
      if (duAh <= 0) print "  ATTENZIONE: la carica non risulta scesa - il telefono era alimentato. Riga da buttare."
      if (scr != "") print "  " scr
    }'
    rm -f "$STATE"
    ;;
  *) echo "usage: battery-drain.sh start|stop <label>"; exit 1 ;;
esac
