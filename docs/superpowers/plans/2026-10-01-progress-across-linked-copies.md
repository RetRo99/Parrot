# Project 3: Progress Across Linked Copies — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan slice by slice. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Your place follows you between linked copies of a book. Open any copy and the app
offers to continue from where you last read or listened in any other copy. When the translation
is reliable, it also updates the other copies' own servers, so Storyteller's and
Audiobookshelf's apps resume in the right place too.

**Architecture:** A *translator* converts a position in one copy into a position in another,
and labels the result with a confidence level: exact, high or approximate. It uses:
- the same file (exact),
- a short text anchor saved with each ebook position (high),
- Storyteller read-aloud timing data, SMIL (high),
- or percentages (approximate).

The translator is used in two places. On open, it powers a "continue from…?" prompt. After a
reading session, it pushes reliable positions to the linked copies through each server's existing
progress sync.

**Tech Stack:** Kotlin Multiplatform, SQLDelight, Koin, xmlutil (already used for SMIL), and
platform EPUB access (Android and iOS, following `EpubMetadataExtractor`).

**Spec:** Part 1 of this document.

**Depends on:** project 1 (one book ID, `LibraryBook`) and project 2 (links, `CopyKey`,
`ResolveCopyUseCase`) being merged.

**Level of detail:** the decisions, rules, algorithms and interfaces are final. File paths for code
changed by projects 1 and 2 may have moved, so check them with `grep`. Write each task's listed
tests before its implementation.

## Global Constraints

- Everything in project 1's "Global Constraints" still applies: minimal functional UI (the user
  designs later), one slice per PR with a working app each time, never commit/push without
  asking, Kotlin style, strings rules, the repo's test style, and honest reporting.
- **Never move the reader silently** to a position translated from another copy. Always ask
  (§1.3).
- **Never "furthest wins".** The latest *real* reading wins (P1).
- **Only exact or high confidence** may be written to another copy's server (P5).
- **No transcription or ML on the device** (no Whisper). Where timing data is missing, the result
  is approximate.

---

# Part 1 — Design

## 1.1 Why

Linking (project 2) tells the app which copies are the same book. Readers expect more than that:

> "I listened in the car on Audiobookshelf. When I open the ebook in Parrot Cloud, it should
> offer the same place."

BookBridge (`cporcellijr/bookbridge`) does this on a server using transcripts. This app can do a
reliable subset on the device, using text anchors and Storyteller's own read-aloud timing.

## 1.2 Decisions

| # | Decision | Why |
|---|---|---|
| P1 | The latest **real** reading wins, compared by observation time. "Real" means saved by the user reading or listening (origin `user`), not by restoring, applying a remote position, or propagating (§1.4). | Furthest-wins drags people forward from stale devices. BookBridge had to add a workaround, and this repo's progress-sync plan already says "Never resolve by maximum percentage". |
| P2 | Moving between copies always goes through a prompt when the book opens. | A wrong jump loses the reader's place. A prompt costs one tap. |
| P3 | Confidence is **Exact** (same file content), **High** (an anchor found uniquely, or SMIL timing) or **Approximate** (percentage). Prompts say "about" for approximate results. | It's honest about quality. |
| P4 | Every local ebook position stores a **text anchor**: about 20 words before and 30 words after the position start. It's stored locally only. For positions pulled from servers (which have no anchor), extract the anchor from the source file if it's on this device. Otherwise the result is approximate. | Anchors let two different files of the same text be matched reliably. Servers won't store the anchor. |
| P5 | **Propagation:** when a reading session ends, translate the final position into every other resolved linked copy. If the result is exact or high, and newer than that copy's own latest real position, save it as that copy's local position with origin `linked_copy`. That copy's normal sync pushes it to its server. Positions with origin `linked_copy` never propagate further. | Other apps (Storyteller, Audiobookshelf, KOReader through their servers) resume correctly, with no loops. |
| P6 | **Audio and text:** (a) inside a Storyteller read-aloud, SMIL maps text fragments to audio times: High. (b) An Audiobookshelf audiobook and a linked read-aloud map directly on global audio time when their total durations match within 1%: High. Otherwise map proportionally within a chapter when chapter counts match, or overall: Approximate. (c) An audiobook and a plain ebook with no read-aloud linked: proportional, Approximate. | Uses only timing data that already exists. |
| P7 | EPUB text reading and SMIL parsing move into a new module, `lib/epub` (`api` + `implementation`). The reader UI keeps using it. | Translation runs outside the reader screen. Today `SmilParser` lives in `feature/reader/ui`. |
| P8 | The card's progress shows the latest real position across copies: that copy's `totalProgression`. | The card agrees with the prompt. |

## 1.3 The resume prompt

When copy **T** of a linked book opens (in the reader open flow, where
`ResolvePositionConflictUseCase` and `PositionConflictDialog` run today):

1. **Gather candidates:**
   - T's local and remote positions, as today.
   - Every other resolved linked copy's latest local position.
   - For linked server copies, `getRemotePosition`, limited to 2 seconds in total. Skip any that
     fail.
2. **Pick the newest real candidate.**
   - If it belongs to T, the existing local/remote behaviour applies.
   - If it belongs to another copy **S**, translate it from S to T, and show the prompt only when:
     - it's more than 60 seconds newer than T's newest real position, **and**
     - the translated position differs from T's current one: a different chapter, or a
       progression difference of more than 1%.
3. **Show one prompt**, replacing today's conflict dialog for this opening. There's never more
   than one:
   - Exact or high: "Continue from %1$s?", with the body "You read there in %2$s %3$s." Here
     `%1$s` is the chapter title, or "this place"; `%2$s` is the copy's source, e.g. "Storyteller
     (read-aloud)"; and `%3$s` is relative time, e.g. "2 hours ago".
   - Approximate: "Continue from about %1$d%%?", with the same body.
   - Buttons: **Continue** jumps and saves the position as T's local position with origin `user`.
     **Stay here** keeps T's position and records a dismissal for that source position, so it
     isn't asked again.

## 1.4 Position origin and anchor

Add to the local `position` table:
- `origin TEXT NOT NULL DEFAULT 'user'`, which is one of `user`, `restore`, `remote`,
  `linked_copy`.
- `observed_at TEXT`: when the reading happened, which is not necessarily when it was saved.
- `text_anchor TEXT`: JSON `{"before": "...", "after": "..."}` for ebook positions, null for audio.

The reader captures the anchor when it saves a position:
- It runs a small script in the current chapter, alongside the TTS sentence script in
  `ChapterSentenceExtractor`, that returns the text before and after the locator's start.
- On Android use Readium's locator text (`Locator.text.before`, `highlight` and `after`) when it's
  present, and the script otherwise.

## 1.5 Translation algorithm (final)

**Input:** a source position (copy S, kind, locator or audio time, anchor if any) and a target copy
T (its file on this device, or its metadata only).

Try these strategies in order, and use the first that succeeds:

1. **Same file (Exact):** S and T are ebooks with an equal content hash, from `device_files` or
   Parrot file states. Copy the locator unchanged.
2. **Text anchor (High):** T is an ebook whose file is on this device.
   - The anchor is S's stored anchor. If there isn't one, extract it from S's file at the source
     locator; if S's file isn't on this device, skip this strategy.
   - Normalize both texts: fold accents, lowercase, straighten quotes, remove soft hyphens and
     zero-width characters, and collapse whitespace.
   - Search T's spine items in reading order for `after`, using its first 200 normalized
     characters.
   - **One exact hit:** High.
   - **Several exact hits:** keep the hit whose preceding text best matches `before`. The result
     is High only if its similarity is at least 90 and at least 10 points ahead of the next hit.
     Otherwise fall through to the next strategy.
   - **No exact hit:** slide a window over the spine items that fall within ±10% of the
     proportional estimate. The best Levenshtein similarity on 60 characters must be at least 92
     for High. Otherwise fall through.
   - **Output:** the target spine `href`, the progression inside the chapter (character offset
     divided by chapter text length), the `totalProgression` recalculated from spine lengths, and
     a `cssSelector` when the offset falls inside an element with an `id`.
3. **SMIL bridge (High):** one side is audio (an audiobook, or the audio mode of a read-aloud)
   and a linked **read-aloud** copy with SMIL is on this device.
   - **Text to audio:** find the text position as in strategy 2 inside the read-aloud, take the
     SMIL clip covering it, and use `clipBegin` plus that audio file's global offset.
   - **Audio to text:** do the reverse.
   - **An Audiobookshelf audiobook against a read-aloud:** only when the total durations match
     within 1%, as in P6(b).
4. **Proportional (Approximate):** use `totalProgression`, or audio time divided by total
   duration. When both copies have the same number of chapters, use the chapter plus the
   progression inside the chapter.

**Output:**

```kotlin
data class TranslatedPosition(
    val target: CopyKey,
    // The reader domain's own position model (the one SaveReadingProgressUseCase takes):
    // href/progression/totalProgression/cssSelector for ebooks, audio ms for audio.
    val position: PositionDomainModel,
    val kind: ProgressKind,
    val confidence: TranslationConfidence, // Exact, High, Approximate
    val strategy: TranslationStrategy,     // SameFile, TextAnchor, SmilBridge, Proportional
)
```

Cache results per (source position ID, source `observed_at`, target key).

## 1.6 Wording (new keys at the bottom of `strings.xml`)

| Key | Value |
|---|---|
| `resume_linked_title_exact` | Continue from %1$s? |
| `resume_linked_title_approximate` | Continue from about %1$d%%? |
| `resume_linked_body` | You read there in %1$s %2$s. |
| `resume_linked_listened_body` | You listened there in %1$s %2$s. |
| `resume_linked_this_place` | this place |
| `resume_linked_continue` | Continue |
| `resume_linked_stay` | Stay here |
| `resume_linked_source_readaloud` | %1$s (read-aloud) |
| `resume_linked_source_audiobook` | %1$s (audiobook) |
| `resume_linked_source_ebook` | %1$s (ebook) |

Build `%2$s` in `resume_linked_body` with the platform's relative-time formatting, the same way
the app formats "last opened" today. Search for it; if there's no helper yet, add one to
`base-ui`.

---

# Part 2 — Slices

| Slice | PR | The app afterwards |
|---|---|---|
| 1 | Position origin, observation time and text anchor | Positions record where they came from and a text anchor. No visible change. |
| 2 | `lib/epub` and the translator | Translation works in tests on fixture EPUBs. The reader still works on the moved SMIL code. |
| 3 | Resume prompt | Opening a linked copy offers to continue from the latest other copy |
| 4 | Propagation to linked copies | Reading in Parrot updates Storyteller's position, and the reverse, when the result is reliable |
| 5 | Audiobook ↔ read-aloud timing | Audiobookshelf audio and a Storyteller read-aloud stay in step (P6b) |

## Slice 1 — Position origin, observation time and text anchor

### Task 1.1: Schema and model

**Files:**
- Create: `lib/database/implementation/.../sqldelight/.../29.sqm` (after project 2's 28), and
  update `Position.sq` to match: `origin`, `observed_at` and `text_anchor`.
- Modify: `lib/server/api/.../ServerPosition` to add `origin: PositionOrigin = PositionOrigin.User`,
  `observedAt: String? = null` and `textAnchor: TextAnchor? = null`. Map these through
  `PositionEntity`, the reader's local models and `SaveReadingProgressUseCase`.
- Modify: every writer of positions, to set the origin:
  - the reader saving progress: `user`,
  - `ResolvePositionConflictUseCase.useRemote` and remote applies in `ProgressSyncEngine`:
    `remote`,
  - restoring after a download (the project 1 finalizer path): `restore`.
- **Tests:**
  - A migration test (27/28 → 29) where existing rows get `origin = 'user'`.
  - A table test that each writer stamps the right origin.
  - Server transports ignore `origin` and `text_anchor` when encoding. Assert that the Storyteller,
    Audiobookshelf and Parrot payloads don't change.

### Task 1.2: Capture the anchor in the reader

**Files:**
- Modify: `feature/reader/ui/src/commonMain/.../navigator/ChapterSentenceExtractor.kt`. Add a
  script, or reuse the sentence script, that returns `{before, after}` at the current locator.
- Modify: the Android and iOS reader controllers that save positions (search for
  `SaveReadingProgressUseCase` callers), so they attach `textAnchor`. On Android, prefer Readium's
  `Locator.text`.
- **Tests:** a unit test for the result parser: JSON to `TextAnchor`, with trimming to 20 and 30
  words. A device check: read a page, then inspect the stored `text_anchor` via a debug log or
  the database, and report what it contains.

## Slice 2 — `lib/epub` and the translator

### Task 2.1: Create `lib/epub` and move SMIL there

**Files:**
- Create the modules `lib/epub/api` and `lib/epub/implementation`, copying the build setup of
  `lib/preferences/api` and `lib/preferences/implementation`. Include them in
  `settings.gradle.kts`.
- Move: `feature/reader/ui/src/commonMain/.../media/smil/SmilParser.kt`, `SmilClockParser.kt` and
  the `SmilClip` model into `lib/epub/implementation` (and `api` for the models). Update the reader
  imports. Keep the platform content providers (`PublicationSmilContentProvider`,
  `SmilParserProvider`) in the reader.
- Create: in `lib/epub/api`:

```kotlin
interface EpubTextReader {
    /** Reading-order chapters with their plain text; null when the file can't be read. */
    suspend fun readChapters(filePath: String): AppResult<List<EpubChapterText>>
}

data class EpubChapterText(
    val href: String,
    val title: String?,
    val text: String,                  // plain text, block elements separated by '\n'
    val elementOffsets: List<ElementOffset>, // (elementId, startOffset) for elements with ids
)

data class ElementOffset(val elementId: String, val startOffset: Int)

interface ReadaloudTimingReader {
    /** SMIL clips in reading order, with each audio file's global start offset. */
    suspend fun readTiming(filePath: String): AppResult<ReadaloudTiming>
}

data class ReadaloudTiming(
    val clips: List<TimedClip>,        // textHref#fragment, audioSrc, clipBegin, clipEnd
    val audioFileOffsetsMs: Map<String, Long>,
    val totalDurationMs: Long,
)
```

- Create: Android and iOS implementations, following `AndroidEpubMetadataExtractor` and
  `IosEpubMetadataExtractor` for zip access, and reusing the moved `SmilParser` for clips.
- **Tests:** build two tiny fixture EPUBs in `lib/epub/implementation/src/androidHostTest/
  resources/`, using a small script in the test setup to zip them:
  - **`plain.epub`:** 3 chapters of known text.
  - **`readaloud.epub`:** the same text split into 4 chapters with different markup, plus SMIL
    files and 2 short silent audio references. The audio files can be empty, because the timing
    comes from SMIL.

  Assert the chapter order, the text normalization, the element offsets and the SMIL global
  offsets. Run the existing reader tests too, to prove the move didn't break read-aloud playback.

### Task 2.2: The translator (pure core)

**Files:**
- Create: `feature/reader/domain/.../translate/CopyPositionTranslator.kt`, `TextAnchorMatcher.kt`,
  `ProportionalMapper.kt`, `SmilBridge.kt` and `TranslatedPosition.kt`, following §1.5.
- Create: `feature/reader/domain/.../usecase/TranslatePositionUseCase.kt`. It resolves the copies'
  files (project 1's `device_files`, and the Storyteller/Audiobookshelf download cache), loads
  text and timing through `lib/epub`, and calls the translator.
- **Tests** (pure; feed chapter text and timing directly, with no files):
  - Same hash gives Exact, with the locator unchanged.
  - The anchor is found once, giving High, with the right href and progression within 0.5%.
  - Duplicate `after` text disambiguated by `before` gives High. If it can't be disambiguated,
    the result falls through to Approximate.
  - One changed word in the target's text still gives High (fuzzy at 92 or more).
  - An anchor missing from the target gives Approximate (proportional).
  - Text to audio through SMIL gives High, and the time is inside the covering clip.
  - Audio to text through SMIL gives High.
  - Durations within 1% give a direct mapping, High. Durations 3% apart give Approximate.
  - The cache returns the same object for the same key, and a changed `observed_at` misses the
    cache.

## Slice 3 — Resume prompt

### Task 3.1: Choose and translate on open

**Files:**
- Create: `feature/reader/domain/.../usecase/FindLinkedResumeUseCase.kt`. Implement §1.3 steps 1–2.
  It returns `LinkedResumeOffer(source: CopyKey, sourceKind, observedAt, translated:
  TranslatedPosition)` or null.
- Modify: the reader open flow, where `ResolvePositionConflictUseCase` is invoked today, to call
  this first. When it returns an offer, show the new prompt instead of the conflict dialog.
- Modify: the reader UI, reusing `PositionConflictDialog`'s component with the §1.6 strings. Don't
  make a new design.
- **"Stay here"** dismissals: store them in user preferences under a new
  `PreferencesKey.DismissedLinkedResume`. The value is a list of
  `"<targetKey>|<sourceKey>|<source observedAt>"` strings, keeping the newest 200. An offer whose
  string is in the list isn't shown.
- **Imports:** `ProgressKind` comes from `feature/sync/domain`. If `feature/reader/domain` doesn't
  depend on it, define a reader-domain `PositionKind { Ebook, Audio }` instead. Don't add a
  dependency from domain to data.
- **Tests:**
  - A candidate from another copy that's newer by more than 60 seconds and at a different place
    gives an offer.
  - Newer by less than 60 seconds gives no offer.
  - The same place gives no offer.
  - A candidate with origin `linked_copy` or `remote` is ignored as the "real" reading.
  - A remote fetch timeout still returns the local-based result.
  - After "Stay here", the same source position doesn't offer again.

### Slice 3 device check

1. A Parrot ebook linked to a Storyteller read-aloud: read to chapter 5 in the Storyteller copy,
   then open the Parrot copy. Expect the prompt "Continue from Chapter 5?"; Continue lands on the
   same paragraph.
2. The same with a plain Audiobookshelf ebook of different markup: expect the prompt and the
   right paragraph (High).
3. An Audiobookshelf audiobook without a read-aloud: expect "Continue from about N%?".

## Slice 4 — Propagation to linked copies

### Task 4.1: Propagate when a session ends

**Files:**
- Create: `feature/reader/domain/.../usecase/PropagateToLinkedCopiesUseCase.kt`. Call it where
  reading sessions end today (search for the reading-session recording), and when the player
  pauses.
- For each resolved linked copy other than the source:
  1. Translate the position.
  2. Skip unless the result is Exact or High.
  3. Skip if the target's latest real position is newer.
  4. Otherwise save the translated position as the target's local position with origin
     `linked_copy` and `observedAt` equal to the source's.
- The target's existing sync (Storyteller, Audiobookshelf or Parrot adapter) pushes it. Don't
  write to servers directly.
- **Loop guard:** positions with origin `linked_copy` never trigger propagation, and remote applies
  of them don't either.
- **Tests:**
  - A High translation saves a target position with origin `linked_copy`, and an outbox entry
    appears for the target's server.
  - An Approximate translation saves nothing.
  - A target that was read more recently is not overwritten.
  - A saved `linked_copy` position doesn't cause another propagation.

### Slice 4 device check

Read in the Parrot copy, close the book, sync, then open the Storyteller web app or the
Storyteller mobile app. Expect it to resume at the same paragraph. Report what you observed. If
you have no Storyteller app, check the server's progress API response for the book instead.

## Slice 5 — Audiobook ↔ read-aloud timing (P6b)

**Files:** extend `SmilBridge` and `TranslatePositionUseCase`:
- Read the Audiobookshelf total duration from the cached item (`duration`, `audioFiles`).
- Compare it with the read-aloud's `ReadaloudTiming.totalDurationMs`.
- Within 1%, map global times directly (High). Otherwise use the §1.5 proportional fallback.

**Tests:** 0.5% apart maps directly; 3% apart is proportional; equal chapter counts use the chapter
plus the progression inside it.

**Device check:** listen for 10 minutes in Audiobookshelf, then open the linked Storyteller
read-aloud. Expect the prompt, and the text highlight within a sentence or two of the spoken
position.

---

# Part 3 — Risks and notes

- **Different editions** (abridged and unabridged, or different translations): the anchor isn't
  found, so the result drops to Approximate. That's shown honestly and never propagated.
- **Performance:** translation reads whole EPUB text. Cache the chapter text per file hash,
  limited to about 20 MB, and run everything off the main thread. The prompt waits at most 2
  seconds for remote positions, then uses what it has.
- **Servers rounding positions:** Storyteller and Audiobookshelf may store less detail than the
  app. The "different place" threshold (§1.3) avoids ping-pong prompts caused by rounding.
- **Privacy:** anchors are short excerpts of the user's own book, stored only on the device and
  never sent anywhere.
- **Later, not now:**
  - server-side transcripts, such as Storyteller forced-alignment assets, for audiobooks without
    a read-aloud,
  - KOReader sync (KOSync) as another link target, as BookBridge does.
