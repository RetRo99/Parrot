# Run 2c device check — 2026-10-09

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`, the only device addressed (`-s RFCWC0SSVDM`
on every adb call; two other devices were attached throughout — a Xiaomi over wireless adb
and an emulator — and neither was ever addressed). `adb install -r` only. No uninstall, no
data clear, no sign-out, no network change. Supertonic terms untouched and never accepted.

Two builds were used, both from this worktree:

- **pid 11956** — the fix after `b755094d` (the session signal, the end-of-queue race,
  `resume()`).
- **pid 21060** — the same plus `99ad689e`, which was written *because* of what pid 11956
  showed on the phone (see "Pause inside a gap" below).

Book: *(title removed)*, a Gutenberg public-domain eBook on the phone, Kokoro · Heart
selected (the pack was already installed).

## Step 1 — a speed change inside a Kokoro gap — PASSED

Hitting a 1–9 s gap with a synthetic tap is luck, so the tap was fired by a watcher that
reads the log and sends it as soon as `state=ENDED` arrives while a `Kokoro synthesize
start` has no `done`. **Landed in a gap on the first attempt, 100 ms in.** Lines in order:

    21:19:36.763 SERVICE onPlaybackStateChanged: state=ENDED       <- the gap opens
    21:19:36.769 SERVICE onIsPlayingChanged: isPlaying=false
    21:19:36.863 tts_rate_changed {rate=1.1}                       <- the tap, 100 ms in
    21:19:36.864 tts_playback_operation {tts_action=settings_change, tts_outcome=attempted}
    21:19:39.625 Kokoro synthesize start: sid=0 speed=1.1          <- the new speed
    21:19:51.348 tts_playback_operation {tts_action=settings_change,
                                         tts_outcome=succeeded, duration_ms=14484}

One attempted, one terminal outcome, one `correlation_id=ecd2ac31-…`, and **the event now
carries `tts_action=settings_change`** — the run-2b gap (TTS-F27) is closed on the device
too. The in-flight synthesis was at `speed=1.0` before the change and every line after it
is `speed=1.1`: the queued clips do not keep the old speed any more.

The same check again later, at the other end of the chapter (pid 21060), with Rate −:

    21:40:09.088 state=ENDED
    21:40:09.183 tts_rate_changed {rate=1.0}                       <- the tap, 95 ms in
    21:40:09.185 tts_playback_operation {tts_action=settings_change, attempted}
    21:40:16.885 Kokoro synthesize start: sid=0 speed=1.0
    21:40:29.926 tts_playback_operation {tts_action=settings_change, succeeded,
                                         duration_ms=20742}

## Step 2 — pause inside a Kokoro gap — FAILED on the first build, PASSED on the second

**pid 11956, two attempts, both landed in a gap (84 ms and 97 ms in), both did nothing at
all** — no `tts_playback_operation`, no pause, and the clip started playing as soon as it
was ready (21:25:38.415 ENDED, tap at ~.50, `isPlaying=true` at 21:25:40.722; and
21:36:… the same shape at 21:29:18.439 / 21:29:19.409). A tap on the same coordinates
while audio was audible paused immediately (21:27:21, `state=PAUSED`), so the coordinates
were right.

The cause is in `B1-sheet-inside-a-gap.png`, taken inside a gap on that build: **the
play/pause button is a spinner.** `ReaderAudioSheet` and `ReaderOverlay` both disable it
with `enabled = !isLoading`, and the engine reported `isLoading` for the whole gap, so
`togglePlayback` was never called and the fix in `b755094d` could not be reached from the
UI. Fixed in `99ad689e` (a wait after audio has been heard is a gap, not a start), with the
failing host test in `805fef76`.

**pid 21060, third attempt, landed 97 ms into a gap — PASSED:**

    21:36:54.980 state=ENDED                                   <- the gap opens
    21:36:54.985 onIsPlayingChanged: isPlaying=false
    21:36:55.0xx                                               <- the pause tap
    21:36:55.233 Kokoro synthesize done in 8564ms              <- the clip arrives
    21:36:55.252 state=BUFFERING
    21:36:55.273 state=READY                                   <- queued, prepared, silent
                                                               <- and no isPlaying=true

The sentence was prepared and **no audio started**. The play press then resumed it at once:

    21:37:38.353 tts_playback_operation {tts_action=resume, tts_outcome=attempted}
    21:37:38.363 onIsPlayingChanged: isPlaying=true
    21:37:38.367 tts_playback_operation {tts_action=resume, succeeded, duration_ms=16}

## Step 3 — read to the end of a chapter after a mid-gap speed change — PASSED

Narration was placed nine sentences from the end of the chapter (sentence 150 of 159) and
the Rate − tap of step 1's second run landed 95 ms into a gap. It then read on through two
more gaps (21:40:39, 21:41:04) to the end of the chapter and the next chapter started by
itself. (The last sentence number read off the sheet was **150 of 159**, just before the
change; the sheet was not dumped again before the rollover, so reaching 159 is taken from
the chapter completion below rather than from the screen.)

    21:41:04.725 tts_playback_operation {tts_action=chapter, tts_outcome=attempted}
    21:41:07.261 tts_playback_operation {tts_action=chapter, tts_outcome=succeeded,
                                         duration_ms=2535}

`C1-chapter-rolled-over-to-next.png` is the sheet right after: the next chapter, sentence 3
of 188. No silent stop anywhere, and the one `failed` outcome of run 2b did not recur —
**there is no `failed` outcome in this run at all.**

## Step 4 — double-tap a sentence (manual QA case 160) — PASSED

With a System voice, nothing playing, the sheet closed, two `input tap` 150 ms apart on a
line of text in the middle of the page. **One try, first attempt:**

    21:43:35.987 tts_playback_operation {tts_action=sentence_tap, attempted}
    21:43:36.453 tts_playback_operation {tts_action=sentence_tap, succeeded,
                                         duration_ms=468}

Narration started from the tapped sentence (`D1-double-tap-started-narration.png`). A
second double-tap further down the page **while it was reading** jumped to it:

    21:43:51.565 tts_playback_operation {tts_action=sentence_tap, attempted}
    21:43:51.615 onIsPlayingChanged: isPlaying=false            <- the old sentence stops
    21:43:51.721 onIsPlayingChanged: isPlaying=true             <- the new one starts
    21:43:51.723 tts_playback_operation {tts_action=sentence_tap, succeeded,
                                         duration_ms=159}

`D2-double-tap-jumped-while-playing.png` shows it reading on from the jump. The 100/200/
300 ms fallbacks were not needed: no page turn, no word selection, both tries worked. The
double-tap was also confirmed with Kokoro earlier (21:39:06.823 attempted /
21:39:13.916 succeeded, `duration_ms=7095`).

## Step 5 — the phone left tidy

Voices → On this phone → Default → **System voice**, **Rate 1×**, Pitch Normal
(`E1-final-sheet-system-voice-rate-1.png`), then **Stop listening**, then out of the reader
to the **Library** screen. The last playback line of the run is
`observeNowPlaying: info=null, isPlaying=false` at 21:44:58.767, and the library screen
shows no now-playing card. No library screenshot is included: the list cannot be shown
without book titles.

## Recorded, not investigated

**Narration stopped 390 ms after the first mid-gap restart, for an unrelated reason.** At
21:19:51.345 the restarted sentence began playing and the operation succeeded; at
21:19:51.735 the player went `state=IDLE` and `MediaPlaybackService.onDestroy` ran
(`MediaPlaybackController.stop()`, i.e. `engine.stop()` through
`releaseCurrentPlayer(stopSharedPlayer = true)`). The reader had been showing CHAPTER V
page 2 of 13 while the TTS layer was reading **CHAPTER VI's** sentence list (141
sentences), and the WebView redrew at 21:19:51.68 and landed on CHAPTER VI page 1 — the
sentence highlight navigated across the spine boundary, the locator's href changed, and
`AndroidTtsController`'s locator collector stops the engine on an href change. So the stop
is the displayed chapter and the narrated chapter being different ones, not the gap. It is
not caused by this run's change — run 2b's step 5 recorded the same rollover ("the chapter
had rolled over to the next one (sentence 12 of 141)"). Every later check in this run was
done inside one chapter and none of them saw it again. Worth its own finding; not filed
here.

**The highlight left behind after a stop** is visible in `D1`/`D2` (the yellow band on a
sentence that is no longer being read). Already known, out of scope.

**"Sentence 1 of 0"** (TTS-F24) showed again on the freshly opened sheet
(`A1-sheet-system-voice-rate-1.png`). Already filed.

## Files

- `tts-run2c-logcat.txt` — pids 11956 and 21060 only, TTS/analytics/breadcrumb lines; book
  titles and authors replaced with `[book title removed]`, and the two library-dump lines
  (which list every title in the library) dropped whole.
- `A1-sheet-system-voice-rate-1.png` — the sheet as opened, System voice, Rate 1×.
- `B1-sheet-inside-a-gap.png` — **the pre-`99ad689e` build, inside a gap**: the play/pause
  button is a spinner, which is why the two mid-gap pauses did nothing.
- `C1-chapter-rolled-over-to-next.png` — the sheet after the chapter was read to its end.
- `D1-double-tap-started-narration.png`, `D2-double-tap-jumped-while-playing.png` — case 160.
- `E1-final-sheet-system-voice-rate-1.png` — the state the phone was left in.

Screenshots are cropped to drop the reader header, which carries the book title and author;
the page text is a public-domain Gutenberg edition.
