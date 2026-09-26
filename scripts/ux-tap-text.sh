#!/bin/zsh
# usage: ux-tap-text.sh "<visible text>" [waitSeconds] [shotName]
# Dumps the UI hierarchy, taps the node whose text matches exactly, waits,
# and (optionally) saves a screenshot to docs/ux-audit/screenshots/<shotName>.png
D=${UX_DEVICE:-7TEULNB6JJTK75Y5}
TEXT=$1
WAIT=${2:-3}
SHOT=$3

adb -s "$D" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
LINE=$(adb -s "$D" shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep -F "text=\"$TEXT\"" | head -1)

if [[ -z "$LINE" ]]; then
  echo "NOT FOUND: $TEXT"
  exit 1
fi

NUMS=$(echo "$LINE" | grep -o '[0-9][0-9]*')
set -- ${=NUMS}
if [[ $# -lt 4 ]]; then
  echo "NO BOUNDS: $TEXT"
  exit 1
fi
CX=$(( ($1 + $3) / 2 ))
CY=$(( ($2 + $4) / 2 ))

adb -s "$D" shell input tap $CX $CY
echo "tapped $TEXT at $CX,$CY"
sleep $WAIT

if [[ -n "$SHOT" ]]; then
  mkdir -p "$(dirname "$0")/../docs/ux-audit/screenshots"
  adb -s "$D" exec-out screencap -p > "$(dirname "$0")/../docs/ux-audit/screenshots/$SHOT.png"
  echo "shot: $SHOT.png"
fi
