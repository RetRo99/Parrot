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
| 3a | The sentence audio cache: a testable store, failed synthesis, the trim, the word clip, the Kokoro voice number | F03, F12, F17, F21 | Opus | done |
| 3b | The rest of the small pure-logic gaps, plus the sentence chunker cases | F08, F09, F13, F18 | Sonnet | done |
| 4 | Voice pack download and delete | F04, F05, F20 | Opus | done |
| 5a | Read-aloud stopping and the highlight it leaves | F23, F24, F14 | Opus | not started |
| 5b | Two readers, the pending voice, logout, the chapter mismatch | QA-0049, QA-0100, F10, F11, F19, F25 | Opus | not started |
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

- The gap is not a "preparing" state, so the play/pause button is not disabled through it
  (F26, cause (0), found on the phone after the first fix).

Done 2026-10-09. Five failing gap tests `32a23af4`, fix `b755094d`
(`TtsReadAloudEngine.isSessionRunning`, the end-of-queue race in `onSentenceCompleted`,
`resume()` on a player that has run out of audio); failing sanitizer test `12dd5dff`, fix
`e4597018`; a sixth failing gap test `805fef76` and fix `99ad689e` after the device pass
showed the mid-gap pause could not be pressed at all. Reader ui host tests 318/318,
analytics 75/75. No earlier test was edited. Device pass on the Samsung SM-S921B:
`docs/manual-qa-evidence/2026-10-09/tts-run2c/` — mid-gap speed change, mid-gap pause,
chapter read to its end after a mid-gap change, and the double-tap of case 160, all passed;
no `failed` outcome in the run.

### Run 3a: the sentence audio cache — done

- `TtsAudioCacheStoreTest`: the file logic moved into `TtsAudioCacheStore` (the directory and
  a clock are injected); key composition, hit rules, eviction order, WAV duration parse.
- `TtsAudioGeneratorTest`: a failed, cancelled or throwing synthesis that wrote bytes leaves
  no cache entry, and the sentence is synthesised again (F03).
- `TtsAudioCacheStoreTest`: a file used in the last 10 minutes is not evicted (F12).
- `WavSilenceTrimmerTest`: normal trim, all-silence, no silence, non-PCM and malformed input,
  no `.trim` left behind. `TtsWordClipFileTest`: the word path trims a copy, never the cache
  entry, and the key still resolves to the sentence audio (F21).
- `SherpaOnnxSynthesizerTest`: the Kokoro speaker id is clamped to the voices that exist (F17).

Done 2026-10-09. `TtsAudioCacheStore` and `TtsAudioGeneratorCore` are the Context-free seams
(`d71cc725`, `94a33e19`); `TtsAudioCache` keeps its constructor and Koin bindings. Failing
test `f253bdc0` → fix `854fb40f` (F03); failing test `442db329` → fix `fb5c6e83` (F12);
trimmer tests `09690022`, failing test `b1ba6dd3` → fix `fbbf89e7` (F21); failing test
`d423e617` → fix `d720979d` (F17). Reader ui host tests 344/344, composeApp 57/57. No
earlier test was edited.

### Run 3b: the rest of the pure logic — done

- `SpeakWordCoordinatorTest`: stop then speak with no dispatch in between (F08); a
  player that never finishes is given up on and narration resumes (F09).
- `TtsSynthesizerRouterTest`: ready when only a neural engine is ready (F13); routing by
  voice id prefix; fallback voice.
- `TtsVoicePreparationStateHolderTest`: a terminal state is not replayed to a new
  observer (F18).
- `TtsSentenceChunkerTest` additions: numbers, quotes, non-Latin text, a sentence with
  no terminator, empty and whitespace-only input.

Failing test `683b5871` → fix `b48309c2` (F08); failing test `27dae308` → fix `de593fae`
(F09); failing tests `f5401995` → fix `5b5ac563` (F13); failing tests `b88c7a37` → fix
`3adba5d7` (F18); ten chunker cases pinned in `949fa619`, three of them named
"- current behaviour" and no chunker change — the suffix carries no brackets because
Kotlin/Native rejects a test name containing "()". `TtsSynthesizerRouterTest` covers readiness
only; routing by voice id prefix and the fallback voice are still untested, both needing
the three Android engines. Reader ui host tests 365/365, reader ui iOS 283/283 (266 before
this run), composeApp 57/57. One earlier test was retimed, not weakened: the F08 ordering
case moved from `advanceUntilIdle` to `runCurrent` once F09's limit existed, since it
holds both clips open on purpose.

### Run 4: voice packs (`TtsModelStoreTest`) — done

Needed the manifest address, the directory, the connection opener, the clock, the
free-space check, the hard link and the log to be injectable; the tests run against a
local server on 127.0.0.1 (the JDK's `HttpServer`, no new libraries).

- A host that accepts and never answers: refresh returns within about 6 seconds and the
  cached manifest is used (F04).
- A host that answers after 2 seconds: that manifest is used.
- An answer arriving after the deadline does not replace the cached manifest (F04).
- Three failed attempts: the current version's partial is kept and the next call resumes
  from exactly those bytes (F05, product decision 3).
- An incomplete folder of a version that is not current is deleted on a refresh; a
  complete one is kept (F05).
- A current-version partial is kept for a week and no longer (F05).
- The active version and the version kept after an update survive the clean-up (F05).
- A clean-up that lands during an install leaves that install's files alone (F05).
- Interrupted transfer resumes from the partial file and the final checksum is verified.
- Checksum mismatch: nothing installed, the call fails, no partial left.
- An update installs beside the old version, reuses unchanged files and moves the marker
  only at the end; a failed update leaves the old version active and usable.
- Delete while downloading: end state is consistently "not installed" (F20).
- `deleteModel` removes the pack and the marker.

Done 2026-10-09. Seam `c15d7305` and `64c0ac69` (`TtsModelStore`, with `TtsModelManager`
keeping its constructor, its Koin binding and every public function); seven
characterization cases green `308cc34e`; failing test `73b2eda3` -> fix `0f9732a9`
(F04); three failing tests `49c240ee` -> fix `03b20ffd` (F05); F20 **passed before any
fix**, recorded in `55b6d3bc`, no product code and no change to the foreground service.
Reader ui host tests 382/382, settings ui 20/20, composeApp 57/57. No earlier test was
edited. The class uses real sockets and real time and takes about 33 seconds.

F19 moved to run 5b: it lives in `ReaderViewModel`, which run 4 did not touch.

### Run 5a: what read-aloud offers and what it leaves behind — not started

- Book opens on a page with no text, then moves to a text chapter: read-aloud becomes
  available, and a play press never does nothing silently (F23).
- No sentence position is shown while the count is zero; position is cleared when the
  engine stops (F24).
- Chapter with no sentences: behaviour per product decision 2 (F14).
- After Stop listening, the last sentence's highlight stays on the page (seen in run 2a,
  not yet a finding).

### Run 5b: two readers, the pending voice, logout, the chapter mismatch — not started

- Two reader screens for one book: one terminal event per start; closing one does not
  break the other (F10, QA-0100).
- Starting TTS setup twice gives one finished-sentence callback per sentence (F11).
- Logout while reading: behaviour per product decision 4 (QA-0049).
- A cancelled or failed pack download clears the pending voice selection and reports one
  cancelled outcome (F19). Run 4 left one case for this: a delete during a download makes
  the install report failure rather than cancellation, so the card shows a failed
  download after the user deleted the pack themselves.
- A player "ended" callback arriving after a stop starts nothing (F25, found in run 2b).
- The chapter on screen and the chapter being narrated can be different ones: the sentence
  highlight then navigates across the spine boundary, the locator href changes, and the
  locator collector stops narration mid-chapter with no event. Seen in run 2b's step 5 and
  again in run 2c (21:19:51, `docs/manual-qa-evidence/2026-10-09/tts-run2c/NOTES.md`
  §"Recorded, not investigated"). Not filed as a finding yet.
- Decision 5, the 30 second start deadline: arm it after the engine is loaded, so loading
  the model does not count against it (F15).

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
- Phone ringer must be on, not silent; run 3a's check heard nothing.
- Cold first Kokoro start timed against the 30 second start deadline (F15).
- Double-tap a sentence to start, and to jump while playing (manual QA case 160). **Done
  in run 2c on the Samsung, 2026-10-09: both passed, one try each**
  (`docs/manual-qa-evidence/2026-10-09/tts-run2c/`). Still worth a human-finger pass.

## Decisions for the owner

1. F06: after a speed or voice change while paused, resume on the same sentence.
   Recommended: yes.
2. F14: a chapter with nothing to read. Recommended: move on to the next chapter with
   text; show a message only if the book has none.
3. F05: failed download leftovers. **Decided by the owner, 2026-10-09, and done in run 4
   (`03b20ffd`):** partial files of the manifest's current version are kept so a retry
   resumes; partial files and incomplete version folders of any other version are
   deleted; a current-version partial nothing has written to for 7 days is deleted. The
   sweep runs on every manifest refresh and at the start of every install, and never on a
   pack whose install is in progress. No new buttons and no new screen text, so the
   earlier "Remove partial download" action is dropped. Also decided: opening the voice
   list never waits more than about 5 seconds for the manifest (F04), and deleting a pack
   while it downloads must end with the pack cleanly not installed and no download still
   running (F20).
4. QA-0049: should logging out of a server stop narration? Recommended: stop it only
   when the book being read came from that server; a local book keeps reading.
5. F15: should loading the model count against the 30 second start deadline?
   Recommended: no, arm the deadline after the engine is loaded.
6. Supertonic terms must be accepted on the test phone by the owner before any
   Supertonic device check.

## Not covered by this plan

iOS, `tools/tts-bench`, synthesis speed, pre-recorded ReadAloud audio except where it
shares code with TTS.
