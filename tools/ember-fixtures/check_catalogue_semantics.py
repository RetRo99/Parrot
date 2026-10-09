#!/usr/bin/env python3
"""Check the accessibility tree of captured catalogue fixtures (design/screens/catalogue-*.xml).

No device needed: it reads the UI hierarchies the capture script saved. It checks what a
hierarchy dump can show (labels and touch target sizes). What it cannot show (headings, focus
order, live regions) is listed in docs/opds-phase4-accessibility-report.md.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[2]
shots = root / "design/screens"
DP = 420 / 160  # the capture emulator's density
MIN = round(48 * DP)
failures = []


def tree(view, theme="day"):
    path = shots / f"catalogue-{view}-{theme}.xml"
    if not path.exists():
        failures.append(f"{path.name}: missing capture")
        return None, {}
    top = ET.parse(path).getroot()
    return top, {child: parent for parent in top.iter() for child in parent}


def size(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return x2 - x1, y2 - y1


def target(node, parents):
    """The nearest clickable node at or above this one."""
    while node is not None and node.get("clickable") != "true":
        node = parents.get(node)
    return node


def check(condition, message):
    if not condition:
        failures.append(message)


for theme in ("day", "eink"):
    top, parents = tree("listStates", theme)
    if top is not None:
        labels = [n.get("content-desc", "") for n in top.iter("node")]
        # A row reads "<title>, <author>, <status>" and is one target; the buttons are others.
        rows = [n for n in top.iter("node") if n.get("clickable") == "true" and n.get("content-desc", "").count(", ") >= 2]
        check(len(rows) >= 5, f"listStates-{theme}: expected at least 5 labelled rows, saw {len(rows)}")
        buttons = [n for n in top.iter("node") if re.match(r"(Download|Cancel download of) ", n.get("content-desc", ""))]
        check(buttons, f"listStates-{theme}: no download or cancel buttons")
        for button in buttons:
            hit = target(button, parents)
            check(hit is not None, f"listStates-{theme}: {button.get('content-desc')!r} is not inside a clickable target")
            if hit is not None:
                w, h = size(hit)
                check(w >= MIN and h >= MIN, f"listStates-{theme}: {button.get('content-desc')!r} target is {w}x{h}px, less than 48dp")
                check(hit not in rows, f"listStates-{theme}: {button.get('content-desc')!r} shares a target with its row")
        check(any(l.startswith("Cancel download of ") for l in labels), f"listStates-{theme}: no 'Cancel download of <title>'")

for theme in ("day", "eink"):
    top, parents = tree("editions", theme)
    if top is not None:
        labels = [n.get("content-desc", "") for n in top.iter("node")]
        check("Choose a file" in labels, f"editions-{theme}: the file options are not one group named 'Choose a file'")
        options = [l for l in labels if re.search(r"(MB|Size unknown)(, best match|, can't be opened in Parrot, unavailable)?$", l)]
        check(len(options) >= 4, f"editions-{theme}: expected '<label>, <size>' options, saw {len(options)}")
        check(any(l.endswith(", best match") for l in options), f"editions-{theme}: no 'best match' option")
        check(any(l.endswith("unavailable") for l in options), f"editions-{theme}: no 'unavailable' option")

top, parents = tree("downloads", "day")
if top is not None:
    for node in top.iter("node"):
        label = node.get("content-desc", "")
        if re.match(r"(Retry|Start downloading|Dismiss|Sign in to|Open|Cancel download of) ", label):
            hit = target(node, parents) or node
            w, h = size(hit)
            check(h >= MIN, f"downloads-day: {label!r} is {h}px high, less than 48dp")

for view in ("add", "addSignin"):
    top, _ = tree(view)
    if top is not None:
        labels = " | ".join(n.get("content-desc", "") + n.get("text", "") for n in top.iter("node"))
        check("Add catalogue" in labels or "Sign in" in labels, f"{view}-day: no primary button label")

if failures:
    print("\n".join(failures))
    sys.exit(1)
print("Catalogue semantics checks passed.")
