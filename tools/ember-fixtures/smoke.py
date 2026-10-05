#!/usr/bin/env python3
"""Exercise submit-only search, caller Back and process-restored search/scroll in fixtures."""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--allow-device", action="store_true")
parser.add_argument("--output", default="design/ember/verification/a1/smoke.txt")
args = parser.parse_args()
if not args.serial.startswith("emulator-") and not args.allow_device:
    parser.error("Real-device tests require explicit --allow-device authorization")
adb = Path.home() / "Library/Android/sdk/platform-tools/adb"
package = "com.retro99.parrot.fixtures"
results = []


def run(*command):
    if command[0] == "shell":
        command = ("shell", shlex.join(command[1:]))
    return subprocess.run([str(adb), "-s", args.serial, *command], check=True,
                          timeout=60, stdout=subprocess.PIPE).stdout


def hierarchy(expect_fixture=True):
    run("shell", "uiautomator", "dump", "/sdcard/ember-fixture-smoke.xml")
    tree = ET.fromstring(run("exec-out", "cat", "/sdcard/ember-fixture-smoke.xml"))
    if expect_fixture:
        assert any(node.get("package") == package for node in tree.iter()), "Phone switched away from fixtures"
    return tree


def labels(tree):
    return {node.get("text", "") for node in tree.iter()}


def bounds(node):
    return [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]


def tap(node):
    assert node.get("package") == package, "Refusing to tap outside the fixture app"
    left, top, right, bottom = bounds(node)
    run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def start(long_list=False):
    run("shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity",
        "--es", "theme", "eink", "--ez", "longlist", str(long_list).lower())


def recreate(long_list=False):
    before = run("shell", "pidof", package).strip()
    run("shell", "input", "keyevent", "KEYCODE_HOME")
    hierarchy(False)  # Wait for the launcher to settle, so onStop has saved fixture state.
    assert before.isdigit(), "Expected exactly one fixture process"
    # Xiaomi may ignore `am kill`. run-as can signal only this debuggable app's own UID.
    run("shell", "run-as", package, "kill", "-9", before.decode())
    start(long_list)
    after = run("shell", "pidof", package).strip()
    assert before != after, "Fixture process was not killed/recreated"
    return hierarchy()


run("shell", "am", "force-stop", package)
start()
tree = hierarchy()
tap(next(node for node in tree.iter() if node.get("class") == "android.widget.EditText"))
run("shell", "input", "text", "Salt")
assert "Orchard Quartet" in labels(hierarchy()), "E-ink filtered before submit"
run("shell", "input", "keyevent", "KEYCODE_ENTER")
tree = hierarchy()
assert "The Salt Roads" in labels(tree) and "Orchard Quartet" not in labels(tree)
results.append("PASS: E-ink search changes results only on submit")
tree = recreate()
assert "Salt" in labels(tree) and "Orchard Quartet" not in labels(tree)
results.append("PASS: submitted search restored after actual fixture process death")
tap(next(node for node in tree.iter() if node.get("text") == "The Salt Roads"))
assert "Browse" not in labels(hierarchy())
run("shell", "input", "keyevent", "KEYCODE_BACK")
tree = hierarchy()
assert "Browse" in labels(tree) and "Salt" in labels(tree) and "Orchard Quartet" not in labels(tree)
results.append("PASS: detail Back preserves the calling list's submitted search")

run("shell", "am", "force-stop", package)
start(True)
tree = hierarchy()
scroll = next(node for node in tree.iter() if node.get("scrollable") == "true")
left, top, right, bottom = bounds(scroll)
x = str((left + right) // 2)
run("shell", "input", "swipe", x, str(bottom - 100), x, str(top + 100), "500")
tree = hierarchy()
visible = sorted(label for label in labels(tree) if label.startswith("Series "))
assert visible, "Long fixture list did not scroll"
restored = recreate(True)
assert visible == sorted(label for label in labels(restored) if label.startswith("Series "))
results.append("PASS: list scroll restored after actual fixture process death")

output = Path(__file__).resolve().parents[2] / args.output
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("\n".join(results) + "\n")
print("\n".join(results))
run("shell", "am", "force-stop", package)
run("shell", "am", "start", "-W", "-n", f"{package}/.FixtureActivity", "--es", "theme", "day")
