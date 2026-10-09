# TTS testing plan

Source: `docs/tts-investigation.md` (27 findings, TTS-F01 to TTS-F27, plus QA-BUG-0049,
0095, 0100). Android only; TTS on iPhone is a stub. Branch `tts/investigation`.

## How each run works

1. Write the test that shows the finding. See it fail for the reason the finding gives.
   Commit it failing, marked and explained.
2. Fix the code. The test passes without being edited.
3. If the test passes before any fix, the finding is wrong or already fixed: record that
   in this file and do not change product code for it.

A finding that cannot be shown by an automated test gets a manual case in section
"Manual device pass" instead, and the run says so.

## Runs, in order

| Run | What | Findings | Model | Status |
| --- | --- | --- | --- | --- |
| 1 | Neural voice crash: callback shape, host guard test, device check | F22 | Opus | done |
| 2a | Read-aloud engine seam and host harness; failures after start | F01, F02, QA-0095 (first two cases) | Opus | done |
| 2b | Restart on a settings change; the chapter a completion belongs to | F06, F07, F16, QA-0095 (third case) | Opus | done |
| 2c | The synthesis gap between sentences; the action on the restart's event | F26, F27 | Opus | done |
| 3 | Small pure-logic gaps | F03, F08, F09, F12, F13, F17, F18, F21 | Sonnet | |
| 4 | Voice pack download and delete | F04, F05, F19, F20 | Opus | |
| 5 | Reader screen and lifecycle | F10, F11, F14, F23, F24, F25, QA-0049, QA-0100 | Opus | |
| 6 | Manual device pass and new cases in `manual-qa-test-plan.md` | all | Sonnet, with the owner's phone | |

Run 1 is first because it is a crash on every Kokoro synthesis and it blocks every other
Kokoro check. Run 2 is second because the harness it builds is reused by runs 3 and 5;
2a builds that harness and 2b uses it.

## Tests to write, by run

### Run 1: neural voice crash (F22)

- Host test: the callback object passed to sherpa has a method `invoke(float[])` returning
  `Integer`. Sherpa's native code looks up exactly that signature; the current lambda
  only has `invoke(Object)`. Covers both `SherpaOnnxSynthesizer` and
  `SupertonicOnnxSynthesizer`, which must share one callback factory.
- Host test: the callback returns 1 when cancelled and 0 otherwise.
- Device: Kokoro preview and Kokoro read-aloud both produce audio; stop during
  synthesis cancels within a second and the app stays alive.
- Supertonic on device needs the owner to accept the model terms first.

Done 2026-10-09. The guard test was committed failing (`2cc762a4`), the fix is
`098a8592`, and a narrow R8 keep rule for the callback is `b2887d29`. Device pass on the
Samsung SM-S921B: `docs/manual-qa-evidence/2026-10-09/tts-f22-fix/`. Supertonic was not
run on the device, as planned; it is covered by the shared host test only.

### Run 2a: engine seam and failures after start (`TtsReadAloudEngineTest`)

Needed a seam first: the engine built its own ExoPlayer and is a singleton, so the
player and the generator had to be replaceable by fakes without changing behaviour.

- Start, pause, resume, stop, two quick starts, skips at both ends, a player error:
  characterization tests, written and committed green before any change.
- Sentence 2 fails synthesis during auto-advance: no uncaught exception, one
  `PlaybackFailure(SYNTHESIS_FAILED)`, engine stopped (F01, F02).
- Same for skip next, skip previous, seek to chapter position (F01).

Done 2026-10-09. Seam `c2c62cef` (`TtsEnginePlayer`, `TtsEnginePlayerProvider`,
`TtsSentenceAudioSource`, injectable engine context), ten characterization tests green
`64796984`, eight failing tests `5c5a6b0e`, fix `fc9e4804`. Reader ui host tests 299/299.
Device smoke on the Samsung SM-S921B: `docs/manual-qa-evidence/2026-10-09/tts-run2a/`.

### Run 2b: settings changes mid-playback (`AndroidTtsControllerTest`)

- Rate, pitch or voice change while playing: one `Attempted` and one terminal outcome;
  a failing new voice gives `Failed`, not silence (F07).
- Rate, pitch or voice change while paused: the paused sentence is kept and play
  resumes on it (F06).
- Chapter completion carries the chapter that finished (F16).

`restartForSettingsChange` is the last QA-BUG-0095 case: it calls `engine.playFrom` from
its own bare `launch`, which run 2a deliberately left alone.

Done 2026-10-09, as `TtsPlaybackAttemptsTest` rather than `AndroidTtsControllerTest`:
`AndroidTtsController` takes fifteen dependencies, most of them concrete Android classes
(`EpubPublication`, `TtsPreviewPlayer`, `TtsWordPlayer`, `TtsModelManager`,
`NotificationPermissionHandler`, `SupertonicTermsStore`, `Context`), so option (b) of the
run brief was taken: the attempt bookkeeping and the settings-change decision moved into
`TtsPlaybackAttempts` (`381f6340`), which needs only the run 2a engine and a scope. Six
failing F06 tests `4bc7cdc8`, fix `480a4773`; four failing F07 tests `af95f1f2`, fix
`6e919edb` (new action value `settings_change`). F16 is **not reproducible in a test** and
no product code changed for it (`ac507841`); its test recorded a separate hazard, TTS-F25.
Reader ui host tests 312/312.

The device pass for run 2b could **not** be run: on the Samsung the reader's Listen button
never opens the Listening sheet, which is the only place the voice and the speed can be
changed, so no speed or voice change could be made on the phone. Evidence and the control
experiment (the neighbouring Search button opens on the identical gesture) are in
`docs/manual-qa-evidence/2026-10-09/tts-run2b/`. The phone was therefore left on whatever
voice and rate the previous run set, not on a System voice at 1.0.

### Run 2c: the gap between sentences (`TtsSynthesisGapTest`)

- In the gap (sentence N ended in the player, N+1 still being synthesised) the session
  reports as running, although no audio is audible (F26).
- A speed change in the gap: one `Attempted` and one terminal outcome with action
  `settings_change`, and N+1 is synthesised at the new rate (F26).
- Pause in the gap: the engine ends paused, the arriving clip does not start playing, and
  the next play press plays it (F26).
- The device's whole step-4b sequence: a speed change in the gap, then the chapter read to
  its end at the new rate with one chapter completion — no silent stop (F26).
- A play press after a clip was appended in the window before the end-of-queue callback
  resumes inside the test's virtual time instead of sitting until the 30 s deadline (F26).
- `tts_action=settings_change` survives the analytics allow-list, attempted and terminal
  (F27).

Done 2026-10-09. Five failing gap tests `32a23af4`, fix `b755094d`
(`TtsReadAloudEngine.isSessionRunning`, the end-of-queue race in `onSentenceCompleted`,
`resume()` on a player that has run out of audio); failing sanitizer test `12dd5dff`, fix
`e4597018`. Reader ui host tests 317/317, analytics 39/39. No earlier test was edited.

### Run 3: pure logic

- `TtsAudioGeneratorTest`: a failed synthesis that wrote bytes leaves no cache entry (F03).
- `TtsAudioCacheTest`: key changes with voice, rate, pitch and text; trim order; a file
  in use is not evicted (F12); word and sentence audio do not share a key (F21).
- `WavSilenceTrimmerTest`: normal trim, all-silence, non-PCM input, failed rename (F21).
- `SpeakWordCoordinatorTest`: stop then speak with no dispatch in between (F08); a
  player that never finishes is given up on and narration resumes (F09).
- `TtsSynthesizerRouterTest`: ready when only a neural engine is ready (F13); routing by
  voice id prefix; fallback voice.
- Kokoro speaker id is clamped to the voices that exist (F17).
- `TtsVoicePreparationStateHolderTest`: a terminal state is not replayed to a new
  observer (F18).
- `TtsSentenceChunkerTest` additions: numbers, quotes, non-Latin text, a sentence with
  no terminator, empty and whitespace-only input.

### Run 4: voice packs (new `TtsModelManagerTest`)

Needs the manifest address to be injectable; tests run against a local server.

- A server that accepts and never answers: refresh returns within about 6 seconds and
  the cached manifest is used (F04).
- Download that fails three times: no orphan partial files, or they are reclaimable
  from the card (F05, product decision 3).
- Interrupted download resumes and the final checksum is verified.
- Checksum mismatch: nothing installed, clear failed state.
- Delete while downloading: end state is consistently "not installed" (F20).
- Cancel from the notification clears the pending voice selection and reports one
  cancelled outcome (F19).
- Update while the old version is loaded: old version keeps working until the switch.

### Run 5: reader screen

- Book opens on a page with no text, then moves to a text chapter: read-aloud becomes
  available, and a play press never does nothing silently (F23).
- No sentence position is shown while the count is zero; position is cleared when the
  engine stops (F24).
- Two reader screens for one book: one terminal event per start; closing one does not
  break the other (F10, QA-0100).
- Starting TTS setup twice gives one finished-sentence callback per sentence (F11).
- Chapter with no sentences: behaviour per product decision 2 (F14).
- Logout while reading: behaviour per product decision 4 (QA-0049).
- After Stop listening, the last sentence's highlight stays on the page (seen in run 2a, not yet a finding).
- A player "ended" callback arriving after a stop starts nothing (F25, found in run 2b).

### Run 6: manual device pass

Checks the investigation could not do, then a regression pass of sections 9, 10, 28,
49 and 50 of `manual-qa-test-plan.md`:

- Kokoro: pause, resume, swipe, chapter end, switch between system and Kokoro mid-read.
- Download interrupted by turning the network off, then resumed.
- Leave the reader while it reads; return; open another book.
- Audio focus lost to a call or another player, during narration and during a word.
- Tap a word during narration, dismiss, tap another immediately.
- Rotate the device and re-enter the reader while reading; count events per start.
- Sleep timer expiry in foreground and with the screen locked.
- The Listen button opening the sheet: **settled on the device, 2026-10-09, no longer a
  blocker.** All three routes work on the Samsung — a short tap shows the compact
  now-playing card and starts no audio, a second short tap dismisses it, a long press
  (`input swipe X Y X Y 800`) opens the full Listening sheet first try, and a tap on the
  card opens it too. The earlier "Listen does nothing" was a synthetic-input artefact after
  all: the control row takes about 0.9 s to finish expanding, and a tap sent ~300 ms after
  the reveal (run 2b's timing) lands before the button has its hitbox and is swallowed.
  Reveal, wait ~0.9 s, then act. Still worth one human-finger pass, but nothing is blocked.
- Cold first Kokoro start timed against the 30 second start deadline (F15).
- Double-tap a sentence to start, and to jump while playing (manual QA case 160).

## Decisions for the owner

1. F06: after a speed or voice change while paused, resume on the same sentence.
   Recommended: yes.
2. F14: a chapter with nothing to read. Recommended: move on to the next chapter with
   text; show a message only if the book has none.
3. F05: failed download leftovers. Recommended: keep them for resume, and show a
   "Remove partial download" action on the card.
4. QA-0049: should logging out of a server stop narration? Recommended: stop it only
   when the book being read came from that server; a local book keeps reading.
5. F15: should loading the model count against the 30 second start deadline?
   Recommended: no, arm the deadline after the engine is loaded.
6. Supertonic terms must be accepted on the test phone by the owner before any
   Supertonic device check.

## Not covered by this plan

iOS, `tools/tts-bench`, synthesis speed, pre-recorded ReadAloud audio except where it
shares code with TTS.
