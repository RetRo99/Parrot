# iOS read-aloud implementation plan

Written from the `tts/ios-spike` spike (branch `tts/ios-spike`, off `main` at `e8ed4319`).
Every number below is measured on the iPhone Air simulator (UDID
`45F57B43-B65F-4F73-AAED-034D5B3DA463`, Xcode 27, iOS 27), not asserted. The spike code
that produced them is committed on that branch and stays out of the product path.

Android reference: `docs/tts-investigation.md` §1 (code map) is the contract this plan
mirrors. Read it first.

---

## 1. What the spike proved

### 1.1 sherpa-onnx runs Kokoro on iOS, in this app

The one fact the whole plan rests on is true: **sherpa-onnx Kokoro synthesises real,
audible speech inside Parrot on the iOS simulator.**

- **Artifact.** `k2-fsa/sherpa-onnx` does not ship an iOS asset on the `v1.13.8` release
  itself. The matching iOS binaries live on the repo's rolling `xcframework` tag:
  `sherpa-onnx-v1.13.8-ios-static.xcframework.zip` (17.0 MB download). That is the same
  engine version as the vendored Android AAR (`sherpa-onnx-1.13.8.aar`), so both platforms
  run identical engine code.
- **A second framework is required.** The static XCFramework's binary still needs
  onnxruntime. The companion is `csukuangfj/onnxruntime-libs` `v1.28.2`,
  `onnxruntime-ios-static-xcframework-1.28.2.xcframework.zip` (~41 MB download). (The
  `-ios-shared.xcframework` (6.2 MB) is *not* self-contained: its Mach-O has an
  `@rpath/onnxruntime.framework` dependency and no onnxruntime ships in the zip, so the
  shared variant was rejected.)
- **Slices.** Both XCFrameworks ship `ios-arm64` (device) and `ios-arm64_x86_64-simulator`.
  The simulator fat binary was thinned to arm64-only (`lipo -thin arm64`) and repacked with
  `xcodebuild -create-xcframework`, because the target sets `ARCHS=arm64` and an x86_64
  simulator slice is dead weight. Result: **iosSimulatorArm64 yes, iosArm64 yes.**
- **How it enters the build.** Vendored XCFrameworks under `iosApp/Frameworks/`, added to
  the `iosApp` target's Frameworks phase (link) plus an Embed Frameworks phase
  (CodeSignOnCopy), and `FRAMEWORK_SEARCH_PATHS=$(SRCROOT)/Frameworks`. No CocoaPods
  dependency (the Podfile is Readium+Firebase only and `pod install` is slow), and **no
  Kotlin/Native cinterop** — see §1.3. Chosen over SPM because the KMP framework
  (`ComposeApp`, `isStatic=true`) is built by Gradle and embedded by a script phase; a
  vendored XCFramework linked in the Xcode project needs no change to that pipeline.
- **Size.** Download 17.0 MB (sherpa) + ~41 MB (onnxruntime). Built `.app` grew from
  237 MB to **275 MB = +38 MB** (simulator Debug, both static archives linked in). The
  Android AAR is 50 MB, so iOS is comparable, not "far more".

### 1.2 Synthesis is fast enough for the prefetch design

`SherpaOnnxSynthesizer.kt:212-224` config was mirrored exactly
(`OfflineTtsKokoroModelConfig(model, voices, tokens, dataDir)`, `numThreads=4`,
`provider="cpu"`, `maxNumSentences=2`). A ~200-char sentence, voice `af` (speaker 0),
speed 1.0:

| Metric | Value |
| --- | --- |
| Engine load (cold, incl. warm-up `generate("a")`) | **~1.5 s** (1481 ms) |
| Cold synthesis, 156k samples | **~4.5 s** (4474 ms) |
| Warm synthesis, 81k samples | **~2.6 s** (2551 ms) |
| Output | 24 000 Hz mono Int16 WAV, 312 486 bytes, 6.509 s |
| Peak / RMS | 0.3161 / 0.0599 (real speech — 14.5 % near-zero samples = inter-word pauses, not silence) |
| Memory footprint | 35 MB → 278 MB after load, → **487 MB peak during cold synthesis** |

Cold-vs-warm confirms Android's prefetch assumption on iOS: the first sentence pays
engine load (~1.5 s) and the first generate is slower than steady state. Audibility was
confirmed two ways: sample statistics (RMS 0.06, peak 0.32, not a flat line) and copying
the WAV to the host and playing it with `afplay` (intelligible English speech).

### 1.3 The seam: Swift implements a Kotlin interface

Chosen seam: **Swift implements a Kotlin-defined interface and registers it in a Kotlin
registry**, the pattern this repo already uses for the reader
(`EpubReaderBridgeRegistry.register(ReadiumEpubReaderBridge())` in
`iOSApp.swift:40`). The spike's `IosTtsSpikeBridge` (Kotlin, iosMain) is implemented by
`TtsSpikeBridgeImpl` (Swift) and proven by a call that starts in
`IosTtsSpike.run()` (Kotlin) and returns a real WAV path from sherpa-onnx's C API.

Why not cinterop onto the C API directly: the sherpa-onnx `c-api.h` is a large,
pointer-heavy C surface (config structs with `const char*` fields, an
audio-buffer-with-lifetime return type, a destroy function per object). Driving that from
Kotlin/Native means hand-writing `@OptIn(ExperimentalForeignApi)` memory management
(`memScoped`, `alloc`, `toKString`, manual `free`) for every call, and the KMP
`isStatic=true` framework would still need the XCFramework's symbols at link time — the
same Xcode wiring either way. The bridge keeps all C interop in Swift (where
`SherpaOnnxCreateOfflineTts` etc. are already imported as safe-ish Swift calls), gives
Kotlin a clean value-type contract, matches the existing repo pattern, and makes error
propagation (a `String?` + `onError` callback) and engine lifetime (a Swift-owned
`OpaquePointer`) trivial to reason about. The one-time cost is that the `TtsSynthesizer`
contract's `java.io.File` must become a path string at the seam — which the commonMain
move needs anyway (§2).

### 1.4 Pack download works unchanged from Kotlin on iOS

`lib/packs` (`PackManager`, `PackDownloader`, `PackTransfer`) is **already fully
multiplatform**: okio `FileSystem`/`HashingSource` for I/O + sha256, ktor for HTTP, with
`iosArm64`/`iosSimulatorArm64` targets and `ktor.client.darwin` on `iosMain`. The spike
called `PackDownloader.download` + `verify` from Kotlin `iosMain` against a real Kokoro
pack file (`kokoro-tokens.txt`, 1078 bytes):

```
download: 479ms verified=true bytes=1078
```

That exercises the exact resume/checksum/`okio` code path Android uses
(`Range` header, `.part` file, sha256 `HashingSource`). What does **not** move unchanged
is the Android-specific orchestrator `TtsModelStore.kt` (894 lines): it imports
`java.io.File`, `HttpURLConnection`, `MessageDigest`, `ZipInputStream`,
`ConcurrentHashMap` (§2). The plan's answer is to route its install through `lib/packs`
instead of porting it.

### 1.5 What the simulator could not prove — file playback

**AVQueuePlayer could not be verified on the simulator, because no AVPlayer can play
audio on it.** This is not an AVQueuePlayer problem: a plain single-file `AVPlayer`
was driven for 4 s and its playhead never left 0.00 s (`rate=1.0`, audio session
`playback` active, route `Speaker`), and the simulator has no `mediaserverd` /
audio HAL (only `systemsoundserver-simd` is running). All "does the queue advance /
is the gap audible / does Now Playing update" questions are **unanswerable on a
simulator** and are deferred to a device run (§4, run D). What *is* established from the
probe and the platform:

- `AVAudioSession` category `.playback`, mode `.default`, no options, activates cleanly.
- `AVPlayerItem.audioTimePitchAlgorithm` exists per item; rate alone pitch-shifts unless
  set. Pitch-without-rate is **not** an `AVPlayer` feature — it needs
  `AVAudioUnitTimePitch` (independent `pitch` cents + `rate`), i.e. an
  `AVAudioEngine` player, not `AVQueuePlayer`, **if** pitch must not change tempo. This
  is a real design fork the plan carries (§3, risk R4).
- `Info.plist` already has `UIBackgroundModes = [audio, processing]` — background-audio
  entitlement is already present.

---

## 2. commonMain move table

Line counts measured at `e8ed4319`. Verdicts: **move** (as-is), **okio** (moves once
`java.io.File`/JVM I/O becomes okio `Path`/`FileSystem`), **expect/actual**,
**Android-only** (stays), **iOS twin** (new iOS file mirroring the Android one).

| File (under `…/reader/ui/`) | Lines | Verdict | Platform imports forcing it |
| --- | ---: | --- | --- |
| `tts/TtsSynthesizer.kt` | 59 | okio | `java.io.File` (the `synthesize` output arg + result) |
| `tts/TtsReadAloudEngine.kt` | 864 | okio | `java.io.File` ×5 (playlist items, cache paths) — the prize |
| `tts/TtsAudioGenerator.kt` | 160 | okio | `java.io.File` |
| `tts/TtsAudioCacheStore.kt` | 124 | okio | `java.io.File`, `java.security.MessageDigest` (→ okio sha256) |
| `tts/TtsAudioCache.kt` | 47 | expect/actual | `android.content.Context` (cacheDir) + `java.io.File` |
| `tts/WavSilenceTrimmer.kt` | 132 | okio | `java.io.File`, `java.nio.ByteBuffer` (→ okio `Buffer`) |
| `tts/TtsSynthesizerRouter.kt` | 124 | okio | `java.io.File` |
| `tts/NeuralGenerationCallback.kt` | 16 | move | none |
| `navigator/TtsPlaybackAttempts.kt` | 305 | okio | `java.util.UUID` (→ `kotlin.uuid.Uuid` or expect/actual) |
| `navigator/AndroidTtsController.kt` | 721 | iOS twin | Android `Context`, service start, notification permission — the per-book orchestration shape is shared but the wiring is platform |
| `tts/SherpaOnnxSynthesizer.kt` | 284 | iOS twin | sherpa-onnx **Android** bindings (`com.k2fsa.sherpa.onnx.*`) — iOS twin drives the seam from §1.3 |
| `tts/SupertonicOnnxSynthesizer.kt` | 307 | iOS twin | same sherpa Android bindings; terms-gated |
| `tts/AndroidSystemTtsSynthesizer.kt` | 274 | iOS twin | Android `TextToSpeech` — iOS twin wraps `AVSpeechSynthesizer` (system voice, `TTS_SYSTEM_VOICE_KEY` parity) |
| `tts/TtsModelManager.kt` | 176 | iOS twin | Android `Context`, `HttpURLConnection` redirect-following, host-trust check |
| `tts/TtsModelStore.kt` | 894 | iOS twin (via lib/packs) | `java.io.File`, `HttpURLConnection`, `MessageDigest`, `ZipInputStream`, `ConcurrentHashMap` |
| `tts/TtsModelPaths.kt` | 58 | okio | `java.nio.file.Files`, `Path`, `FileVisitResult` (→ okio) |
| `tts/TtsPreviewPlayer.kt` | 146 | iOS twin | ExoPlayer, `android.net.Uri`, `Context` |
| `tts/TtsWordPlayback.kt` | 171 | iOS twin | ExoPlayer, `Uri`, `Context`, `java.io.File` |
| `tts/AndroidSupertonicTermsStore.kt` | 33 | iOS twin exists | `android.content.Context` — `IosSupertonicTermsStore.kt` already present |
| `tts/TtsVoicePreparationForegroundService.kt` | 222 | Android-only | Android `Service`, notification, 13 android/androidx imports |
| `tts/ExoTtsEnginePlayer.kt` | 195 | iOS twin | ExoPlayer (implements `TtsEnginePlayer`) |
| `tts/TtsEnginePlayer.kt` | 96 | okio | `java.io.File` in `TtsEnginePlayerItem` (the contract iOS implements) |
| `playback/MediaPlaybackService.kt` | 1271 | Android-only | 27 android/androidx imports, `MediaLibraryService`, media session |
| `playback/TtsSessionPlayer.kt` | 124 | Android-only | `ForwardingPlayer`, media3 |
| `playback/MediaPlaybackController.kt` | 664 | Android-only | media3 controller, `Context` |
| `playback/MediaSessionManager.kt` | 128 | Android-only | media3 session |
| `playback/NotificationPermissionHandler.kt` | 293 | Android-only | `POST_NOTIFICATIONS`, `Activity`, 8 android imports |
| `playback/ForegroundServiceController.kt` | 64 | Android-only | `Context`, foreground-service start |
| `playback/MediaMetadataArtwork.kt` | 22 | Android-only | media3 `MediaMetadata` |
| `playback/AndroidNowPlayingProvider.kt` | 58 | iOS twin exists | none — iOS has `IosNowPlayingProvider.kt` |
| `playback/ClipScheduler.kt` | 112 | Android-only | `android.os` handler/looper timing |
| `playback/ReadAloudPlayback.kt` | 22 | Android-only | media3 |
| `playback/auto/HeadlessPlaybackSession.kt` | 268 | Android-only | Android Auto media session |
| `playback/auto/HeadlessSessionFactory.kt` | 239 | Android-only | Android Auto |
| `playback/auto/AutoMediaBrowser.kt` | 222 | Android-only | Android Auto `MediaBrowser` |

**Verdict counts** (TTS-relevant files): move 1 · okio 9 · expect/actual 1 · iOS twin 11 ·
Android-only 12.

The engine's host-test safety net is what makes moving `TtsReadAloudEngine` reviewable.
Of the **104** `@Test`s in `androidHostTest`, the ones exercising the components being
moved are: `TtsReadAloudEngineTest` (10), `TtsReadAloudEngineSynthesisFailureTest` (9),
`TtsReadAloudEngineChapterCompletionTest` (2), `TtsAudioCacheStoreTest` (11),
`TtsAudioGeneratorTest` (5), `WavSilenceTrimmerTest` (5), `TtsSynthesizerRouterTest` (4),
`TtsPlaybackAttemptsTest` (11), `NeuralGenerationCallbackTest` (3) = **60 tests**. Their
only JVM ties are `java.io`/`java.nio` (temp dirs, byte buffers) and
`kotlinx.coroutines.test` (multiplatform) — all replaceable with okio + `kotlin.test` in
`commonTest`, so **all 60 would survive a move to commonTest**. The remaining 44
(`TtsModelStoreTest`, `SherpaOnnxSynthesizerTest`, `LogoutStopsReadAloudTest`, etc.) test
Android-specific classes and stay.

---

## 3. Phases

Order rationale: **synthesis before playback before download before polish.** The spike
shows synthesis and download are the two proven pieces; file playback is the piece that
is *blocked on hardware*, so it must land early enough to device-test but is sequenced
after the engine exists to drive it. The commonMain move comes last, once both platforms
have a working `TtsSynthesizer`, because moving the engine before iOS has a player would
leave Android hosting code iOS can't yet satisfy.

- **Phase 0 — Foundations (this spike, done).** Vendored sherpa-onnx 1.13.8 +
  onnxruntime 1.28.2 XCFrameworks, seam proven, sizes and timings measured.
- **Phase 1 — iOS synthesis engine.** `IosTtsSynthesizer` (Swift) implementing the
  Kotlin `TtsSynthesizer` contract via the registry seam; Kokoro only; `TtsSynthesizer`
  `java.io.File` → path string at the seam. System voice (`AVSpeechSynthesizer`) as a
  second iOS provider for `TTS_SYSTEM_VOICE_KEY` parity.
- **Phase 2 — iOS file playback.** `AvTtsEnginePlayer` implementing `TtsEnginePlayer`
  over the synthesised WAV queue; `AVAudioSession` `.playback`; Now Playing through the
  existing `IosNowPlayingProvider`. **Device-verified** (§4 run D).
- **Phase 3 — Model delivery.** iOS `TtsModelManager`/store routed through multiplatform
  `lib/packs` (proven in §1.4), manifest fetch, side-by-side versions, `.active` marker;
  Supertonic terms gate reusing the existing `IosSupertonicTermsStore.kt` and the
  `docs/THIRD_PARTY_TTS.md` licence text.
- **Phase 4 — commonMain move.** Move the §2 "okio"/"move" files and the 60 surviving
  tests to commonMain/commonTest; engine, cache, generator, chunker, timeline shared
  between platforms.
- **Phase 5 — Feature on.** Flip `TtsSupport.isTtsSupported` on iOS; Voices sheet,
  prefetch, sleep timer wired through the shared controller.

---

## 4. Runs

| Run | What | Model | Status |
| --- | --- | --- | --- |
| A | Phase 0 spike (this document) | opus | done |
| B | Phase 1 — iOS Kokoro synthesizer behind the seam + system-voice provider; host test that synthesises to a temp WAV | sonnet | open |
| C | Phase 4a — `TtsSynthesizer`/`TtsEnginePlayer` `java.io.File` → okio `Path` (Android refactor only, no behaviour change; run the 60 host tests) | sonnet | open |
| D | Phase 2 — `AvTtsEnginePlayer`; **device pass**: queue gap audibility, auto-advance vs seek callback, Now Playing, pitch-without-rate via `AVAudioEngine`+`AVAudioUnitTimePitch` if required | sonnet | open |
| E | Phase 3 — iOS pack install via lib/packs + Supertonic terms gate; download/cancel/resume | sonnet | open |
| F | Phase 4b — move engine/cache/generator/chunker + 60 tests to commonMain/commonTest | sonnet | open |
| G | Phase 5 — `isTtsSupported`, Voices sheet, prefetch, sleep timer; reader QA | sonnet | open |

### How each run shows correctness

- **B**: a Kotlin `iosTest`/`androidHostTest` synthesises a fixed sentence and asserts
  WAV header, 24 kHz, duration > 0, peak > silence threshold. Timing logged; cold <
  10 s, warm < 5 s on the simulator (spike: 4.5 / 2.6 s).
- **C**: pure refactor — the 60 moved tests pass unchanged on Android; `git diff` shows
  only `File`→`Path` and import changes.
- **D**: on a **physical device** (simulator cannot render audio — §1.5), two queued
  WAVs play with no audible gap; `onItemTransition(isAutoAdvance=true)` fires on natural
  advance, `false` after `seekTo`; `setPlaybackPitch` changes pitch without changing
  duration (else record the AVAudioEngine decision); Now Playing shows title/duration;
  app backgrounded keeps playing (UIBackgroundModes.audio already present).
- **E**: install Kokoro from the real manifest on device; `verified=true`; delete
  removes the model root; Supertonic is gated until terms accepted
  (`IosSupertonicTermsStore`).
- **F**: `./gradlew :feature:reader:ui:compileKotlinIosSimulatorArm64` and the Android
  build both pass; the 60 tests run in commonTest on both targets.
- **G**: end-to-end read-aloud on device: chapter plays, sentence tap seeks, prefetch
  keeps 4 ahead, voice change re-anchors, sleep timer stops.

---

## 5. Risks

Each with the signal that it is happening and the fallback.

1. **Peak memory kills the app on device.** Spike: 487 MB footprint during cold
   synthesis on the simulator. *Signal:* jetsam / `EXC_RESOURCE` on older iPhones during
   the first sentence. *Fallback:* `numThreads=2`, quantised (int8) model only — already
   the pack — and synthesise-ahead-1 instead of 4 under memory pressure.
2. **Pitch-without-rate needs AVAudioEngine, not AVQueuePlayer.** `AVPlayer` rate
   pitch-shifts; independent pitch needs `AVAudioUnitTimePitch`. *Signal:* run D finds
   `setPlaybackPitch` also changes tempo. *Fallback:* ship pitch via tempo-pitch
   algorithm `AVPlayerItem.audioTimePitchAlgorithm` (acceptable quality) or bake pitch
   into synthesis ( Kokoro has no pitch knob — would need re-synthesis per pitch).
3. **Background synthesis is throttled.** iOS suspends CPU-heavy work in background even
   with audio background mode. *Signal:* prefetch stalls when the screen locks mid-
   chapter. *Fallback:* prefetch deeper while foregrounded; `BGProcessingTask`
   (the app already registers one for sync) to top up the audio cache.
4. **onnxruntime/sherpa binary size in Release.** Debug +38 MB; Release + dead-code
   stripping should be smaller but app-store size is a product call (§6).
   *Signal:* Release `.ipa` grows > ~40 MB. *Fallback:* strip x86_64 (already done for
   sim), confirm bitcode/thinning, consider the shared XCFramework if its rpath issue is
   resolved upstream.
5. **The commonMain move breaks Android.** The engine touches ExoPlayer via
   `TtsEnginePlayer`. *Signal:* the 60 moved tests fail in commonTest. *Fallback:* keep
   `TtsReadAloudEngine` in `androidMain` one more cycle and move only the pure
   value/logic files (chunker, timeline, cache store) first.
6. **System-voice parity gap.** Android exposes `TTS_SYSTEM_VOICE_KEY`; iOS
   `AVSpeechSynthesizer` voices differ in capability (no file output on all voices).
   *Signal:* `synthesizeToFile` unavailable for the picked system voice.
   *Fallback:* document that the iOS "system" voice speaks live (no seek/scrub) while
   neural voices keep the file-based path — surfaces in the Voices sheet.

---

## 6. What only the owner can decide

- **App size.** +38 MB Debug for the two engines. Acceptable to ship, or gate behind an
  on-demand download / App Thinning variant?
- **Which voices ship.** Kokoro has 11 speakers; Supertonic 10. Ship all, or a curated
  subset to bound the Voices sheet?
- **Whether iOS ships without pack support first.** Phase 1+2 (bundled or system voice
  only, no download UI) could ship before Phase 3 (pack download) if time matters.
- **Pitch UX.** If risk R2 forces tempo-pitch instead of true pitch, is that acceptable
  for the pitch setting, or does iOS drop the pitch control?

---

*Decisions made this run (so later runs don't reopen them): (1) static XCFrameworks, not
shared — the shared one's `@rpath/onnxruntime.framework` is unsatisfiable from the
release zip; (2) vendored in Xcode, not CocoaPods/SPM — zero change to the Gradle-driven
framework pipeline; (3) Swift-implements-Kotlin seam, not cinterop — memory safety and
it matches the existing reader bridge; (4) arm64-only simulator slice — target is
`ARCHS=arm64`; (5) pack download routes through multiplatform `lib/packs`, not a port of
`TtsModelStore.kt`.*
