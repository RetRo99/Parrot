# Audiobook positions saved per file — bug note and fix plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Status:** confirmed by reading code on 2026-10-01. It hasn't been reproduced on a real server
yet; Task 0 does that.

**Goal:** audiobook positions record the book-level time, length and progress as well as the
current file, so Audiobookshelf's own apps, our library cards and project 3's translator all
see the right place in multi-file audiobooks.

## What's wrong

Many audiobooks, especially on Audiobookshelf, are several audio files (for example 40 MP3s).
The player plays them as a playlist (Media3 media items). When it saves a position, every value
is **relative to the current file**: `AudiobookPlayerViewModel.kt` (`androidMain`, around lines
318–341 and 471–490):

| Field | Holds today | Should hold |
|---|---|---|
| `audioTimestampMs` | time inside the current file (`player.currentPosition`) | (keep as is: the file offset, used to resume) |
| `chapterIndex` | file index (`currentMediaItemIndex`) | (keep as is) |
| `totalDurationMs` | **the current file's** length (`player.duration`) | the whole book's length |
| `progression` / `totalProgression` | **the share of the current file** | the share of the whole book |
| (missing) | — | the time from the start of the book |

**Example:** a 10-hour book in 40 files of 15 minutes. You're 7:30 into file 21, which is
5 h 07 min into the book (about 51%). The app saves 7 min 30 s, 15 min and 50%.

**Consequences:**
1. **Audiobookshelf's apps resume at the wrong place.**
   `AudiobookshelfProgressTransport.toApiModel` sends `currentTime = audioTimestampMs / 1000`
   (7 min 30 s), `duration` set to the file's length, and `progress` set to the file's share.
   Audiobookshelf treats `currentTime` as time from the start of the book.
2. **Positions pulled from Audiobookshelf land wrong.** The pull sets `audioTimestampMs` from
   Audiobookshelf's book-level `currentTime` (5 h 07 min) with no file index. `restoreProgress`
   then seeks `chapterIndex ?: 0`, which is file 1, to 5 h 07 min, far past its end.
3. **Our own library cards and progress bars show the file's share, not the book's**, for
   every multi-file audiobook, even with no server involved.
4. **Project 3:** per-file audio positions only translate as Approximate, so audio never updates
   linked copies automatically (`CopyPositionTranslator.kt`, the track-plus-fraction path).

Single-file audiobooks (one `.m4b`) aren't affected, because the file *is* the book.

## Decision: keep the file offset, add the book-level values

Don't change what `audio_timestamp_ms` and `chapter_index` mean. The player needs them to resume
exactly, and project 3's code reads them. Instead:
- Add a column `book_time_ms` (`position` table, next free `.sqm`), plus `bookTimeMs` on
  `PositionDomainModel`, `ServerPosition` and the entities: the time from the start of the book.
- **Redefine** `total_duration_ms` and `total_progression` for audiobooks as **whole-book**
  values. Every consumer already assumes that: cards, `ObserveAllBooksWithProgressUseCase`, and
  Audiobookshelf's `duration` and `progress`. Rows saved before the fix are corrected on the next
  save.
- **When the book-level values can't be known** (file lengths unknown): leave `book_time_ms` and
  `total_progression` **null**, rather than storing a wrong value. For multi-file books, don't
  push an audio position to Audiobookshelf without `book_time_ms` (log it). Single-file books:
  `book_time_ms` is the file offset.

**Where file lengths come from**, in order:
1. The player's timeline: `player.currentTimeline.getWindow(i, window).durationMs`, when every
   window's duration is known (not `C.TIME_UNSET`).
2. The server's metadata. Audiobookshelf returns `audioFiles[].duration` (seconds), which
   `AudiobookshelfLibraryItemMapper.kt` already reads for the total. Cache the per-file lengths:
   add a column `audio_track_durations_ms` (a comma-separated list) to the `books` table, and
   `ServerBook.audioTrackDurationsMs: List<Long>?`.
3. Otherwise, unknown.

## Tasks

### Task 0: Reproduce (needs a real Audiobookshelf server; skip in cloud sessions)

Use a multi-file test audiobook.
1. Listen in this app to the middle of a later file, sync, and record
   `GET /api/me/progress/<itemId>` (`currentTime`, `duration`, `progress`). Open the book in
   Audiobookshelf's web player and note where it resumes.
2. Listen further in Audiobookshelf's web player, then open the book in this app and note where
   it resumes.
3. Note the library card percentage in this app.

### Task 1: Pure conversion functions

**Files:**
- Create: `feature/reader/domain/src/commonMain/.../audio/AudioBookTime.kt`:

```kotlin
/** Book time for [trackIndex] at [offsetMs], or null when any earlier track length is unknown. */
fun bookTimeMs(trackDurationsMs: List<Long>, trackIndex: Int, offsetMs: Long): Long?

/** (track index, offset in it) for [bookTimeMs], clamped into the book; null when lengths unknown. */
fun trackPosition(trackDurationsMs: List<Long>, bookTimeMs: Long): Pair<Int, Long>?
```

- **Tests** (table-driven): the first, middle and last track; exactly on a boundary (it belongs
  to the next track); past the end (clamped to the last track's end); an empty list (null); and a
  single track (book time equals the offset).

### Task 2: Cache per-file lengths

- Add `audio_track_durations_ms` to `books` (next free `.sqm`, and in `Book.sq`), and
  `ServerBook.audioTrackDurationsMs`.
- Fill it from `audioFiles[].duration` in `AudiobookshelfLibraryItemMapper.kt`. For Storyteller
  audiobooks, fill it only if its API provides per-file lengths; otherwise leave it null.
- **Tests:** the mapper maps `[600.5, 900.0]` seconds to `[600500, 900000]` ms, and missing
  durations give null.

### Task 3: The player saves book-level values

**Files:**
- `feature/reader/ui/src/androidMain/.../audiobook/AudiobookPlayerViewModel.kt`, both save sites.
- `feature/reader/ui/src/androidMain/.../playback/auto/HeadlessPlaybackSession.kt`. Audit it:
  its `buildPosition` saves `audioTimestampMs` with `chapterIndex = null` and a `chapterHref`.
  Work out whether that time is per file and fix it the same way.
- Any other writer of `audioTimestampMs`, for example `ReaderViewModel`'s read-aloud audio save.
  Run `grep -rn "audioTimestampMs =" --include='*.kt' feature`. For each writer, document in the
  report what its time is relative to.

Pull the position building into a pure function, so it can be tested without Media3:
`buildAudiobookPosition(trackIndex, offsetMs, trackDurationsMs, ...)` sets `audioTimestampMs`
and `chapterIndex` as today, plus `bookTimeMs`, and whole-book `totalDurationMs` and
`totalProgression` when the lengths are known. Otherwise those stay null.

**Tests:**
- For the 40 × 15 min book at file 21, 7:30: `bookTimeMs` = 5 h 07 min 30 s,
  `totalDurationMs` = 10 h, `totalProgression` ≈ 0.5125.
- With unknown lengths, the book-level values are null.
- A single file gives book time equal to the offset.

### Task 4: The player resumes from book time

In `restoreProgress`: when the saved position has `bookTimeMs` and the lengths are known, seek
to `trackPosition(...)`. Otherwise keep today's behaviour (`chapterIndex` plus
`audioTimestampMs`). Test the choice as a pure function.

### Task 5: Audiobookshelf mapping

**File:** `lib/server-audiobookshelf/.../AudiobookshelfProgressTransport.kt`.
- **Push:**
  - `currentTime` = `bookTimeMs / 1000`;
  - `duration` = whole-book `totalDurationMs / 1000`;
  - `progress` = whole-book `totalProgression`.
  - For a multi-file book without `bookTimeMs`, don't send an audio position. Return `Rejected`
    with a long `retryAfterMillis` and a clear reason; never send a per-file time.
- **Pull:** `currentTime` goes into `bookTimeMs`, `duration` into `totalDurationMs`, and
  `progress` into `totalProgression`. Fill `chapterIndex` and `audioTimestampMs` through
  `trackPosition(...)` when the cached lengths are known. Otherwise leave them null; Task 4 then
  resolves them when the player has its timeline.
- **Tests:** both directions, for a multi-file and a single-file book.

### Task 6: Project 3 uses book time

In `CopyPositionTranslator.kt`, when an audio position has `bookTimeMs`, use it directly instead
of the track-plus-fraction path. That makes audio ↔ audio and audio ↔ read-aloud (through the
SMIL timeline and a duration match) High instead of Approximate. Update the translator tests.

### Task 7: Check on the real server (local only)

Repeat Task 0. Expect:
- both directions land within a few seconds of each other;
- the card shows the book-level percentage;
- after a sync, a linked read-aloud copy is updated automatically (project 3).

## Related

- Project 3: `2026-10-01-progress-across-linked-copies.md` (P6, guard 9).
- The overview of all open bugs: `2026-10-01-open-bugs-fix-plan.md`.
