#!/bin/zsh
# Usage: ux-tap-desc.sh <screenshot-name> <exact content-description> [wait-secs]
# Dumps the UI hierarchy, taps the center of the first node whose content-desc
# matches, then saves a screenshot under docs/ux-audit/screenshots/<name>.png
D=${UX_DEVICE:-7TEULNB6JJTK75Y5}
DIR="$(cd "$(dirname "$0")/.." && pwd)/docs/ux-audit/screenshots"
mkdir -p "$DIR"
NAME=$1
TEXT=$2
WAIT=${3:-3}

adb -s "$D" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
LINE=$(adb -s "$D" shell cat /sdcard/ui.xml | tr '<' '\n<' \
  | grep -F "content-desc=\"$TEXT\"" | head -1)

if [ -z "$LINE" ]; then
  echo "NOT_FOUND: $TEXT"
  exit 1
fi

BOUNDS=$(echo "$LINE" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
read -r X1 Y1 X2 Y2 <<< "$(echo "$BOUNDS" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')"
CX=$(( (X1 + X2) / 2 ))
CY=$(( (Y1 + Y2) / 2 ))

adb -s "$D" shell input tap "$CX" "$CY"
sleep "$WAIT"
adb -s "$D" exec-out screencap -p > "$DIR/$NAME.png"
echo "$DIR/$NAME.png (tapped $TEXT at $CX,$CY)"
