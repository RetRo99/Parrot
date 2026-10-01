# Audiobookshelf ebook position format — bug note and fix plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Status:** suspected bug, found by reading code on 2026-10-01. It hasn't been confirmed on a real
Audiobookshelf server yet. Task 0 confirms it.

**Goal:** ebook positions round-trip correctly between this app and Audiobookshelf's own readers:
the web reader, and the iOS and Android apps.

## What's wrong

Audiobookshelf stores an ebook position in `mediaProgress.ebookLocation`. The server treats the
value as **opaque**: it stores whatever the reader wrote and returns it unchanged. Its readers
write different shapes:
- **Audiobookshelf web reader (epub.js):** an EPUB CFI string, e.g. `epubcfi(/6/14!/4/2/8:0)`.
- **Audiobookshelf mobile apps (Readium):** a JSON Readium locator, e.g.
  `{"href":"OEBPS/ch05.xhtml","type":"application/xhtml+xml","locations":{"progression":0.42,...}}`.

Evidence: BookBridge's Audiobookshelf ebook client (`cporcellijr/bookbridge`,
`src/sync_clients/abs_ebook_sync_client.py`, `_build_position_state` and
`_location_for_target`), and its `_CFI_DEPENDENT_CLIENTS`, which includes `ABSEbook` (BookBridge
issue #364).

**This app** (`lib/server-audiobookshelf/.../AudiobookshelfProgressTransport.kt`):
- **Push** (`ProgressMutation.toApiModel`) writes `ebookLocation = snapshot.locator?.href`: a
  bare chapter href, which is neither shape.
- **Pull** reads `ebookLocation` straight into `href`, whatever its shape.

**What users probably see (not yet confirmed):**
1. **Audiobookshelf to this app:** a CFI or JSON string becomes our `href`.
   - **On Android,** `PositionUiModel.toAndroidLocator()` (`AndroidBookController.kt`) can't build
     a valid locator, so the reader opens **at the start of the book**. `totalProgression` (from
     `ebookProgress`) isn't used as a fallback.
   - **On iOS,** `IosBookController.goToPosition` passes only `href`, `type`, `progression` and
     `position` to the Swift bridge, **not `totalProgression`**. So iOS has no fallback either.
2. **This app to Audiobookshelf:** the web reader gets a bare href, which isn't a CFI, and the
   mobile apps get a bare href, which isn't JSON. Both probably open at the start, or wherever
   they fall back to.

**Also check while you're in this file:** push writes `progress = snapshot.progression`. In
Audiobookshelf, `progress` is the **whole item's** progress, from 0 to 1. Confirm whether our
`progression` is whole-book or chapter-level for audio positions, and fix it if it's
chapter-level.

## Fix: BookBridge's approach

**Reading** (pull: `ebookLocation` to our position). Detect the shape:

| Shape | How to detect it | Maps to |
|---|---|---|
| JSON Readium locator | Parses as JSON with an `href` | `href`, `locations.progression`, `locations.totalProgression`, `cssSelector` (from `locations.otherLocations` or `cssSelector`) |
| CFI | Starts with `epubcfi(` | Resolve the spine step (`/6/N`, where N/2 is the 1-based spine item) to the chapter `href` through the publication's reading order. Keep `ebookProgress` as `totalProgression`. Resolving the element path inside the chapter is a later improvement. |
| Bare href (our old writes) | Anything else that parses as a URL | `href`, as today |
| Empty or unknown | | Only `totalProgression` from `ebookProgress` |

Keep the raw value too: store it as `ebook_location_raw` alongside the position, so pushing can
mirror its shape.

**Writing** (push). Mirror the shape that's already stored. BookBridge says "the reader that wrote
it is the reader that will read it back".
- **Stored value is a JSON locator:** write a JSON Readium locator built from our position.
- **Stored value is a CFI, or empty:** write a CFI. Use at least the spine step plus the chapter
  start, `epubcfi(/6/{2*(spineIndex+1)}!/4)`, and always send `ebookProgress`. A precise element
  path (from `cssSelector` or element IDs) is an improvement that can follow later; reuse project
  3's `EpubTextReader` when it exists. BookBridge also falls back to a CFI when the field is empty,
  which keeps web-reader installs working.
- **Never write a bare href again.**

**Reader fallback** (Android and iOS): when a stored position's `href` can't become a locator,
open at `totalProgression` (Readium: map through the publication's positions list) instead of the
start of the book. This protects users from any bad value, whatever its source.

## Tasks

### Task 0: Confirm the bug on a real server (before changing code)

Ask the user for an Audiobookshelf server with an EPUB, or use their dev server. Never change
data they care about: use a test book.
1. Read the test book in Audiobookshelf's **web reader** to about 40%. Then run
   `GET /api/me/progress/<libraryItemId>` and record `ebookLocation` and `ebookProgress`.
2. Open the same book in this app and record where it opens.
3. Read to about 70% in this app, sync, and call the endpoint again. Record `ebookLocation`. Open
   the web reader and record where it opens.
4. If possible, repeat steps 1–2 with the Audiobookshelf **mobile app**, to confirm the JSON shape.

Report the exact values seen. If Audiobookshelf behaves differently from this note, stop and
update the note before Task 1.

### Task 1: Parse and write the shapes (pure, tested)

**Files:**
- Create: `lib/server-audiobookshelf/src/commonMain/.../AbsEbookLocation.kt`, containing
  `parseAbsEbookLocation(raw, ebookProgress): ParsedAbsLocation` and
  `buildAbsEbookLocation(position, storedRaw, spineIndexOf): String`.
- Modify: `AudiobookshelfProgressTransport.kt`, on both the pull and push paths.
- Modify: the position storage, adding `ebook_location_raw` in the next `.sqm` migration and
  following the real-migrations plan (`2026-10-01-real-database-migrations.md`).
- **Tests:** a table test with a JSON locator, a CFI, a bare href, an empty value and garbage.
  Pushing mirrors JSON when JSON is stored, writes a CFI when a CFI or nothing is stored, and
  never writes a bare href. The CFI spine step is correct for spine indexes 0, 1 and 9.

### Task 2: Fall back to `totalProgression` in the reader

**Files:**
- Modify: `feature/reader/ui/src/androidMain/.../navigator/AndroidBookController.kt`, around
  `toAndroidLocator` and the initial-locator path in `EpubReaderView.android.kt` (line 183).
- Modify: `feature/reader/ui/src/iosMain/.../navigator/IosBookController.kt` (`goToPosition`), the
  Swift bridge method it calls, and the iOS initial-position path. Pass `totalProgression`
  through, and use it when the `href` doesn't match a reading-order item.
- **Tests:** an invalid href with `totalProgression = 0.4` opens at about 40%, not at the start.

### Task 3: Check again on the real server

Repeat Task 0's steps and report the positions. All four directions should land within the same
chapter. Precision inside a chapter improves with the later element-path work.

## Related

- Project 3 (`2026-10-01-progress-across-linked-copies.md`, guard 11) blocks writing ebook
  positions to Audiobookshelf until this fix lands.
- Use the same shape detection for project 3's translator when Audiobookshelf is the source copy.
