# Prepared chapters

## Run status (2026-10-10)

### Continuation checkpoint

### Step 5: reader wiring

`TtsController` (commonMain) gained five members, all with defaults that mean "not
supported", so `IosTtsController` is untouched and the iPhone behaves exactly as
before: `chapterPreparation: Flow<TtsChapterPreparationState>` (default always
Idle), `preparedChapterAudio(chapterHref): Flow<TtsPreparedChapterAudio>` (default
NotPrepared), `suspend prepareChapter(chapterHref): TtsChapterPreparationRequest`
(default UNAVAILABLE), `cancelChapterPreparation()`, `suspend
deletePreparedChapter(chapterHref)`, plus `suspend preparedAudioBytes()` and
`suspend deleteAllPreparedAudio()` for the Settings row.

`AndroidTtsController` implements them over the app-wide job and the prepared
store (both injected `@Single`s). `prepareChapter` applies the read-aloud gates in
order: an undownloaded pack or unaccepted Supertonic terms answers VOICE_UNUSABLE,
a denied notification permission answers NOTIFICATIONS_DENIED, then the chapter's
sentences are taken from the chapter the reader has loaded now (the controller's
own loaded sentences, else `bookController.getChapterSentences()` when the locator
is on that chapter, never another chapter's text), the job is started, and only
then the foreground service. Preparation then continues without the reader. The
prepared-audio flow re-reads the store whenever the job moves or the voice, speed
or pitch changes (`restartForSettingsChange` bumps a revision). A failed store read
is logged and reported as not prepared rather than crashing the sheet.

The row decision is pure and in commonMain with commonTest tests, in the pattern of
`derivePackState`: `derivePreparedChapterRow` in `reader/PreparedChapterRowState.kt`
returns `PreparedChapterRowState` or null for "not shown" (recorded narration, no
read-aloud, no chapter). Order: this chapter preparing, another chapter preparing,
an unusable voice, a failure of this chapter, then what is on disk (ready, partly,
other settings, not prepared). A chapter that has begun but has no sentence yet
reads as not prepared, and progress of the chapter on screen wins over a voice that
became unusable mid-run. `preparedChapterVoice` repeats read-aloud's gates
(system or non-neural usable, Supertonic needs terms, neural needs its pack) and
`preparedChapterPressOutcome` maps the request answer to what the screen does
beyond the row: open the Voices sheet, show read-aloud's own failure feedback, or
nothing. 14 of the 15 first row tests failed against a stubbed decision function,
and the press-outcome test failed against a stubbed mapping, before implementation.

`ReaderViewModel` only forwards: `observeChapterPreparation` copies the job state
into `ReaderViewState.chapterPreparation` and, keyed on the locator's chapter,
`preparedChapterAudio` into `ReaderViewState.preparedChapterAudio`; the three new
intents (`PrepareChapter`, `CancelChapterPreparation`, `DeletePreparedChapter`)
call the controller and route the press outcome through the tested mapping.

Green after step 5: reader Android 500/500, iOS 340/340 (the new commonTest cases
run on both), settings 22/22, home 84/84, composeApp 63/63, analytics 77/77.

### Step 4: the preparation job, its service and its analytics

`TtsChapterPreparationJob` is the app-wide `@Single` (its own `SupervisorJob` +
`Dispatchers.IO` scope, so it outlives the reader screen). The pure logic is
`TtsChapterPreparationCore`, which takes two seams a host test fakes:
`TtsPreparationSentenceSource` (key, prepare one sentence) over
`TtsAudioGenerator.preparedKey`/`prepareSentence`, and
`TtsPreparationChapterStore` (begin, isPrepared, markComplete, enforceLimit) over
`TtsPreparedStore`. `usableBytes` is `filesDir.usableSpace`.

State (commonMain `TtsChapterPreparationState`, so iOS compiles against it):
`Idle`, `Running(chapterHref, done, total)`, `Completed(chapterHref)`,
`Failed(chapterHref, reason)`, `Cancelled(chapterHref)`. Failure reasons:
`SENTENCE_FAILED`, `NOT_ENOUGH_SPACE`, `VOICE_UNUSABLE`, `NOTIFICATIONS_DENIED`,
`UNEXPECTED_ERROR`. One press answers with `TtsChapterPreparationRequest`:
`STARTED`, `ALREADY_PREPARING`, `NOT_ENOUGH_SPACE`, `VOICE_UNUSABLE`,
`NOTIFICATIONS_DENIED`, `UNAVAILABLE`.

Behaviour: `start` claims the job synchronously (compare-and-set to `Running`)
before any suspension, so a second press is refused with `ALREADY_PREPARING` and
nothing is queued; the state flow keeps reporting the chapter that is running,
which is what the row shows as "preparing another chapter". Sentences already in
the chapter are skipped, so a press after a cancel or an app kill resumes. A
failing sentence is retried once; a second failure ends the job as
`SENTENCE_FAILED` and keeps every sentence already stored. `cancel()` sets a flag
checked between sentences: the sentence in flight finishes, native synthesis is
never cut off, and the partial chapter stays valid and resumable. Completion marks
the manifest complete and then enforces the 1 GiB limit with this chapter as the
protected active one. Free space is checked before anything is written:
32 MiB headroom plus 64 kB per sentence (the measured AAC sentences were 11 to
20 kB), otherwise `NOT_ENOUGH_SPACE` with nothing begun. An empty chapter is
`UNAVAILABLE`.

`TtsChapterPreparationForegroundService` (declared in
`androidApp/src/main/AndroidManifest.xml` with `foregroundServiceType="dataSync"`)
holds the process up and shows progress with a Cancel action; it only watches the
job's state and stops itself on any non-`Running` state. The notification names no
book and no chapter. New Android resource strings (translations androidMain):
`tts_chapter_preparation_channel_name`, `..._channel_description`,
`..._notification_title`, `..._notification_progress` ("%1$d of %2$d sentences"),
`..._notification_cancel`.

Analytics: `ReaderAnalyticsEvent.TtsChapterPreparationStarted` and
`TtsChapterPreparationEnded`, one each per preparation, never per sentence.
Parameters: `operation=tts_chapter_preparation`, `stage` (started/ended),
`voice_kind` (neural/system), `sentence_count`, plus `outcome`
(completed/failed/cancelled) and `duration_ms` on the end event. No text, no
titles, no book, chapter or voice id. The fail-closed sanitizer gained
`voice_kind` as an enum dimension (neural, system) and `sentence_count` as a safe
long; `AnalyticsParameterSanitizerTest.chapterPreparationEventsKeepVoiceKindCountAndOutcome`
failed before that (voice_kind and the count were dropped) and
`chapterPreparationDropsAnythingItDoesNotRecognize` pins that a voice id under
`voice_kind`, a title, a negative count and a count sent as text all disappear.

Tests: 11 of the 12 new `TtsChapterPreparationTest` cases failed against the stub
core (the twelfth, the empty chapter, passed fail-closed). They cover the full
pass, skip-and-resume, the refused second request, per-sentence progress, retry
once then fail, retry then succeed, cancel after the sentence in flight, the disk
space check, the empty chapter, both analytics events with their outcome, count,
duration and voice kind, and the job being free again after a terminal state.
`PreparedChapterJobWiringTest` resolves the job from the real app graph; with the
`@Single` removed it fails with `NoDefinitionFoundException`, which was checked.

Green after step 4: reader Android 484/484, iOS 324/324, settings 22/22,
home 84/84, composeApp 63/63, analytics 77/77; zero failures/errors/skips.
Android `assembleDebug` succeeded. Not built yet in step 4: nothing the user can
see; the controller, the row and the Settings row are steps 5 and 6. The
notification-permission and voice-pack/terms gates live in the controller
(step 5), which is why `VOICE_UNUSABLE` and `NOTIFICATIONS_DENIED` exist as
request answers the core itself never returns.

### Step 3: prepared-first playback and synthesis priority

Production `TtsAudioGenerator` now resolves the prepared store and selected
encoder from Koin. Lookup order: prepared by the unchanged cache key, ordinary
sentence cache, synthesizer. Prepared hits return manifest duration and file,
including from valid partial chapters. `findCached` stays cache-only for words.
No `TtsReadAloudEngine` changes: the six-sentence phone probe already demonstrated
real WAV/M4A playback through its injected source and real ExoPlayer.

The single-sentence preparation operation skips an entry already in the target
chapter, copies a matching prepared entry from another chapter into this chapter
so every folder stays self-contained, or takes a cached WAV/synthesizes privately,
encodes, and adds it atomically. Skipping/copying does not mark the chapter used
for live playback. A newly synthesized private WAV is not a published cache hit
until encoding succeeds; failures clean private synthesis/encoding files and
publish nothing. Existing valid cache/prepared entries are preserved. An optional
ordinary-cache copy after success does not replace a live-generated entry.

`TtsSynthesisPriorityGate` owns one permit and separate FIFO live/background
queues. Release selects a live waiter before any background waiter; it never
interrupts native synthesis already running. With no live work, background
continues immediately. Cancelled requests remove themselves or hand the permit
on under NonCancellable cleanup. The generator's per-key mutex remains for
deduplication; encoding is outside it, so a live request is not held up by
background encoding. A private WAV prevents encode failure deleting audio
already handed to a live player (a test failed before this fix).

Tests: initial ten tests had seven intended failures. Real-generator priority
test drives the exact call order first preparation → live → queued preparation
using an injected test dispatcher (production uses IO). Gate tests cover twenty
queued background sentences, uninterrupted preparation order, and cancellation.
Further regressions failed first for independent chapter copies and live-file
safety during failed encoding. `PreparedGeneratorWiringTest` resolves the real
generator and its source binding with only Context/native synthesis boundaries
replaced. No bug-fix tests changed. Full verification green: reader Android
469/469, iOS 324/324, settings 22/22, home 84/84, composeApp 62/62,
analytics 75/75; zero failures/errors/skips. Step 3 complete at this commit.

Step 3 red checkpoint (after green Step 2 `0ed7f197`): ten new generator/
priority tests, seven intended failures before implementation. Prepared-first
lookup, preparation/cache reuse/skip/failure cleanup, and live overtaking queued
preparation fail. Existing key-setting misses, uninterrupted background order,
and permit cleanup characterize existing behavior and pass. No bug-fix tests
edited; `TtsReadAloudEngine` unchanged.

### Step 2: prepared store implementation checkpoint

`TtsPreparedStore` is Context-free with root and clock injected;
`TtsPreparedAudioStore` is the Android app-wide `@Single` supplying
`filesDir/tts-prepared`. No DB change. Real-graph `PreparedStoreWiringTest`
failed before the binding was added; only Android Context is replaced at the
host boundary (wrapper's store is lazy). Unit tests use actual temporary files.

Layout for the selected format:
`filesDir/tts-prepared/<sha256(server-id-length:server-id + book-id)>/<sha256(chapter-href)>/`.
Null server id uses length -1, distinct from an empty server id. Each folder
holds `manifest.json` and `<existing-sentence-cache-key>.m4a`. Keys are supplied
using `TtsAudioCacheStore.key`, unchanged (voice, model version, hundredth rate
and pitch, text). Neither folder/file names nor manifests store sentence text
or book/chapter titles. Book id, server id and chapter href are identifiers
required in the manifest; they never become path components directly.

Manifest version 2 fields (version 1 was the withdrawn WAV layout; it is rejected): `formatVersion` (required, even with default 1),
`bookId`, nullable `serverId`, `chapterHref`, nullable `voiceId`, nullable
`modelVersion`, `rate`, `pitch`, `createdTimeMs`, ordered `sentences` entries
(`key`, nullable `durationMs`, `bytes`), `complete`, `totalBytes`, nullable
`lastUsedTimeMs`. Planned entries have null duration and zero bytes, preserving
the ordered total for resume without text. Duplicate keys retain repeated
positions but refer to one file; totalBytes counts distinct audio payloads once.
Settings comparisons use the cache's hundredth precision. Ready state reports
audio payload bytes; store total and the 1 GiB enforcement include manifest bytes.

Operations: begin/resume matching settings and keys (a new setting/order
replaces the old variant), key-only lookup with measured duration and persisted
last-used time, state (not prepared, partial n/m, ready size, other settings
including the old voice/model/rate/pitch and partial counts), add via `.part`
and atomic rename, completion only after all entries exist, chapter/all deletion,
total size, oldest-created complete-chapter eviction. Active chapter, partials,
and chapters used in the last ten minutes are protected; the limit may remain
temporarily exceeded when every candidate is protected, rather than breaking
playback/resume. No UI state or strings are exposed yet.

Audio and manifests are both atomically replaced. Restart reconciles listed
file lengths and missing files, downgrades complete to partial if needed,
removes unlisted/staging files, and retains valid partials. Malformed, missing
version, unknown version, unsafe metadata or invalid manifest totals are
not prepared and cleaned. Paths hash hostile ids; symlinks are refused or
unlinked without following outside targets. Writes and store operations are
synchronized inside the app singleton. No checksum/fsync guarantee is claimed.

Tests first: 11/11 initial tests failed against the stub; a twelfth
missing-version regression subsequently failed before adding `@Required`.
Full verification green: reader Android 456/456, iOS 324/324, settings 22/22,
home 84/84, composeApp 61/61, analytics 75/75, zero failures/errors/skips.
Step 2 complete; step 3 not started at this commit.

Step 2 red checkpoint: 11 new `TtsPreparedStoreTest` cases all fail against
the unimplemented store. They cover ordered/resumable partials, other settings,
atomic failure, malformed/unknown manifests, orphan cleanup, missing/truncated
audio, hostile paths and symlinks, oldest-first eviction with protections,
deletion/size including manifests, and repeated keys/completion safety. Step 1
is committed green (`0b19d065`) before starting these tests.

**Current format decision: AAC (final, owner-revised 2026-10-10).** The earlier
50 ms duration gate, and the WAV fallback it selected, are withdrawn in the
brief. Prepared audio is AAC-LC, mono, about 48 kbit/s, at the WAV's sample rate,
in an `.m4a` file. The measured 87 to 127 ms per sentence (table below) is the
encoder's own silent padding and is accepted: the files are about seven times
smaller, which the later 200 MB-per-account cloud backup needs. The manifest
records the duration the encoder measured from the finished container
(`MediaExtractor`), never the PCM duration, so the chapter timeline stays right.
No padding trimming and no player change. A sentence that cannot be encoded
fails; there is no silent fallback to WAV inside a chapter, so a chapter is all
one format.

`AndroidTtsPreparedAacEncoder` is the production `@Single` binding of
`TtsPreparedAudioEncoder` (file `AndroidTtsPreparedAacEncoder.kt`); the WAV-copying
`AndroidTtsPreparedAudioEncoder` is deleted. Prepared files are
`<sentence-cache-key>.m4a` and the manifest format version is 2
(`PREPARED_AUDIO_EXTENSION`, `PREPARED_MANIFEST_VERSION`). The version bump makes
any version-1 chapter a previous build left behind read as "not prepared" and
cleaned up, rather than played or crashed on; there was none on the phone.

Format-switch tests, written first: three of four new `PreparedAudioFormatTest`
cases failed before the change (the `.m4a` name with the encoder-measured
duration, the version-1 WAV chapter being discarded, and the current manifest
version), and `PreparedAudioWiringTest` failed on the real graph because the
interface resolved to the WAV copier. The fourth case passed before and after:
the production encoder, given a valid WAV on a host with no MediaCodec, returns
`Failure`, publishes nothing and leaves the input untouched — the no-fallback
rule. Two existing tests were edited for the switch, both mechanically:
`TtsPreparedStoreTest` (prepared file names `.wav` → `.m4a`, and the stripped
required-version literal 1 → 2) and `PreparedAudioWiringTest` (the production
class name). Green after the switch: reader Android 472/472, iOS 324/324,
settings 22/22, home 84/84, composeApp 62/62, analytics 75/75; zero
failures/errors/skips.

### Step 1: Samsung measurements and format gate

Two six-sentence probes synthesized three short Gutenberg sentences with Heart
and a system voice, then played each WAV and AAC group through the unchanged
`TtsReadAloudEngine`, using its existing injectable source and real local
ExoPlayer. Both groups reached indices 0, 1, 2, without playback errors. The
second probe also tried a lossless remux setting encoder-delay (1,024 samples)
and calculated end-padding metadata. MediaMuxer did not preserve those keys;
MediaExtractor reported the same excessive durations afterwards. No spoken
packets were deleted to disguise the duration error. Following the brief, WAV
is selected rather than spending further time on native padding.

Second probe (PID 17556, Samsung SM-S921B, RFCWC0SSVDM):

| Sentence | Kind | WAV bytes | M4A bytes | WAV ms | M4A ms | Delta ms | After trim trial delta ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | Kokoro | 76,970 | 11,190 | 1,602 | 1,706 | +104 | +104 |
| 1 | Kokoro | 99,606 | 14,050 | 2,074 | 2,176 | +102 | +102 |
| 2 | Kokoro | 127,086 | 17,690 | 2,646 | 2,773 | +127 | +127 |
| 3 | System | 105,764 | 14,909 | 2,202 | 2,304 | +102 | +102 |
| 4 | System | 103,340 | 14,570 | 2,152 | 2,261 | +109 | +109 |
| 5 | System | 141,262 | 19,364 | 2,942 | 3,029 | +87 | +87 |

First probe (PID 16730) WAV/M4A bytes and delta: 76,968/11,190 +104 ms;
99,588/14,050 +103 ms; 126,978/17,430 +86 ms; system values identical to
the second probe. Neural synthesis varies slightly between runs. Both
probes complete with instrumentation code 0. Evidence logs contain only
measurement numbers and engine indices, no text or book/chapter titles.

**Audible joins: not assessed.** The agent has no audio-monitoring tool, and
does not infer perceived quality from successful playback. The objective
duration gate alone rejects AAC, so this missing subjective check does not
block the mandated WAV fallback. Selected WAV is byte-identical to the input,
so it adds no encoder padding or join change. All probe audio is removed after
each run. The tiny instrumentation-only APK remains installed because the
device rules prohibit uninstall; it has no launcher and no stored app data.

Probe setup obstacles were bounded: Application startup needed `waitForIdleSync`,
and router readiness can return for neural packs before system readiness, so
the probe awaits the system synthesizer explicitly. An early empty probe
folder was removed with exact `rmdir`; cleanup now runs before instrumentation
finish. No app/library data, settings, terms, account or network was changed.

Step 1 native-loop red checkpoint: `TtsPreparedAacPumpTest` adds three
tests for bounded PCM feeding, sample timestamps/EOS and measured muxed
duration, stall timeout/cleanup, and cancellation propagation/cleanup.
All three fail against the unimplemented pump; the initial test-only
ByteBuffer return-type compile error was corrected before the red run.
Native MediaCodec adapter and six-sentence measurement remain pending.

Native AAC adapter implementation now exists as `AndroidTtsPreparedAacEncoder`:
MediaCodec AAC-LC, mono, 48 kbit/s at the WAV sample rate, MediaMuxer M4A.
The host-tested pump feeds bounded PCM buffers, derives timestamps from sample
counts, queues a separate EOS, drains through output EOS, and bounds a stall
at ten seconds without progress. Cancellation propagates and releases the codec.
The adapter excludes codec-config buffers, starts the muxer on output format,
and measures the finished container with MediaExtractor (never substitutes the
WAV duration). The existing atomic core publishes only successful output and
cleans staging on failure. No cache WAV is changed. Native resources are released
on constructor failure, normal completion, encode failure and cancellation.

`PreparedAudioWiringTest` failed with `NoDefinitionFoundException` before the
`@Single` interface binding was added; it resolves the real app graph, not a
manually built encoder. No iOS source changed. Six-sentence phone gate pending;
no format decision or prepared storage/playback feature is claimed yet.

Native-adapter checkpoint verification: Android reader 443/443, iOS reader
324/324, settings 22/22, home 84/84, composeApp 60/60, analytics 75/75;
zero failures/errors/skips. Android assemble and iOS framework succeeded.
The dated evidence folder includes a standalone platform Instrumentation probe
and build script (no new libraries or app/Gradle configuration changes). It
can synthesize three short Gutenberg sentences with Heart and a system voice,
measure WAV/M4A and play each group through the unchanged read-aloud engine
with a file-serving source. It cannot make an audible-quality judgment; that
must remain explicit. Probe device execution is next.

Owner brief committed first as `542736b3`. The clean nested comparison
worktree was removed with `git worktree remove` after an empty `git status --short`.
All commands continue in the primary `tts-investigation` worktree.

Dead Play regression pinned before fixing: Listening-sheet Play dispatches
`StartListening` when `isListening` is false (`ReaderOverlay.kt:767–772`).
The extracted `ReaderListeningStart` initially preserves the silent
`ReaderViewModel.kt:2544` availability guard. Its setup-integrated regression
uses an ebook on an empty chapter with loaded voices and availability false:
expected exactly one controller request, actual zero. Focused run: 4/5 pass,
one intended assertion failure. Recorded narration and an unconfigured book
are pinned separately; no existing test was edited. Red checkpoint: `8fe3cfdc`.
The fix allows the request when page availability is false but voices have
loaded. Availability itself still waits for readable text; the controller owns
empty-chapter skipping and playback outcomes. Six-module verification is green:
reader Android 440/440, iOS 324/324, settings 22/22, home 84/84,
composeApp 59/59, analytics 75/75; zero failures/errors/skips from XML.
Phone baseline rechecked: the empty opening page offers System voice at 1×,
Play produces no operation. Both Android assemble and iOS framework succeeded.
Fixed-build Samsung retest (`071d8867` code): Play moved off Page 1 of 1
into the first chapter with text, showed Pause and Sentence 2 of 397. One
attempted and one succeeded operation, 1,327 ms, one correlated start/terminal
breadcrumb. Playback paused after observation. Evidence is in the dated folder
(`dead-play-fixed-logcat.txt`). The cause matches the proposed guard.

## Previous run status (historical; superseded by continuation above)

Branch `tts/prepared-chapters`, based on `e8ed4319`. The supplied worktree was
detached at that commit. All work and Gradle commands run from that worktree;
no configuration files are created or edited.

Step 0 comparison is complete on Samsung `RFCWC0SSVDM` (SM-S921B). The dead
Listening-sheet Play reproduces for Pride and Prejudice via Continue reading
and Library → Read, on both the baseline APK built at `e8ed4319` and the APK
built at `7045b62b`. Each shows Page 1 of 1, Play remains Play, and no
`tts_playback_operation` or `start_tts_playback` appears in a three-second
app-process log window after pressing Play. Alice's Adventures in Wonderland
plays on both APKs: Page 9 of 14 on baseline, Page 8 of 14 on the old build;
Play becomes Pause, with attempted/succeeded operations (684 ms / 463 ms).

The failure predates run 5b. No lease reversal or product fix is made. Following
the explicit Step 0 instruction to proceed when it is older, Step 1 may start;
the feature is not claimed to fix this pre-existing empty-opening-page problem.
Root cause is not established. A relevant silent guard is
`ReaderViewModel.kt:2544` (`isTtsReadAloud` false); `ReaderTtsSetup.kt:68–72`
only marks availability after readable content. Those are code leads, not
device-confirmed state values. The scope lease cannot explain introduction of
the failure because the old build does not have it. Five tries were not needed:
the failure reproduced and the specified old-build comparison was made.

The old APK was built in a nested detached comparison worktree at
`.claude/worktrees/prepared-chapters-before-5b`; commands were invoked from the
primary worktree using Gradle `-p`, with ANDROID_HOME and no local.properties.
Both APKs were installed with `adb -s RFCWC0SSVDM install -r`; baseline restored
after comparison. No other device was targeted or Supertonic terms accepted.

## Previous run baseline tests

The requested six-module verification command succeeded (existing test tasks
up to date). Counts read from `build/test-results` XML, not console summaries:
reader Android 423/423; reader iOS 319/319; settings 22/22; home 84/84;
composeApp 59/59; analytics 75/75. No bug-fix-run test has been edited.

## Previous run: what was built (historical)

Step 1 (partial): added the small Android-source-set `TtsPreparedAudioEncoder`
interface, a success/failure result type, PCM WAV reader and encode core.
`TtsPreparedAudioEncoderTest` specifies mono PCM16 WAV parsing (including RIFF
chunks and padding), rejection of unsupported/truncated input, atomic output,
failure/cancellation cleanup, measured duration and source/output preservation.
The focused red run failed as intended: 12 tests, four failures (valid mono
PCM, odd chunk padding, successful publication, cancellation propagation).
The other rejection/safety cases passed against the fail-closed stub. The
implementation then replaced that stub without editing the tests. Six-module
verification is green: reader Android 435/435 (423 existing + 12 new), reader
iOS 319/319 (before 319/319), settings 22/22, home 84/84, composeApp 59/59,
analytics 75/75. Counts are from result XML; zero failures/errors/skips. The
existing bug-fix tests are unedited. Both baseline and post-seam final Android
APK/iOS framework builds succeeded. Phone left on the restored baseline APK,
System voice, rate 1×, Library; `files/tts-prepared` does not exist (run-as
checked). No prepared audio was created, so none needed deleting.

The core invokes an injected suspend encoding function, giving it a unique
same-directory `.part` file. Only a nonempty result with positive measured
duration is atomically renamed into the requested output. An exception or
cancellation deletes the staging file; cancellation is propagated. It rejects
an existing destination and a destination aliasing the source. It never edits
the input cache WAV. The parser reads RIFF chunk headers without allocating
from their untrusted lengths, supports mono PCM16, and validates bounds,
alignment and byte rate. Temporary names contain neither text nor titles.

No platform MediaCodec/MediaMuxer adapter or Koin binding has been built yet.
No caller uses this seam. AAC-LC mono 48 kbit/s in M4A remains a candidate, not
the chosen format: six real-sentence measurements and an acoustic join
comparison on the Samsung are mandatory before any storage/playback feature
is built on it. This run stops at the tested host seam; Step 1 is not complete.
Do not infer compression quality or gapless playback from the fake-encoder
tests. They test publication/cleanup and parsing, not the native codec.
No prepared store, manifest, UI state, screen text, resource, foreground service
or analytics event has been added. No encoding measurements have been made.

## Previous run: storage and manifest plan (implemented in Step 2 above)

Intended root: `filesDir/tts-prepared`, outside the 128 MB sentence cache.
One chapter folder under each book, with `manifest.json` and audio files named
by the existing sentence-cache key. Paths must be safe for hostile ids/hrefs.
Manifest fields: format version, book id, server id, chapter href, voice id,
model version, rate, pitch, created time, ordered sentence keys with durations
and byte sizes, complete flag, total bytes. No sentence text or titles.
The 1 GB limit evicts oldest complete chapters, excluding the active chapter
and chapters used within ten minutes. Partial chapters survive for resume.

## Button states and text (required, not implemented)

Not prepared; preparing this chapter; preparing another chapter; prepared;
prepared for other settings; partly prepared; failed; voice not usable.
No new screen strings or resource keys exist in this run yet.

## Previous run: not done (historical)

Step 1 is partial; Steps 2–8 remain unbuilt. The platform codec and its six
sentence checkpoint are the next work, before prepared storage or playback.
No cloud, server, database migration, library,
cache-key change, sentence-cache compression, automatic or whole-book
preparation, iPhone TTS, TTS-F15 or TTS-F25 changes.

## Current handoff after Step 3

Steps 1–3 are built and committed green in order. Steps 4–8 are not built:
no app-wide chapter job/state flow, foreground service, notification/Voices
gates, preparation analytics, controller/screen wiring, row or strings,
Settings total/Delete all row, UI render tests, or full prepared-chapter device
journey. This is not an end-user-ready feature. Continue at Step 4 with tests
first for the pure job, then the foreground service and fail-closed analytics
sanitizer. Do not skip ahead to reader/UI while Step 4 is uncommitted/non-green.

Existing seams ready for that run: `TtsPreparedAudioStore.store.begin(id,
settings, orderedKeys)`, per-chapter `lookup(id,key)` without playback touch,
`TtsAudioGenerator.prepareSentence(...)`, store `markComplete`, `enforceLimit`
and deletion. Generate keys with the existing cache key/model version and
effective rate. Freeze those settings and loaded sentence order for the job.
The manifest holds ids and keys, not sentence text; the running job must hold
texts in memory and receive them again to resume after app kill. Current encoder binding is AAC (`.m4a`). Every new `@Single` needs a real-graph resolution test.
Cloud packaging guidance above reflects the implemented store, but Step 7's
final UI/strings/state/limits documentation remains unfinished until the feature
exists. No previously built bug-fix test was changed or weakened.

Final verification and restoration: the six-module counts above remain green;
Android assemble and iOS framework succeeded on Step 3 source `9478844e`.
That APK was installed with `install -r` on the Samsung. Normal System playback
still starts and shows Pause with one attempted and one succeeded operation
(889 ms, PID 23332). This is a regression smoke, not a Step 8 prepared-chapter
check. Fresh sheet showed System voice at 1×; Stop listening and reader Back
returned to Library. `run-as` confirmed both probe folder and `files/tts-prepared`
absent. Only the authored temporary UI dump was removed; no app data was cleared.
Tiny instrumentation APK remains installed under the no-uninstall rule.

This run ends for execution-time budget after complete, committed green Steps
1–3, rather than starting an unfinished Step 4. The complete end-of-step report
is `docs/tts-prepared-chapters-report.txt`. No full preparation UI/device journey
is claimed; acoustic comparison remains explicitly unassessed.

## For the cloud backup run

Host-testable folders now exist; the app does not yet expose preparation UI/jobs.
Package one hashed chapter folder, its version-1 `manifest.json`, and exactly the
distinct `.m4a` files for entries with nonnull duration. Preserve ordered entry
positions (duplicate keys share one audio file), voice/model/rate/pitch, identifiers
and complete/partial status. Missing entries have null duration and zero bytes.
Never package `.part` files, cache synthesis/encode staging files, or the ordinary
sentence cache. Run store reconciliation before packaging, and do not package
while a writer is active. Cloud restore must validate version, keys, file lengths,
identifiers and paths and rebuild hashed folders safely; cloud conflict/account
isolation policy is not implemented. The manifest contains required ids/href but
no text/title fields; do not add book metadata to telemetry. Model version and
sentence keys remain unchanged. Payload format is AAC-LC mono in `.m4a` at manifest
format version 2; each file carries 87 to 127 ms of encoder padding and the
manifest duration is the container's measured duration. No upload/download/cloud code exists in this feature.

## Previous run: resume instructions (superseded by continuation checkpoint)

Keep the existing bug-fix tests unchanged. Finish the native adapter behind
`TtsPreparedAudioEncoder`, then run the six-sentence checkpoint (three real
Kokoro and three real system-voice WAVs, encoded and played through the existing
read-aloud engine). Record source/encoded byte sizes, each reported duration
delta in milliseconds, and an actual audible WAV/AAC join comparison. Try
padding trimming if needed; select WAV if AAC cannot meet the 50 ms/join gate.
Only then add the prepared store, with tests first and this document current.
The nested comparison worktree contains no authored changes and is retained,
not deleted. It is not the feature branch's working directory.
