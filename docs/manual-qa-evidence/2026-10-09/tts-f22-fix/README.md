# TTS-F22 fix — device evidence, 2026-10-09

Device: Samsung SM-S921B, serial RFCWC0SSVDM, Android 16 (SDK 36), app pid 26487 for the
whole pass. Debug build of `tts/investigation` with the fix (`098a8592`) installed with
`adb install -r`. Kokoro pack already present (`20260928123911-4`). Supertonic terms were
**not** accepted; Supertonic stays covered by the host test only.

Logcat excerpts are filtered to the app process and the TTS tags; no book text or titles.

| File | What it shows |
| --- | --- |
| `01-kokoro-preview.txt` | Voices sheet, preview ▶ on a Kokoro voice. Two chunks synthesised (9.4 s and 5.5 s), ExoPlayer initialised, `AudioTrack setVolume` — audio played and pid 26487 survived. Before the fix this call killed the process. |
| `02-voices-sheet-kokoro-preview-in-progress.png` | The preview spinner on the Kokoro voice being previewed. |
| `03-voices-sheet-kokoro-bella-selected.png` | A Kokoro voice selected for read-aloud. |
| `04-kokoro-read-aloud.txt` | Read-aloud with that voice: 24 `Kokoro synthesize done` lines back to back, no crash. |
| `06-stop-during-synthesis.txt` | `synthesize start chars=164` at 17:57:50.905; Stop pressed at 17:57:51.404 (`QA-MARKER`), `playback_paused` at 17:57:51.604. **No `synthesize done` for that job, ever** — the native generation was aborted by the callback. The ONNX generation thread (tid 26546) stopped accumulating CPU inside the first 100 ms sample after the tap, i.e. the synthesis ended well under a second after Stop. This is what proves the callback is really being invoked and its return value used. |
| `07-pause-resume-swipe.txt` | Play (`tts_playback_operation attempted → succeeded`), pause at 18:00:14.251, resume at 18:00:22.597 (`attempted → succeeded`), page swipe forward at 18:00:37.948 while reading. Synthesis continued across all of it; no failure event, no crash. |
| `08-kokoro-read-aloud-sentence-89-of-120.png` | Sentence 89 of 120 with the Kokoro voice, still playing. |
| `09-left-on-system-voice-rate-1.png` | The state the phone was left in: System voice, rate 1×, pitch Normal, sleep timer Off, not playing. |

Pre-existing issues seen during the pass, not part of this run:

- The Listen button does not react to a zero-duration tap; it needs a held press
  (~120 ms). Already logged as a Run 6 item ("The Listen button not opening the sheet").
- `Sentence 1 of 0` before the chapter's sentences load — TTS-F24, unchanged.
