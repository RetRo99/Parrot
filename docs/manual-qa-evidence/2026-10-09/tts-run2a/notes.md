# Run 2a device smoke — read-aloud still plays after the player seam

Date: 2026-10-09. Device: Samsung SM-S921B (Galaxy S24), serial `RFCWC0SSVDM`, Android 16.
Build: `androidApp-debug.apk` from commit `a8ef6cb8` on `tts/investigation`, installed with
`adb -s RFCWC0SSVDM install -r`. No uninstall, no data clear, no settings or account change.
Two other devices were attached to the host; every command carried `-s RFCWC0SSVDM`.

The point of this pass is the seam: the engine now talks to ExoPlayer through
`TtsEnginePlayer` / `ExoTtsEnginePlayerProvider` instead of holding one directly. A
synthesis failure was not induced here; that is covered by the host tests.

Voice: System voice ("Device voice"), rate 1x, pitch Normal — untouched throughout.
Book: a local public-domain EPUB; titles are kept out of this evidence.

## Steps and results

| # | Step | Result |
| --- | --- | --- |
| 1 | Open the book, reveal the reader controls, press Listen (held ~120-150 ms) | Sheet opened on the third attempt; see "What went wrong" below |
| 2 | Press play | Played. One `tts_playback_operation attempted` + one `succeeded` (`tts_action=controls`, 1527 ms), then `playback_started` |
| 3 | Let it read five sentences | Read continuously from sentence 89 to 94 and on; highlight followed the sentence, pages turned by themselves (page 9 -> 10 -> 11) |
| 4 | Pause | Stopped at "Sentence 100 of 120"; control showed play |
| 5 | Resume | Continued from the same sentence. One `resume attempted` + one `resume succeeded` (7 ms) |
| 6 | Skip next / previous from the media session (the notification's own commands, sent with `cmd media_session dispatch next` / `previous`) | Worked both ways. Cleanest reading, paused first: 16 -> previous -> 15, then next -> on from 16. No duplicate events |
| 7 | Swipe a page by hand while it read | Page 2 -> 3 of chapter IV, narration carried on (sentence 21), as designed for a page turn inside the chapter |
| 8 | Let it cross a chapter boundary | Chapter III sentence 120 -> chapter IV "Sentence 1 of 151", still playing. Exactly one `tts_playback_operation attempted` + one `succeeded` with `tts_action=chapter` (442 ms), one correlation id |
| 9 | Stop ("Stop listening" in the Listen sheet) | Narration stopped, sheet closed, the sentence counter left the status strip, the control row went back to "Listen" |

No `FATAL`, no `AndroidRuntime` crash, no failed TTS outcome anywhere in the run; the app
process (pid 16041) was the same before and after. `logcat-tts.txt` is the app process's
log filtered to TTS, media and playback lines, with the library dump and all titles removed.

## What went wrong, and what it is not

- The Listen button needed three attempts. The first two presses landed after the reader's
  control row had auto-hidden (it hides a few seconds after being revealed, and a
  `uiautomator` dump taken a moment later showed the row gone from the hierarchy). Pressing
  within a second of revealing the controls opened the sheet every time. This is a plausible
  explanation for the "Listen button does nothing" sighting in `manual-qa-test-plan.md`, but
  it was not separated from tap timing here either; run 6 still owns it.
- The sheet showed "Voice · sentence 1 of 0" before the chapter's sentences were counted —
  TTS-F24, already recorded, unchanged by this run.
- After stopping, the last sentence's highlight stayed on the page. Not investigated here;
  no finding is recorded for it yet.

The artwork warning in the log (`W/TtsReadAloudEngine: Skipping embedded artwork ...`) comes
from the new `ExoTtsEnginePlayer`, which keeps the engine's log tag on purpose, so the line
reads exactly as it did before the seam.
