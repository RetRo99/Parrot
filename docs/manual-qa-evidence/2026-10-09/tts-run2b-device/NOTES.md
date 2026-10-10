# Run 2b device check (re-run) — 2026-10-09

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`, the only device addressed (`-s RFCWC0SSVDM`
on every adb call; two other devices were attached and never addressed). Build:
`androidApp-debug.apk` from this worktree at commit `9c97acf6`, installed with
`adb install -r` only. No uninstall, no data clear, no sign-out, no network change. No
product code and no test was changed in this run.

Purpose: check on a phone that run 2b's fixes for TTS-F06 and TTS-F07 work, using the
long-press route to the Listening sheet that run 2b's blocked attempt did not try.

One line per check, written as the run went.

## Log

- Build `:androidApp:assembleDebug` succeeded; APK installed on `RFCWC0SSVDM`.
- Book: *Alice's Adventures in Wonderland* (Gutenberg, public domain), on-device eBook,
  opened at Chapter IV, page 7 of 13. `feature_exposed{feature_name=tts, is_available=true}`.

### Step 1 — opening the sheet

- Control row revealed by a tap at the middle of the page. A `uiautomator` dump taken while
  it was up gives the four buttons at y 2001–2159: Contents `[53,2001][280,2159]`, Search
  `[301,…]`, **Listen `[550,2001][778,2159]`, the only one with `long-clickable="true"`**,
  Display `[799,…]`. Listen centre = **(664, 2080)**.
- **The whole of run 2b's "Listen does nothing" is a tap-timing artefact.** A tap sent
  ~300–400 ms after the reveal (run 2b's timing, and my own first try) lands while the row
  is still expanding and hits nothing: no card, no log line, exactly run 2b's symptom
  (`B1-short-tap-too-early-no-card.png`). The row needs ~0.9 s to settle. With the reveal
  tap, `sleep 0.9`, then the Listen tap, it works every time.
- **Short tap on Listen → the compact now-playing card appears** (yes), and the Listen
  button relabels itself *Audio* — `B2-short-tap-card-appears.png`. No audio starts. The
  card reads "Voice · sentence 1 of 0" (the known TTS-F24 zero count).
- **Tap on the compact card → the full Listening sheet opens** (yes, first try) —
  `B3-sheet-open-system-voice-rate-1.png`. (The card sits in the bottom bar and is drawn at
  zero height while the control row is up, so this route needs the row to have auto-hidden;
  (664, 2080) hits the card once it has.)
- **Voice and rate when the sheet first opened: System voice, "On this phone", Rate 1×,
  Pitch Normal.** The sheet also says "This book has no narration — reading with your
  device voice."
- **Second short tap on Listen → the card goes away** (yes): the dump after it has no
  "Voice · sentence 1 of 0" node and the button is labelled *Listen* again, not *Audio*.
- **Long press on Listen → the full Listening sheet opens, first try, one try needed.**
  `input swipe 664 2080 664 2080 800` after a reveal + 0.9 s settle —
  `B4-long-press-opens-sheet.png`. So all three routes in the code work on the phone; only
  the timing of the synthetic tap was ever wrong.
- Sheet control centres used for the rest of the run (from the dump, sheet fully open):
  Play/Pause (540, 1110) · Rate − (189, 1643) · Rate + (398, 1643) · Change voice
  (915, 1337) · Close (954, 639) · Stop listening (188, 2206).

### Step 2 — a setting changed while paused (TTS-F06), System voice — PASSED

The sheet's "Sentence 1 of 0" becomes a real count ("Sentence 63 of 151") as soon as
playback starts, so TTS-F24's zero is confined to the not-yet-played state.

**2a, speed.** System voice, Rate 1×. Play → read from sentence 60-odd to **63**, on to
**69**; the start logged `tts_action=controls` attempted 20:26:32.744 / succeeded
20:26:34.348. Paused at **sentence 69**, Rate 1× (`C1-f06-paused-sentence-69-rate-1.png`).
Rate + once → **Rate 1.1×, sentence still 69**, and the only log line is
`tts_rate_changed{rate=1.1}` at 20:27:23.929 — no `engine.stop`, no synthesis
(`C2-f06-rate-1.1-still-sentence-69.png`). Play → **restarted at sentence 69** and ran on to
73; fresh `TtsRouter: synthesize` lines from 20:27:54.836; operation lines exactly:

    20:27:54.822 tts_playback_operation {tts_action=resume, tts_outcome=attempted}
    20:27:55.126 tts_playback_operation {tts_action=resume, tts_outcome=succeeded, duration_ms=306}

One attempted, one succeeded, **action `resume`** — not the `controls` that TTS-F06
originally produced. The position is kept and the press is reported as a resume: the fix
works on the phone. *Differs from the brief in one detail:* the `synthesize` log line prints
`voice=` and `engine=` only, it carries **no rate field**, so "the new rate in the log" can
only be read from `tts_rate_changed{rate=1.1}` and from the sheet, not from the synthesize
lines; the lines themselves are fresh (new timestamps after the play press).

**2b, voice.** Continued at Rate 1.1×, paused at **sentence 82**
(`C3-f06-paused-sentence-82-before-voice-change.png`). Voices → United Kingdom → Voice 1;
`tts_voice_selected{is_neural=false}` at 20:29:24.934, and back on the sheet **the sentence
is still 82**, again with no stop and no synthesis. Play → **started at sentence 82**, and
the synthesize lines now carry the new voice (`voice=en-GB-language`, where before the
change they were `voice=null`). Operation lines exactly:

    20:29:53.020 tts_playback_operation {tts_action=resume, tts_outcome=attempted}
    20:29:53.908 tts_playback_operation {tts_action=resume, tts_outcome=succeeded, duration_ms=889}

### Step 3 — a setting changed while playing (TTS-F07), System voice — PASSED

**3a, one speed change.** Playing at Rate 1.1× on **sentence 100**. Rate + → 1.2×.
Narration **carried on from the same sentence** (100 → 103 four seconds later, still
`Pause` on the button, no gap in the position stream). Operation lines, in order:

    20:31:24.528 tts_rate_changed {rate=1.2}
    20:31:24.530 tts_playback_operation {tts_outcome=attempted}
    20:31:24.910 tts_playback_operation {tts_outcome=succeeded, duration_ms=381}

One attempted, one terminal outcome — the restart is inside the attempt machinery now,
which is exactly what TTS-F07 was about. *Differs from the brief:* the
`tts_playback_operation` **event carries no `tts_action` parameter at all** for this
restart (where the `controls` and `resume` starts above both printed one). The action is
not lost — the paired diagnostic breadcrumbs say
`entry_point=settings_change action=start_tts_playback`, both under one
`correlation_id=d1657584-…` — it simply is not on the analytics event. Recorded, not
investigated.

**3b, three changes as fast as possible.** Three `input tap` on Rate + back to back
(1.2× → 1.5×, all three landed within 90 ms). **Still playing afterwards**, sentence 108,
Rate 1.5×, no crash, **no `failed` outcome anywhere**
(`D1-f07-still-playing-after-three-quick-changes.png`). Every operation line of this step,
in order — three attempts, each with its own correlation id, the first two superseded:

    20:31:51.747 tts_rate_changed {rate=1.3}
    20:31:51.748 tts_playback_operation {tts_outcome=attempted}                     # corr 1edb9e34
    20:31:51.797 tts_rate_changed {rate=1.4}
    20:31:51.798 tts_playback_operation {tts_outcome=cancelled, duration_ms=51,
                                         tts_reason_code=operation_cancelled}       # corr 1edb9e34
    20:31:51.799 tts_playback_operation {tts_outcome=attempted}                     # corr 970c299f
    20:31:51.833 tts_rate_changed {rate=1.5}
    20:31:51.834 tts_playback_operation {tts_outcome=cancelled, duration_ms=35,
                                         tts_reason_code=operation_cancelled}       # corr 970c299f
    20:31:51.835 tts_playback_operation {tts_outcome=attempted}                     # corr a0473e8f
    20:31:52.187 tts_playback_operation {tts_outcome=succeeded, duration_ms=353}    # corr a0473e8f

Three attempts, three terminal outcomes, one per correlation id, and no attempt got two —
the supersede path behaves as `6e919edb` describes.

### Step 4 — the same with Kokoro — F06 passed; F07 passed on the second attempt

Voice changed to **Kokoro · Heart** while paused at sentence 125, Rate 1.5×: the sentence
stayed at 125 (a third TTS-F06 data point, this time across engines). Kokoro takes
2.5–9 s per sentence on this phone, so every step below was given time rather than tapped
again.

**4a, F06 with Kokoro — passed.** Play → resumed at 125 and read on to **137**
(`Kokoro synthesize start: sid=0 speed=1.5` lines throughout, so the rate reaches the
engine). Paused at **sentence 137**. Rate − → 1.4×, **sentence still 137**. Play →
**started at sentence 137**, `Kokoro synthesize start: … speed=1.4`:

    20:35:16.959 tts_playback_operation {tts_action=resume, tts_outcome=attempted}
    20:35:25.669 tts_playback_operation {tts_action=resume, tts_outcome=succeeded, duration_ms=8711}

**4b, F07 with Kokoro, first attempt — the tap fell in a synthesis gap, so it took the
*paused* path.** With Kokoro, playback repeatedly reaches `state=ENDED` / `isPlaying=false`
for a second or two while the next sentence is still synthesising. My Rate − tap at
20:35:50.851 landed 61 ms inside such a gap (`ENDED` at 20:35:50.790), so the controller saw
`isPlaying == false` and took the paused branch: **only `tts_rate_changed{rate=1.3}`, no
attempted, no terminal outcome, no `settings_change`** — correct for the paused path, but
not the check I was after. Two things followed, recorded and not investigated:
  - the already-queued clips kept synthesising at the **old** `speed=1.4` for the next 23 s
    while the sheet read 1.3×, and
  - at 20:36:13.616 the player went `ENDED` and **never resumed**: narration stopped
    silently at sentence 145 of 151 mid-chapter, with no failure event at the time.
  - the next Play press then **timed out**: `tts_action=resume, attempted` at 20:38:12.800
    with no Kokoro synthesis at all, then
    `failed, duration_ms=30029, tts_reason_code=start_timeout` at 20:38:42.826. The 30 s
    deadline fired exactly as `6e919edb` intends — the thing that failed is the Kokoro
    resume itself, not the tracking. One more Play press recovered it:
    `tts_action=controls, is_retry=true` attempted 20:39:46.783 / succeeded 20:39:49.132,
    now synthesising at `speed=1.3`. **This is the one `failed` outcome in the whole run.**

**4c, F07 with Kokoro, second attempt, tap placed inside a confirmed playing window —
passed.** Rate − at 20:40:06 while the log's last `onIsPlayingChanged` said
`isPlaying=true`. Narration continued; synthesis switched to `speed=1.2`
(`E1-kokoro-heart-playing-after-speed-change.png`). Operation lines, in order:

    20:40:06.989 tts_rate_changed {rate=1.2}
    20:40:06.991 tts_playback_operation {tts_outcome=attempted}                      # corr 11a0bfe7
    20:40:17.935 tts_playback_operation {tts_outcome=succeeded, duration_ms=10945}   # corr 11a0bfe7

One attempted, one terminal outcome, one correlation id, breadcrumbs
`entry_point=settings_change` — the same shape as the System-voice case, just ten seconds
slower because Kokoro has to synthesise first. `tts_action` is absent from the event here
too.

### Step 5 — the phone left tidy

Voices → On this phone → Default → **System voice** ("Follows your phone's text-to-speech
settings"), Rate stepped back to **1×**, Pitch Normal. Paused, then **Stop listening**, then
out of the reader to the **Library** screen: `onIsPlayingChanged isPlaying=false` is the last
playback line of the run and the library shows no now-playing card.
`F1-final-sheet-system-voice-rate-1.png` is the sheet in that state before it was closed;
`F2-left-on-the-library-screen.png` is the screen it was left on (cropped to the header,
because the library list cannot be shown without book titles).

While putting the rate back, the two Rate − presses landed during playback and produced one
more clean `settings_change` pair each (20:42:16.239 and 20:42:17.699, both `succeeded`), and
the chapter had rolled over to the next one (sentence 12 of 141), which is why the final
screenshot shows a different chapter from the checks above.

## Summary

- Step 1 passed: the Listen button is not broken. **Run 2b's blocker was tap timing.**
- Step 2 passed: TTS-F06 is fixed on the device, for a speed change and for a voice change.
- Step 3 passed: TTS-F07 is fixed on the device, single and triple change.
- Step 4 passed: both the same with Kokoro (F07 needed the tap placed inside a playing
  window, see 4b).
- Step 5 done: System voice, Rate 1×, narration stopped, app on the library screen.
- One `failed` outcome in the run, in 4b: a Kokoro resume that never started and hit the
  30 s `start_timeout`; the next press recovered it. No crash anywhere.

## Differences from "Expected", recorded and not investigated

1. `tts_playback_operation` carries **no `tts_action`** for the settings-change restart,
   where `controls` and `resume` starts both carry one. The action is in the breadcrumb
   (`entry_point=settings_change`) instead.
2. `TtsRouter: synthesize` prints `voice=` and `engine=` but **no rate**, so "the new rate
   in the log" is only readable from `tts_rate_changed` and, for Kokoro, from
   `SherpaOnnxTts … speed=`.
3. With Kokoro, a speed change can land in a 1–2 s gap where the engine reports
   `isPlaying=false` between sentences; it then takes the paused path and emits no
   operation pair. After one such change, queued clips kept the **old** speed and
   narration **stopped silently** at sentence 145 of 151, and the next play press timed out
   at 30 s before a later press recovered. Not investigated, not filed.

## Not in this run

No product code and no test was touched. "Sentence 1 of 0" (TTS-F24), the highlight left
after stop, Supertonic (terms untouched, never accepted), and any other device were all out
of scope. Two other devices were attached throughout and never addressed; every adb call
carried `-s RFCWC0SSVDM`.

## Files

- `B1-short-tap-too-early-no-card.png` — the ~300 ms tap, run 2b's symptom reproduced.
- `B2-short-tap-card-appears.png` — the same tap after a 0.9 s settle: the compact card.
- `B3-sheet-open-system-voice-rate-1.png` — the sheet, opened from the card.
- `B4-long-press-opens-sheet.png` — the sheet, opened by the long press.
- `C1-f06-paused-sentence-69-rate-1.png`, `C2-f06-rate-1.1-still-sentence-69.png`,
  `C3-f06-paused-sentence-82-before-voice-change.png` — step 2.
- `D1-f07-still-playing-after-three-quick-changes.png` — step 3b.
- `E1-kokoro-heart-playing-after-speed-change.png` — step 4c.
- `F1-final-sheet-system-voice-rate-1.png`, `F2-left-on-the-library-screen.png` — step 5.
- `tts-run2b-device-logcat.txt` — pid 19820 only, TTS/analytics/breadcrumb lines, book
  titles and authors removed (the three library-dump lines, which list every title in the
  library, were dropped whole).

Screenshots are cropped to drop the reader header, which carries the book title and author;
the page text is a public-domain Gutenberg edition.
