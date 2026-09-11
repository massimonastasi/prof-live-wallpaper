# What the wallpaper costs

Measured 11 September 2026 on a **Fairphone 6** (Android 16), app version **1.1.5**.

`docs/PLAN.md` phase 7 set the goal years of planning ago: *delta % against a static wallpaper,
screen-on, **under 2%** an hour*. This is that measurement, and the answer is **about 1%**.

## The answer

| | |
|---|---|
| **Screen on, wallpaper visible** | **~46 mAh/h** (34-59), **~1.0% of the battery an hour** (0.8-1.4) |
| **Screen off, or another app in front** | **0.00% of a core** - measured, not assumed |
| Target from `PLAN.md` | under 2% an hour - **met, with room** |

The second row matters more than the first, because it is where a phone spends its day.
`onVisibilityChanged` removes the frame callback the moment the wallpaper stops being visible,
and a window with a fullscreen app in front measured **0.00 seconds of CPU** over ten minutes.
Not "almost nothing": nothing.

## How it was measured

Charge counters only fall while a device discharges, and `cmd battery unplug` merely changes
what the framework is told while the hardware keeps charging. So every window ran with the
phone **physically unplugged**, and the counters were read on the cable at both ends:
`tools/battery-drain.sh start` before pulling the cable, `stop` within seconds of plugging
back in - the reconnect starts a charge session and rolls `batterystats`' since-charged totals.

The primary number is the **subtraction between two charge-counter readings** (µAh, from
`dumpsys battery`), done by the script. It does not depend on how `batterystats` decides to
reset itself.

**Four windows of about half an hour, alternating.** The screen dies at 30 minutes - the
system UI caps `screen_off_timeout` there, and `svc power stayon` only holds the screen while
charging - so the hour `PLAN.md` asked for became two alternating pairs, which is better
anyway: it cancels drift instead of dumping it all on the last window.

Conditions held identical throughout: **airplane mode**, **brightness manual and at minimum**,
home screen in front, nobody touching the phone. Every window was checked afterwards for
`Screen on: (100.0%)`; a window whose screen had blanked would have been discarded.

## The windows

| # | Time | Condition | Drain | CPU |
|---|---|---|---|---|
| 1 | 17:15-17:43 | static wallpaper | 83.4 mAh/h | not valid, see below |
| 2 | 17:47-18:13 | Prof, 20 fps | 141.9 mAh/h | 15.26% of a core |
| 3 | 18:15-18:42 | static wallpaper | 37.2 mAh/h | 0.00% |
| 4 | 18:45-19:14 | Prof, 20 fps | 71.1 mAh/h | 15.34% of a core |

| Pair | Static | Prof | Delta | % of battery per hour |
|---|---|---|---|---|
| 1-2 | 83.4 | 141.9 | **58.5** | 1.36% |
| 3-4 | 37.2 | 71.1 | **33.9** | 0.79% |
| | | mean | **46.2** | **1.07%** |

## The honest part: absolute drain drifted, and by a lot

Two windows of the *same* condition differ by a factor of two - 83.4 against 37.2 for a static
wallpaper, 141.9 against 71.1 for Prof. **That spread is larger than the effect being
measured.** Taken carelessly, this data can be made to say anything between 0.8% and 2.4%.

What rescues it is that the drift is **not random**. Windows 1 and 2 are both high, 3 and 4
both low, and the ratio between the passes is 2.24 on static and 2.00 on Prof - almost the same
number. It is a monotonic settling, consistent with the phone still working off an hour of
earlier load: window 1 began two minutes after a long CPU-profiling session and started at
30 °C on its way down to 28 °C.

A drift like that cancels when **adjacent** windows are paired, which is what the alternation
was for. Pairing across it - window 2 against window 3 - is meaningless, and produced a
confident 2.43% at one point during this session before the fourth window exposed it.

**The two CPU figures are what make the pairing trustworthy**: 15.26% and 15.34% of a core.
The application did the same work in both Prof windows to within half a percent, so the 2x
difference in battery drain is the phone's, not ours.

## What this does not say

- **Not a precise figure.** Two pairs leave about ±27% on the delta. "About 1% an hour" is the
  claim; "1.07%" is not.
- **The ratio is not transferable.** Prof drew roughly 1.8x the static baseline here, but only
  because brightness was at minimum, where the screen costs very little. At normal brightness
  the screen dominates and the same app weighs proportionally far less. **Quote the mAh/h, never
  the ratio.**
- **One device, one state of charge.** Everything above is a Fairphone 6 between 80% and 77%.
  The code carries its own note that a Pixel 6a measured 12.0% of a core where this phone
  measures 15.3%.
- Window 1's CPU column is void: a `stop` run by mistake against an unplugged phone destroyed
  the baseline, which was then rebuilt from the printed charge figures without the tick counter.
  The charge number - the one that matters - was never at risk.

## Doing it again

    tools/battery-probe.sh <label> <seconds>     # CPU only, works on the cable
    tools/battery-drain.sh start|stop <label>    # battery, needs the cable out

`battery-probe.sh` measures CPU time from `/proc/<pid>/stat`, which does not care whether the
phone is charging - so ranking frame rates or checking an optimisation needs no unplugging at
all. Only the milliamp figure needs the offline protocol.

**Let the phone settle before the first window.** The strongest methodological lesson here is
that window 1 was contaminated by preceding activity. Give it fifteen idle minutes, or treat
the first window as a warm-up and throw it away.

**Never quote a CPU percentage as a battery figure.** They are different measurements and only
one of them is in milliamps.
