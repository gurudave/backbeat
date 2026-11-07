#!/usr/bin/env bash
# Installs the preview build on a running emulator, walks through the app, and saves
# screenshots (plus the engine's contact sheets, timing reports and sample sounds) to look over.
# The emulator has no microphone worth the name, so the Ear is shown with a stand-in that claps
# along (see app/src/preview/.../FakeMicrophone.kt).
# Usage: emulator-preview.sh <preview.apk> <output dir>
set -uo pipefail

APK="$1"
OUT="$2"
PKG=app.djungle.backbeat
mkdir -p "$OUT"

shot() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "$OUT/$1.png"
}

# Prints the centre of the first on-screen element whose text or description matches $1.
find_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1 || return 1
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pattern = re.compile(sys.argv[1], re.I)
for node in ET.parse(sys.argv[2]).iter('node'):
    # Text and description are tried apart: a button can carry its label in both.
    labels = [node.get('text', '').strip(), node.get('content-desc', '').strip()]
    if any(label and pattern.search(label) for label in labels):
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', node.get('bounds', '')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

tap_text() {
  local xy
  xy=$(find_text "$1") || { echo "nothing on screen matches '$1'"; return 1; }
  # shellcheck disable=SC2086
  adb shell input tap $xy
}

# Scrolls the tab under the screen up by about half its height.
scroll_panel() {
  adb shell input swipe 540 2000 540 1550 500
  sleep 1
}

# Slides the chip row holding the element that matches $1 to the left, to reach chips off screen.
slide_row() {
  local xy
  xy=$(find_text "$1") || { echo "nothing on screen matches '$1'"; return 1; }
  adb shell input swipe 950 "${xy#* }" 150 "${xy#* }" 500
  sleep 1
}

# Taps a place found earlier: while the metronome plays, the screen never sits still long
# enough for uiautomator, so anything pressed then is looked up beforehand.
tap_at() {
  if [ -n "$1" ]; then
    # shellcheck disable=SC2086
    adb shell input tap $1
  else
    echo "no position for $2"
  fi
}

adb install -r "$APK"
# Granted up front, so no permission dialog covers a screenshot.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true
adb logcat -c

echo "== Rendering the contact sheets, timing report and sounds"
adb shell am start -W -n "$PKG/.DemoActivity"
sleep 20

echo "== How smoothly the ball moves after START, on the emulator's sound card"
adb shell am start -W -n "$PKG/.DemoActivity" --es mode clock
sleep 8
adb pull "/sdcard/Android/data/$PKG/files/preview" "$OUT/gallery" || echo "could not pull the gallery"
cat "$OUT/gallery/timing.txt" "$OUT/gallery/ear.txt" "$OUT/gallery/tempo.txt" "$OUT/gallery/clock.txt" 2>/dev/null || true

echo "== First launch"
adb shell am start -W -n "$PKG/.MainActivity"
shot 01-home 5

START=$(find_text '^start$') || START=""
PRACTICE=$(find_text '^practice$') || PRACTICE=""

echo "== Playing"
tap_at "$START" start
shot 02-playing 2
shot 03-playing-a-moment-later 1
tap_at "$PRACTICE" practice
shot 04-practice-tab-while-playing 2
tap_at "$START" stop
sleep 2

echo "== Practice aids"
tap_text 'tempo ramp' && shot 05-ramp-on 2
# Past the logbook and the ramp's settings.
scroll_panel
scroll_panel
tap_text 'silent bars' && shot 06-silent-bars-on 2

echo "== Tapes"
tap_text '^tapes$' && tap_text 'rec this tape' && shot 07-tape-recorded 2

echo "== A compound meter with swing"
tap_text '^beat$' && sleep 1
slide_row '^5/4$' && tap_text '^6/8$'
slide_row '^16ths$' && tap_text '^swing$'
shot 08-six-eight-swing 2
START=$(find_text '^start$') || START=""
tap_at "$START" start
shot 09-six-eight-playing 3
tap_at "$START" stop
sleep 1
scroll_panel
shot 10-beat-tab-scrolled 2

echo "== Other channels"
tap_text '^set up$' && shot 11-set-up 2
for vibe in 'arcade' 'roller rink' 'sunday drive' 'record shop'; do
  if tap_text "vibe $vibe"; then
    shot "12-vibe-${vibe// /-}" 3
  fi
done
tap_text 'vibe high score' > /dev/null

echo "== The Ear, with a stand-in microphone that claps along"
# A fresh start, so the stand-in is in place before the app first listens.
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.DemoActivity" --es mode ear
sleep 5
tap_text '^ear$' && shot 13-ear-tab 2
# The first time, it calibrates: it listens to its own clicks for a few seconds.
tap_text 'judge my timing' && shot 14-calibrating 3
sleep 10
shot 15-calibrated 0
START=$(find_text '^start$') || START=""
tap_at "$START" start
shot 16-playing-along 9
shot 17-playing-along-later 3
tap_at "$START" stop
shot 18-results 3
tap_text '^sign it$|^again!$' && shot 19-hi-score-table 3
scroll_panel
scroll_panel
scroll_panel
LISTEN=$(find_text '^listen$') || LISTEN=""
tap_at "$LISTEN" listen
shot 20-finding-a-tempo 6
# Once sure, it stops listening by itself.
sleep 9
shot 21-tempo-found 0
# The picture unfolded to show the tempo, so the SET key is further down.
scroll_panel
scroll_panel
tap_text '^set [0-9]' && shot 22-tempo-set 2

echo "== A cross-rhythm, and the big screen"
tap_text '^beat$' && sleep 1
tap_text '^3:4$' && shot 23-three-against-four 2
BIG=$(find_text '^big screen$') || BIG=""
START=$(find_text '^start$') || START=""
tap_at "$START" start
shot 24-three-against-four-playing 4
tap_at "$BIG" 'big screen'
shot 25-big-screen 3
# Keys, as a page-turner pedal or a keyboard sends them: space stops, page down loads the next tape.
adb shell input keyevent KEYCODE_SPACE
sleep 1
adb shell input keyevent KEYCODE_PAGE_DOWN
shot 26-big-screen-next-tape 1
# BACK leaves the big screen, and should leave the app open.
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | tee "$OUT/after-back.txt"
if ! grep -q "$PKG" "$OUT/after-back.txt"; then
  echo "BACK left the app; opening it again"
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
  sleep 2
fi

echo "== The logbook"
tap_text '^practice$' && shot 27-logbook 2

echo "== Keep ticking in the background"
tap_text '^set up$' && sleep 1
scroll_panel
scroll_panel
tap_text 'keep ticking' && shot 28-keep-ticking-on 2
START=$(find_text '^start$') || START=""
tap_at "$START" start
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell dumpsys activity services "$PKG" | grep -E 'ServiceRecord|isForeground|foregroundServiceType' | tee "$OUT/service.txt"
adb shell cmd statusbar expand-notifications
shot 29-notification 3
if ! tap_text '^stop$'; then
  # A quiet notification starts folded; pull it open to reach its STOP button.
  if xy=$(find_text '^Backbeat'); then
    adb shell input swipe "${xy% *}" "${xy#* }" "${xy% *}" $(( ${xy#* } + 500 )) 400
    shot 30-notification-open 2
    tap_text '^stop$'
  fi
fi
sleep 2
adb shell dumpsys activity services "$PKG" | grep -c ServiceRecord | sed 's/^/services still running after STOP: /' | tee -a "$OUT/service.txt"
adb shell cmd statusbar collapse

adb logcat -d > "$OUT/logcat.txt"
echo "== Crashes, if any"
grep -A 25 'FATAL EXCEPTION' "$OUT/logcat.txt" | head -80 || true
grep -E 'BackbeatDemo|Metronome|Ear  |Pedal|Backbeat:' "$OUT/logcat.txt" | head -80 || true
exit 0
