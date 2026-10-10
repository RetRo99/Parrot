# TTS run 3a device check — 2026-10-09

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`. Debug build of `d720979d` (the four run 3a
fixes) installed with `adb install -r`. No other device was touched; the Supertonic terms
were not accepted. The phone's ringer was on silent, so nothing was audible — the check is
about the cache and the playback state machine, which the log shows.

Files: `logcat-app.txt` (the whole run, app process only, book titles replaced with
`<book title>`) and `logcat-tts-timeline.txt` (the same log reduced to the Kokoro synthesis,
playback-state and `tts_playback_operation` lines).

## System voice: play, pause, play, stop — all four worked

- 22:21:48 `tts_action=controls, tts_outcome=attempted` → 22:21:49 `succeeded` (614 ms),
  `onIsPlayingChanged: isPlaying=true`. Sentences advanced for ~38 s (more than the five
  asked for).
- 22:22:27 pause: `isPlaying=false` and `Analytics Event: playback_paused`.
- 22:22:44 play: `tts_action=resume` attempted → succeeded, `isPlaying=true`.
- 22:23:00 stop from "Stop listening": `isPlaying=false`, the sheet closed and the sentence
  stayed highlighted on the page.

## Kokoro "Heart": the cache still serves hits

Voice switched to Kokoro · Heart in Voices; `Kokoro loaded: sampleRate=24000 speakers=11`
and every synthesis logged `sid=0`, the Heart index — the F17 clamp did not disturb it.

- First pass, 22:24:28 to 22:25:17: six sentences synthesised (`Kokoro synthesize start` /
  `done`), stopped at sentence 35 of 188.
- Because the first pass had only reached a handful of sentences, the cache-hit check was run
  on a stretch the earlier passes had already cached, which is the same question:
  - **Pass A, 22:30:31 → 22:31:07**, play from sentence 76 for 35 s: `isPlaying=true`, the
    sentence advanced, and **not one `Kokoro synthesize start`** in the log. The cache
    directory held 654 WAVs before and 654 after.
  - **Pass B, 22:31:47 → 22:32:24**, three sentences back (72 of 188) and play again: again
    **no `Kokoro synthesize start`** for sentences 72–76, still 654 files; the first new
    synthesis (`chars=170`) came only when playback ran past the cached stretch.

So a second pass over cached sentences produces no synthesis at all: cache hits work with
the run 3a changes, and the `trim` keep-window did not evict the audio that was in use.

One expected consequence of the TTS-F03 fix was visible: a sentence whose synthesis was
cancelled by "Stop listening" is synthesised again on the next play, because the partial
file is now deleted instead of being left at the cache key.

## State the phone was left in

System voice, rate 1×, pitch Normal, on the Library screen, nothing playing.
