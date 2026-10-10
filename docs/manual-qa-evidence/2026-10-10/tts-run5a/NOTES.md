# Run 5a device pass — Samsung SM-S921B (RFCWC0SSVDM), 2026-10-10

Build: branch `tts/investigation` at `3df8dc2e`, `androidApp-debug.apk`, `adb install -r`.
The phone was not uninstalled, not cleared, not signed out; no network or ringer change.
The Supertonic terms were **not** accepted. No command was sent to any other device
(one other phone was attached over Wi-Fi and never addressed; every call passed
`-s RFCWC0SSVDM`).

Shortened on the owner's instruction mid-run ("do minimal tests on device, just to see it
works"): the four checks below were run, the paging-forward, double-tap and Resume
variants were not.

## Checks

- **A book opened fresh from the shelf (not Resume) lands on its title page and read-aloud
  is available.** `feature_exposed {feature_name=tts, is_available=false}` at 01:14:59.245
  followed by `is_available=true` at 01:14:59.903 — in this Gutenberg edition the title
  page itself has text, so availability is answered on the first page and the pre-fix
  symptom (no voices for the session) cannot arise from this page.
- **The Voices sheet lists the installed Kokoro pack.** "Kokoro · High quality · 11 voices ·
  149 MB · English · ✓ Downloaded · works offline", opened from the Listening sheet's
  Change. `A4-voices-kokoro-downloaded.png`. (Supertonic shows "Model terms not accepted
  yet", untouched.)
- **The Listening sheet before any play shows no "Sentence 1 of 0".** The position line is
  not there at all: the progress bar, then the transport.
  `A1-listening-sheet-no-sentence-line.png` (TTS-F24). While playing the same line reads
  "Sentence 2 of 397 · about 46 min left in chapter"
  (`A2-listening-sheet-position-while-playing.png`).
- **Play on a page with text starts in place.** 01:18:12.425 `tts_action=controls,
  tts_outcome=attempted, is_retry=false` → 01:18:14.630 `tts_outcome=succeeded,
  duration_ms=2210`. One attempt, one success.
- **Play on the image-only cover page starts in the next chapter that has text (TTS-F14).**
  Paged back to the cover (page 1 of 1, no chapter title, a single full-page image), pressed
  play: 01:19:52.477 `tts_action=controls, tts_outcome=attempted` → 01:19:52.983
  `tts_outcome=succeeded, duration_ms=506`, and the reader was in the first text chapter
  reading it ("Sentence 3 of 397" by the time of the screenshot, system voice, rate 1×).
  One attempt, one success, no `failed(content_unavailable)` and no retry bar — before this
  run the same press produced one failure with a Retry that failed identically.
- **Stop listening takes the highlight off the page.** After "Stop listening" the sentence
  that had been read is plain text with no highlight and no underline:
  `A3-page-after-stop-no-highlight.png`.

## Not checked on the device

- A book that opens on a page with **no** text at all: this edition's first document has
  text, so the fresh-open half of TTS-F23 could not be reproduced on this book. The
  play-on-an-image-page half was reproduced (the cover check above), and the
  availability-turns-on-later path is covered by host tests
  (`ReaderTtsSetupTest`).
- Paging forward then play, double-tap to start, and Resume on a text page — dropped when
  the run was shortened. Resume on a text page is partly covered by the "play on a page
  with text" check above, which started in place exactly as before.

## State the phone was left in

System voice, rate 1×, pitch Normal, sleep timer Off, nothing playing, library screen.

Evidence: `tts-logcat-app-process.txt` — the app process only, filtered to the TTS and
analytics lines, with the book and chapter titles replaced. Screenshots are cropped so no
book title shows.
