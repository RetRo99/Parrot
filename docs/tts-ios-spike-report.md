# TTS iOS spike — report

Branch: tts/ios-spike

Commits this run (oldest first):
- 24033947 spike(tts-ios): sherpa-onnx 1.13.8 static XCFrameworks + Kokoro synth seam
- 124117d1 spike(tts-ios): pack download + AVQueuePlayer probe findings
- (plus this doc's commit)

## Steps

1. sherpa-onnx iOS binaries — DONE.
2. One synthesis end to end on the simulator — DONE (real audible speech).
3. Kotlin-to-synthesis seam — DONE (Swift implements a Kotlin interface).
4. Pack download from Kotlin — DONE; AVQueuePlayer — simulator-blocked (documented).
5. commonMain move table — DONE (in docs/tts-ios-implementation-plan.md §2).
6. Plan document — DONE (docs/tts-ios-implementation-plan.md).

## Measured facts (iPhone Air simulator, iOS 27, Xcode 27)

- sherpa-onnx artifact: v1.13.8, from the k2-fsa/sherpa-onnx repo rolling `xcframework`
  release tag, file `sherpa-onnx-v1.13.8-ios-static.xcframework.zip` (17.0 MB). The
  `v1.13.8` release itself ships no iOS asset; the `xcframework` tag does. Enters the
  build as a vendored XCFramework under `iosApp/Frameworks/`, linked + embedded (Embed
  Frameworks phase, CodeSignOnCopy), FRAMEWORK_SEARCH_PATHS=$(SRCROOT)/Frameworks.
  Requires a second vendored XCFramework: onnxruntime-ios-static 1.28.2 from
  csukuangfj/onnxruntime-libs (~41 MB download). Simulator slice: yes (ios-arm64-simulator,
  thinned from the fat ios-arm64_x86_64-simulator to arm64). Device arm64 slice: yes.
  Built .app grew 237 MB -> 275 MB = +38 MB (Debug).
- Kokoro synthesised real audible speech on the simulator: yes.
  - cold WAV: .../Documents/spike-cold.wav, 312486 bytes, 24000 Hz mono Int16, 6.509 s
  - cold synthesis 4474 ms; engine load 1481 ms; warm synthesis 2551 ms
  - peak memory (task phys_footprint): 35 MB before, 278 MB after load, 487 MB peak
    during cold synthesis
  - confirmed speech, not silence: peak 0.3161 / RMS 0.0599 with 14.5% near-zero samples
    (inter-word pauses), and the WAV copied to the host and played with afplay is
    intelligible English speech.
- Seam chosen: Swift implements a Kotlin interface registered in a Kotlin registry
  (IosTtsSpikeBridge / IosTtsSpikeBridgeRegistry, mirroring EpubReaderBridgeRegistry).
  Reason: keeps sherpa-onnx's pointer-heavy C API in Swift (no hand-written
  ExperimentalForeignApi memory management in Kotlin), matches the existing reader bridge
  pattern, and makes error propagation + engine lifetime explicit. Proven by
  IosTtsSpike.run() (Kotlin iosMain) -> TtsSpikeBridgeImpl (Swift) -> SherpaOnnx C API ->
  real WAV path back in Kotlin.
- Pack download: lib/packs PackDownloader (okio + ktor-darwin) downloaded and
  sha256-verified a real Kokoro pack file (tokens.txt) from Kotlin iosMain on the
  simulator: 479 ms, verified=true, 1078 bytes. So the download/verify/okio path moves
  unchanged; TtsModelStore.kt (java.io/File, HttpURLConnection, MessageDigest,
  ZipInputStream, ConcurrentHashMap) does not and is replaced by lib/packs routing.
- AVQueuePlayer: could not be verified — no AVPlayer can render audio on the iOS
  simulator (a single-file AVPlayer's playhead stayed at 0.00 s over 4 s with rate=1.0,
  session .playback active, route Speaker; no mediaserverd/audio HAL in the sim, only
  systemsoundserver-simd). Established from probe + platform: AVAudioSession category
  .playback/mode .default activates; AVPlayerItem.audioTimePitchAlgorithm exists per item
  but pitch-without-rate is not an AVPlayer feature (needs AVAudioEngine +
  AVAudioUnitTimePitch); UIBackgroundModes already contains audio. Now Playing and
  inter-item gap: unverifiable on simulator, deferred to a device run.
- commonMain move table written: yes (plan §2). Counts: move 1, okio 9, expect/actual 1,
  iOS twin 11, Android-only 12.
- Android host tests surviving a move to commonTest: 60 of 104 (the TtsReadAloudEngine,
  TtsAudioCacheStore, TtsAudioGenerator, WavSilenceTrimmer, TtsSynthesizerRouter,
  TtsPlaybackAttempts, NeuralGenerationCallback suites — their only JVM ties are
  java.io/java.nio, replaceable with okio).
- Plan document: docs/tts-ios-implementation-plan.md, 7 runs (A-G).

## Decisions I made (one line each)

- Static XCFrameworks, not shared: the shared sherpa XCFramework has an unsatisfiable
  @rpath/onnxruntime.framework dependency and ships no onnxruntime in the zip.
- Vendored in the Xcode project, not CocoaPods/SPM: no change to the Gradle-driven
  ComposeApp framework pipeline and pod install stays Readium/Firebase-only.
- Swift-implements-Kotlin seam, not cinterop: memory safety + matches the existing
  EpubReaderBridge pattern.
- arm64-only simulator slice via lipo -thin + xcodebuild -create-xcframework: the target
  sets ARCHS=arm64, so the x86_64 sim slice is dead weight.
- Pack install routes through multiplatform lib/packs, not a port of TtsModelStore.kt.

## Ambiguities and what I chose

- "iOS artifact from the sherpa-onnx project": the v1.13.8 tag has none, so I used the
  same-version asset on the rolling xcframework tag (still official k2-fsa). Reported above.
- Seam proof: the spike bridge is spike-scoped (IosTtsSpikeBridge), not the real
  TtsSynthesizer — the real one is Phase 1/Run B, since the contract's java.io.File must
  become a path string first.
- Pitch: probed and documented as a design fork (R2) rather than building an AVAudioEngine
  spike, since AVPlayer can't render on the simulator anyway.

## Scope

Files changed outside docs/, iosApp/, feature/reader/ui/src/iosMain/ and build files: none.
Existing .kt files under androidMain or commonMain modified: none.
Build files touched: feature/reader/ui/build.gradle.kts (iosMain deps: lib.packs +
ktor-darwin, spike-only), iosApp/iosApp.xcodeproj/project.pbxproj (framework wiring).

## Known problems left

- AVQueuePlayer/Now Playing/gap are unverified until a device run (plan Run D).
- The spike files (IosTtsSpike.kt, TtsSpike.swift, TtsSpikeBridge.swift, the iOSApp.swift
  hook, the Frameworks dir) are throwaway; they compile and run but are not product code.
  Kept in the tree per the run's "keep findings" rule; Phase 1 replaces them.
- feature/reader/ui iosMain now depends on lib.packs for the spike; a real iOS TTS module
  should own that dependency.

## Where this plan is most likely to fail

Memory (487 MB peak during cold synthesis) on older physical devices, and the
pitch-without-rate fork (R2) if AVAudioEngine integration proves heavier than expected.
