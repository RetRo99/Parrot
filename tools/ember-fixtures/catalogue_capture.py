#!/usr/bin/env python3
"""Capture the book catalogue (OPDS) fixtures of the isolated fixture APK.

The list of fixtures is read from CatalogueFixtures.kt, so a fixture added there is captured
without touching this script. Each capture goes to design/screens/catalogue-<view>-<theme>.png
with its UI hierarchy next to it.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
registry = root / "tools/ember-fixtures/src/main/kotlin/com/retro99/parrot/fixtures/CatalogueFixtures.kt"
# fixture(view = "name", expect = "text") — literals on one line, as the registry asks.
fixtures = dict(re.findall(r'^\s*fixture\(\s*view\s*=\s*"([^"]+)"\s*,\s*expect\s*=\s*"((?:[^"\\]|\\.)*)"', registry.read_text(), re.M))

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--allow-device", action="store_true", help="Explicitly authorize installing the fixture APK on a real device")
parser.add_argument("--output", default="design/screens")
parser.add_argument("--themes", nargs="+", default=["day", "eink"], choices=["day", "eink", "night"])
parser.add_argument("--views", nargs="+", default=None, help="Only these fixtures (default: all)")
parser.add_argument("--list", action="store_true", help="Print the fixtures and exit")
parser.add_argument("--skip-install", action="store_true", help="Use the fixture APK already installed on the target")
args = parser.parse_args()
if args.list:
    print("\n".join(fixtures))
    sys.exit(0)
if not fixtures:
    parser.error(f"No fixtures found in {registry}")
unknown = [view for view in (args.views or []) if view not in fixtures]
if unknown:
    parser.error(f"Unknown fixture(s): {', '.join(unknown)}. Known: {', '.join(fixtures)}")
if not args.serial.startswith("emulator-") and not args.allow_device:
    parser.error("A real device requires explicit --allow-device authorization. Only the fixture APK is installed.")

adb = Path.home() / "Library/Android/sdk/platform-tools/adb"
package = "com.retro99.parrot.fixtures"
output = root / args.output
output.mkdir(parents=True, exist_ok=True)


def run(*command, capture=False):
    if command[0] == "shell":
        command = ("shell", shlex.join(command[1:]))
    return subprocess.run([str(adb), "-s", args.serial, *command], check=True, timeout=180,
                          stdout=subprocess.PIPE if capture else subprocess.DEVNULL).stdout


def hierarchy():
    # UIAutomator waits for idle; no fixed sleep.
    run("shell", "uiautomator", "dump", "/sdcard/ember-catalogue.xml")
    data = run("exec-out", "cat", "/sdcard/ember-catalogue.xml", capture=True)
    return data, ET.fromstring(data)


def focused():
    window = run("shell", "dumpsys", "window", capture=True).decode()
    current = next((line for line in window.splitlines() if "mCurrentFocus=" in line), "")
    app = next((line for line in window.splitlines() if "mFocusedApp=" in line), "")
    assert package in current or ("pop-up window" in current.casefold() and package in app), "Fixture lost focus"


run("wait-for-device")
assert run("shell", "getprop", "sys.boot_completed", capture=True).strip() == b"1", "Target has not booted yet"
# A fresh emulator covers the first full-screen app with a "Viewing full screen" notice.
run("shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed")
if not args.skip_install:
    run("install", "--no-streaming", "-r", str(root / "tools/ember-fixtures/build/outputs/apk/debug/ember-fixtures-debug.apk"))
for theme in args.themes:
    for view in args.views or fixtures:
        # The fixture registry is UTF-8 Kotlin source; decode Kotlin/JSON-style
        # escapes without round-tripping literal non-ASCII text through latin-1.
        expected = json.loads(f'"{fixtures[view]}"')
        # Right after an install the system can still be on top ("Updating...") and may then
        # reopen the app without the fixture's extras. Each dump waits for idle, so there is
        # no fixed sleep; a fixture that is not on screen is launched again, three times at most.
        for _ in range(3):
            run("shell", "am", "force-stop", package)
            run("shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity", "--es", "theme", theme, "--es", "catalogue", view)
            xml, tree = hierarchy()
            labels = " ".join(node.get("text", "") + " " + node.get("content-desc", "") for node in tree.iter())
            if expected in labels or "Unknown catalogue fixture" in labels:
                break
        assert "Unknown catalogue fixture" not in labels, f"The APK has no fixture {view!r}: rebuild it"
        assert expected in labels, f"Missing {expected!r} in {view} ({theme}). On screen: {labels.strip()[:300]!r}"
        # A second idle barrier catches a frame still laying out the generated title cover.
        xml, tree = hierarchy()
        focused()
        screenshot = run("exec-out", "screencap", "-p", capture=True)
        focused()
        (output / f"catalogue-{view}-{theme}.png").write_bytes(screenshot)
        (output / f"catalogue-{view}-{theme}.xml").write_bytes(xml)
        print(f"Captured catalogue-{view}-{theme}.png", flush=True)
