#!/usr/bin/env bash
set -euo pipefail

# Measures per-sentence TTS latency on a connected device/emulator.
#
# Scenarios:
#   COLD   - fresh process: engine load + synthesis (tap -> audio file ready)
#   GEN-N  - warm engine, empty cache: pure per-sentence synthesis (cache cleared)
#   HIT-N  - warm engine, warm cache: cached replay path
#
# Requires: a debug build installed, one imported book, and TTS enabled with the
# target neural voice selected (defaults below assume the test fixture book).
#
# Usage:
#   scripts/benchmark-tts-sentences.sh [runs]
#   DEVICE=emulator-5556 BOOK_TITLE="My Book" scripts/benchmark-tts-sentences.sh 5

RUNS="${1:-5}"
SERIAL="${DEVICE:-$(adb devices | awk 'NR==2{print $1}')}"
BOOK_TITLE="${BOOK_TITLE:-Unified Library Demo}"
PKG="com.retro99.parrot"

a() { adb -s "$SERIAL" "$@"; }
dump() { a shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1; a shell cat /sdcard/w.xml; }
findb() { echo "$2" | grep -o "$1[^>]*" | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1; }
tapbounds() {
  local B="$1"; [ -z "$B" ] && return 1
  local L=$(echo "$B" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1/')
  local T=$(echo "$B" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\2/')
  local R=$(echo "$B" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\3/')
  local Bt=$(echo "$B" | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\4/')
  a shell input tap $(( (L + R) / 2 )) $(( (T + Bt) / 2 ))
}
waitb() {
  for _ in $(seq 1 12); do
    local X B
    X=$(dump); B=$(findb "$1" "$X")
    [ -n "$B" ] && { echo "$B"; return 0; }
    sleep 1.5
  done
  return 1
}

# One preview run: clears the audio cache (unless KEEP_CACHE=1), taps Preview,
# and reports wall-clock + the app's existing synthesis-complete log. Cached
# previews intentionally have no per-hit log, so their timing is not reported.
run_preview() {
  local label="$1"
  [ "${KEEP_CACHE:-0}" != "1" ] && a shell "run-as $PKG rm -rf cache/tts"
  a logcat -c
  local X B
  X=$(dump); B=$(findb 'text="Preview"' "$X")
  if [ -z "$B" ]; then echo "$label | NO-UI"; return; fi
  local T0 T1 RESULT
  T0=$(date +%s.%N)
  tapbounds "$B"
  if [ "${KEEP_CACHE:-0}" = "1" ]; then
    sleep 1
    printf '%-12s cache retained | timing unavailable (no cache-hit log)\n' "$label"
    sleep 3
    return
  fi
  RESULT="no result within poll ceiling"
  local ITERS=36
  for _ in $(seq 1 "$ITERS"); do
    sleep 5
    local LINE
    LINE=$(a logcat -d -v epoch -s 'SherpaOnnxTts:*' 'SupertonicOnnxTts:*' \
      | grep -m2 -E "synthesize done|synthesize start" | tail -2 | tr '\n' '; ')
    if echo "$LINE" | grep -q "synthesize done"; then RESULT="$LINE"; break; fi
  done
  T1=$(date +%s.%N)
  printf '%-12s wall=%6.1fs | %s\n' "$label" "$(echo "$T1 $T0" | awk '{print $1-$2}')" "$RESULT"
  sleep 3
}

echo "device=$SERIAL book='$BOOK_TITLE' runs=$RUNS"
SCREEN=$(a shell wm size | grep -o '[0-9]*x[0-9]*$' | tail -1)
CX=$(echo "$SCREEN" | cut -dx -f1); CY=$(echo "$SCREEN" | cut -dx -f2)
CX=$((CX / 2)); CY=$((CY / 2))
a shell am force-stop "$PKG"
a shell am start -n "$PKG/com.retro99.parrot.android.MainActivity" >/dev/null

# Navigate: library -> book -> reader -> menu -> voice settings
B=$(waitb "text=\"$BOOK_TITLE\"") || { echo "FAIL: book '$BOOK_TITLE' not found"; exit 1; }
tapbounds "$B"
B=$(waitb 'content-desc="Read aloud"' 3)
if [ -z "$B" ]; then a shell input tap "$CX" "$CY"; sleep 0.8; B=$(waitb 'content-desc="Read aloud"' 4); fi
[ -n "$B" ] || { echo "FAIL: reader toolbar"; exit 1; }
tapbounds "$B"; sleep 0.8
X=$(dump); B=$(findb 'text="Voice settings"' "$X")
if [ -z "$B" ]; then
  tapbounds "$(findb 'text="Text-to-Speech"' "$X")"; sleep 1
  a shell input tap "$CX" "$CY"; sleep 0.5
  tapbounds "$(waitb 'content-desc="Read aloud"' 4)"; sleep 0.8
  X=$(dump); B=$(findb 'text="Voice settings"' "$X")
fi
tapbounds "$B" || { echo "FAIL: voice settings"; exit 1; }
sleep 2

echo "===== benchmarks ====="
run_preview "COLD"
for i in $(seq 1 "$RUNS"); do run_preview "GEN-$i"; done
KEEP_CACHE=1 run_preview "HIT-1"
KEEP_CACHE=1 run_preview "HIT-2"
