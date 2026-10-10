# Run 2b device check — blocked: the Listen button never opens the Listening sheet

Device: Samsung SM-S921B, serial `RFCWC0SSVDM` (the only device addressed; `-s RFCWC0SSVDM`
on every adb call). Build: `androidApp-debug.apk` from this worktree at commit `0dd05d88`,
installed with `adb install -r` only. No uninstall, no data clear, no sign-out, no network
change. App process pid 3250; logcat filtered to that pid, book titles and authors redacted
from the one line that carried them (`tts-run2b-logcat.txt`).

## What was planned

With a system voice: pause on a sentence, change the speed, play, and check the same
sentence plays at the new rate; the same with a different voice; a speed change during
playback; three quick speed changes. Then the same two checks with a Kokoro voice. All of
them need the reader's **Listening sheet**, which is the only place the voice and the speed
can be changed, and the only way to start read-aloud (sentence-tap detection is enabled by
the sheet).

## What happened

The sheet never opened, so none of the checks could be run and no narration was ever
started on the phone.

1. Book opened, reader shown, `feature_exposed{feature_name=tts, is_available=true}` in the
   log, so read-aloud was offered for this chapter.
2. A tap in the middle of the page reveals the control row — `A1-control-row-visible.png`
   (Contents / Search / Listen / Display). The row auto-hides after about two seconds.
3. Listen pressed within 300 ms of the reveal, at the centre of the button
   (1080×2340 screen, x=663 y=2081): nothing opens, the page does not turn, the row hides
   on its own — `A2-listen-pressed-no-sheet.png`. Tried three ways, each several times:
   `input tap`, `input swipe` with equal coordinates, and `input motionevent DOWN/UP` with a
   120 ms hold. The log shows the touch arriving (`ViewPostIme pointer 0/1`) and a redraw,
   and no TTS, sheet or analytics event of any kind.
4. Control: the same gesture, same timing, same y, on **Search** (x=415) opens the Search
   sheet every time — `A3-search-same-gesture-opens-a-sheet.png`. So the coordinates, the
   timing and the synthetic input are right, and Listen specifically does nothing.
   A tap at Display's x turned the page, which places that x inside the page-turn edge zone;
   Listen's x is not in it (no page turn, ever).
5. Repeated after leaving and re-entering the reader, and on a second, one-chapter local
   book: same result — `A4-second-book-listen-no-sheet.png`.
6. The app-level **Settings → Reader settings → Read aloud** tab has the read-aloud toggle,
   keep-screen-on, progress bar and highlight style, but **no voice and no speed**
   (`A5-read-aloud-settings-has-no-voice-or-speed.png`), so there is no second route to the
   settings this run needed to change.

This is the same symptom the investigation already noted twice as "the Listen button not
opening the sheet", recorded in §5.4 of `docs/tts-investigation.md` as not separated from
tap-timing. This run separates it from tap timing: the control row was verified visible in
the frame before the press, the neighbouring button responds to the identical gesture, and
the touch is visible in the app's own log. It is not written up as a finding here because
run 2b's scope excludes the Listen button (it belongs to run 5), and because a human finger
was not tried — only synthetic input.

## State the phone was left in

No TTS setting was changed, because the sheet that holds them never opened: the voice and
the rate are exactly as the previous run left them, and this run could neither read nor set
them (they are not in the app's Settings screen, and no settings file on disk carries them
in readable form). The owner's request to leave the phone on a System voice at rate 1.0 was
therefore **not carried out** — it needs the Listening sheet. Narration was never started,
nothing was downloaded, no Supertonic terms were accepted, and the app was left at the
library screen.

## Files

- `A1-control-row-visible.png` — the row, in the frame before the Listen press.
- `A2-listen-pressed-no-sheet.png` — right after the press: no sheet.
- `A3-search-same-gesture-opens-a-sheet.png` — the control: Search opens.
- `A4-second-book-listen-no-sheet.png` — second book, same result.
- `A5-read-aloud-settings-has-no-voice-or-speed.png` — the app-level read-aloud settings.
- `tts-run2b-logcat.txt` — the whole session, pid 3250 only, titles redacted.

Screenshots are cropped to exclude the title bar and the progress bar, so no book title
appears in them; the page text is from a public-domain Gutenberg edition.
