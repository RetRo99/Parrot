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
