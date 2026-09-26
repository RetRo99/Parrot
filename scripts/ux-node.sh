#!/bin/zsh
# Find a UI node by its visible text or content-description on the connected device.
# Usage: ux-node.sh <text> [device_id]
# Prints center tap coordinates and the node bounds, so flows can be driven by label
# instead of hard-coded coordinates.
D=${2:-${UX_DEVICE:-7TEULNB6JJTK75Y5}}
TEXT="$1"
adb -s "$D" shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1
adb -s "$D" pull /sdcard/uidump.xml /tmp/uidump.xml >/dev/null 2>&1
python3 - "$TEXT" <<'PY'
import re, sys
needle = sys.argv[1].lower()
data = open('/tmp/uidump.xml', encoding='utf-8', errors='ignore').read()
hits = []
for m in re.finditer(r'<node[^>]*>', data):
    tag = m.group(0)
    text = re.search(r'text="([^"]*)"', tag)
    desc = re.search(r'content-desc="([^"]*)"', tag)
    label = (text.group(1) if text else '') or (desc.group(1) if desc else '')
    if needle in label.lower():
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if bounds:
            x1, y1, x2, y2 = map(int, bounds.groups())
            cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
            hits.append((label, cx, cy, bounds.group(0)[8:-1]))
if not hits:
    print(f'NO MATCH for "{needle}"')
    sys.exit(1)
for label, cx, cy, b in hits:
    print(f'{cx} {cy}\t{b}\t{label}')
PY
