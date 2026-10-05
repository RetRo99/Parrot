# Speak a word with the book's TTS voice — investigation

**Status:** investigation only, no code changed. Written 2026-10-05 against `main` (working tree has unrelated Browse/Links edits).

**Evidence labels.** *[repo]* read in code. *[bench]* from `docs/tts-bench-results.md` (debug build, 2026-09-30, Nova Air and Xiaomi 2602BPC18G). *[est]* extrapolated by me and not measured. *[recall]* general platform knowledge I did not verify against this repo or a device. *[untested]* needs a device check before anyone relies on it.

**Not available to me.** `design/ember/marks-define-day.png` and `marks-dict-day.png` do not exist in the repo, so I could not look at the UI. `design/DICTIONARY_REPORT.md` has no section on pronunciation or audio. I ran nothing on a device. **No measurement of a one-word synthesis exists anywhere in the repo.** Every latency figure for a single word below is an estimate.

---

## 1. One-off speech — can the TTS layer do it, and is there a preview path?

**Yes. The layer is not tied to the read-aloud queue.**

| Class (all in `feature/reader/ui/src/androidMain/.../reader/ui/`) | Role |
|---|---|
| `tts/TtsSynthesizer` (interface) | `synthesize(text, voiceId, rate, pitch, outputFile)` writes a WAV. Stateless per call. |
| `tts/TtsSynthesizerRouter` | Routes by voice-id prefix: `kokoro:` → `SherpaOnnxSynthesizer`, `supertonic:` → `SupertonicOnnxSynthesizer`, anything else → `AndroidSystemTtsSynthesizer`. |
| `tts/TtsAudioGenerator` | Singleton wrapper: SHA-256 cache lookup, then a **single-permit semaphore** (`MAX_CONCURRENT_SYNTHESIS = 1`), then `synthesize`. |
| `tts/TtsAudioCache` | WAV files in `cacheDir/tts`, 128 MB LRU. The key is `voiceId\|modelVersion\|rate\|pitch\|text`. |
| `tts/TtsReadAloudEngine` | Owns the read-aloud sentence queue and the ExoPlayer playlist. **Not needed for a one-off word.** |
| `tts/TtsPreviewPlayer` (`@Scoped ReaderScope`) | Separate small ExoPlayer. `play(chunks, voiceId, rate, pitch)` synthesizes **all** chunks, then plays them as a playlist. |
| `navigator/AndroidTtsController.previewVoice()` (`:231`) | Orchestrates preview: pauses read-aloud, runs `TtsPreviewPlayer`, resumes read-aloud in `finally`. |

**The Voices-screen preview path can be reused, but not unchanged.** It already covers: pause read-aloud → synthesize via the shared cache → play on a separate player → resume (`resumeNarrationAfterPreview`, `:518`). What it is wrong for:

- `ReaderViewModel.previewTtsVoice` (`:1555`) logs the `TtsVoicePreviewed` analytics event, gates on `needsDownload` and Supertonic terms, and writes `ttsPreviewingVoiceId` / `isTtsPreviewPlaying` into view state. That is Voices-sheet UI state, and it would recompose (and on e-ink, refresh) when a word is spoken.
- It takes the rate and pitch from global settings.
- It pauses read-aloud **before** synthesizing, so narration is silent for the whole synthesis time (see §3).
- It synthesizes every chunk before playing the first (`TtsPreviewPlayer.play`, the `chunks.mapIndexed` loop), which is wrong for word + definition (§6).
- Resuming goes through `requestPlayback(PREVIEW_RESUME)`, which emits playback-operation analytics and arms the start timeout.
- Timeouts of 30 s to start and 60 s max are tuned for voice samples.

**iOS:** `IosTtsController.previewVoice` is `= Unit` (`IosTtsController.kt:46`). See §8.

---

## 2. Per engine

The app currently loads engines in three different ways [repo]:

- **System TTS** is bound by `AndroidTtsController.availableVoices()` → `synthesizer.awaitReady()`. That runs from `ReaderViewModel.initTts()` (`:1062`) whenever a book has readable content, **whether or not read-aloud is enabled**. So the `TextToSpeech` instance is normally already up when the user selects a word.
- **Kokoro and Supertonic** are loaded lazily, on the first `synthesize()` or `prepareVoice()`. `initTts` calls `prepareTtsVoice` only if `settings.ttsEnabled` and the selected voice is neural and downloaded (`:1111-1117`). So for a reader who has not turned read-aloud on, **the neural engine is cold when they tap the speaker.**
- **Nothing unloads a loaded engine.** I found no caller of `synthesizer.release()`, and no `onTrimMemory` / `onLowMemory` handler anywhere in the repo. Engines stay resident until the voice pack is deleted or updated. "Keeping it warm while a book is open" is therefore already the behaviour once loaded, and it also persists after the book is closed.

| | System voices | Kokoro | Supertonic |
|---|---|---|---|
| **Load** | `TextToSpeech(context)` bind. Usually done already. Init is 3 s-bounded (`awaitReady`). Bind time is *[untested]*. | 4.5 s load + 4.9 s warm-up on Nova Air *[bench]*. 0.9 s load on an emulator *[repo docs]*. Xiaomi load not recorded. | 2.5 s load + 4.7 s warm-up on Nova Air *[bench]*. Xiaomi load not recorded. |
| **Warm, one word** | *[untested]*. `synthesizeToFile` then ExoPlayer prepare. I would expect a few hundred ms *[est]*. | Short sentence (3.8 s audio): 4.7 s on Xiaomi, 20.5 s on Nova Air (r 1.23 / 5.35) *[bench]*. One word ≈ 0.4–0.6 s of audio *[est]*. If time scales with audio: ~0.6 s Xiaomi, ~3 s Nova Air. The fixed per-call cost is unknown. | Short sentence (4.6 s audio) at 8 steps: 2.8 s on Xiaomi, 12.5 s on Nova Air *[bench]*. By the same scaling, ~0.3–0.5 s Xiaomi, ~1.5–3 s Nova Air *[est]*. |
| **Cold** | Add the bind time if not yet bound. | Add ~9 s on Nova Air (load + warm-up). | Add ~7 s on Nova Air. |
| **Cache hit** | The same word tapped again skips synthesis (key includes the text). Only ExoPlayer start remains. | same | same |
| **Memory** | Held by the system TTS service process, not ours. | 134 MB int8 model file *[repo docs]* plus ORT buffers. **Resident RSS has not been measured** (the bench records none). I would budget roughly the file size or more *[est]*. | Four ONNX graphs. **RSS unmeasured.** |
| **Battery (warm, idle)** | None from us. | A loaded ORT session uses no CPU while idle. The cost is memory, which raises the chance the OS kills the process in the background. The Supertonic optimisation plan already flags ORT thread spinning as something to measure (`docs/supertonic-chunk-synthesis-optimization.md`). | same |
| **Battery (per word)** | Negligible. | A CPU burst of ~0.5–3 s on 2–4 threads, per *uncached* word. | same |
| **Quality on one word** | *[untested]*. Vendor voices are usually fine on isolated words. | *[untested]*. The model is trained on sentences. Isolated words often get odd prosody or a clipped tail *[recall]*. | *[untested]*. Same concern. |

**Cache detail:** a WAV for a 0.6 s word at 44.1 kHz 16-bit mono (Supertonic) is ~53 KB *[est]*, so word caching is cheap. The cache is shared with read-aloud and lives in `cacheDir`, so the OS may clear it.

**What to measure before committing.** Add a one-word row to `tools/tts-bench` for cold and warm, on both devices. The existing `ListeningLab` is the right place for a listening check on about 30 isolated words, including the heteronyms in §5. The fixed per-call cost of both neural engines is the one unknown that decides whether "immediate" is achievable. The 4.7 s warm-up on Nova Air (`WARMUP_TEXT = "a"` in the app) hints that it may be large.

### Two problems that are not about speed

**1. A speaker tap can silently start a ~150 MB download.** `synthesize()` → `ensureLoaded()` → `modelManager.ensureSupertonicModel()` / `ensureKokoroModel()` → `loadModel()`, which calls `installVersion()` when there is no active local version (`TtsModelManager.kt:154-190`). Nothing in that path checks consent, Wi-Fi, disk, or Supertonic terms. Only `ReaderViewModel.previewTtsVoice` gates it today. **A word-speak path must check `TtsVoice.isDownloaded` and `hasAcceptedSupertonicTerms` itself, and must never call `synthesize` for a voice that fails those checks.**

**2. Synthesis is globally serialized.** `TtsAudioGenerator` has one permit, and each neural synthesizer has its own `generationMutex`. A word requested while read-aloud is prefetching waits for the sentence currently being synthesized. On Nova Air a sentence takes 12–40 s at 8 steps *[bench]*. The native call cannot be interrupted: `stop()` only bumps a generation counter and the result is discarded after the call returns. **With a neural voice and read-aloud active, a word tap on a slow device can take as long as one sentence.** The system voice is much less affected.

---

## 3. While something is playing

### Device-voice read-aloud

**Pause → speak → resume works without losing position or re-synthesising.**

- `TtsReadAloudEngine.pause()` is `player.pause()`. The playlist, `readyFiles`, and the position *within* the sentence are kept. `resume()` is `player.play()`. There is no re-synthesis. The resume point is the exact pause position, not the sentence start.
- `AndroidTtsController.previewVoice` already does this, and it only resumes if read-aloud was playing when the preview started (so a user-paused read-aloud stays paused).
- Issues found:
  1. The preview path pauses **before** synthesis. With a neural voice, read-aloud is silent for the whole synthesis time. **Synthesize first, then pause and play** (the file is ready in milliseconds on a cache hit).
  2. Resuming goes through `requestPlayback(PREVIEW_RESUME)` with the start-timeout and operation analytics. It works, but it produces playback-operation events for every word tap.
  3. The semaphore wait described in §2 holds the word behind prefetch work.
  4. **Cancellation hazard.** `AndroidSystemTtsSynthesizer.synthesize` catches `CancellationException` and calls `stop()`. That calls `TextToSpeech.stop()` and completes *every* pending request as `CANCELLED`, including the read-aloud sentence or prefetch currently in flight. In `TtsReadAloudEngine.synthesizeIfMissing`, a `CANCELLED` result throws `CancellationException`. Cancelling a word request (sheet closed, selection changed) while read-aloud is synthesizing can therefore cancel read-aloud's own synthesis. *[repo; the resulting user-visible failure is untested]*. The word path needs its own cancellation that does not call global `stop()`.

### Recorded narration and audiobook

- Both play on the single ExoPlayer owned by `MediaPlaybackService` (`:330-340`): `handleAudioFocus = true`, `USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`, `setHandleAudioBecomingNoisy(true)`.
- `MediaPlaybackController.pause()` / `play()` (`:415`, `:425`) act on that player, so they would work for narration and audiobook. ExoPlayer keeps position, so resume is exact.
- **`NarrationController` has no `pause()` / `resume()`.** It has only `togglePlayback()`, which is racy for this use. A word-speak feature needs an explicit pause and resume, remembering whether it was the one that paused.
- **Do not rely on audio-focus auto-resume.** `TtsPreviewPlayer` uses `setAudioAttributes(USAGE_MEDIA, …, handleAudioFocus = true)`. I believe Media3 requests a permanent `AUDIOFOCUS_GAIN` for `USAGE_MEDIA` *[recall, verify]*. The service player would then receive a permanent loss and not resume by itself. The existing preview code works only because it resumes explicitly. Keep explicit pause/resume.
- Audiobook: `AudiobookPlayerViewModel` sits on the same service player. Whether pause triggers a progress sync to the server, or anything else with side effects, I did not check. *[untested]*
- Recorded narration: `ClipScheduler` drives highlights from player position, so pause/resume is consistent. *[not traced in depth]*

### Focus, media notification, Bluetooth

| | What happens |
|---|---|
| **Audio focus** | The preview player takes focus. The service player is paused by us anyway. On resume, `play()` re-requests focus. |
| **Media notification** | Shows pause → play flicker for the duration of the word, about a second. The word player has no MediaSession, so the notification and lock screen do not show the word. |
| **Bluetooth / headset buttons** | Buttons target the service MediaSession, which is still active. A play press during the word resumes narration **on top of the word**. The remote state flips twice (visible as flicker on car head units). *[untested]* |
| **A2DP wake-up** | After narration is paused, the Bluetooth sink may suspend. The first ~100–300 ms of a short word can be clipped when the stream restarts *[recall]*. A word is short enough that this could eat the start. Test on real headphones. Mitigation if it happens: ~200 ms of leading silence. |

**Alternative considered:** duck narration instead of pausing (transient-may-duck focus). Rejected: a quiet narrator under a spoken word is confusing, and ducking does nothing for a paused audiobook.

---

## 4. Voice and language

**Which voice is "selected"?** One **global** voice id, `ReaderSettingsLocalModel.ttsVoiceId` (`feature/reader/data/.../ReaderSettingsLocalModel.kt:45`). It is stored in the user-scoped reader-settings table (`ReaderLocalDataSource.getReaderSettings`) and the model has **no book id and no language field**. So "the voice the user has chosen for this book" is in fact the one global voice. Rate and pitch are also global (`ttsRate`, `ttsPitch`).

**Neural voices are English-only in this code.**

- `SupertonicOnnxSynthesizer` hard-codes `extra = mapOf("lang" to "en")` (`DEFAULT_LANGUAGE`) and every `SUPERTONIC_VOICES` entry has `locale = "en"`.
- Every Kokoro entry has `locale = "en"`, and the Kokoro voice list is US/UK English only.
- System voices carry real locales (`Voice.locale`), and the Voices sheet groups them by language.

So for a Slovenian book, a global "Supertonic F1" is already an English-accent reader. For word speech it will mangle Slovenian words (§5).

**Book language is available.** `publication.language` is used in `ReaderViewModel` (recap code, `:797`, `:836`). The dictionary pack language is also known at lookup time. Decide the language from the **dictionary entry's language** (what the word actually is), with book language as a tiebreaker.

**Proposed fallback order**

1. The selected voice, if **all** hold: the voice exists in `availableVoices()`; it is usable now (`!needsDownload`, Supertonic terms accepted, engine loadable); its language matches the word's language.
2. An offline system voice whose locale matches the word's language. Prefer the user's selected system voice if one exists for that language, then the highest-quality offline voice (`isHighQuality`, `latency`).
3. The engine's offline default voice, **only if** its locale language matches.
4. Hide the button.

**Pack states**

| State | Behaviour |
|---|---|
| Not downloaded | Use fallback 2. Never trigger the download from the speaker. |
| Downloading or preparing (`TtsVoicePreparationState.Running`) | Same: use the system voice silently. Do not queue behind the download. |
| Downloaded, engine cold | Load on demand (see §2 for cost and the optional background load). If load is not finished, falling back to the system voice beats a multi-second silence. |
| Pack doesn't cover the language | Fall back to 2. Do not pass a Slovenian word to an English neural voice. |
| Update available but not installed | Use the active version (the manager already treats the active version as authoritative). |

**Hide the button entirely when:** no voice can speak the word's language offline; the TTS engine fails to initialise; the platform has no implementation (iOS today); or the selection is not word-like (digits only, symbols, more than ~3 tokens).

**Decision for the product:** falling back to a system voice breaks "same voice as read-aloud". I think that is right, because the alternative is silence or a wrong-language neural voice. A small voice label on long-press, or no label at all, is a design call for you.

**Speed.** The rate setting is global (`TtsSpeechRate` 0.5–2.0) and flows into the system engine's `setSpeechRate` and into the neural `speed`. For a single isolated word I would **cap at 1.0×**: at 1.5–2× a pronunciation check becomes unintelligible, and it also gets a separate cache entry per rate. I would not slow a word unless the user's rate is already below 1.0. Slow-pronunciation as a long-press option would be a separate feature. Pitch: the system engine applies pitch at synthesis; neural voices apply it at playback (`PlaybackParameters` pitch). Use 1.0 for words.

---

## 5. Text preparation

*None of this was tested. Everything below is recall or reasoning.*

**Normalise before speaking (cheap, engine-independent):**

- Trim whitespace and surrounding punctuation and quotes (straight and curly, « », „ ").
- Keep internal apostrophes (`don't`, `o'clock`) and hyphens (`well-known`).
- Strip soft hyphen U+00AD, zero-width characters and other invisibles that EPUB text commonly contains.
- Speak the **selected surface form**, not the lemma (`ran`, not `run`).
- Normalise case to lowercase unless the word is a genuine all-caps acronym; all-caps words can be spelled out letter by letter by some engines *[recall]*.
- For the neural engines, a trailing period on a one-word input ("word.") often avoids a clipped or rising tail *[recall; test]*.

**Engine behaviour**

| | Notes |
|---|---|
| **System** | The vendor engine handles its own normalisation. Digits and symbols are read per the engine's rules. |
| **Kokoro** | Uses the espeak-ng phonemizer (`dataDir = espeak-ng-data`). In isolation it cannot pick a heteronym sense. |
| **Supertonic** | Takes raw text via a unicode indexer. Internal normalisation of numbers and abbreviations is *[not verified]*. Characters outside its indexer may be dropped, so a word may come out silent or garbled. |

**Heteronyms** ("read", "lead", "wind", "bass", "tear", "live", "close", "wound") cannot be resolved from one word on any engine. Expect the default sense: typically /riːd/, /liːd/, /wɪnd/ *[recall]*. The dictionary entry knows the part of speech and senses, but none of the three engines take phoneme input through our wrapper, so we cannot pass the right one. Accept the limitation and say so in the design, or hide the button for a short list of known heteronyms. I would accept it.

**Cross-language**

- *Slovenian word in an English neural voice:* č, š, ž likely dropped or wrongly mapped, and stress wrong. **Avoid by routing (§4), not by text tricks.**
- *English word in a Slovenian system voice:* usually read with Slovenian letter-to-sound rules. Some engines switch language on their own; most do not.
- Both directions fall out of the §4 rule: choose the voice from the word's language.

**Numbers:** a digits-only selection is probably not a dictionary word, so hide the button. If shown, "1984" (year vs cardinal) differs per engine.

---

## 6. Reading the definition

**Yes, as a queue of separate chunks. Not as one utterance.**

- `TtsPreviewPlayer.play(chunks)` already plays a list of chunks as an ExoPlayer playlist. `TtsSentenceChunker.chunk()` splits text at sentence ends and caps chunks at 280 chars.
- Word + pause + definition: items `[word, silence, definition]`. ExoPlayer has no pause item by default. Options: a prebuilt ~300 ms silent WAV in the playlist (`SilenceMediaSource` from Media3 is the alternative), or a short `delay` between items. The silent item is simplest and keeps playback gapless.
- Make it **progressive**: synthesize and start the word, then synthesize the definition **while the word plays**. Today `play()` synthesizes all chunks first, so for a neural voice the user would wait for the definition before hearing the word. This is the one change needed in `TtsPreviewPlayer`'s shape.
- **Cost for neural voices:** the definition is a 100–280 character chunk, so on Nova Air it would take 12–40 s *[bench, scaled]*, and 3–10 s on Xiaomi *[est]*. I would restrict long-press-definition to system voices, or accept it only on devices where the neural engine is warm and fast. For a slow neural setup, falling back to the system voice for the definition is a reasonable rule.
- **Definition text** must be plain: strip markup and labels, expand or drop dictionary abbreviations ("n.", "adj.", "esp."), drop example sentences, take the first sense only.
- **Cancel:** the preview player stops immediately (`player.stop()`, playlist cleared). The in-flight synthesis is a coroutine; cancelling it is instant for the System engine *but calls global `stop()` (§3)*, and for the neural engines it cancels the coroutine but the native call runs to completion. The result is discarded and the semaphore is released only then. Stop playback instantly, and expect brief extra CPU work after cancel. Wire cancel to: sheet closed, selection changed, new tap on the speaker, and reader leaving the screen (`AndroidTtsController.close()` already closes the preview player).

---

## 7. E-ink and accessibility

- **No UI update is required.** The preview state machine (`TtsPreviewState`: IDLE/LOADING/SPEAKING) is only consumed by the Voices sheet and the narration `isLoading` combine. A word-speak path should **not** write to `ttsPreviewingVoiceId` / `isTtsPreviewPlaying` or emit loading state, so nothing recomposes and nothing refreshes on e-ink.
- Avoid press ripples or pressed-state animations on the button when the e-ink profile is on. I did not inspect how the app implements its e-ink mode, so that detail is *[not checked]*.
- Do not show a spinner during a slow neural synthesis; an unexplained silence is the trade-off. A quiet tap acknowledgement (haptic) is better than a visual one.
- **TalkBack:** TalkBack speaks the button's label on focus. On activation, our audio starts on the media stream and TalkBack's own feedback is on the accessibility stream; whether they overlap or duck each other depends on the device *[untested]*. Keep the content description short ("Pronounce"), set a role of button, and do not call `announceForAccessibility`. Expose "Read definition" as a **custom accessibility action** (`onLongClickLabel` plus a `customActions` entry), because a long-press is awkward with TalkBack.
- A TalkBack user who is already hearing the word spoken by TalkBack may not need the button at all; do not hide it, since the voice can differ from TalkBack's.

---

## 8. iOS

**Current state: nothing exists.** `IosTtsController` is all stubs (`availableVoices() = emptyList()`, `previewVoice = Unit`, `hasReadableContent() = false`). Device read-aloud is not built on iOS (its own KDoc says so). `TtsModelManager`, Kokoro and Supertonic are Android (`androidMain`) only; iOS has only `IosSupertonicTermsStore`. The iOS app has one audio file, `MediaOverlayPlayer.swift`, which configures `AVAudioSession` as `.playback` / `.spokenAudio` with `[.duckOthers, .interruptSpokenAudioAndMixWithOthers]` (`:302-315`).

**What is available** *[recall]*

- `AVSpeechSynthesizer` with `AVSpeechSynthesisVoice.speechVoices()`. Includes enhanced and premium voices that the user downloads in Settings. Slovenian (`sl-SI`) exists as far as I recall.
- `speak(_:)` is low-latency once the voice is loaded; first use of a voice can take a noticeable moment.
- `write(_:toBufferCallback:)` can deliver PCM if we want to route through our own player.
- It can share the app's `AVAudioSession` (`usesApplicationAudioSession`), so interrupting narration follows the same session rules as `MediaOverlayPlayer`.

**What cannot match Android**

- **No neural voice packs.** The sherpa-onnx integration is Android-only. `kokoro:` and `supertonic:` ids mean nothing on iOS. If reader settings sync between devices, an Android `ttsVoiceId` arriving on iOS (a vendor voice name, or a `kokoro:3` id) must not be treated as valid.
- **No "the voice the user picked in the app".** There is no Voices screen on iOS, so the word voice would be a separate choice: a system voice by language, or the iOS default.
- Enhanced voices depend on the user having downloaded them; we cannot download for them.
- The implementation would sit in Swift behind a Kotlin interface (as with the other `iosMain/bridge` code), not in `commonMain`.

The sensible iOS scope is: `AVSpeechSynthesizer` with a voice chosen by the word's language, hidden when no voice exists for it. That is separate from, and much smaller than, bringing read-aloud to iOS.

---

## Simplest implementation that gives an immediate response on tap

**Immediate response = instant feedback and quick sound.** Instant sound is only possible for system voices and cached words. Neural synthesis is a compute cost that no plumbing removes.

1. **New method on `TtsController`:** `speakWord(word, language)` and `stopWord()`. No analytics event from the preview path, no view state. The default (iOS) implementation is a no-op, and `canSpeakWord(language)` drives button visibility.
2. **Voice resolution** in one small function, implementing the §4 fallback order. Pure and unit-testable in `commonMain`. Hard gates: downloaded, terms accepted, language match.
3. **Playback:** reuse `TtsAudioGenerator` (cache and serialization) and a lean player. Either a `speakWord` on `TtsPreviewPlayer`, or a trimmed copy of it with progressive playback. Synthesize first, then pause narration via `MediaPlaybackController.pause()` (or `engine.pause()` for device read-aloud), play, and resume only if we were the one who paused.
4. **Text prep:** a `prepareWordForSpeech()` helper per §5. Rate capped at 1.0, pitch 1.0.
5. **Warm-up (optional but what makes it feel immediate):** when the strip appears, if the resolved voice is a **system** voice, pre-synthesize the word into the cache (free). For a neural voice, start loading the engine in the background only if it is downloaded and the device is not in low-power mode; do not pre-synthesize words.
6. **Long-press:** word chunk, silent item, definition chunk, progressive. Allowed for system voices; for neural voices only when warm, otherwise fall back to the system voice for the definition.
7. **Cancellation:** a word-specific cancel that does not call `TtsSynthesizer.stop()`.

### What it costs

- **Engineering:** roughly 2–4 days for the system-voice path including resolution, pause/resume, cancel and tests, plus 1–2 days for neural gating, progressive definition and the bench row *[est]*. It needs a device pass for Bluetooth and TalkBack.
- **Runtime:** system voices: negligible. Neural: memory from a resident engine (unmeasured; it is already resident today once loaded), a CPU burst per uncached word, and a possible wait behind read-aloud synthesis.
- **Binary / storage:** nothing new, aside from a small silent WAV and tiny cached word files.

### Failure cases the UI must handle

| Case | UI behaviour |
|---|---|
| No usable voice for the word's language | Button hidden (not disabled). |
| Selected neural pack not downloaded, downloading, or terms not accepted | Silent fallback to a system voice; otherwise hide. **Never start a download.** |
| Engine fails to load or synthesis fails or times out | Do nothing visible; log via analytics. At most one subtle non-visual signal. |
| System TTS has no offline voice (only network voices) | Hide; `availableVoices()` already filters out network voices. |
| Narration paused by us but synthesis is slow or fails | Resume narration in `finally`. Never leave the user's audio paused. |
| User paused read-aloud before tapping | Do not resume afterwards. |
| Two quick taps | Latest tap wins; stop the previous word; do not stack. |
| Selection changes or sheet closes mid-word | Stop playback immediately. Resume narration if we paused it. |
| Bluetooth clips the first syllable | Add ~200 ms leading silence if it reproduces. |
| Headset Play pressed during the word | Narration resumes over the word. Accept, or pause the word on the next `isPlaying` edge. |
| TalkBack on | Custom action for the definition; short label; no announcements. |
| iOS | Hidden until the Swift implementation exists. |

### When I would advise against using the selected voice

Use the system voice instead when any of these hold:

1. **The word's language is not covered by the selected voice.** Both neural packs are English-only in this code, so for any non-English book this is the normal case, not an edge case.
2. **A neural engine is cold, or read-aloud is active.** A cold engine costs ~7–9 s on Nova Air to load, and an active read-aloud queue serializes the word behind sentence-length synthesis (12–40 s on Nova Air). Both defeat "immediate".
3. **A slow device.** On Nova Air-class devices (r ≈ 2.3–5), even a warm one-word synthesis is probably 1.5–3 s *[est]*.
4. **The pack is not downloaded or is downloading.**
5. **A listening test shows neural voices speak isolated words badly** (clipped, odd prosody). I would run it before shipping the neural path at all.

**What to use instead:** the user's selected system voice if they have one for that language, else the best offline system voice for the word's language, else hide the button. System voices are already bound, cache well, start in a few hundred ms *[est]*, and are the only option that works for every language the Voices sheet can list.

**My recommendation:** ship system voices first, with the neural path behind a measured gate (warm, downloaded, English, fast device, no active read-aloud). Add the one-word bench row before deciding how far to take the neural path.
