# On-device text-to-speech: code map, findings and emulator checks

Investigation run of 2026-10-09 on branch `tts/investigation`, based off `90675a6b`.
Scope: the Android on-device TTS read-aloud stack. No product code and no test was
changed in this run. A separate testing plan will be written from this document, so
every finding below names a file, line numbers and a way to catch it.

TTS exists on Android only: `feature/reader/ui/src/iosMain/.../navigator/IosTtsController.kt`
is a deliberate stub and `feature/settings/ui/src/iosMain/.../TtsSupport.ios.kt:3` reports
`isTtsSupported = false`. iOS was not investigated or built.

Line numbers refer to the state of the tree at `90675a6b`.

---

## 1. How the TTS stack works

### 1.1 Components and who owns what

| Component | File | Koin lifetime | Owns |
| --- | --- | --- | --- |
| `TtsController` (contract) | `commonMain/.../navigator/TtsController.kt` | interface | Reader-facing API: play/pause/stop, voices, preview, word speech, pack preparation, playback-operation outcomes |
| `NarrationController` (shared contract) | `commonMain/.../navigator/NarrationController.kt` | interface | The controls ReadAloud (recorded audio) and TTS have in common, so the reader can drive either |
| `AndroidTtsController` | `androidMain/.../navigator/AndroidTtsController.kt` (889 lines) | `@Scoped(ReaderScope)` | Per-book orchestration: chapter sentences, playback attempts and their analytics outcomes, voice selection, preview, word taps, pack download hand-off |
| `TtsReadAloudEngine` | `androidMain/.../tts/TtsReadAloudEngine.kt` (782 lines) | `@Single` (app-wide) | The actual playlist: synthesis scheduling, prefetch, ExoPlayer/media-session player, sentence advance, chapter end |
| `TtsPlaybackOperationLifecycle` | `commonMain/.../navigator/TtsPlaybackOperationLifecycle.kt` | plain object owned by the controller | The one pending request job and the 30 s startup deadline |
| `TtsSynthesizerRouter` | `androidMain/.../tts/TtsSynthesizerRouter.kt` | `@Single(binds=[TtsSynthesizer])` | Picks system / Kokoro / Supertonic per voice id |
| `AndroidSystemTtsSynthesizer` | `androidMain/.../tts/AndroidSystemTtsSynthesizer.kt` | `@Single` | Platform `TextToSpeech`, `synthesizeToFile`, per-utterance bookkeeping |
| `SherpaOnnxSynthesizer` (Kokoro) | `androidMain/.../tts/SherpaOnnxSynthesizer.kt` | `@Single` | sherpa-onnx Kokoro engine, 11 voices |
| `SupertonicOnnxSynthesizer` | `androidMain/.../tts/SupertonicOnnxSynthesizer.kt` | `@Single` | sherpa-onnx Supertonic engine, 10 voices (terms-gated; code-only this run) |
| `TtsAudioGenerator` | `androidMain/.../tts/TtsAudioGenerator.kt` | `@Single` | Cache lookup, per-key mutex, the single global synthesis permit |
| `TtsAudioCache` | `androidMain/.../tts/TtsAudioCache.kt` | `@Single` | `cacheDir/tts/<sha256>.wav`, LRU-ish trim, WAV duration parsing |
| `TtsModelManager` | `androidMain/.../tts/TtsModelManager.kt` (801 lines) | `@Single` | Manifest fetch, versioned pack install/verify/update/delete |
| `TtsVoicePreparationForegroundService` | `androidMain/.../tts/TtsVoicePreparationForegroundService.kt` | Android service | Runs the pack download with a progress notification and a Cancel action |
| `TtsVoicePreparationStateHolder` | `commonMain/.../tts/TtsVoicePreparationStateHolder.kt` | `@Single` | The single in-memory preparation state (Idle/Running/Complete/Failed) |
| `TtsPreviewPlayer` | `androidMain/.../tts/TtsPreviewPlayer.kt` | `@Scoped(ReaderScope)` | Voices-sheet preview on a private ExoPlayer |
| `TtsWordPlayer` / `TtsWordAudioSource` | `androidMain/.../tts/TtsWordPlayback.kt` | `@Scoped(ReaderScope)` / plain | Dictionary "speak this word": its own ExoPlayer, silence trimming |
| `SpeakWordCoordinator` | `commonMain/.../tts/SpeakWordCoordinator.kt` | plain, owned by the controller | One word at a time; pauses and resumes whatever was playing |
| `TtsSessionPlayer` | `androidMain/.../playback/TtsSessionPlayer.kt` | `internal`, wraps the service player | Makes a chapter look like one track to the media session (duration/position/seek mapping) |
| `TtsChapterTimeline` | `commonMain/.../tts/TtsChapterTimeline.kt` | value object | Estimated then measured per-sentence durations, chapter position maths |
| `TtsHeardSentenceTracker` | `commonMain/.../tts/TtsHeardSentenceTracker.kt` | value object | Whether a sentence was really heard in full (for recap capture) |
| `TtsSentenceChunker` | `commonMain/.../tts/TtsSentenceChunker.kt` | object | Splits preview/arbitrary text into <=280-char chunks |
| `ReaderViewModel` | `commonMain/.../reader/ReaderViewModel.kt` (3422 lines) | Android ViewModel | The only caller of `TtsController` outside these folders; maps playback operations to analytics, owns the Voices sheet state, sleep timer, listen-source switch |
| `ReaderSyncCoordinator` | `commonMain/.../reader/ReaderSyncCoordinator.kt` | `@Scoped(ReaderScope)` | Routes sentence-tap events to the active narration controller |

Callers of `TtsController` outside `tts/`, `navigator/`, `playback/`
(`grep -rln --include='*.kt' -e TtsController -e NarrationController`):
`reader/ReaderViewModel.kt`, `reader/ReaderSyncCoordinator.kt`,
`reader/saved/ReaderSavedItems.kt`, `navigator/AudioController.kt` (shares the
`NarrationController` contract). The ViewModel is the main one.

Ownership consequence worth keeping in mind for the findings below: the **engine is
app-scoped (`@Single`) while the controller is reader-scoped**. Playback therefore
outlives the reader screen on purpose (media notification keeps running), and
`AndroidTtsController.init` calls `engine.stopIfPlayingAnotherBook(...)`
(`AndroidTtsController.kt:199`, `TtsReadAloudEngine.kt:214-219`) to stop a previous book.

### 1.2 Playback states and transitions

Engine state is four `StateFlow`s plus internal bookkeeping
(`TtsReadAloudEngine.kt:70-117`): `currentSentence`, `sentenceCount`,
`currentSentenceDurationMs`, `isPlaying`, `isLoading`, plus `playbackFailures`,
`chapterCompleted`, `finishedSentences` as shared flows. `currentIndex = -1` means
"nothing loaded"; `generation` is a monotonic token that invalidates in-flight
synthesis; `pendingStartToken` marks "a new target is being synthesised, the old
playlist must not advance past it".

Controller state adds the attempt machinery: `activePlaybackAttempt`,
`playbackRequestPending`, `previousPlaybackAttemptFailed`
(`AndroidTtsController.kt:117-138`).

| Transition | Path | Notes |
| --- | --- | --- |
| Enable TTS | `ReaderViewModel.enableTtsSentencePlayback():1531` → `enableSentencePlayback():501` → `loadChapterSentences():844` | Loads the chapter's sentences from the WebView and turns on sentence-tap detection |
| Start (controls) | `togglePlayback():566` → `requestPlayback(CONTROLS)` → `startPlayback():658` → `engine.playFrom():252` → `startSentence():325` | Reads rate/pitch/voice from settings, asks for notification permission, arms the 30 s deadline, then synthesises |
| Start (sentence tap) | `playFromSentence():514` | Awaits the chapter, (re)loads sentences, finds the fragment id, else `CONTENT_UNAVAILABLE` |
| Start (chapter) | `playFromChapterStart():548` | Used for auto-advance at chapter end |
| Resume | `togglePlayback():571` when `currentSentence != null` → `engine.resume():294` → `player.play()` | No synthesis; the deadline is armed first |
| Pause | `togglePlayback():567` / `pause():583` → `player.pause()` | Position kept; `currentIndex` unchanged |
| Stop | `stop():591` → `cancelActivePlaybackAttempt()` + `engine.stop()` → `stopInternal():660` | Clears generation, prefetch, playlist; releases/stops the player |
| Sentence end | ExoPlayer `onMediaItemTransition(AUTO)` → `onSentenceStarted():704`; last item → `STATE_ENDED` → `onSentenceCompleted():617` → `startSentence(next)` | Prefetch keeps 4 sentences ahead (`PREFETCH_AHEAD`, line 776) |
| Chapter end | `onSentenceCompleted():628-634` → `stopInternal()` + `_chapterCompleted` → controller maps it to an href (`:153`) → ViewModel starts the next chapter | Also reachable by `skipToNextSentence()` on the last sentence (`:303-311`) |
| Page swipe / re-anchor | `AndroidTtsController.init:201-215` observes `bookController.currentLocator`; on a **chapter** change it clears sentences and calls `engine.stop()`, then reloads | Page turns inside the same chapter do not stop playback; playback itself turns pages via `applyHighlightWithPageTurn():227` |
| Voice change | `selectVoice():287` → `restartForSettingsChange():606` | Playing: re-`playFrom` at the same index. Paused: **`engine.stop()`** |
| Speed / pitch change | `setRate():596`, `setPitch():601` → same `restartForSettingsChange()` | `synthesisConfigChanged` in `playFrom():268` bumps generation and drops cached files for the old rate |
| Word tap over narration | `speakWord():350` → `SpeakWordCoordinator.speak():93`: synthesise first, then pause what plays, play, resume in `finally` | `ReadAloudWordInterruption:185` pauses/resumes the engine; `MediaPlaybackWordInterruption` covers recorded audio |
| Voice preview over narration | `previewVoice():292` pauses the engine, remembers `resumeNarrationAfterPreview`, resumes via a `PREVIEW_RESUME` attempt (`:642-656`) | 30 s start timeout, 60 s max preview |
| Sleep timer | `ReaderViewModel.startSleepTimer():2939`; countdown in `viewModelScope`; `cancelSleepTimer():2992` | Drives the shared narration controller, not the engine directly |
| Leaving the reader | `ReaderViewModel.close():2809` → later `onCleared():3191` → `readerScope.close()` → `AndroidTtsController.close():634` | Closes preview/word players and the controller scope; **does not stop the engine**, so narration continues by design |
| Logout | `ServerManagementViewModel.signOutEverything():156` / `onLogoutClick():193` → `LogoutUseCase` | Touches credentials and the database only; no playback path (see §4, QA-BUG-0049) |
| App backgrounded | No TTS-specific hook; the media session + `ForegroundServiceController` keep the player alive | `showPlaybackNotification = true` routes playback through the service player |
| Audio focus loss | Handled by ExoPlayer: `setAudioAttributes(..., handleAudioFocus = true)` for the engine's local player (`:446`), the preview player (`:121-127`) and the word player (`:79-85`); the service player is configured in `MediaPlaybackService` | Focus loss pauses, which surfaces as `isPlaying = false` |
| External media takes over | `onMediaItemTransition` sees a non-`tts:` media id → `detachForExternalPlayback():682` | Engine lets go of the shared player without stopping it |

### 1.3 The three synthesizers, routing and fallback

`TtsSynthesizerRouter` routes purely on the **voice id prefix**
(`TtsVoice.kt:3-21`): `kokoro:<n>` → Kokoro, `supertonic:<n>` → Supertonic,
anything else (including `null`) → the platform engine.

- `synthesize()` (`TtsSynthesizerRouter.kt:72-90`) picks the engine per call and logs
  `voice=… engine=…`.
- `availableVoices()` (`:22-25`) concatenates all three lists; neural entries carry
  `isDownloaded`, `downloadSizeBytes`, `updateAvailable` from `TtsModelManager`.
- `awaitReady()` (`:19-20`) **only waits for the system engine**.
- `defaultVoice()` (`:27-28`) is the system default, else Kokoro's first voice.
- `prepareVoice`, `activeModelVersion`, `warmUp`, `deleteNeuralVoicePackage` all
  dispatch on the same prefix.

There is **no automatic fallback between engines at synthesis time**: if Kokoro's model
is missing, `SherpaOnnxSynthesizer.synthesize():97-102` returns `ERROR` and the engine
turns that into a failed start; it does not retry on the system voice. The only
fallback in the stack is for single-word speech
(`resolveWordVoice`, `SpeakWordVoice.kt:32-86`), which falls back from an unusable
neural voice to the best offline English system voice and never triggers a download.

### 1.4 The audio cache

`TtsAudioCache` (`TtsAudioCache.kt`) stores one WAV per synthesised chunk in
`context.cacheDir/tts/`. The key (`:20-26`) is
`sha256("<voiceId>|<modelVersion>|<rate*100>|<pitch*100>|<text>")`, so a voice change,
a rate change, a pitch change or a pack version change all miss the cache.
`modelVersion` comes from `TtsSynthesizer.activeModelVersion()` and is `null` for
system voices, which means system-voice audio is **not** keyed to the platform engine's
version. `get()` touches `lastModified` (LRU), `onStored()` trims every 10th store
(`STORES_PER_TRIM`) down to `MAX_CACHE_BYTES = 128 MB`, oldest first. `durationMs()`
parses byte rate straight out of the canonical 44-byte WAV header rather than paying
for `MediaMetadataRetriever`.

The synthesizer writes **directly to the final cache path** (`TtsAudioGenerator.kt:41`),
guarded by a per-key `Mutex` and one global `Semaphore(1)`
(`MAX_CONCURRENT_SYNTHESIS`, `:20`, `:109`), so read-aloud, preview and word taps all
queue on one permit.

### 1.5 Voice packs: download, verification, update, delete

`TtsModelManager` installs each version into `filesDir/tts-models/<modelId>/<version>/`
and records the live one in a `.active` marker (`:517-532`).

- **Manifest**: `MANIFEST_URL` is a GitHub "latest release" asset (`:773-774`); fetched
  by hand-rolled redirect following where every hop must be https on a trusted host
  (`openTrustedConnection():646-675`), validated by `TtsModelManifestValidator`
  (`trustedModel():604-609`), cached to `tts-models/manifest.json` and refreshed at most
  daily (`refreshManifestIfStale():87-101`).
- **Download**: `installVersion():222-278` reuses unchanged files from the active
  version by hard link (`reuseUnchangedFile():285-310`), checks free space with a 64 MB
  margin, then `downloadFile():327-353` → `transfer()` (HTTP `Range` resume into
  `<path>.part`) → `verifyChecksum()` (SHA-256, deletes the part on mismatch) →
  `install()` (rename into place; zip entries extracted via a `.tmp` staging dir with
  path-escape checks in `unzip():432-474`). Three attempts with backoff.
- **Verification**: every downloaded file is checksum-verified before install; the
  `.active` marker only moves after `isComplete(...)` passes (`loadModel():196-201`),
  and `isComplete` also enforces minimum byte sizes (`:733-745`).
- **Update**: `prepareVoice(updateToLatest = true)` downloads the new version
  side-by-side, then releases and reloads the engine
  (`SherpaOnnxSynthesizer.kt:64-72`). The previous version is kept until the next
  successful update (`deleteOutdatedVersions():534-540`).
- **Delete**: `deleteNeuralVoicePackage()` stops playback and preview, then
  `loadMutex`-guards `stop(); releaseEngine(); deleteModel()`
  (`SherpaOnnxSynthesizer.kt:79-88`), which removes the model root plus legacy paths.
- **Foreground service**: the controller starts
  `TtsVoicePreparationForegroundService` (`AndroidTtsController.kt:424-444`) after
  `notificationPermissionHandler.ensurePermission()`; the service owns the progress
  notification, a Cancel action, and marks the state holder Complete/Failed/Idle.

### 1.6 Concurrency: which guard protects what

| Guard | Where | Protects |
| --- | --- | --- |
| `controllerScope` = `SupervisorJob() + Dispatchers.Main.immediate` | `AndroidTtsController.kt:101` | All controller coroutines; cancelled in `close()` |
| `TtsPlaybackOperationLifecycle.requestJob` | `TtsPlaybackOperationLifecycle.kt:16-44` | Exactly one pending playback request; `cancelPendingRequest()` on stop/failure |
| `startupTimeoutJob` | same file, `:46-63` | The 30 s start deadline (`TTS_PLAYBACK_START_TIMEOUT_MS`) |
| `activePlaybackAttempt` identity checks | `AndroidTtsController.kt:736, 751, 769, 790` | One terminal outcome per attempt |
| `engine.scope` = `SupervisorJob() + Dispatchers.Main.immediate` | `TtsReadAloudEngine.kt:66` | Prefetch jobs, auto-advance, media-session requests |
| `generation` / `pendingStartToken` | `:78`, `:87` | Invalidate stale synthesis and stop an old playlist stealing a new target |
| `activeSynthesisJob`, `prefetchJobs` | `:91-92`, `cancelPrefetch():601`, `cancelActiveSynthesis():612` | Cancel in-flight and queued synthesis |
| `prefetchHolds` | `:579-599` | Lets a word tap jump the synthesis queue |
| `TtsAudioGenerator.lockRegistryMutex` + per-key `Mutex` | `TtsAudioGenerator.kt:18-19, 84-101` | One synthesis per cache key; ref-counted registry |
| `TtsAudioGenerator.synthesisSemaphore(1)` | `:20` | One concurrent synthesis in the whole app |
| `TtsAudioCache` `@Synchronized` | `TtsAudioCache.kt:30, 38, 85, 90` | `get` / `onStored` / `clear` / `trim` |
| Sherpa/Supertonic `loadMutex`, `generationMutex`, `cancellationGeneration` | `SherpaOnnxSynthesizer.kt:30-32` | One engine load; one native generation at a time; cooperative abort |
| `AndroidSystemTtsSynthesizer` `ConcurrentHashMap` of pending utterances | `AndroidSystemTtsSynthesizer.kt:31-33` | Per-utterance completion without a global stop |
| `TtsVoicePreparationStateHolder` CAS loop | `TtsVoicePreparationStateHolder.kt:22-31` | Only one pack preparation at a time |
| `ReaderViewModel.readerSettingsSaveMutex`, `ttsPreparationJob`, `ttsSentencePlaybackJob`, `isObservingTtsPlaybackOperations` | `ReaderViewModel.kt:324, 317, 320, 321` | Settings writes, one preparation, one enable job, one operations collector |

---

## 2. Existing tests and what has none

### 2.1 TTS tests that exist

| Test class | Source set | `@Test` count | Covers |
| --- | --- | --- | --- |
| `SpeakWordCoordinatorTest` | commonTest | 8 | Word request replacement, pause/resume of interruptions, failures, cancellation |
| `SpeakWordTextTest` | commonTest | 8 | `prepareWordForSpeech` / `isSpeakableWordForm` |
| `SpeakWordVoiceTest` | commonTest | 16 | `resolveWordVoice` gating and fallback |
| `TtsChapterTimelineTest` | commonTest | 5 | Estimate, measured durations, position maths |
| `TtsHeardSentenceTrackerTest` | commonTest | 6 | Heard-in-full accounting across transitions and seeks |
| `TtsModelManifestTest` | commonTest | 4 | Manifest parsing |
| `TtsModelManifestValidatorTest` | commonTest | 11 | Path/URL/host validation |
| `TtsPreparationProgressTest` | commonTest | 2 | Percentage/fraction maths |
| `TtsSentenceChunkerTest` | commonTest | 4 | Chunking, abbreviations, long-chunk split |
| `TtsVoicePreparationStateHolderTest` | commonTest | 3 | begin/progress/terminal transitions |
| `TtsVoiceTest` | commonTest | 5 | Voice id prefixes, `needsDownload`, quality |
| `TtsPlaybackOperationLifecycleTest` | commonTest | 3 | Request cancellation and the startup deadline |
| `VoicePackStateTest` | commonTest | 14 | `derivePackState` state table |
| `VoiceSettingsScreenTest` | commonTest | 5 | Voices sheet rendering/selection |
| `ReaderRecapCaptureTest` | commonTest | 7 | Finished-sentence capture |
| `TtsModelPathsTest` | androidHostTest | 6 | `resolveInside`, `deleteRecursivelyNoFollow`, safe folder names |
| `SupertonicOnnxSynthesizerTest` | androidHostTest | 1 | Voice list shape |

Baseline (this run): `:feature:reader:ui:testAndroidHostTest` 277/277 passed,
`:feature:settings:ui:testAndroidHostTest` 20/20 passed, no failures, no skips.

### 2.2 Classes with no test at all

- `TtsReadAloudEngine` — the whole playlist/prefetch/advance state machine, 782 lines.
- `AndroidTtsController` — attempts, outcomes, preview resume, chapter awaiting, 889 lines.
- `TtsSynthesizerRouter` — routing and `awaitReady`/`defaultVoice` semantics.
- `AndroidSystemTtsSynthesizer` — utterance bookkeeping, timeout recovery, global stop.
- `SherpaOnnxSynthesizer` — load/release/cancel, error paths, partial-save handling.
- `SupertonicOnnxSynthesizer` — only the static voice list is asserted.
- `TtsAudioGenerator` — per-key lock, the single permit, cache-hit path.
- `TtsAudioCache` — key composition, `trim` eviction order, `durationMs` WAV parsing.
- `TtsModelManager` — download/resume/checksum/install/update/delete, manifest timeout.
- `TtsVoicePreparationForegroundService` — start/cancel/finish and notification lifecycle.
- `TtsPreviewPlayer`, `TtsWordPlayer`, `TtsWordAudioSource` — player lifecycle, trimming.
- `TtsSessionPlayer` — chapter duration/position mapping and seek redirection.
- `WavSilenceTrimmer` — `trimWavSilence` and `parsePcm16Wav` (no test despite being pure).
- `ReaderViewModel`'s TTS surface — voice selection, preparation observers, listen-source
  switch, sleep timer interaction with narration.

### 2.3 Transitions with no test at all

Start/pause/resume/stop ordering; two quick taps; sentence end and auto-advance;
chapter end and hand-off to the next chapter; page swipe and chapter re-anchor; voice
change while playing and while paused; speed/pitch change while playing; speak-a-word
interrupting narration and the resume afterwards; preview over narration and
`PREVIEW_RESUME`; sleep-timer expiry; leaving the reader while playing; logout while
playing; app backgrounded; audio-focus loss; external media taking the shared player;
empty chapter; image-only chapter; very long sentence; last sentence of the last
chapter; partial pack download and resume; checksum mismatch; delete of the selected
voice; update while that voice plays; two packs installed side by side; cache trim
while a playlist references the trimmed file.

---

## 3. Findings

Twenty-seven findings in total: TTS-F01 to TTS-F21 below came out of the source read in
step 2, TTS-F22 to TTS-F24 in §3b came out of the device run in step 3, and TTS-F25 to
TTS-F27 in §3c came out of writing run 2b's tests (F25) and of run 2b's device pass
(F26 and F27). The Evidence
line of each one says whether it was reproduced. Two of the source findings (TTS-F06 and
TTS-F07) were confirmed on the device; their Evidence lines point at §5. Severity is the
effect on a user, not on the code.

### TTS-F01 — A synthesis or player failure outside a user-initiated start crashes the app

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/TtsReadAloudEngine.kt:346-367` (throws `TtsPlaybackStartException`), thrown into bare `launch` blocks at `:312-314` (`skipToNextSentence`), `:320-322` (`skipToPreviousSentence`), `:625-627` (auto-advance in `onSentenceCompleted`), `:652-657` (`seekToChapterPosition`); and `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/navigator/AndroidTtsController.kt:611-620` (`restartForSettingsChange`).
- **Trigger:** Start narration. While it plays, make the *next* sentence fail synthesis — the system engine returns `ERROR`/`TIMEOUT` for it, or the Kokoro model has been deleted or fails to load, or the device runs out of space for the WAV. The next sentence is then started by auto-advance, by the media-notification skip button, or by a speed change, i.e. not by a user playback request.
- **Expected:** One bounded failure outcome, narration stops or retries, no crash.
- **Actual:** `startSentence` throws. On the user-request path the exception is caught by `requestPlayback` (`AndroidTtsController.kt:704-711`); on these four engine paths and the settings-restart path nothing catches it. The engine scope is `SupervisorJob() + Dispatchers.Main.immediate` (`TtsReadAloudEngine.kt:66`) with no `CoroutineExceptionHandler`, and `grep -rn CoroutineExceptionHandler --include='*.kt' .` finds none anywhere in the repository, so the exception reaches the thread's default handler: the app crashes.
- **Evidence:** CODE-ONLY.
- **Confidence:** high that the throw is uncaught on those paths and that no handler exists; medium that every one of them is reachable with a real engine (auto-advance is the easiest). Raise it with the unit test below, which needs no device.
- **Severity for a user:** crash.
- **How a test could catch it:** unit test on `TtsReadAloudEngine` (new `TtsReadAloudEngineTest`, androidHostTest) with a fake `TtsAudioGenerator` that succeeds for sentence 0 and returns `TtsSynthesisResult(ERROR)` for sentence 1, plus a fake player: call `setSentences` + `playFrom(0, …)`, drive the sentence-ended callback, and assert no uncaught exception escapes the engine scope and that one `playbackFailures` value is emitted.
- **Fixed:** `fc9e4804` (run 2a) — the four paths go through `launchEngineStart`, which stops and emits one `PlaybackFailure`; tests committed failing in `5c5a6b0e`, seam in `c2c62cef`.

### TTS-F02 — A mid-playback synthesis failure emits no outcome at all; narration just stops

- **Where:** `TtsReadAloudEngine.kt:346-367` and `:506-540` (synthesis failure throws or returns null and never touches `_playbackFailures`); the only producer of `_playbackFailures` is `onPlayerError` at `:174-183`. Consumer: `AndroidTtsController.kt:240-263`.
- **Trigger:** As TTS-F01, for whichever of those paths does not crash — in particular any synthesis failure while `activePlaybackAttempt` is null, which is every moment after a start has succeeded.
- **Expected:** One `TtsPlaybackOperation.Failed(SYNTHESIS_FAILED)`, which the ViewModel turns into the retry bar (`ReaderViewModel.kt:1191-1199`).
- **Actual:** `stopInternal()` runs and playback ends silently: no failure event, no retry bar, no analytics outcome. This is the residual case of QA-BUG-0095 (§4).
- **Evidence:** CODE-ONLY.
- **Confidence:** high — `_playbackFailures.tryEmit` appears exactly once in the file, in the player-error callback.
- **Severity for a user:** silent failure (plus analytics).
- **How a test could catch it:** the same `TtsReadAloudEngineTest` fixture as TTS-F01, asserting a `PlaybackFailure` with `SYNTHESIS_FAILED` is emitted when a mid-chapter sentence fails.
- **Fixed:** `fc9e4804` (run 2a) — one `PlaybackFailure(SYNTHESIS_FAILED)` with the current correlation id on each of those four paths; the user-start path still emits none, so it keeps producing exactly one `Failed`. The last gap, `restartForSettingsChange`, was closed in run 2b (`6e919edb`, TTS-F07).

### TTS-F03 — A failed neural save leaves a partial WAV that is served as a valid cache hit

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/SherpaOnnxSynthesizer.kt:150-157` and `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/SupertonicOnnxSynthesizer.kt:161-168` — the `ERROR` branch returns without deleting `outputFile`, unlike the cancellation branch (`:158-160` / `:169-171`) and unlike `AndroidSystemTtsSynthesizer`, which deletes on every failure path (`AndroidSystemTtsSynthesizer.kt:46-51, 70-78, 180, 207, 261`). That file is the final cache path (`TtsAudioGenerator.kt:41`) and `TtsAudioCache.get` accepts any non-empty file (`TtsAudioCache.kt:31-36`).
- **Trigger:** With Kokoro selected, make `audio.save()` fail or truncate for one sentence — fill the device's storage, or interrupt the write. Then play that same sentence again with the same voice, rate and pitch.
- **Expected:** A failed synthesis leaves no cache entry; the retry synthesises again.
- **Actual:** The truncated WAV stays at the cache key, so every later request for that sentence is a cache hit and the sentence is permanently clipped or garbled until the 128 MB trim happens to evict it.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium-high for the missing delete (plainly visible); medium that `save()` leaves a non-empty partial rather than nothing. Raise it by stubbing the sherpa `OfflineTts` wrapper, or by filling the emulator's data partition.
- **Severity for a user:** wrong audio.
- **How a test could catch it:** unit test on `TtsAudioGenerator` (new `TtsAudioGeneratorTest`) with a fake `TtsSynthesizer` that writes a few bytes to `outputFile` and returns `ERROR`: assert `cache.get(key)` is null afterwards — a non-success result must never leave a cache entry.

### TTS-F04 — The manifest refresh timeout cannot fire, so opening the voice list can block for up to 90 s

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/TtsModelManager.kt:87-101` wraps `fetchManifest()` in `withTimeoutOrNull(MANIFEST_FETCH_TIMEOUT_MS = 5_000)` (`:788`), but `fetchManifest()` (`:611-640`) is an ordinary blocking function — `HttpURLConnection` with `CONNECT_TIMEOUT_MS = 30_000` and `READ_TIMEOUT_MS = 60_000` (`:790-791`) and no suspension point inside it. `withTimeoutOrNull` can only cancel at a suspension point.
- **Trigger:** Put the device on a network that accepts TCP connections but never answers (captive portal; on the emulator, a stalled egress). Open the reader on a book with TTS enabled, or open Voices: `ReaderViewModel.initTts` (`ReaderViewModel.kt:1089`) awaits `ttsController.availableVoices()`, which awaits `modelManager.refreshManifestIfStale()` (`AndroidTtsController.kt:281-285`).
- **Expected:** At most about 5 s, then fall back to the cached manifest.
- **Actual:** Up to 90 s on the IO dispatcher. The voice list, the selected-voice application and the pack sizes all wait on it, so the sheet shows an empty or stale list for that whole time. It does not block the main thread, so there is no ANR.
- **Evidence:** CODE-ONLY.
- **Confidence:** high — a direct consequence of `withTimeoutOrNull` semantics plus a body with no suspension points.
- **Severity for a user:** stuck state.
- **How a test could catch it:** unit test on `TtsModelManager` (new `TtsModelManagerTest`) against a local `ServerSocket` that accepts and never replies, asserting `refreshManifestIfStale()` returns within about 6 s. Testability limit worth noting in the plan: `MANIFEST_URL` is a `const` (`:773-774`), so the URL has to become injectable first.

### TTS-F05 — Abandoned `.part` files are never cleaned up and the user cannot reclaim the space

- **Where:** `TtsModelManager.kt:327-353` (`downloadFile` keeps the partial on cancellation by design, `:340-342`, and also leaves it after the third failed attempt, `:343-352`), `:192-194` and `:207-212` (`loadModel` returns null; nothing deletes the version directory), `:542-545` (`partialFile`). The only cleanup is `deleteModel` (`:556-582`), which the UI offers only for an installed pack (`VoicePackState.kt:49-70`: Delete belongs to `Downloaded`/`UpdateAvailable`).
- **Trigger:** Start the Kokoro pack download, keep the network off until all three attempts fail, then leave the sheet.
- **Expected:** Either a visible resumable download, or the partial bytes are reclaimable.
- **Actual:** Up to the full pack size (about 150 MB for Kokoro) stays in `filesDir/tts-models/<model>/<version>/*.part` while the card says the pack is not downloaded. A later download does resume from it, so the bytes are not lost forever — but if the manifest version moves on, the stale version directory is only removed by `deleteOutdatedVersions` (`:534-540`), which runs **after** a successful install, so a user who never retries keeps the bytes with no in-app way to free them.
- **Evidence:** CODE-ONLY. Supporting observation from §5 check 21: a *successful* install leaves no partial files behind, so the leak is specific to the abandoned-failure path, which was not induced on device.
- **Confidence:** medium-high.
- **Severity for a user:** silent failure (disk usage).
- **How a test could catch it:** `TtsModelManagerTest` with a stubbed download that always fails, asserting the version directory holds no orphaned partial after `ensureKokoroModel` returns null; manual: interrupt a download, then compare the app's `tts-models` size against a card that says "not downloaded".

### TTS-F06 — Changing voice, speed or pitch while narration is paused throws the paused position away

- **Where:** `AndroidTtsController.kt:606-624` — `restartForSettingsChange()` calls `engine.stop()` whenever `engine.isPlaying.value` is false; `stopInternal` (`TtsReadAloudEngine.kt:660-680`) resets `currentIndex = -1` and `_currentSentence = null`. Entered from `selectVoice:287-290`, `setRate:596-599`, `setPitch:601-604`, which the ViewModel calls from `selectTtsVoice:1230`, `setTtsRate:1477`, `setTtsPitch:1491`.
- **Trigger:** Start narration mid-chapter, pause, change the speed (or pick a different voice), then press play.
- **Expected:** Playback resumes at the sentence it was paused on, with the new setting.
- **Actual:** The engine is stopped, so `togglePlayback` (`:571`) sees `currentSentence == null`, treats the next press as a fresh `CONTROLS` start, and `resolveStartIndex()` (`:866-870`) restarts from the first *visible* sentence. The user silently loses their place within the page.
- **Evidence:** REPRODUCED — `D1-paused-sentence-89.png` (paused at sentence 89), `D2-rate-changed-while-paused.png` (rate now 1.2×, sheet still claims 89), `D3-restarted-at-sentence-87.png` (restarted at 87). The decisive signal is in `E1-tts-logcat-excerpt.txt`: the play after the paused rate change emitted `tts_action=controls` at 16:20:33, where a plain pause/resume had emitted `tts_action=resume` at 16:01:07.
- **Confidence:** high.
- **Severity for a user:** stuck state / wrong position.
- **How a test could catch it:** unit test on `AndroidTtsController` (new `AndroidTtsControllerTest`, androidHostTest, fake engine) asserting `setRate` while not playing records the rate without calling `engine.stop()`; manual: the trigger above, checking which sentence is highlighted after resuming.
- **Fixed:** `480a4773` (run 2b) — the paused change keeps the position instead of stopping the engine, and the next play press restarts that same sentence from its start through `engine.playFrom`, so the audio made with the old setting is re-synthesised rather than resumed; the press reports `resume`. Tests committed failing in `4bc7cdc8` (`TtsPlaybackAttemptsTest`, six cases), seam in `381f6340`.
- **Device check 2026-10-09:** passed — paused on 69 and 82 with a System voice and on 137 with Kokoro, the sentence held across a speed and a voice change each time, and every play press restarted that same sentence reporting `resume` (`docs/manual-qa-evidence/2026-10-09/tts-run2b-device/`).

### TTS-F07 — A speed or voice change during playback restarts synthesis outside the attempt machinery

- **Where:** `AndroidTtsController.kt:610-620` — the playing branch of `restartForSettingsChange` launches `engine.playFrom(...)` straight into `controllerScope`, bypassing `requestPlayback` (`:689-713`).
- **Trigger:** Change the speed (or the voice) while narration plays; worst case with a voice whose synthesis then fails.
- **Expected:** The restart is an operation with an attempt and a terminal outcome, like every other start.
- **Actual:** No `Attempted`, no `Succeeded`, no `Failed`; `isPlaybackStartPending` stays false so the UI shows no progress; no 30 s deadline covers it; and if the new synthesis fails, narration stops with no event (TTS-F02) and may crash (TTS-F01).
- **Evidence:** REPRODUCED — `E1-tts-logcat-excerpt.txt` at 16:19:09: tapping Rate + during playback logged `tts_rate_changed{rate=1.1}` and five fresh `TtsRouter: synthesize` lines within 1.4 s, with **no** `tts_playback_operation` line of any outcome for the restart.
- **Confidence:** high for the missing outcomes — the call simply does not go through `requestPlayback`.
- **Severity for a user:** silent failure; analytics only in the success case.
- **How a test could catch it:** `AndroidTtsControllerTest` — change the rate while the fake engine reports playing and assert an `Attempted` plus one terminal outcome on `playbackOperations`.
- **Fixed:** `6e919edb` (run 2b) — the restart is a tracked operation reporting the new `TtsPlaybackAction.SETTINGS_CHANGE`: one `Attempted`, exactly one terminal outcome, `isPlaybackStartPending` set while it runs, the 30 s deadline armed before synthesis, and the engine given this attempt's correlation id. A second change supersedes the restart in flight (`Cancelled`), so one attempt never gets two terminal outcomes. Tests committed failing in `af95f1f2` (four cases).
- **Device check 2026-10-09:** passed — one attempted and one terminal outcome per restart with a System voice and with Kokoro, three quick changes gave three attempts with two `cancelled` and one `succeeded` and narration kept playing; one gap, the `tts_playback_operation` event carries no `tts_action` for this action, only the breadcrumb's `entry_point=settings_change` (`docs/manual-qa-evidence/2026-10-09/tts-run2b-device/`).

### TTS-F08 — `stopWord()` immediately followed by `speakWord()` can resume narration under the new word

- **Where:** `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/tts/SpeakWordCoordinator.kt:93-123`. `speak()` serialises against the previous request with `previous?.cancelAndJoin()` (`:98`), but `stop()` (`:119-123`) sets `job = null` before cancelling, so a `speak()` that follows a `stop()` sees `previous == null` and does not wait for the cancelled job's `finally` block (`:111-115`: `player.stop()`, `interruption.resume()`, state `Idle`).
- **Trigger:** With narration playing, tap a word (narration pauses, the word speaks), dismiss the dictionary — which calls `stopWord` (`AndroidTtsController.kt:368-370`) — then tap another word immediately.
- **Expected:** The replaced request finishes its cleanup before the new one changes any state, which is exactly what the class comment at `:71-77` promises.
- **Actual:** The old `finally` can run after the new request has set `Preparing`/`Speaking`: it stops the new word's player, resumes narration while the new word is speaking, and leaves `state = Idle` while audio plays, so the speaker button stops showing progress.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium — the window is one dispatch; the existing `SpeakWordCoordinatorTest` covers `speak`-replaces-`speak` but not `stop`-then-`speak`.
- **Severity for a user:** wrong audio / stuck state.
- **How a test could catch it:** add a case to `SpeakWordCoordinatorTest` on a `StandardTestDispatcher`: `speak(a)`, `stop()`, `speak(b)` with no advancing in between, then assert the interruption is paused once, is resumed only after b finishes, and the state order is `Preparing → Speaking → Idle`.

### TTS-F09 — A word clip that never completes leaves narration paused indefinitely

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/TtsWordPlayback.kt:47-63` — `TtsWordPlayer.play` awaits a `CompletableDeferred` with no timeout, completed only by `STATE_ENDED` or `onPlayerError` (`:37-45`). `SpeakWordCoordinator` pauses narration before calling it (`SpeakWordCoordinator.kt:107-110`) and resumes only in the `finally`.
- **Trigger:** Tap a word while the app cannot gain audio focus — during a phone call, or with another app holding exclusive focus. The player is built with `handleAudioFocus = true` (`TtsWordPlayback.kt:79-85`), so playback is deferred rather than failed: no `STATE_ENDED` and no error.
- **Expected:** A bounded wait, then resume narration and report one word failure.
- **Actual:** The coordinator stays `Speaking`, narration stays paused, no failure event; only another word tap or leaving the reader clears it.
- **Evidence:** CODE-ONLY — focus denial was not simulated on the emulator (§5).
- **Confidence:** medium. Raise it by holding audio focus from another app and tapping a word.
- **Severity for a user:** stuck state.
- **How a test could catch it:** `SpeakWordCoordinatorTest` with a `WordPlayer` whose `play` never returns: assert the coordinator gives up within a bounded time and resumes the interruption. That assertion fails today, which is the finding.

### TTS-F10 — Two reader ViewModels share one reader scope: duplicated TTS events, and a controller closed under the survivor

- **Where:** `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReaderViewModel.kt:236-250` (`getKoin().getOrCreateScope<ReaderScope>(bookUuid)` — keyed by book, therefore shared), `:264-268` (`addCloseable(controller)`), `:1140-1142` (`isObservingTtsPlaybackOperations` is an instance field, so it de-duplicates only within one ViewModel), `:3191-3203` (`onCleared` → `readerScope.close()`); `AndroidTtsController.kt:118-120` (`MutableSharedFlow`: every subscriber receives every event).
- **Trigger:** Have two `ReaderViewModel` instances alive for the same book — re-enter the reader route for the book that is already open, or a configuration change where the previous ViewModel is not cleared before the new one initialises — then start TTS once.
- **Expected:** One `attempted` and exactly one matching terminal event and breadcrumb per user action.
- **Actual:** Each live ViewModel holds its own collector of the same shared flow, so one successful start produces two identical `succeeded` events and two identical breadcrumbs with the same correlation ID — precisely what QA-BUG-0100 recorded. Separately, whichever ViewModel is cleared first closes the shared `TtsController` and the Koin scope the other is still using, so TTS stops working for the surviving screen.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium. The duplicate-collector mechanism is certain; what is unproven is which user action produces two live ViewModels. Raise it by starting TTS, rotating the device or re-entering the reader from Continue reading, and counting `tts_playback_operation` lines per correlation ID.
- **Severity for a user:** analytics only for the duplicate; stuck state if the shared controller is closed under a live screen.
- **How a test could catch it:** unit test at the ViewModel/Koin boundary asserting that `getOrCreateScope<ReaderScope>(bookUuid)` resolves the same `TtsController` for two ViewModels and that closing one does not close it for the other; manual: the rotation steps above with the debug analytics log.

### TTS-F11 — Only the playback-operations collector is guarded against a second initialisation

- **Where:** `ReaderViewModel.kt:1109-1113` launches `observeNarrationPlaybackState`, `observeTtsPreviewState`, `observeTtsSentenceProgress` and `observeTtsVoicePreparationState` with no re-entry guard, while `observeTtsPlaybackOperations` has one (`:1140-1142`). `initTts` is called at `:879` and `:890`, and `retry()` (`:582-613`) re-runs `initializeReader()`.
- **Trigger:** Any path that reaches `initTts` twice in the same ViewModel.
- **Expected:** One collector each, as for playback operations.
- **Actual:** Duplicated collectors would double every state update and, worse, call `recapCapture?.onSentenceFinished(finished)` twice per sentence (`:1634-1636`), double-counting recap sentences.
- **Evidence:** CODE-ONLY.
- **Confidence:** low that a second `initTts` is reachable today — the two call sites are mutually exclusive branches and `retry()` only runs after a failure that never reached `initTts`. High that nothing structurally prevents it, which makes it a latent regression beside a guard that exists for one sibling.
- **Severity for a user:** analytics only (double-counted recap sentences).
- **How a test could catch it:** ViewModel unit test that drives initialisation twice and asserts `onSentenceFinished` fires once per finished sentence.

### TTS-F12 — The cache trim can delete a WAV that is already queued in the player

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/TtsAudioCache.kt:90-102` (`trim` deletes oldest-first with no notion of what is in use), called from `onStored` every tenth store (`:38-49`; `STORES_PER_TRIM = 10`, `MAX_CACHE_BYTES = 128 MB`, `:115-117`). The engine checks `exists()` only when it builds or extends the playlist (`TtsReadAloudEngine.kt:738-749`, `:751-764`).
- **Trigger:** Read long enough with a neural voice that the `tts` cache directory exceeds 128 MB (uncompressed WAV is roughly 44 KB/s, so a few hundred sentences), so a trim runs while a playlist of up to five queued sentences is live.
- **Expected:** Audio still referenced by the active playlist is not evicted.
- **Actual:** If a queued file is deleted before it plays, ExoPlayer raises a source error → `onPlayerError` (`:174-183`) → one `PLAYER_ERROR` and `stopInternal()`: narration stops mid-chapter. Queued files are recent so they are last in the eviction order, which makes this unlikely but not excluded — `readyFiles` also holds earlier sentences of the same chapter whose `lastModified` is old.
- **Evidence:** CODE-ONLY.
- **Confidence:** low. Raise it with a unit test that trims to a tiny budget while a playlist holds a file.
- **Severity for a user:** stuck state (playback stops).
- **How a test could catch it:** unit test on `TtsAudioCache` (new `TtsAudioCacheTest`) for eviction order and key composition, plus an engine-level test that a trimmed file re-synthesises instead of erroring.

### TTS-F13 — Router readiness is decided by the system engine alone, so a neural-only setup loses the word speaker

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/TtsSynthesizerRouter.kt:19-20` (`awaitReady` delegates only to `systemSynthesizer`), used by `AndroidTtsController.availableVoices:282` and `currentWordVoice:387` (`if (!synthesizer.awaitReady()) return WordVoiceResolution.Hidden`). Contrast `isReady()` at `:14-17`, which does consider all three engines.
- **Trigger:** A device or image with no usable system TTS engine, or one that fails to initialise within the 3 s default (`TtsSynthesizer.kt:23`), with the Kokoro pack installed and a Kokoro voice selected. Select a word in the reader.
- **Expected:** The speaker button appears and speaks through the installed neural voice.
- **Actual:** `canSpeakWord` is false so the button is hidden, and `availableVoices()` sits behind a readiness gate the neural engines do not need.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium-high for the code path; frequency depends on how often the platform engine is missing.
- **Severity for a user:** silent failure (a feature silently absent).
- **How a test could catch it:** unit test on `TtsSynthesizerRouter` (new `TtsSynthesizerRouterTest`) asserting `awaitReady()` is true when only a neural engine is ready, mirroring `isReady()`.

### TTS-F14 — A chapter with no readable sentences fails every start with a retry that cannot succeed

- **Where:** `AndroidTtsController.kt:844-864` (`loadChapterSentences` returns false when `getChapterSentences()` is empty, `:854-855`), consumed at `:526-528`, `:535-537`, `:559-561` and `:662-664` as `CONTENT_UNAVAILABLE`; the ViewModel turns a `Failed` operation into the retry bar (`ReaderViewModel.kt:1191-1199`).
- **Trigger:** Open a chapter that is images only or empty — cover pages and plates in Gutenberg EPUBs are the ordinary case — and press play.
- **Expected:** A clear "nothing to read on this page" state, or an automatic move to the next chapter with content.
- **Actual:** A failed start with a Retry affordance that fails identically every time, and one `failed(content_unavailable)` per press.
- **Evidence:** CODE-ONLY — not checked on device: the Gutenberg edition used "had all images removed", so only the cover qualified, and the cover produced TTS-F23 instead.
- **Confidence:** medium — the mechanism is clear; the user-visible wording was not checked on device.
- **Severity for a user:** silent failure / a confusing dead end.
- **How a test could catch it:** `AndroidTtsControllerTest` with a fake `BookController` returning no sentences: assert one `Failed(CONTENT_UNAVAILABLE)` and no engine start; manual: open an image-only chapter and press play.

### TTS-F15 — The first Kokoro start must load the model inside the 30 s start deadline

- **Where:** `AndroidTtsController.kt:674` arms `armPlaybackStartTimeout` before `engine.playFrom` (`TTS_PLAYBACK_START_TIMEOUT_MS = 30_000`, `:877`); a cold Kokoro engine's first synthesis runs `ensureLoaded()` → `loadEngine()` including a warm-up generation (`SherpaOnnxSynthesizer.kt:186-242`) inside that window, behind the single synthesis permit (`TtsAudioGenerator.kt:20`).
- **Trigger:** Select a Kokoro voice, force-stop the app so the engine is cold, reopen the book and press play once on slow hardware.
- **Expected:** The start completes, or reports progress; a cold model load should not be charged against a 30 s user-start deadline.
- **Actual:** If load plus the first sentence exceeds 30 s, `onStartupTimeout` (`:126-136`) reports `Failed(START_TIMEOUT)` and calls `engine.stop()`, so the press looks broken while the model was loading correctly. `docs/tts-preparation-benchmarks.md:19` measures engine load at about 0.9 s on the benchmark phone, so real hardware has a comfortable margin; software-rendered emulators are the realistic risk.
- **Evidence:** CODE-ONLY — the deadline could not be measured, because on this build the first Kokoro synthesis never completes at all (TTS-F22). For scale, the pack download took 10.9 s and the engine load about 2 s, both well inside the 30 s window.
- **Confidence:** low for ordinary devices, medium for slow ones.
- **Severity for a user:** silent failure (a start that looks broken).
- **How a test could catch it:** `AndroidTtsControllerTest` with a fake engine that never becomes active, asserting `Failed(START_TIMEOUT)` after the virtual 30 s; manual: measure the cold first start from `logcat -s SherpaOnnxTts` timestamps.

### TTS-F16 — `chapterCompleted` reports whatever chapter is current, not the one that finished

- **Where:** `TtsReadAloudEngine.kt:112-113` and `:628-634` emit `Unit` with no chapter identity; `AndroidTtsController.kt:153-154` turns it into `lastLocator?.href`. The same pattern affects finished sentences: `:158-159` labels them with `engineChapterHref`, which `startPlayback` reassigns at `:678`.
- **Trigger:** Narration reaches the end of a chapter at the moment the locator has already moved — a page swipe into the next chapter, or a new chapter start already in flight.
- **Expected:** The completion event names the chapter that actually finished.
- **Actual:** The event can carry the next chapter's href, so auto-advance can act on the wrong chapter and recap sentences can be attributed to the wrong one.
- **Evidence:** CODE-ONLY.
- **Confidence:** low — the locator collector stops the engine on a chapter change (`:205-212`), which closes most of the window. I could not rule out the remaining race by reading alone and did not construct it on the emulator.
- **Severity for a user:** wrong audio (a chapter replayed or skipped) at worst; otherwise analytics only.
- **How a test could catch it:** make the engine emit the completed chapter's href instead of `Unit` and assert it in an engine unit test; manual steps cannot reliably hit the window.
- **Outcome:** **not reproducible in a test, left as is** (run 2b, `ac507841`). `TtsReadAloudEngineChapterCompletionTest` tries the window and both cases pass unchanged: the locator collector stops the engine on a chapter change, and a stopped engine emits no completion at all, so the engine never reports a chapter under the next one's name. What is left is the controller labelling the event with `lastLocator?.href` at the moment its *collector* runs (`AndroidTtsController.kt:153-154`), which a host test cannot reach — the controller needs fifteen Android dependencies, which is why run 2b tests `TtsPlaybackAttempts` instead. `finishedSentences` already labels with `engineChapterHref`, the source the finding asks for. No product code changed for this finding.

### TTS-F17 — Kokoro speaker ids are not clamped to the voices that exist

- **Where:** `SherpaOnnxSynthesizer.kt:244-247` — `parseSpeakerId` applies `coerceAtLeast(0)` only, while `SupertonicOnnxSynthesizer.kt:268-271` applies `coerceIn(SUPERTONIC_VOICES.indices)`. Kokoro exposes 11 voices (`:256-279`).
- **Trigger:** A persisted `ttsVoiceId` of `kokoro:<n>` with `n > 10` — written by a build with a longer voice list, or a corrupted preference — then press play.
- **Expected:** The id is clamped, or the voice falls back.
- **Actual:** The out-of-range speaker id is handed to the native engine. What sherpa-onnx does with it is unverified; a native abort would take the process down.
- **Evidence:** CODE-ONLY.
- **Confidence:** low — I could not check the native behaviour and found no path that writes such an id today.
- **Severity for a user:** unknown, potentially crash.
- **How a test could catch it:** a pure unit test over `parseSpeakerId` equivalent to `SupertonicOnnxSynthesizerTest`, asserting the clamp; the helper has to become `internal` first.

### TTS-F18 — Preparation terminal states are never cleared, so a stale failure banner can reappear

- **Where:** `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/tts/TtsVoicePreparationStateHolder.kt:49-84` — `Complete` and `Failed` are terminal and nothing moves the holder back to `Idle` (`markIdle` runs only on cancellation, `:63-68`). It is a `@Single`, so the state outlives the reader. `ReaderViewModel.observeTtsVoicePreparationState:1702-1709` maps `Failed` into `failedTtsVoicePackage`, which `derivePackState` renders as `PackUiState.Failed` (`VoicePackState.kt:59`).
- **Trigger:** Let a pack download fail, leave the reader, then open any book and open Voices again in the same process.
- **Expected:** A fresh sheet shows the pack's real state (not downloaded), not a failure the user has already seen.
- **Actual:** The new collector receives the retained `Failed` state and shows the error banner for a download this reader never started.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium.
- **Severity for a user:** cosmetic.
- **How a test could catch it:** extend `TtsVoicePreparationStateHolderTest` with "a terminal state is consumed once" semantics; manual: fail a download, reopen the reader, open Voices.

### TTS-F19 — A cancelled pack download produces no outcome, and queued post-download work is dropped silently

- **Where:** `AndroidTtsController.awaitVoicePreparation:457-490` throws `CancellationException` when the holder goes `Idle` (`:483-485`); the notification's Cancel action calls `markIdle` (`TtsVoicePreparationForegroundService.kt:51, 89-90`). `ReaderViewModel.prepareTtsVoice`'s job is then cancelled, so its `finally` clears the progress UI (`ReaderViewModel.kt:1376-1387`) but `onPrepared` (`:1366`) never runs.
- **Trigger:** Tap a not-downloaded voice, which queues "select it once prepared" (`:1215-1219`), then press Cancel on the download notification.
- **Expected:** The cancellation is an observable outcome and the pending voice selection is visibly abandoned.
- **Actual:** No analytics event for a user-cancelled download, and `pendingTtsVoiceId` is cleared only by `cancelTtsVoicePreparation` (`:1263-1268`), not by the notification path — so the sheet can keep showing a pending selection that will never happen.
- **Evidence:** CODE-ONLY.
- **Confidence:** medium.
- **Severity for a user:** analytics only, with a cosmetic leftover.
- **How a test could catch it:** ViewModel unit test that cancels via the holder going `Idle` and asserts `pendingTtsVoiceId` is cleared; manual: the trigger above, then inspect the card.

### TTS-F20 — Deleting a pack is not mutually excluded from the service that is downloading it

- **Where:** `ReaderViewModel.deleteNeuralVoicePackage:1393-1397` cancels only its own `ttsPreparationJob`; the download runs in `TtsVoicePreparationForegroundService`'s own `serviceScope` (`TtsVoicePreparationForegroundService.kt:31, 78-98`) and is cancelled only by `ACTION_CANCEL`. `TtsModelManager.deleteModel:556-582` then deletes the tree the service is writing into, and `SherpaOnnxSynthesizer.deleteNeuralVoicePackage:79-88` holds only `loadMutex`.
- **Trigger:** Start a pack download and delete the same pack before it finishes.
- **Expected:** One of the two waits for the other, or deletion cancels the download first.
- **Actual:** Files are deleted under an active install; the download can recreate directories after the delete, leaving a half-populated version directory and possibly an `.active` marker pointing at a removed version.
- **Evidence:** CODE-ONLY.
- **Confidence:** low — `derivePackState` (`VoicePackState.kt:49-58`) shows `Downloading` instead of the Delete affordance, so the sequence is probably unreachable through the UI; I could not confirm every entry point respects that.
- **Severity for a user:** stuck state.
- **How a test could catch it:** `TtsModelManagerTest` running a stubbed install concurrently with `deleteModel`, asserting the end state is consistently "not installed".

### TTS-F21 — The word path rewrites a shared cache file in place

- **Where:** `TtsWordPlayback.kt:125-128` calls `trimWavSilence(file)` on the file the cache returned (deliberately, per the comment at `:125-126`); `WavSilenceTrimmer.kt:67-77` writes `<name>.trim` and renames it over the cache entry.
- **Trigger:** A one-word sentence that read-aloud has cached, then tapping that same word with the same voice at rate ≤ 1.0 and pitch 1.0 — both paths then compute the same cache key (`TtsAudioGenerator.kt:75-82`; word pitch fixed at 1 f, `TtsWordPlayback.kt:135`; word rate capped at 1 f, `AndroidTtsController.kt:362, 883`).
- **Expected:** Trimming for the word path does not mutate an entry another player may be reading.
- **Actual:** The cache entry is replaced while the engine's ExoPlayer may hold it open, and a failed rename leaves a `<name>.trim` file in the cache directory that only `trim()` will eventually evict.
- **Evidence:** CODE-ONLY.
- **Confidence:** low — the key collision needs a single-word sentence, and POSIX rename keeps an open descriptor valid.
- **Severity for a user:** cosmetic.
- **How a test could catch it:** a `WavSilenceTrimmer` unit test (there is none today) covering a failed rename and a non-PCM file, plus a cache test asserting word and sentence audio do not share a key.

---

## 4. Status of the three logged bugs

### QA-BUG-0049 — logout leaves playback running: **PRESENT**

Nothing on either logout path touches playback. `ServerManagementViewModel.signOutEverything`
(`feature/settings/ui/src/commonMain/kotlin/com/retro99/settings/ui/servers/ServerManagementViewModel.kt:156-172`)
and `onLogoutClick` (`:193`) call `LogoutUseCase`
(`feature/auth/domain/src/commonMain/kotlin/com/retro99/auth/domain/usecase/LogoutUseCase.kt:20-45`),
which clears credentials and the database only.
`grep -rn --include='*.kt' "TtsReadAloudEngine\|ForegroundServiceController"` returns only
reader-internal files, so no logout, data-clear or `DataClearable` path can stop the engine.
For TTS the mechanism is explicit: the engine is `@Single` (`TtsReadAloudEngine.kt:51-58`),
and the only teardown that could stop it is the reader-scoped controller, whose `close()`
(`AndroidTtsController.kt:634-640`) closes the preview player, the word player and
`controllerScope` but **never calls `engine.stop()`** — deliberate, so playback survives
leaving the reader. Logout therefore leaves TTS narration and its media notification running.
Severity for a user: stuck state — audio continues after the session is over.

### QA-BUG-0095 — start failures lack outcomes: **FIXED**

Fixed for user-initiated starts. `requestPlayback` (`AndroidTtsController.kt:689-713`)
emits `Attempted` and exactly one of `Succeeded`/`Failed`/`Cancelled`; permission denial is
a cancellation (`:698-700`, `:671-673`); the 30 s deadline is armed before synthesis (`:674`)
and before `engine.resume()` on the resume path (`:574`); `stop()` and
`disableSentencePlayback()` cancel the pending request (`:591-594`, `:506-512`); and
`TtsPlaybackOperationLifecycle` owns both the job and the deadline.

Fixed in run 2a (`fc9e4804`), both outside a user request:
1. A synthesis failure after a successful start emitted no outcome at all — `_playbackFailures`
   was produced only by `onPlayerError`, while the synthesis failure paths emitted nothing
   (TTS-F02). They now emit one `PlaybackFailure(SYNTHESIS_FAILED)` with the current
   correlation id, which the controller turns into one `Failed` through its existing
   collector (`AndroidTtsController.kt:240-263`).
2. Those same paths threw into bare `launch` blocks with no handler anywhere in the
   repository, so the likely outcome was a crash rather than a missing event (TTS-F01).
   `launchEngineStart` now catches everything but `CancellationException`.

Fixed in run 2b (`6e919edb`):
3. Restarts for a voice, speed or pitch change bypassed the attempt machinery entirely, so
   they had neither outcomes nor a start deadline (TTS-F07). The restart now goes through
   the same machinery as a user start, reporting `settings_change`: one `Attempted`, exactly
   one terminal outcome, the pending flag set while it runs, the 30 s deadline armed before
   synthesis, and the engine labelled with that attempt's correlation id. A second change
   supersedes the restart in flight as `Cancelled(operation_cancelled)`.

QA-BUG-0095 has no open case left. The bookkeeping now lives in
`TtsPlaybackAttempts` (androidMain, `navigator/`), covered by
`TtsPlaybackAttemptsTest`.

### QA-BUG-0100 — one successful start reported twice: **PRESENT** (cause narrowed)

Not fixed, but the candidate causes can now be separated.

Ruled out — a double emission inside the controller. Both callers of
`finishPlaybackSucceeded`, the `engine.isPlaying` collector (`AndroidTtsController.kt:234-238`)
and `awaitPlaybackStart` (`:735-743`), are guarded by the `activePlaybackAttempt != attempt`
identity check (`:751`), and `clearActivePlaybackAttempt` (`:809-813`) nulls the attempt
before the event is emitted, so the second caller returns early.

Ruled out as the sole cause — a missing collector guard in `initTts`.
`isObservingTtsPlaybackOperations` (`ReaderViewModel.kt:1140-1142`) was introduced by
`198629e6` (the QA-BUG-0095 fix), an ancestor of `da244a00`, the commit the duplicate was
observed on: the guard was already in place when the bug was recorded.

Remaining cause, and the one consistent with two identical events sharing one correlation ID:
more than one live subscriber to `playbackOperations`, which the shared reader scope permits —
`getOrCreateScope<ReaderScope>(bookUuid)` (`:236-250`) hands the same `AndroidTtsController`
to every ViewModel for that book, while the de-duplication guard is per ViewModel instance.
See TTS-F10 for the trigger, the test and the secondary hazard of `addCloseable` closing a
shared controller.

---

## 3b. Findings found on the device

Three findings came out of step 3 rather than the source read. They keep the same shape.

### TTS-F22 — Every Kokoro synthesis kills the app with a JNI fatal error

- **Where:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/tts/SherpaOnnxSynthesizer.kt:119-128` — the progress/cancel callback passed to `OfflineTts.generateWithCallback`. The abort happens inside `com.k2fsa.sherpa.onnx.OfflineTts.generateWithCallbackImpl` (`Tts.kt:176`) when it invokes that callback. The identical pattern is at `SupertonicOnnxSynthesizer.kt:130-139` (`generateWithConfigAndCallback`).
- **Trigger:** Download the Kokoro pack, then cause any Kokoro synthesis. Both of these do it, on the first attempt, every time: (a) open the reader's Voices sheet and tap the preview ▶ on any Kokoro voice; (b) select a Kokoro voice and press play for read-aloud.
- **Expected:** Audio is synthesised; the callback reports progress and can abort.
- **Actual:** After a few seconds of native generation the process dies with
  `JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception java.lang.NoSuchMethodError: no non-static method "Lcom/retro99/reader/ui/tts/SherpaOnnxSynthesizer$synthesize$audio$1$1$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"`.
  The native code looks up a specialised `invoke([F)Ljava/lang/Integer;` on the callback object, but the lambda is compiled to a desugared `$$ExternalSyntheticLambda0` that only carries the erased `invoke(Object)Object`, so the lookup fails and ART aborts the process. The engine itself loads fine (`Kokoro loaded: sampleRate=24000 speakers=11`) because `loadEngine`'s warm-up uses `generate(...)` without a callback (`SherpaOnnxSynthesizer.kt:227`).
- **Evidence:** REPRODUCED twice — `G1-kokoro-jni-crash.txt`. Run 1 (preview): `Kokoro synthesize start: sid=0 speed=1.2 chars=253` at 16:30:02.133 → fatal at 16:30:11.507, pid 9194 gone. Run 2 (read-aloud): start at 16:39:44.857 `chars=126` → fatal at 16:39:49.326, pid 30706 gone.
- **Confidence:** high for Kokoro — reproduced twice from two independent entry points, with a stack trace naming the exact call site. Medium for Supertonic: the call shape is identical but its terms were deliberately not accepted, so it was never executed.
- **Severity for a user:** crash. Kokoro is entirely unusable, and the pack is a 149 MB download before the user finds out. Worse, the voice selection persists: after the crash the reader reopens with that voice still selected, so the next press of play crashes again.
- **How a test could catch it:** no host unit test can catch it (the failure is in the native/JNI boundary, and `OfflineTts` is not available on the JVM). It needs either an instrumented (`androidTest`) case on a device with the pack installed that calls `SherpaOnnxSynthesizer.synthesize` once and asserts the process survives, or the existing `tools/tts-bench` module run as a release gate. Manual: download Kokoro, preview any Kokoro voice, observe the app die.
- **Fixed:** `098a8592` — both synthesizers now take the callback from a shared `neuralGenerationCallback` factory that returns an explicit `object : (FloatArray) -> Int`, whose class declares `invoke([F)Ljava/lang/Integer;`. A host test *can* catch it after all, by reflection on the factory's return value: `NeuralGenerationCallbackTest`, committed failing in `2cc762a4`. R8 keep rule for the method name in `b2887d29`. Verified on the device (Kokoro preview, read-aloud, stop mid-synthesis, pause, resume, page swipe): `docs/manual-qa-evidence/2026-10-09/tts-f22-fix/`.

### TTS-F23 — TTS availability is decided once, from the chapter the book opens on

- **Where:** `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReaderViewModel.kt:1083-1086` — `initTts` awaits the first locator and then `if (!hasContent) return@launch`, so `activeNarrationController` (`:1099`), `isTtsReadAloud` (`:1102`), `selectVoice` (`:1115`) and every TTS collector are skipped for the rest of the reader session. `hasReadableContent()` is `AndroidBookController.kt:711-719`, which evaluates JavaScript against **only the currently loaded chapter**. The play control then calls `activeNarrationController?.togglePlayback()` (`:2892`) on a null reference.
- **Trigger:** Open a book whose first document has no readable text — a cover image, which is the normal first page of a Gutenberg EPUB. Page forward into real text and press play.
- **Expected:** Once the reader is on a chapter with text, read-aloud works; or availability is re-evaluated per chapter.
- **Actual:** `feature_exposed {feature_name=tts, is_available=false}` is logged once at open and TTS stays off for the session. The Listening sheet still opens and still offers a play button, but pressing it does nothing at all — no audio, no attempt event, no failure event, no retry bar. Closing and reopening the reader (which now restores onto a text page) makes it work.
- **Evidence:** REPRODUCED — `E1-tts-logcat-excerpt.txt` 15:49:58.923 `is_available=false` on the cover; `B1-listening-sheet-sentence-1-of-0.png` shows the sheet with a live play button in that state; pressing it produced no `tts_playback_operation` line at all. After reopening, 15:57:50.553 `is_available=false` → 15:57:51.137 `is_available=true`, and the next press produced `attempted` → `succeeded` (15:59:05/06).
- **Confidence:** high — observed, and the mechanism is a plain early return.
- **Severity for a user:** silent failure — read-aloud appears present but is inert for the whole session.
- **How a test could catch it:** ViewModel unit test with a fake `BookController` whose `hasReadableContent()` returns false on the first locator and true after a chapter change: assert that TTS becomes available (or that the play control reports a failure instead of doing nothing). Manual: the trigger above.

### TTS-F24 — The sheet shows "Sentence 1 of 0" before the chapter's sentences load

- **Where:** `ReaderOverlay.kt:734-735` passes `sentenceNumber = (viewState.ttsSentenceIndex + 1).coerceAtLeast(1)` and `sentenceCount = viewState.ttsSentenceCount`; the count comes from `engine.sentenceCount` which is 0 until `setSentences` runs (`TtsReadAloudEngine.kt:97`, `:234`). The index is also never reset, because `observeTtsSentenceProgress` only updates state when the sentence is non-null (`ReaderViewModel.kt:1626-1628`).
- **Trigger:** Open the Listening sheet before pressing play, or reopen the reader on a book that has a TTS session.
- **Expected:** No position, or "Sentence 1 of 97" once known.
- **Actual:** "Sentence 1 of 0" — a count of zero with a position of one. The same stale-index behaviour hides the engine stop in TTS-F06: after `engine.stop()` the sheet still showed "Sentence 89 of 97" even though the position had been discarded.
- **Evidence:** REPRODUCED — `B1-listening-sheet-sentence-1-of-0.png`, and again at 16:36 and 16:47 after reopening.
- **Confidence:** high.
- **Severity for a user:** cosmetic — but it is what masked TTS-F06 from the UI.
- **How a test could catch it:** a Compose/unit test over the sheet's `AudioSheetUi` mapping asserting no position is rendered while `sentenceCount == 0`.

## 3c. Finding found while testing

### TTS-F25 — A player "ended" callback arriving after a stop starts narration nobody asked for

- **Where:** `TtsReadAloudEngine.kt:604-622` — `onSentenceCompleted()` computes `next = currentIndex + 1` with no guard on `currentIndex`. After `stopInternal()` the index is `-1`, so `next` is `0` and `launchEngineStart { startSentence(0) }` runs.
- **Trigger:** The last item of a playlist ends at about the same moment the engine is stopped — a swipe into the next chapter, Stop listening, or a failure path — so the player's `ENDED` callback, already posted to the looper, is delivered after `stopInternal`. The sentence list loaded by then may belong to the *next* chapter.
- **Expected:** A sentence completing with no current sentence starts nothing.
- **Actual:** Narration starts at sentence 0 of whatever chapter is loaded, with no user request, no attempt and no outcome.
- **Evidence:** Shown in a host test, with the caveat that the fake player delivers `onEnded` unconditionally: `TtsReadAloudEngineChapterCompletionTest."a chapter abandoned by a locator move does not complete at all"` asserts `currentSentenceIndex == 0` after a stop. Whether a real ExoPlayer can still deliver `ENDED` after `stop()` + `clearItems()` is not confirmed — callbacks are posted to the application looper, which makes it plausible, but it was not seen on a device.
- **Confidence:** high that the guard is missing; medium that the callback is deliverable after the stop.
- **Severity for a user:** wrong audio (a chapter read from its start unasked).
- **How a test could catch it:** the test above, once the fake only reports `ENDED` while it has items; the product guard is `if (currentIndex < 0) return` in `onSentenceCompleted`.
- **Status:** not fixed — found by run 2b's TTS-F16 test, outside that run's scope. Candidate for run 5.

### TTS-F26 — A slow voice's gap between sentences reads as "not playing", and narration can die in it

- **Where:** four places, all at commit `433e03e8`. (0) `ReaderAudioSheet.kt:505` and `ReaderOverlay.kt:1093`/`:1224` disable the play/pause button with `enabled = !isLoading`, and `TtsReadAloudEngine.kt:352` reports `isLoading` for every wait between sentences, so the only pause control is dead for the whole gap — found on the device, see the Evidence line. (1) `TtsPlaybackAttempts.kt:136-154` (`onSettingsChanged`) and `AndroidTtsController.kt:508-521` (`togglePlayback`) ask `engine.isPlaying.value`, which is the player's "audio is audible", not "a session is running"; the same question is asked by `AndroidTtsController.kt:160` (`ReadAloudWordInterruption.isPlayingNow`) and `:236-240` (`previewVoice`). (2) `TtsReadAloudEngine.kt:604-605` — `onSentenceCompleted()` returns when `player.hasNextItem()` is true, trusting the player to auto-advance to an item that was appended after it had already finished. (3) `TtsReadAloudEngine.kt:271-274` — `resume()` only calls `player.play()`, which does nothing on a player that has run out of audio.
- **Trigger:** A voice slow enough that synthesis does not keep up with playback — Kokoro takes 2–9 s per sentence on the Samsung. The player then reaches `state=ENDED` for one to two seconds between sentences while the next clip is still being made. In that window: change the speed; or press pause; or let the next clip be appended in the few milliseconds before the end-of-queue callback is delivered.
- **Expected:** A session waiting for its next sentence counts as playing for every decision. A speed change in the gap is the same tracked `settings_change` restart it is while audio is audible. A pause in the gap pauses, and the sentence being synthesised does not start playing when it arrives. Narration never ends silently mid-chapter: it continues, or one failure outcome is reported.
- **Actual:** Four failures, with four different causes. (0) The pause cannot even be pressed: through the whole gap the sheet's and the overlay's play/pause button is a disabled spinner, so two taps placed 84 ms and 97 ms inside a gap on the phone produced no event of any kind and narration carried on when the clip arrived. (1) The speed change took the *paused* path: no `attempted`, no terminal outcome, no `settings_change`, and the clips already queued kept the old speed while the sheet showed the new one. (2) Twenty-three seconds later the player went `ENDED` with a clip appended behind it, `hasNextItem()` was true, so the engine returned and left it to a player that had already finished: narration stopped at sentence 145 of 151, mid-chapter, with no event of any kind. (3) The next play press called `resume()`, whose `play()` on the ended player did nothing, so the only thing that ever fired was the 30 s start deadline (`failed`, `start_timeout`); a second press recovered it.
- **Evidence:** REPRODUCED — on the device in run 2b's re-run, `docs/manual-qa-evidence/2026-10-09/tts-run2b-device/NOTES.md` §"Step 4" 4b and "Differences from Expected" item 3, with `tts-run2b-device-logcat.txt` 20:35:50–20:39:50: `ENDED` 20:35:50.790, `tts_rate_changed{rate=1.3}` 20:35:50.851 with no operation pair, `speed=1.4` synthesis lines continuing to 20:36:13, the appended clip at 20:36:13.611 five milliseconds before `state=ENDED` at 20:36:13.616 and nothing after it, `attempted` 20:38:12.800 then `failed … start_timeout` 20:38:42.826. Then in host tests: all five cases of `TtsSynthesisGapTest` failed before the fix, committed failing in `32a23af4`. Cause (0) was found on the phone *after* that fix, in run 2c's own device pass: `docs/manual-qa-evidence/2026-10-09/tts-run2c/NOTES.md` §"Step 2", with `B1-sheet-inside-a-gap.png` showing the spinner in place of the button; its failing test is `805fef76`.
- **Confidence:** high — reproduced on the device and in five host tests, each failing on its own symptom.
- **Severity for a user:** narration stops for good mid-chapter with nothing shown, and the obvious recovery (press play) waits 30 s and then reports a failure; a speed change in the gap is also silently not applied to the audio already queued.
- **How a test could catch it:** a host test that sits inside the gap — a synthesis the test completes by hand, so sentence N has ended in the player while sentence N+1 is still being made — and then asks the session's own question, changes the speed, presses pause, or appends a clip just before the end-of-queue callback. That is `TtsSynthesisGapTest` (five cases).
- **Fixed:** `b755094d` (run 2c) — `TtsReadAloudEngine.isSessionRunning` is the single answer to "is a session running", true from an accepted start until pause, stop, a failure or the end of the chapter, the gaps included; `isPlaying` keeps its meaning. `onSentenceCompleted` starts the next sentence itself from the clip already synthesised instead of trusting `hasNextItem()`, and `resume()` restarts the sentence when the player has reached the end of its queue. Tests committed failing in `32a23af4`. Cause (0) is fixed in `99ad689e`: `isLoading` now means what the UI uses it for, a start with nothing audible yet, so the button stays live through the gaps; test committed failing in `805fef76`.
- **Device check 2026-10-09 (run 2c):** passed — `docs/manual-qa-evidence/2026-10-09/tts-run2c/`. A speed change landed 100 ms inside a Kokoro gap produced one `attempted` and one `succeeded` with `tts_action=settings_change` and `speed=1.1` on every synthesis line after it; a pause landed 97 ms inside a gap left the arriving clip queued, prepared and silent, and the next press resumed it in 16 ms; after a mid-gap speed change the chapter was read to its end and the next chapter started by itself. No `failed` outcome anywhere in the run. The two mid-gap pauses that failed first are cause (0) above.

### TTS-F27 — `tts_playback_operation` for a settings-change restart carries no `tts_action`

- **Where:** `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsParameterSanitizer.kt:239-241` — `SAFE_TTS_ACTIONS` is a fail-closed allow-list and was never extended when `TtsPlaybackAction.SETTINGS_CHANGE` was added in run 2b, so the key is dropped at the provider boundary. The event itself does carry it (`AnalyticsEvent.kt:642-659`), and so does the ViewModel (`ReaderViewModel.kt:1145`).
- **Trigger:** Change the voice, the speed or the pitch while narration plays, and read the `tts_playback_operation` events.
- **Expected:** `tts_action=settings_change` on the `attempted` event and on the terminal one, as `controls` and `resume` starts both carry theirs.
- **Actual:** No `tts_action` parameter at all on either event. The action survives only in the paired diagnostic breadcrumb, as `entry_point=settings_change`, so the analytics event cannot tell a settings-change restart from an unlabelled one.
- **Evidence:** REPRODUCED — `tts-run2b-device-logcat.txt` 20:31:24.530 and 20:31:24.910 (System voice) and 20:40:06.991 and 20:40:17.935 (Kokoro), against `tts_action=resume` at 20:35:16.959; recorded in that run's "Differences from Expected" item 1. Host test `AnalyticsParameterSanitizerTest.ttsPlaybackSettingsChangeKeepsItsAction` committed failing in `12dd5dff`.
- **Confidence:** high.
- **Severity for a user:** none directly — it is a hole in the measurement of the restart path that run 2b added.
- **How a test could catch it:** the sanitizer test above, one case per action value the product can emit.
- **Fixed:** `e4597018` (run 2c) — `settings_change` added to `SAFE_TTS_ACTIONS`, which now lists every `TtsPlaybackAction.analyticsValue`.
- **Device check 2026-10-09 (run 2c):** passed — every settings-change event of the run carries the action, e.g. `{tts_action=settings_change, tts_outcome=attempted}` at 21:19:36.864 and `{tts_action=settings_change, tts_outcome=succeeded, duration_ms=14484}` at 21:19:51.348 (`docs/manual-qa-evidence/2026-10-09/tts-run2c/`).

---

## 5. Emulator and device checks

### 5.1 Device actually used

The prompt allowed only the emulator AVD `Medium_Phone_API_37.0` and forbade any adb
command to the Samsung. During the run the user twice redirected the device: first to a
Xiaomi reached over wireless adb, then explicitly to "samsung for testing". The on-device
work below was therefore done on the Samsung **RFCWC0SSVDM** (SM-S921B, Android 16 /
API 36), with the user's explicit instruction overriding the prompt's restriction. See
§5.4 for exactly what each device received.

Build: `./gradlew :androidApp:assembleDebug` — BUILD SUCCESSFUL in 44 s
(`androidApp-debug.apk`, 178 932 426 bytes). The worktree has no `local.properties`, so
`ANDROID_HOME` had to be supplied on the command line; nothing in the repository was changed.

### 5.2 Checks done, one line each

| # | Check | Result |
| --- | --- | --- |
| 1 | Emulator `Medium_Phone_API_37.0` booted, Google TTS present | PASS — booted as `emulator-5554`; no app work done on it after the device switch |
| 2 | Debug APK built | PASS |
| 3 | APK installed on the Samsung (`install -r`, data kept) | PASS |
| 4 | Book fetched with in-app Get books → Project Gutenberg → Popular | PASS — *Alice's Adventures in Wonderland*, EPUB 0.1 MB (`A1`, `A2`) |
| 5 | Reader opens the book | PASS |
| 6 | Press play on the image-only cover page | **FAIL — TTS-F23**: `is_available=false`, play does nothing, no event |
| 7 | Reopen the reader on a text page, press play | PASS — `attempted` → `succeeded` in 1589 ms, system voice, audible |
| 8 | Exactly one terminal event per start (QA-BUG-0100) | PASS — one `succeeded` per correlation ID; the duplicate did **not** reproduce |
| 9 | Pause | PASS — `playback_paused`, position kept |
| 10 | Resume | PASS — `resume` attempted → succeeded in 6 ms |
| 11 | Sentence advance and highlight tracking | PASS — highlight follows, pages turn on their own |
| 12 | Chapter end → next chapter | PASS — `tts_action=chapter` attempted → succeeded (16:21:51), one pair |
| 13 | Swipe pages during playback | PASS — playback continued, re-anchored, sentence counter followed the new chapter |
| 14 | Background the app while reading | PASS — media session `PLAYING`, synthesis continued, sentence actions present |
| 15 | Return to foreground | PASS — still playing, transport controls restored |
| 16 | Speed change while playing | **FAIL — TTS-F07**: `tts_rate_changed{rate=1.1}` + immediate re-synthesis, **no** attempt/terminal pair |
| 17 | Speed change while paused, then play | **FAIL — TTS-F06**: emitted `tts_action=controls` (fresh start, not `resume`) and restarted at sentence 87 after pausing at 89 (`D1`–`D3`) |
| 18 | Two quick taps on play/pause | INCONCLUSIVE — ended consistently playing with no spurious events; a true same-frame race could not be forced with adb taps |
| 19 | Voices sheet lists packs with sizes | PASS — Kokoro 149 MB, Supertonic 145 MB, manifest resolved fast (`F1`) |
| 20 | Kokoro pack download | PASS — 6 files / 148 969 454 bytes in 10 936 ms, `tts_model_prepared{is_success=true}` |
| 21 | Pack install layout on disk | PASS — `.active` = `20260928123911-4`, all 6 entries present, **no `.part` leftovers** |
| 22 | Kokoro engine load | PASS — `Kokoro loaded: sampleRate=24000 speakers=11` (confirms the 11-voice list matches `numSpeakers`, which bounds TTS-F17's risk) |
| 23 | Preview a Kokoro voice while narration plays | PARTIAL/**FAIL** — narration paused correctly and routing was right (`voice=kokoro:0 engine=KOKORO`), then **the app died**: TTS-F22 |
| 24 | Read-aloud with a Kokoro voice | **FAIL — TTS-F22**: process died again, same JNI error |
| 25 | Voice selection survives the crash | Observed — the reader reopens with the crashing Kokoro voice still selected |
| 26 | "Sentence 1 of 0" in the sheet | **FAIL — TTS-F24** |
| 27 | Supertonic terms | Not accepted, as instructed; Supertonic stayed code-only |

### 5.3 Checks I could not do, and why

- **Kokoro pause / swipe / switching between system and Kokoro** — blocked by TTS-F22: the process dies at the first Kokoro synthesis, so no Kokoro playback state ever exists to pause, swipe through, or switch away from.
- **Interrupted Kokoro download (network off/on)** — not run. The download completed in about 11 s on this connection, leaving no practical window; doing it properly needed deleting the pack and disabling Wi-Fi and mobile data on the user's personal phone, which I stopped short of. TTS-F05 therefore remains CODE-ONLY, with the supporting observation that a *successful* install leaves no partial files (check 21).
- **Leaving the reader while it reads (QA-BUG-0049's mechanism)** — not run on device; CODE-ONLY in §4.
- **Logout while playing** — not run; it would have required signing out of the user's real catalogue account.
- **Audio-focus loss (TTS-F09)** — not simulated; it needs a second app holding exclusive focus or an incoming call.
- **Word tap / dictionary speaker (TTS-F08, TTS-F13)** — not exercised; it needs a text selection gesture that blind adb taps kept turning into page turns.
- **An image-only chapter *mid*-book (TTS-F14)** — this Gutenberg edition "had all images removed", so only the cover qualified, and the cover produced TTS-F23 instead.
- **Two live ReaderViewModels (TTS-F10 / QA-BUG-0100)** — not forced; a rotation or re-entry sequence was not reached before the Kokoro crash took priority.
- **Sleep timer** — the control was seen in the sheet (Off / 15 min / 30 min / End of ch.) but no expiry was timed.

### 5.4 What each device received, and measurement artifacts

- **Samsung `RFCWC0SSVDM`** — all of §5.2: APK installed over the existing one (`-r`, data preserved), the Gutenberg book downloaded into the library, the Kokoro pack downloaded (149 MB, still installed), TTS rate left at 1.2×, and the reader's TTS voice left on **Kokoro "Heart", which crashes on play**. To clear that: reader → Listen/Audio → Change → pick a System voice, or Delete pack on the Kokoro card.
- **Xiaomi `192.168.1.248:5555`** — debug APK installed, app launched, four navigation taps (Browse, Books, Add, Get books). Nothing else, nothing destructive.
- **Emulator `emulator-5554`** — booted and checked for a TTS engine only. A second emulator, `emulator-5556` (Wear OS), belongs to another session and was never addressed.
- **Artifact worth knowing for the test plan:** the reader chrome auto-hides after a couple of seconds, so scripted taps on "Listen" frequently landed on the page and turned it instead. An early impression that the Listen button had "gone dead" was mostly this; in two later attempts the chrome was verified visible 1 s before the tap and the sheet still did not open, so a genuine issue there cannot be excluded — it is not written up as a finding because I could not separate it from the tap-timing artifact, and the log shows no `sleep_timer` exposure that would mark the sheet opening.
- **Run 2b update (2026-10-09), still not a finding but no longer a timing artifact:** the whole run 2b device pass was blocked by this. The control row was verified visible in the frame before the press, Listen was pressed within 300 ms by three different synthetic gestures including a 120 ms hold, the touch is visible in the app's own log, nothing opened and the page never turned — while the neighbouring **Search** button opens its sheet on the identical gesture at the same y. Reproduced in two books and after re-entering the reader. What is still untested is a human finger. Evidence: `docs/manual-qa-evidence/2026-10-09/tts-run2b/`. It belongs to run 5 (with TTS-F23/F24) rather than to run 2b.
- Analytics quoted above come from the in-app debug analytics logger; Firebase delivery is not claimed. The logcat excerpt was filtered to the app's pid and contains no book titles, account details or server addresses (checked).
