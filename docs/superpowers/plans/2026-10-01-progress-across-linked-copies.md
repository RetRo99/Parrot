# Project 3: Progress Across Linked Copies — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan slice by slice. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Your place follows you between linked copies of a book. Open any copy and the app
offers to continue from where you last read or listened in any other copy. When the translation
is reliable, it also updates the other copies' own servers, so Storyteller's and
Audiobookshelf's apps resume in the right place too. When copies disagree, a **positions panel**
shows where every copy is, and the person picks the right one and applies it to whichever other
copies they choose.

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

**Depends on:**
- Project 1 (one book ID, `LibraryBook`) and project 2 (links, `CopyKey`, `ResolveCopyUseCase`)
  being merged.
- Before slices 3b and 4, which write to other servers, two bug fixes:
  - `2026-10-01-storyteller-progress-conflict-fix.md`: today an HTTP 409 from Storyteller is
    retried forever.
  - `2026-10-01-audiobookshelf-ebook-location-fix.md`: until it lands, ebook positions are never
    written to Audiobookshelf (guard 11).

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
| P1 | The latest **real** reading wins, compared by observation time. "Real" means reading or listening by the person: on this device (origin `user`), or pulled from a server (origin `remote`) **unless it's an echo of our own write** (§1.4). Restores, propagated positions and echoes are not real. | Furthest-wins drags people forward from stale devices. BookBridge had to add a workaround, and this repo's progress-sync plan already says "Never resolve by maximum percentage". Reading done in another app, such as Storyteller's on another device, must count. |
| P2 | Moving between copies always goes through a prompt when the book opens. | A wrong jump loses the reader's place. A prompt costs one tap. |
| P3 | Confidence is **Exact** (same file content), **High** (an anchor found uniquely, or SMIL timing) or **Approximate** (percentage). Prompts say "about" for approximate results. | It's honest about quality. |
| P4 | Every local ebook position stores a **text anchor**: about 20 words before and 30 words after the position start. It's stored locally only. For positions pulled from servers (which have no anchor), extract the anchor from the source file if it's on this device. Otherwise the result is approximate. | Anchors let two different files of the same text be matched reliably. Servers won't store the anchor. |
| P5 | **Propagation:** when a reading session ends on this device, translate the final position into every other resolved linked copy. If the result is exact or high, passes the write guards (§1.4), and is newer than that copy's own latest real position, save it as that copy's local position with origin `linked_copy`, and record it in the write log. That copy's normal sync pushes it to its server. Only origin `user` positions propagate. | Other apps (Storyteller, Audiobookshelf, KOReader through their servers) resume correctly, with no loops. The guards are BookBridge's, which have been tested in practice. |
| P6 | **Audio and text:** (a) inside a Storyteller read-aloud, SMIL maps text fragments to audio times: High. (b) An Audiobookshelf audiobook and a linked read-aloud map directly on global audio time when their total durations match within 1%: High. Otherwise map proportionally within a chapter when chapter counts match, or overall: Approximate. (c) An audiobook and a plain ebook with no read-aloud linked: proportional, Approximate. | Uses only timing data that already exists. |
| P7 | EPUB text reading and SMIL parsing move into a new module, `lib/epub` (`api` + `implementation`). The reader UI keeps using it. | Translation runs outside the reader screen. Today `SmilParser` lives in `feature/reader/ui`. |
| P8 | The card's progress shows the latest real position across copies: that copy's `totalProgression`. | The card agrees with the prompt. |
| P9 | **Positions panel:** for a linked book, the person can see every copy's current position, pick the one that's right, and apply it to any of the other copies (each target ticked separately). The result is saved with origin `manual`, which counts as real reading at the moment it's applied, and pushed to each server through its normal sync. | The person is the judge when copies disagree. This is the user-controlled version of BookBridge's automatic leader selection (requested by the user on 2026-10-01). |

## 1.3 The resume prompt

When copy **T** of a linked book opens. Today there are **two** places that prompt about
positions, and both must use the same logic:
- **Book detail, before opening:** `BookDetailViewModel` uses `ResolvePositionConflictUseCase`, and
  `BookDetailScreen` shows the books-ui `PositionConflictDialog`.
- **The reader, when it starts:** `ReaderViewModel`, around line 611, derives `positionConflict`
  from `progressResult`, and `ReaderScreen` (around line 273) shows the reader-ui
  `PositionConflictDialog`. Every entry point passes through this one: book detail, "Continue
  reading" on Home, and others.

The steps:

1. **Gather candidates:**
   - T's local and remote positions, as today.
   - Every other resolved linked copy's latest local position.
   - For linked server copies, `getRemotePosition`, limited to 2 seconds in total. Skip any that
     fail. Classify each fetched position with the same echo check as `applyRemote` (§1.4,
     guard 2): an echo of our own write is not a real reading. Use one shared function for both,
     so the logic can't drift.
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
   - Buttons:
     - **Continue** jumps there and saves the position as T's local position with origin `user`.
     - **Stay here** keeps T's position and records a dismissal for that source position, so it
       isn't asked again.
     - **Compare all** opens the positions panel (§1.3b).

## 1.3b The positions panel (P9)

**Entry point:** a "Reading positions" action on the detail screen of a linked book. The resume
prompt (§1.3) also gets a third button, **Compare all**, that opens the panel.

**Opening the panel:**
- It fetches the latest position of every server copy, allowing 2 seconds per server. Copies
  whose fetch failed show their last known position, marked "may be out of date".
- Fetched positions go through the same echo check as everything else (§1.4, guard 2).

**One row per resolved copy:**
- **Source and format**, for example "Storyteller · read-aloud" or "Audiobookshelf · audiobook".
- **Position:**
  - ebooks: the chapter title and `totalProgression` as a percentage;
  - audio: "2 h 13 min of 10 h 2 min" and the percentage.
- **When and where:** relative time, plus where it came from:
  - `user`: "read on this device"
  - `remote`: "from <server>"
  - `manual` or `linked_copy`: "set from <source copy>"
- The newest real reading (P1) is labelled **Latest**.
- **A text excerpt** for ebook positions (and audio positions that map to text through SMIL): a
  short snippet with the position marked, so the person can recognise where each copy is without
  opening it. This is the same idea as BookBridge's "Show position".
  - Use the position's stored anchor (§1.4). If there isn't one, extract the text at the locator
    from the copy's file, if it's on this device.
  - When neither is possible, show no excerpt.
  - A percentage-only position is labelled "approximate", as BookBridge does.

**Applying a position:**
1. Tapping a row offers **Use this position**.
2. That opens an apply sheet listing every *other* copy, each with a checkbox and a preview of
   where it would land:
   - Exact or High translation: "Chapter 7 · same place".
   - Approximate translation: "about 43%".
3. Default ticks:
   - Ticked: Exact and High targets.
   - **Unticked**: Approximate targets, and targets the collapse guards (§1.4, guards 7 and 8)
     would block. Those also show a warning, e.g. "This would move Audiobookshelf to the start of
     the book". The person can still tick them deliberately.
   - **Disabled** with a reason: targets that can't be written yet, such as an Audiobookshelf ebook
     before the CFI fix (guard 11, and `2026-10-01-audiobookshelf-ebook-location-fix.md`). Also
     disabled: copies whose translation needs a file that isn't on this device and has no
     percentage fallback.
4. **Apply** writes each ticked target's local position:
   - origin `manual`, `observed_at` = now,
   - the threshold guard doesn't apply (the person chose this),
   - plus the target's write-log row.

   Each target's normal sync then pushes it. Afterwards the sheet shows: "Updated Audiobookshelf
   and Parrot Cloud", or which ones failed.

Applying never changes the source copy. Applying to the copy currently open in the reader moves
the reader there.

**Wording** (new keys, added at the bottom of `strings.xml`):

| Key | Value |
|---|---|
| `positions_action` | Reading positions |
| `positions_title` | Where you are in this book |
| `positions_latest` | Latest |
| `positions_stale` | May be out of date |
| `positions_from_device` | Read on this device %1$s |
| `positions_from_server` | From %1$s %2$s |
| `positions_set_from` | Set from %1$s %2$s |
| `positions_use_this` | Use this position |
| `positions_apply_title` | Move these copies to this position? |
| `positions_same_place` | %1$s · same place |
| `positions_about` | about %1$d%% |
| `positions_warn_start` | This would move %1$s to the start of the book. |
| `positions_warn_end` | This would mark %1$s as almost finished. |
| `positions_not_supported` | Can't update %1$s yet. |
| `positions_apply` | Apply |
| `positions_applied` | Updated %1$s |
| `positions_apply_failed` | Couldn't update %1$s |
| `resume_linked_compare` | Compare all |

## 1.4 Position origin and anchor

Add to the local `position` table:
- `origin TEXT NOT NULL DEFAULT 'user'`, which is one of `user`, `restore`, `remote`,
  `linked_copy` or `manual` (applied by the person in the positions panel, §1.3b).
  - **Real reading** (P1): `user`, `manual`, and `remote` when it isn't an echo.
  - **Never propagates automatically**: `manual`. Applying already wrote every target the person
    ticked.
- `observed_at TEXT`: when the reading happened, which is not necessarily when it was saved.
- `text_anchor TEXT`: JSON `{"before": "...", "after": "..."}` for ebook positions, null for audio.

The reader captures the anchor when it saves a position:
- It runs a small script in the current chapter, alongside the TTS sentence script in
  `ChapterSentenceExtractor`, that returns the text before and after the locator's start.
- On Android use Readium's locator text (`Locator.text.before`, `highlight` and `after`) when it's
  present, and the script otherwise.

### Write guards (checked against BookBridge's code at commit `246dde9`, 2026-09-25)

Source: `cporcellijr/bookbridge`, files `src/services/write_tracker.py`, `src/sync_manager.py`
(`_own_writeback_window_seconds`, `_peer_position_is_own_writeback`, `_should_hold_backward_leader`,
the freshness guards) and `src/sync_clients/storyteller_sync_client.py`.

**1. Write log.** A new local table, `linked_copy_writes`, holds **one row per target copy**: the
latest write only, like BookBridge's tracker, which is keyed per (client, book). Columns:
`target_key`, `written_at`, `marker`, `locator_href`, `progression`, `total_progression`,
`audio_ms`. Each new write replaces the row. Rows older than 7 days are deleted.

**2. Echo detection** runs when a pulled position is applied for copy T, in
`ProgressSyncEngine.applyRemote`. Today that applies every pull with no version check, so this is
new. Same rule as BookBridge: identity first, value as the fallback.
- **Storyteller: identity.** The marker is the `timestamp` (ms) we sent with the position, which
  is what BookBridge records (`record_write(..., marker=write_ts)`). Our Storyteller mapping sends
  `timestamp` and reads it back unchanged (`StorytellerProgressMapping.kt`). Equal timestamp
  means an echo. A different timestamp means someone else wrote, however close the values are.
- **Parrot Cloud: identity.** The marker is the `remote_revision` acknowledged for our write. The
  same revision means an echo.
- **Audiobookshelf: value.** There's no marker, because the server restamps `lastUpdate` on
  every write (BookBridge #413). It's an echo when `|Δ totalProgression| < 1%`, BookBridge's
  `SYNC_DELTA_BETWEEN_CLIENTS_PERCENT` default.

An echo is stored with origin `linked_copy`. Anything else is stored with origin `remote` and
counts as real reading.

**3. Unchanged pulls (BookBridge's "staleness suppression").** If the pulled snapshot has the same
`timestamp` or revision and the same locator as the stored position, leave the stored row alone,
including its origin and `observed_at`. Our engine re-pulls the same remote position often.

**4. Propagation threshold.** Propagate only when the translated position differs from the
target's current one by at least **1% `totalProgression`**, or, for ebooks, by at least **2000
characters** (about 400 words). These are BookBridge's `sync_delta_between_clients` and
`delta_chars_thresh`. Audio uses the 1% rule only, as in BookBridge.

**5. Backward moves** propagate straight away. See the table below for why.

**6. Deduplication:** never write the same source position (same source key and `observed_at`)
to the same target twice. The write log answers this.

**7. Collapse to the start (BookBridge #290).** Don't propagate when the translated
`totalProgression` is ≤ 0.5% but the source's is > 0.5%. A failed match that falls back to the
start of the book would wipe real progress on the other server. A genuine restart keeps the
source near 0% and isn't affected. BookBridge's `_locator_collapsed_to_start` uses the same
epsilon (0.005).

**8. Collapse to the end (BookBridge #358).** Don't propagate when the translated
`totalProgression` is ≥ 99.5% while the source is more than 5% behind it. A match landing in the
back matter would mark the book finished on the other server. These are BookBridge's
`_locator_collapsed_to_end` values (epsilon 0.005, `min_gap` 0.05).

**9. Map directly (BookBridge #434).** Audio to audio maps time to time (P6b). Text to text maps
by anchor. Never route a translation through the other kind (audio to text to audio) when a direct
mapping exists. Each conversion loses precision.

**10. Clamp (BookBridge #426).** Clamp every mapped audio time to `[0, totalDurationMs]` and every
progression to `[0, 1]`. Proportional and SMIL mapping can overshoot at the edges.

**11. Each server's locator format (BookBridge #364).** Write each target the locator format its
own apps read:
- **Storyteller:** a Readium locator (`href`, `progression`, `totalProgression`, `cssSelector`).
- **Audiobookshelf ebook:** an **EPUB CFI** in `ebookLocation`, plus `ebookProgress`. BookBridge
  lists `ABSEbook` in `_CFI_DEPENDENT_CLIENTS`.
- **Audiobookshelf audio:** `currentTime` in seconds.
- **Parrot Cloud:** our own locator.

Until slice 2 can build a CFI (spine index plus element path, from `EpubTextReader`), **don't
propagate ebook positions to Audiobookshelf**. Propagating audio is fine.

**12. No reading sessions (related to BookBridge #424, which fixed double-counted sessions).**
Saving a `linked_copy` or `manual` position must never record a reading session or count towards
statistics. It isn't reading time.

**13. Storyteller timestamps.** Storyteller only accepts a position whose `timestamp` is newer than
the one it stores, and answers HTTP 409 otherwise. This was verified in BookBridge's
`storyteller_api.py` (`update_position`).
- **Automatic propagation** (`linked_copy`) sends the source's original `observed_at` as the
  timestamp. A 409 then means Storyteller holds newer reading, and that's correct: handle it as a
  conflict (this needs the Storyteller 409 fix) and don't retry.
- **The positions panel** (`manual`) sends the current time, so the person's choice is accepted.
- The value sent is the echo marker (guard 2). Record exactly that value in the write log.
- BookBridge always sends the current time, because it has already decided automatically that its
  position should win.

**Found during this check: an existing bug.** Today the app writes `ebookLocation =
snapshot.locator?.href` (`AudiobookshelfProgressTransport.kt`, `toApiModel`), and when pulling it
reads `ebookLocation` back as an `href`. Audiobookshelf's own reader stores and expects an EPUB
CFI there. So resuming an ebook between Audiobookshelf's reader and this app is probably broken
in both directions. Fix it separately from project 3: write and read CFIs, and keep the href only
as a fallback.

**How this compares with BookBridge:**

| Topic | BookBridge | This app | Why it differs |
|---|---|---|---|
| Echo check | Marker identity, falling back to a 1% value match | The same, with Storyteller timestamp, Parrot revision, and Audiobookshelf 1% | No difference |
| Echo window | 10 min to 1 h, sized to its 5-minute polling | The latest write per target, up to 7 days | Our app only pulls a copy's progress when it's opened or during routine sync, which can be hours later. BookBridge notes that the value match, not the window, is what "keeps the exclusion honest". |
| Threshold | 1% or 2000 characters | The same | No difference |
| Which position leads | Furthest wins, plus guards; "the device you're reading on wins" is opt-in | Automatic only for your own reading on this device (slice 4). Otherwise the person decides: in the resume prompt, or in the **positions panel**, where they pick the source and which copies it overrides (§1.3b) | This repo's progress plan forbids max-percentage resolution. When copies disagree, the person knows which one is right and the app doesn't. |
| Backward jumps | Held up to 300 s unless corroborated, then accepted | Propagated straight away | We only propagate the person's own reading on this device, which BookBridge calls corroborated. Even an uncorroborated move is accepted after the hold in BookBridge. |
| Clock-skew tolerance | 600 s, before *overriding* a position | 60 s, before *offering* a prompt (§1.3) | A prompt is harmless when it's wrong; an override isn't |
| Finished books | Marked finished on other clients at 99% or more | Not in this project | Possible later addition |
| Collapse to start or end, direct mapping, clamping, locator formats, no extra sessions | #290, #358, #434, #426, #364, #424 | Guards 7–12, with the same values | No difference |
| Large EPUBs | #414: never load a whole multi-GB EPUB | The `EpubTextReader` rule in slice 2 | No difference |
| Manual control | Dashboard with progress across clients, "Show position" excerpt, plus Sync Now, Clear position and Mark finished. **No** "use this client's position for these others" | The positions panel (§1.3b), with excerpts and per-target choice | The user asked for manual control on 2026-10-01. BookBridge has no apply step to copy, so ours stays behind explicit ticks with the collapse warnings. |
| Storyteller timestamp sent | Always the current time | The source's original time for automatic writes, the current time for manual ones (guard 13) | Automatic writes must not beat newer reading done elsewhere. A 409 is the correct answer then. |

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
| 3b | Positions panel (§1.3b) | The person sees every copy's position and applies one to any others |
| 4 | Propagation to linked copies | Reading in Parrot updates Storyteller's position, and the reverse, when the result is reliable |
| 5 | Audiobook ↔ read-aloud timing | Audiobookshelf audio and a Storyteller read-aloud stay in step (P6b) |

## Slice 1 — Position origin, observation time and text anchor

### Task 1.1: Schema and model

**Files:**
- Create: the **next free** migration, `lib/database/implementation/.../sqldelight/.../<N>.sqm`.
  Project 2 used `28.sqm`, and the Audiobookshelf fix may take the next number, so check the
  highest existing `.sqm` and add 1. Update `Position.sq` to match: `origin`, `observed_at` and
  `text_anchor`. Below, "the slice 1 migration" means this `<N>.sqm`.
- Modify: `lib/server/api/.../ServerPosition` to add `origin: PositionOrigin = PositionOrigin.User`,
  `observedAt: String? = null` and `textAnchor: TextAnchor? = null`. Map these through
  `PositionEntity`, the reader's local models and `SaveReadingProgressUseCase`.
- Modify: every writer of positions, to set the origin:
  - the reader saving progress: `user`,
  - `ResolvePositionConflictUseCase.useRemote` and remote applies in `ProgressSyncEngine`:
    `remote`,
  - restoring after a download (the project 1 finalizer path): `restore`.
- **Tests:**
  - A migration test (from the previous version to `<N>` + 1) where existing rows get
    `origin = 'user'`. Use the real-migrations plan's `MigrationChainTest` if it has landed.
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
- **Memory (BookBridge #414):** Storyteller read-aloud EPUBs contain the audio and can be several
  GB.
  - Open the zip and read only the entries you need (OPF, XHTML, SMIL), streamed one at a time.
  - Never load the whole archive, and never read audio entries.
  - Skip any XHTML or SMIL entry over 10 MB and log it.
  - Test with a fixture that has a large dummy audio entry, and assert peak memory stays low by
    checking that the reader never touches that entry.
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
- Modify: `feature/reader/domain/build.gradle.kts`. Add `projects.feature.sync.domain` (domain to
  domain, which is allowed), because `TranslatedPosition` uses `ProgressKind`.
- Modify: `feature/reader/domain/build.gradle.kts`. Add `projects.lib.epub.api` (Task 2.1).
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
- Modify, in **both** prompt places from §1.3, so they behave the same way and never ask twice:
  - **`BookDetailViewModel`'s open flow** (`pendingOpenBookType` and the
    `ResolvePositionConflictUseCase` paths): call `FindLinkedResumeUseCase` first. If there's an
    offer, show the linked prompt *instead of* the same-copy conflict dialog. The offer already
    considers T's own local and remote positions. Then navigate to the reader with a flag (a new
    reader route argument, `linkedResumeResolved = true`) and the chosen position.
  - **`ReaderViewModel`'s start-up** (around line 611, where `positionConflict` is derived): when
    `linkedResumeResolved` isn't set, call `FindLinkedResumeUseCase`. If there's an offer, show
    the linked prompt in place of `positionConflict`. When it is set, skip both prompts.
- Modify: both UIs, reusing each screen's existing `PositionConflictDialog` component with the §1.6
  strings. Don't make a new design.
- **"Stay here"** dismissals: store them in user preferences under a new
  `PreferencesKey.DismissedLinkedResume`. The value is a list of
  `"<targetKey>|<sourceKey>|<source observedAt>"` strings, keeping the newest 200. An offer whose
  string is in the list isn't shown.
- **Module dependency:** `feature/reader/domain` depends on `feature/sync/domain` from Task 2.2,
  so use `ProgressKind` directly; Task 3b.1's `EchoClassifier` lives in sync-domain. Never add a
  domain-to-data dependency.
- **Tests (both places):** an offer from book detail followed by opening the reader with
  `linkedResumeResolved = true` shows no second prompt. Opening from "Continue reading" (no flag)
  shows the prompt in the reader.
- **Tests:**
  - A candidate from another copy that's newer by more than 60 seconds and at a different place
    gives an offer.
  - Newer by less than 60 seconds gives no offer.
  - The same place gives no offer.
  - A candidate with origin `linked_copy` or `restore` is ignored as the "real" reading.
  - A `remote` candidate that is real reading on another device counts. A `remote` echo of our
    own write is stored as `linked_copy`, so it's ignored.
  - A remote fetch timeout still returns the local-based result.
  - After "Stay here", the same source position doesn't offer again.

### Slice 3 device check

1. A Parrot ebook linked to a Storyteller read-aloud: read to chapter 5 in the Storyteller copy,
   then open the Parrot copy. Expect the prompt "Continue from Chapter 5?"; Continue lands on the
   same paragraph.
2. The same with a plain Audiobookshelf ebook of different markup: expect the prompt and the
   right paragraph (High).
3. An Audiobookshelf audiobook without a read-aloud: expect "Continue from about N%?".

## Slice 3b — Positions panel (P9, §1.3b)

This slice is the first to write positions to other copies, so it introduces the shared write
path. Slice 4 reuses it.

### Task 3b.1: Write path, write log and echo detection

**Files:**
- Create: `feature/reader/domain/.../usecase/WriteCopyPositionUseCase.kt`. It's the **only** way any
  feature writes a position into another copy.
  - It saves the target's local position with the given origin (`manual` here, `linked_copy` in
    slice 4) and `observed_at`.
  - It replaces the target's `linked_copy_writes` row, and records the marker once the push is
    acknowledged (§1.4, guard 2).
  - It never records a reading session (guard 12), and never writes to targets that guard 11 rules
    out.
- Create: the `linked_copy_writes` table, in the slice 1 migration (`<N>.sqm`) if slice 1 hasn't
  shipped yet, otherwise in the next free migration. Delete rows older than 7 days on each write. Put
  its database interface, `LinkedCopyWritesDatabase`, in `lib/database/api`. Reader-domain and
  sync-data both already depend on that.
- Create: `feature/sync/domain/.../EchoClassifier.kt`, the single shared, pure function for §1.4
  guard 2.
  - It has to be in **sync-domain**: `ProgressSyncEngine` (sync-data) can't depend on
    reader-domain, while reader-domain gets a dependency on sync-domain in Task 3.1.
  - Use it from `ProgressSyncEngine.applyRemote` (guard 3 lives there too), from the resume
    prompt (§1.3) and from the panel (§1.3b).
  - Each transport passes its marker on `RemoteProgressSnapshot`: the `timestamp` for Storyteller,
    the `version` for Parrot, nothing for Audiobookshelf.
- Modify: **the library merge** (project 1's `mergeLibraryBookRows`, and project 2's
  `mergeLibraryCopyLinks`). When `library:fromId` merges into `library:intoId`:
  - rewrite `linked_copy_writes.target_key`, keeping the newer row on a clash;
  - rewrite the `PreferencesKey.DismissedLinkedResume` entries.
  - Test both.
- **Tests:**
  - **Echo, Storyteller:** a pulled position with the same `timestamp` marker as our write is
    stored as `linked_copy`. With a different `timestamp`, it's stored as `remote` **even when the
    progression is identical**.
  - **Echo, Parrot:** the same revision gives `linked_copy`. A newer revision gives `remote`.
  - **Echo, Audiobookshelf:** within 1% `totalProgression` of our write gives `linked_copy`. 5%
    further on gives `remote`, which counts as real reading.
  - **Unchanged pull:** re-pulling an identical snapshot doesn't change the stored row's origin or
    `observed_at`.
  - Write-log rows older than 7 days are deleted and don't match.
  - `WriteCopyPositionUseCase` with `manual` sets `observed_at` to now, replaces the write-log
    row, and records no reading session.
  - `WriteCopyPositionUseCase` refuses an Audiobookshelf ebook target without the CFI fix
    (guard 11).

### Task 3b.2: The panel

**Files:**
- Create: `feature/reader/domain/.../usecase/ObserveCopyPositionsUseCase.kt`. For a link it returns
  one `CopyPositionRow` per resolved copy: `copy`, `position`, `observedAt`, `origin`,
  `sourceLabel`, `isLatest` and `isStale`. It fetches remote positions with the 2-second budget,
  and classifies them with `EchoClassifier`.
- Create: `feature/reader/domain/.../usecase/PreviewApplyPositionUseCase.kt`. For a source row and
  every other copy it returns
  `ApplyPreview(target, translated: TranslatedPosition?, defaultChecked, enabled, warning)`,
  following the ticking rules in §1.3b.
- Create: `feature/reader/domain/.../usecase/ApplyPositionUseCase.kt`. For each ticked target it
  calls `WriteCopyPositionUseCase` with origin `manual`, and returns the result per target.
- Create: `feature/books/ui/.../positions/PositionsScreen.kt` and `PositionsViewModel.kt`. Minimal
  UI: a list, a bottom sheet with checkboxes, and a result message. The user will design it later.
  Add a route next to the book detail. Add the "Reading positions" action to the detail screen for
  linked books, and **Compare all** to the resume prompt.
- Modify: `strings.xml` with the §1.3b keys.

**Tests:**
- **Rows:** the Latest label goes to the newest real reading. An echo is never Latest. A failed
  fetch gives a stale row with the last known position.
- **Preview:** Exact or High is ticked; Approximate is unticked with "about N%"; collapse to start
  or end is unticked with a warning; an Audiobookshelf ebook without the CFI fix is disabled.
- **Apply:** only ticked targets are written, each with origin `manual` and a write-log row. The
  source is never written. A failure on one target doesn't stop the others, and the result lists
  both.
- **After applying:** the next sync's pull of our own write is classified as an echo (Storyteller
  by timestamp, Audiobookshelf by the 1% match).

### Slice 3b device check (only if the user asks for device testing)

A book linked in Storyteller, Audiobookshelf and Parrot, with three different positions:
1. Open the panel and check that all three positions and their times are shown.
2. Choose Storyteller's position, keep the default ticks, and apply.
3. Expect Parrot (and Audiobookshelf audio) to move. After a sync, Audiobookshelf's own app and
   the Parrot copy open at that place. The panel shows Storyteller as Latest and the others as
   "set from Storyteller".

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
  4. Apply the propagation threshold (guard 4), deduplication (guard 6) and collapse guards
     (guards 7 and 8) from §1.4.
  5. Otherwise call slice 3b's `WriteCopyPositionUseCase` with origin `linked_copy` and
     `observed_at` equal to the source's.
- The write path, write log, echo detection and unchanged-pull handling all come from slice 3b.
  Don't duplicate them.
- **Loop guard:** only origin `user` positions trigger propagation. `remote`, `manual` and
  `linked_copy` positions never do.
- **Setting:** "Update my other servers as I read", in reading settings, **on by default**. When
  it's off, only the resume prompt and the positions panel move other copies. The user can change
  the default.
- **Tests:**
  - A High translation calls `WriteCopyPositionUseCase` with origin `linked_copy`, and an outbox
    entry appears for the target's server.
  - An Approximate translation writes nothing.
  - A target that was read more recently is not overwritten.
  - `linked_copy`, `manual` and `remote` positions don't cause propagation.
  - **Threshold:** a 0.5% move that's under 2000 characters isn't propagated. A 0.5% move of
    2500 characters in an ebook is. A 0.5% move in audio isn't.
  - **Deduplication:** propagating the same source position twice writes once.
  - **Collapse to start:** a source at 40% that translates to 0.2% isn't propagated. A source at
    0.3% that translates to 0.1% is.
  - **Collapse to end:** a source at 60% that translates to 99.8% isn't propagated. A source at
    97% that translates to 99.8% is.
  - **Direct mapping:** audio to audio with matching durations never calls the text matcher
    (assert with a fake).
  - **Clamping:** a mapped time past the end is clamped to `totalDurationMs`.
  - **Audiobookshelf ebook:** with no CFI available, an ebook position isn't propagated to an
    Audiobookshelf ebook copy. An audio position to an Audiobookshelf audiobook is.
  - **Setting off:** nothing is propagated.

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
- **Database upgrades:** migrations run on devices; `verifyMigrations` and `MigrationChainTest`
  must pass. Version 28 is the immutable baseline; older databases and downgrades still reset.
- **Later, not now:**
  - server-side transcripts, such as Storyteller forced-alignment assets, for audiobooks without
    a read-aloud,
  - KOReader sync (KOSync) as another link target, as BookBridge does.
