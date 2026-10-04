"""Run saved-mark DOM regression tests in Chromium (set CHROMIUM to its executable)."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


root = Path(__file__).resolve().parents[1]
source = root / "feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/navigator/SavedPageScript.kt"
install = source.read_text().split('private const val INSTALL = """', 1)[1].split('"""', 1)[0]
browser = os.environ.get("CHROMIUM") or shutil.which("chromium") or shutil.which("google-chrome")
if not browser:
    browser = "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"

tests = r"""
const P = window.parrotSaved;
let count = 0;
function eq(actual, expected, message) {
    if (actual !== expected) throw new Error(message + ': ' + actual + ' != ' + expected);
    count++;
}
function tap(x, y) {
    document.dispatchEvent(new TouchEvent('touchstart', {
        touches: [new Touch({identifier: 1, target: document.body, clientX: x, clientY: y})]
    }));
    return P.takeSavedTap();
}
function center(rect) { return [(rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2]; }
const quote = document.getElementById('text').textContent;
const mark = {id: 'one', quote, tappable: true, ruleCount: 2, ruleColor: -16777216, barColor: -16777216};
function draw(options = {}) { P.page([mark], {marginLeft: 20, marginRight: 20, ...options}); }
for (const scroll of [false, true]) {
    document.documentElement.style.columnWidth = scroll ? 'auto' : '800px';
    draw({scroll});
    const range = document.createRange();
    range.selectNodeContents(document.getElementById('text'));
    const rect = range.getBoundingClientRect();
    eq(tap(rect.left + 2, rect.top + 2), 'one', 'left-zone highlight');
    eq(tap(rect.right - 2, rect.top + 2), 'one', 'right-zone highlight');
    eq(P.takeSavedTap(), null, 'hit consumed once');
    const boxes = [...document.getElementById('parrot-marks').children];
    eq(boxes.length > 1, true, 'rules and note bar rendered');
    for (const box of boxes) {
        eq(getComputedStyle(box).pointerEvents, 'auto', 'mark is interactive');
        eq(tap(...center(box.getBoundingClientRect())), 'one', 'rule or bar opens item');
    }
    eq(tap(2, 250), null, 'blank left margin');
    eq(tap(798, 250), null, 'blank right margin');
    eq(tap(400, 250), null, 'blank middle');
    draw({scroll, marginLeft: 0, marginRight: 0});
    eq([...document.getElementById('parrot-marks').children].every(b => b.getBoundingClientRect().height <= 3),
        true, 'narrow margins omit bars');
    for (const box of document.getElementById('parrot-marks').children) {
        eq(tap(...center(box.getBoundingClientRect())), 'one', 'narrow-margin rule');
    }
    P.page([{...mark, tappable: false, barColor: 0}], {scroll});
    eq(tap(rect.left + 2, rect.top + 2), null, 'non-highlight range does not intercept');
    P.page([], {scroll});
    eq(document.getElementById('parrot-marks').children.length, 0, 'redraw removes targets');
    eq(tap(rect.left + 2, rect.top + 2), null, 'removed highlight does not intercept');
}
document.body.innerHTML = '<pre>PASS ' + count + ' saved tap assertions</pre>';
"""

with tempfile.TemporaryDirectory(prefix="parrot-tap-tests-") as directory:
    page = Path(directory) / "taps.html"
    html = """<!doctype html><meta charset="utf-8"><style>
    html { width:800px; } body { margin:20px; }
    #text { font:20px/40px serif; white-space:nowrap; }
    </style><span id="text">A highlight stretches across both navigation zones and opens its detail.</span>
    <script>try {""" + install + tests + """} catch (error) {
    document.body.innerHTML = '<pre>FAIL ' + error.message + '</pre>';
    }</script>"""
    if len(sys.argv) == 2:
        Path(sys.argv[1]).write_text(html)
        raise SystemExit(0)
    page.write_text(html)
    result = subprocess.run([
        browser, "--headless", "--disable-gpu", "--no-sandbox", "--no-first-run",
        f"--user-data-dir={directory}/profile", "--window-size=800,600", "--dump-dom", page.as_uri(),
    ], capture_output=True, text=True, check=True, timeout=30)
    if "<pre>PASS " not in result.stdout:
        raise SystemExit(result.stdout + result.stderr)
    print(result.stdout.split("<pre>")[1].split("</pre>")[0])
