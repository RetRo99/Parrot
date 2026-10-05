#!/usr/bin/env python3
"""Capture the isolated fixture APK on an explicitly authorized target."""
import argparse
from pathlib import Path
import subprocess
import shlex
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--output", default="design/ember/verification/a1")
parser.add_argument("--allow-device", action="store_true", help="Explicitly authorize installation of the fixture APK on a real device")
parser.add_argument("--themes", nargs="+", choices=("day", "eink", "night"), default=("day", "eink"))
args = parser.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_device:
    parser.error("Real devices require explicit --allow-device authorization. Only the fixture APK is installed.")

root = Path(__file__).resolve().parents[2]
adb = Path.home() / "Library/Android/sdk/platform-tools/adb"
package = "com.retro99.parrot.fixtures"
output = root / args.output
output.mkdir(parents=True, exist_ok=True)


def run(*command, capture=False):
    if command[0] == "shell":
        command = ("shell", shlex.join(command[1:]))
    return subprocess.run([str(adb), "-s", args.serial, *command], check=True,
                          timeout=180, stdout=subprocess.PIPE if capture else subprocess.DEVNULL).stdout


run("wait-for-device")
assert run("shell", "getprop", "sys.boot_completed", capture=True).strip() == b"1", "Target has not booted yet"
run("install", "--no-streaming", "-r", str(root / "tools/ember-fixtures/build/outputs/apk/debug/ember-fixtures-debug.apk"))


def assert_fixture_focused():
    window = run("shell", "dumpsys", "window", capture=True).decode()
    assert any("mCurrentFocus=" in line and package in line for line in window.splitlines()), "Phone switched away from fixtures"


for theme in args.themes:
    for screen in ("series", "detail", "offline", "empty"):
        run("shell", "am", "force-stop", package)
        launch = ["shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity", "--es", "theme", theme]
        if screen == "detail":
            launch += ["--es", "series", "The Salt Roads"]
        elif screen in ("offline", "empty"):
            launch += ["--ez", screen, "true"]
        run(*launch)
        # UIAutomator waits for idle; no fixed sleep or fabricated rendering.
        run("shell", "uiautomator", "dump", "/sdcard/ember-fixture.xml")
        hierarchy = run("exec-out", "cat", "/sdcard/ember-fixture.xml", capture=True)
        labels = {node.get("text", "") for node in ET.fromstring(hierarchy).iter()}
        if screen == "detail":
            assert "The Salt Roads" in labels and "Browse" not in labels, "Fixture detail did not open"
        else:
            assert "Browse" in labels, "Fixture Browse did not open"
        assert_fixture_focused()
        screenshot = run("exec-out", "screencap", "-p", capture=True)
        assert_fixture_focused()
        (output / f"{screen}-{theme}.png").write_bytes(screenshot)
        (output / f"{screen}-{theme}.xml").write_bytes(hierarchy)
        print(f"Captured {screen}-{theme}.png", flush=True)
