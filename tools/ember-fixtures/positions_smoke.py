#!/usr/bin/env python3
"""Interactive radio/sheet/disabled-state checks against real production fixture composables."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--allow-device", action="store_true")
args = parser.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_device:
    parser.error("Real devices require --allow-device")
adb = Path.home() / "Library/Android/sdk/platform-tools/adb"
package = "com.retro99.parrot.fixtures"


def run(*command, capture=False):
    if command[0] == "shell":
        command = ("shell", shlex.join(command[1:]))
    return subprocess.run([str(adb), "-s", args.serial, *command], check=True, timeout=90,
                          stdout=subprocess.PIPE if capture else subprocess.DEVNULL).stdout


def dump():
    run("shell", "uiautomator", "dump", "/sdcard/ember-positions-smoke.xml")
    return ET.fromstring(run("exec-out", "cat", "/sdcard/ember-positions-smoke.xml", capture=True))


def labels(tree):
    return " ".join(n.get("text", "") + " " + n.get("content-desc", "") for n in tree.iter())


def tap(label, tree=None):
    tree = tree if tree is not None else dump()
    node = next(n for n in tree.iter() if label in (n.get("text", "") + n.get("content-desc", "")))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return dump()


def launch(theme, scenario):
    run("shell", "am", "force-stop", package)
    run("shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity", "--es", "theme", theme,
        "--es", "positions", scenario)
    return dump()


def checked(tree):
    return [n for n in tree.iter() if n.get("checkable") == "true" and n.get("checked") == "true"]


for theme in ("day", "eink"):
    tree = launch(theme, "no-selection")
    assert not checked(tree)
    tree = tap("Use this position", tree)
    assert "Move the others" not in labels(tree), "Disabled use button opened the sheet"
    tree = tap("eBook · On this phone", tree)
    assert len(checked(tree)) == 1
    tree = tap("eBook · On this phone", tree)
    assert len(checked(tree)) == 1, "Selected radio toggled off"
    tree = tap("Audiobook · Storyteller", tree)
    assert len(checked(tree)) == 1
    tree = tap("eBook · On this phone", tree)
    tree = tap("Use this position", tree)
    assert "Update 1 version" in labels(tree)
    tree = tap("Goes to about", tree)
    assert "Update 2 versions" in labels(tree)
    tree = tap("Would jump", tree)
    assert "Update 3 versions" in labels(tree)
    tree = tap("download it first", tree)
    assert "Update 3 versions" in labels(tree), "Unavailable target toggled"
    tree = tap("Goes to 5:58:40", tree)
    assert "Update 2 versions" in labels(tree)
    tree = tap("Close", tree)
    assert "Move the others" not in labels(tree)
    tree = tap("Use this position", tree)
    assert "Update 1 version" in labels(tree), "Dismiss did not reset ticks"
    tree = tap("Update 1 version", tree)
    assert "syncing" in labels(tree) and "Move the others" not in labels(tree)
    tree = launch(theme, "pair")
    selected = checked(tree)[0].get("bounds")
    tree = tap("Not started", tree)
    assert checked(tree)[0].get("bounds") == selected, "Not-started row became selected"
    tree = launch(theme, "error")
    assert "Use this position" not in labels(tree)
    tree = tap("Try again", tree)
    assert "Couldn't load" not in labels(tree) and "Use this position" in labels(tree)
    tree = launch(theme, "updating")
    tree = tap("Goes to about", tree)
    assert "Updating" in labels(tree) and len(checked(tree)) == 1, "Applying did not lock targets"
    tree = launch(theme, "partial")
    tree = tap("Details", tree)
    assert "Couldn't update these versions" in labels(tree) and "Can't be updated" in labels(tree)
    tree = launch(theme, "full")
    assert "Storyteller (home)" in labels(tree)
    run("shell", "input", "swipe", "540", "1500", "540", "450", "400")
    tree = dump()
    assert "Storyteller (work)" in labels(tree), "Full fixture did not expose the second server"
    print(f"PASS {theme}: radio, disabled rows, ticks/counts, dismissal, apply notice, retry, lock, details, server names")
