#!/usr/bin/env python3
"""Capture production positions composables in the isolated fixture APK, never production data."""
import argparse
from pathlib import Path
import shlex
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--allow-device", action="store_true")
parser.add_argument("--output", default="design/screens")
parser.add_argument("--themes", nargs="+", default=["day", "eink"], choices=["day", "eink", "night"])
parser.add_argument("--scenarios", nargs="+", default=None)
args = parser.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_device:
    parser.error("A real device requires explicit --allow-device authorization")
root = Path(__file__).resolve().parents[2]
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
    run("shell", "uiautomator", "dump", "/sdcard/ember-positions.xml")
    data = run("exec-out", "cat", "/sdcard/ember-positions.xml", capture=True)
    return data, ET.fromstring(data)


def focused():
    window = run("shell", "dumpsys", "window", capture=True).decode()
    current = next((line for line in window.splitlines() if "mCurrentFocus=" in line), "")
    app = next((line for line in window.splitlines() if "mFocusedApp=" in line), "")
    assert package in current or ("Pop-up window" in current and package in app), "Fixture lost focus"


run("install", "--no-streaming", "-r", str(root / "tools/ember-fixtures/build/outputs/apk/debug/ember-fixtures-debug.apk"))
for theme in args.themes:
    for scenario in (args.scenarios or ("normal", "pair", "error", "apply", "apply-all", "apply-start", "apply-no-match", "apply-not-supported",
                     "loading", "unlinked", "updating", "success", "partial", "none", "details", "no-selection", "full")):
        run("shell", "am", "force-stop", package)
        run("shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity", "--es", "theme", theme,
            "--es", "positions", scenario)
        xml, tree = hierarchy()
        labels = " ".join(node.get("text", "") + " " + node.get("content-desc", "") for node in tree.iter())
        assert any(title in labels for title in ("Reading positions", "Move the others here?", "Couldn't update these versions")), f"Wrong screen: {scenario}"
        expected = {
            "error": "Couldn't load positions", "unlinked": "aren't linked any more",
            "loading": "Loading", "updating": "Updating", "apply": "Tick the versions",
            "apply-all": "Would jump to the end", "pair": "Not started",
            "success": "syncing", "partial": "couldn't update", "none": "Couldn't update any",
            "apply-start": "Would jump to the start", "apply-no-match": "Can't be matched to this place",
            "apply-not-supported": "Can't be updated from here yet", "details": "Couldn't update these versions",
        }.get(scenario)
        if expected:
            assert expected in labels, f"Missing {expected!r} in {scenario}"
        if scenario == "normal":
            assert "Read-along" in labels and "Not started yet" in labels
            readalong = next(node for node in tree.iter() if "Read-along" in node.get("content-desc", ""))
            assert readalong.get("enabled") == "false", "Unstarted version must not be selectable"
        if scenario == "apply-no-match":
            assert "download it first" not in labels
        focused()
        screenshot = run("exec-out", "screencap", "-p", capture=True)
        focused()
        (output / f"positions-{scenario}-{theme}.png").write_bytes(screenshot)
        (output / f"positions-{scenario}-{theme}.xml").write_bytes(xml)
        print(f"Captured positions-{scenario}-{theme}.png", flush=True)
