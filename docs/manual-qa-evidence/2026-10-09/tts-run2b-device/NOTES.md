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
