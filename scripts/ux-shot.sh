#!/bin/zsh
# Usage: ux-shot.sh <name> [tap x y] [swipe x1 y1 x2 y2] [text "str"] [key KEYCODE] [wait secs]
D=${UX_DEVICE:-7TEULNB6JJTK75Y5}
DIR="$(cd "$(dirname "$0")/.." && pwd)/docs/ux-audit/screenshots"
mkdir -p "$DIR"
NAME=$1; shift
while [ $# -gt 0 ]; do
  case "$1" in
    tap) adb -s $D shell input tap $2 $3; shift 3;;
    swipe) adb -s $D shell input swipe $2 $3 $4 $5; shift 5;;
    text) adb -s $D shell input text "$2"; shift 2;;
    key) adb -s $D shell input keyevent $2; shift 2;;
    wait) sleep $2; shift 2;;
    *) shift;;
  esac
done
sleep 1
adb -s $D exec-out screencap -p > "$DIR/$NAME.png"
echo "$DIR/$NAME.png"
