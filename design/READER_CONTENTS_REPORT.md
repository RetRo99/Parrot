# Reader Contents — code/data report

Date: 2026-10-01. Static inspection of the current working tree, including existing uncommitted reader changes. No application code changed; no device/runtime validation. The referenced ZIP/screenshots were not available in this request, so the exact Oathbringer file was not inspected.

## Source paths

For the compact references below:

- **UI** = `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/`
- **Android UI** = `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/`
- **iOS UI** = `feature/reader/ui/src/iosMain/kotlin/com/retro99/reader/ui/`
- **Domain** = `feature/reader/domain/src/commonMain/kotlin/com/retro99/reader/domain/`
- **EPUB API** = `lib/epub/api/src/commonMain/kotlin/com/retro99/epub/api/`
- **SQL** = `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/`
- **Strings** = `translations/src/commonMain/composeResources/values/strings.xml`

## 1. Which Contents screen is active?

- `UI/reader/ReaderOverlay.kt:592–617` opens `ReaderContentsSheet` for either `isTocVisible` or `isBookmarksVisible`.
- This is the Chapters / Bookmarks sheet with `JumpToPositionCard`, title filtering, and an optional audio mini-player footer.
- `UI/reader/TableOfContentsSheet.kt` and `UI/reader/BookmarksSheet.kt` are older standalone sheets. Repository search found their definitions but no call sites. Do not attribute their features to the active screen.
- The active sheet always initializes on **Chapters**, even when opened via the bookmarks visibility flag (`ReaderContentsSheet:1160`).

## 2. TOC data and hierarchy

- `UI/model/TocItemUiModel.kt`: `href: String`, `title: String`, `level: Int = 0`, `children: List<TocItemUiModel> = emptyList()`.
- No explicit entry ID/index, section type, author-supplied chapter number, page count, length, read state, or duration field.
- **Android:** `Android UI/publication/EpubPublication.kt:69–83` recursively converts Readium links into a flat preorder list, preserving `level`. Its `children` field contains flattened descendants, not just immediate children; descendants also appear in the main list.
- **iOS:** `iosApp/iosApp/ReadiumEpubReaderBridge.swift`, `cacheTableOfContents` / `flattenToc`, also emits a flat preorder list with depth. `iOS UI/publication/EpubPublication.kt:34–42` preserves `level`, but sets `children` to empty.
- Hierarchy is therefore **flattened but reconstructable from depth on both platforms**, if the EPUB supplies it. Parts / Interludes / Prologue are titles, not typed categories. A flat source EPUB cannot automatically provide meaningful grouping.
- The active `ChaptersTab` only applies `level * 10.dp` indentation. No group headers, collapse/expand, or ancestor context in filtering.
- Existing `UI/reader/TocNode.kt` reconstructs immediate parent/child relationships from the flat list and levels. The unused `TableOfContentsSheet` uses this, expands everything at ≤20 entries, and otherwise opens the current entry's ancestors. This approach avoids traversing Android's duplicated descendant representation.

### Why three chapter numbers can disagree

- Left row number = first matching href's index in the **flat TOC** + 1 (`ReaderOverlay.kt:1313,1326`). It counts parts/front matter/interludes as entries. It is not the book's chapter numbering.
- A number such as `94. A Small Bottle` is part of `chapter.title`, taken from the EPUB. The app does not parse/reconcile it.
- Reader header chapter number prefers `currentPosition.chapterIndex + 1`, falling back to a TOC href lookup (`ReaderOverlay.kt:246–251`).
- `UI/model/PositionUiModel.kt:52–69`, `LocatorState.toPositionUiModel`, **preserves chapterIndex and totalChapters from the previous/saved position**, rather than recalculating them when href changes. Neither platform's `LocatorState` callback supplies a chapter index.
- Thus the header can retain a saved/stale index while rows use live TOC order. The jump card has yet another basis: Android tick indices are **reading-order resources**, not TOC entries.
- Exact provenance of `139` vs `118` in the user's book: **unsure without that EPUB and saved position**. The code has the above concrete mismatch paths.

## 3. Per-chapter information and upfront cost

- **Rendered pages:** `UI/model/LocatorState.kt` defines `ChapterInfo(currentPage, totalPages, totalWords)`. Both platform controllers run `ChapterPageCalculator` JavaScript on locator changes in the currently rendered document.
- `UI/navigator/ChapterPageCalculator.kt` uses horizontal scroll width / viewport width, then computes total pages as `(reportedTotalPages - 1).coerceAtLeast(1)`. These are layout-dependent screen pages, not publisher pages or EPUB positions. The subtraction merits validation; currentPage is not adjusted to the reduced total.
- **Why almost every row says “— p”:** active `ChaptersTab:1351–1356` deliberately displays a count only for the current href when `chapterInfo.totalPages` exists. All other rows get the hard-coded placeholder. There is no all-chapter page-count model/cache, even for previously visited chapters.
- A TOC entry can be an anchor inside an XHTML file; page/word calculations cover the entire displayed document, not necessarily that TOC subsection.
- **Words:** `ChapterWordCountCalculator` counts the current DOM's text using whitespace splitting. Android/iOS controllers cache this in memory by href. No upfront count for all TOC entries or persisted counts.
- **Characters:** not in TOC/reader state. `EPUB API/EpubTextReader.kt` supplies `EpubChapterText(href, title, text, elementOffsets)` per reading-order resource. Character lengths and word counts can be derived after parsing local chapter text.
- **Positions:** Android already calls Readium `positionsByReadingOrder()` in `chapterStartProgressions`. Position counts/start/end progressions could be derived from that service, but are not exposed per TOC row. They are not rendered page counts. iOS does not implement this common controller method.
- **Reading time:** `UI/reader/ReadingSpeedTracker.kt` provides current-file remaining words/minutes using cached total words, rendered pages (or progression fallback), and measured/fallback WPM. `ReaderViewState.chapterReadingTimeInfo` carries it. No all-chapter estimates or whole-book text time-left field.
- **Recorded narration:** `AudioPlaybackState.totalDurationMs` is the current audio segment, not a universal TOC-chapter duration. No TOC duration map.
- Reusable lower-level data: `EPUB API/ReadaloudTimingReader.kt` exposes `TimedClip(textHref, fragmentId, audioSrc, clipBeginMs, clipEndMs)` and `ReadaloudTiming(totalDurationMs, audioFileOffsetsMs, clips)`. Per-resource narration durations can be derived from clip intervals; TOC subsections require fragment boundaries, and repeated/overlapping intervals need care.
- `Domain/translate/CopyContentCache.kt` caches parsed text and SMIL timing for linked-copy translation, not Contents. Parsing reads archive entries individually, without loading embedded audio. Text is retained with an approximately 20 MB cache budget (one oversized book can exceed it).
- `Domain/audio/GetAudioTrackDurationsUseCase.kt` reads cached `audioTrackDurationsMs` for audiobook files. Tracks are not guaranteed to equal authored chapters.
- Device-voice duration is not an upfront chapter metadata field; actual synthesized timing depends on voice/generated audio.
- **Cheap immediately:** titles, levels, hierarchy reconstruction, TOC ordinal, href-based current selection. Existing/cached positions and timing are cheap to summarize once available.
- **Requires background I/O/parsing:** whole-book word/character totals and recorded narration clip totals. Arithmetic is cheap afterward; parsing an uncached book is not free and should not block sheet opening.
- **Not cheap upfront:** accurate layout page counts for every resource under the current viewport/font settings; this would require rendering/pagination and invalidation after layout changes.

## 4. Progress and current location

- `PositionUiModel.progression` = within current resource; `totalProgression` = whole book. `UI/reader/BookProgress.kt`, `bookPercent`, floors a clamped fraction; null becomes 0.
- The active sheet passes **resource progression** into `currentProgress` (`ReaderOverlay.kt:596`) and uses that for “You’re here · …” (`1342–1344`). Therefore 0% can mean “start of this file,” not “start of the book.”
- Book progression is separately passed to the jump card. No active-row within-chapter bar or page-position label; `currentPage` is passed but unused by `ChaptersTab`.
- Current row = **exact href equality**. No normalization of fragments, relative paths, or subsection position. `chapter.xhtml#part` may not match a locator href of `chapter.xhtml`.
- Opening the Chapters tab auto-scrolls via nonanimated `scrollToItem` when the query is blank (`1287–1291`). It scrolls again when current href changes or filtering is cleared; it does not scroll to a current entry absent from the TOC.
- **Per-chapter finished/current/not-started:** no persisted read-state model. Active rows distinguish only current. The unused legacy sheet treats entries before the current index as “read,” which is merely an order-based inference, not evidence of completion.
- Current-chapter text time left exists through `ReadingSpeedTracker`; whole-book text time left is **not supported** in current reader state. Audio time/duration fields can support audio-segment remaining time, not automatically whole-book narration remaining time.

## 5. Navigation and return

- Chapter tap dispatches `ReaderIntent.GoToChapter(href, currentPosition)`; `ReaderViewModel.goToChapter` navigates and closes `isTocVisible`, storing the previous position.
- Android uses Readium `navigator.go(Link(href))`; iOS delegates to `ReadiumEpubReaderBridge.goToChapter`. This follows a supplied fragment when present; no separate “completed chapter” handling.
- `ReaderViewModel.navigateKeepingAudio` wraps ordinary chapter/bookmark/search/percentage intents: if listening and playing, it pauses narration or stops device voice, performs navigation, waits for href/progression change (with timeout), then resumes. Intended to preserve active listening; same-position jumps/timeouts are not runtime-validated here.
- **Return exists:** `previousTocPosition` plus `undoChapterNavigation`. `UI/reader/ReaderScreen.kt:784–825`, `ChapterNavigationUndoSnackbar`, shows “jumped to chapter” with Undo for a short duration, then clears it. This is one ephemeral previous location, not persistent history or a permanent “Back to where I was” button.
- Bookmark jumps do not populate `previousTocPosition`. Search now has its own `searchOrigin` / find-bar return state, independent of TOC undo.
- Previous/next chapter functions choose adjacent entries in the flat TOC by exact href. They can visit a part heading/front matter/subsection, not exclusively leaf chapters. Plain previous/next intents call these directly, rather than the audio-preserving wrapper; separate `…AndPlay` actions navigate and start recorded audio.
- The active Contents sheet has no previous/next chapter controls. Reader strip/audio-only controls and the unused legacy sheet have chapter navigation actions.
- Visibility edge case: chapter jump only clears `isTocVisible`; bookmark jump only clears `isBookmarksVisible`. Since either flag opens the combined sheet, opening through one flag and choosing the other kind of destination can leave it open.

## 6. Jump to position — removal dependencies

- `UI/reader/JumpToPositionCard.kt` holds a local target fraction. Touch slider/±1% buttons (and ±10% on e-ink) only change the target; **Go** dispatches a book-wide jump.
- `ReaderViewModel.jumpToBookProgress` uses `BookController.goToTotalProgression`, closing the TOC and storing undo only on success.
- Android resolves via Readium `locateProgression`; tick starts come from `positionsByReadingOrder`. Slider renders ticks only for 2–40 tick values; this is a drawing limit, **not a TOC size limit**.
- iOS controller does not override `chapterStartProgressions` or `goToTotalProgression`; interface defaults are empty/false (`UI/navigator/BookController.kt`). Consequently this card's Go path is **not supported on iOS currently**, even though Swift has a private progression-navigation implementation for other bridge paths.
- Card-specific chain: `ReaderContentsSheet` parameters → `JumpToPositionCard` → `ReaderIntent.JumpToBookProgress` → `jumpToBookProgress`; `toggleToc` → `loadChapterTicks` → `chapterTickProgressions` supplies its ticks.
- Repository references show no other consumers of `chapterTickProgressions` or `JumpToPositionCard`. Removing the card and its private tick-loading/UI wiring does not remove chapters, bookmarks, search, reading progress, or audio seeking.
- **Do not remove the general `goToTotalProgression` API indiscriminately:** `ReaderViewModel` also uses it for linked-copy/position-application flows; Android initial-open handling uses it too. `SeekToChapterProgress` is a separate reader/audio control, not this card.

## 7. Chapter-title search

- Active `ChaptersTab` filters only `chapter.title.contains(query, ignoreCase = true)` on every edit. Blank query returns all entries; no minimum query length, debounce, Enter requirement, accent normalization, snippet search, result highlighting, or hierarchy grouping.
- Query is local composable state, not saved per book, and resets when the tab is disposed/reopened. Href is not searched.
- It has no external consumers. It is separate from **Search in book**, which has its own intents, jobs, results, recent queries, and decorations.
- The unused legacy sheet also filters titles, but flattens filtered results to depth zero.

## 8. Bookmarks

- `UI/model/BookmarkUiModel.kt` and `Domain/model/BookmarkDomainModel.kt`: ID, book UUID (domain), locator href/type/title, resource progression, book progression, chapter index, position, created ISO date, sort order.
- **No text excerpt/snippet, separate chapter title, text anchor/CSS selector, note, or highlight range.** Rename overwrites `locatorTitle`, which initially copies the current position's title.
- Active row displays “Chapter N · Page P”, locator title (fallback “Saved page”), relative creation time, and edit/delete/drag actions. Stored book percentage exists but is not shown here.
- `position` is an EPUB locator position, **not rendered chapter page**; using it as Page P is misleading. Missing chapter index/position fabricates Chapter 1 / Page 1 (`ReaderOverlay.kt:1447–1448`).
- Supported: navigate, add current position via the reader toolbar, rename dialog, drag reorder, immediate delete. The active tab has no Add button; its empty state tells users to use the top bookmark icon (`Strings`, `reader_bookmarks_empty_overlay`).
- Add duplicate detection compares href + position; bookmarks without position can collapse distinct locations in one href.
- **Undo:** successful add has an Undo action that deletes the newly created bookmark. Delete itself has no undo/restore UI or confirmation; rename/reorder have no undo.
- `SQL/Bookmark.sq:32–35`: nondeleted rows sorted by `sort_order ASC, created_at DESC`. Default order is 0; dragging persists indices through `ReorderBookmarksUseCase`.
- Rows are lazy and use bookmark IDs as keys. Reordering is optimistic with a pending-order wait for database acknowledgement.

## 9. Other position lists

- User highlights/notes: **not supported** by the inspected reader models/UI and database schema. Search-result and narration highlights are temporary decorations, not saved annotations.
- Recent navigation history: **not supported** as a list. `previousTocPosition` is single-step session undo; `searchOrigin` is separate temporary return state.
- Recent **search queries** exist (`ReaderViewState.bookSearchRecents`), but these are not a list of visited pages.
- Other real location data: `Domain/positions/CopyPositionRow.kt` / `ObserveCopyPositionsUseCase`, linked-copy resume offers, and local/remote saved positions can back a “positions in other copies” feature. They are not currently part of Contents and are not historical chapter-read states.
- `SQL/Position.sq` has one local and one remote position per book (primary key book UUID), not a visited-location log. Reading-session data is separate, not a ready-made Contents history list.

## 10. Formats and iOS differences

- **eBook:** common Contents/Bookmarks sheet backed by EPUB TOC and locators; device voice can share this navigation where available.
- **Read-along:** same EPUB sheet/model, plus optional mini-player footer; narration availability comes from media overlays. No richer per-row narration data currently passed to Contents.
- **Read-along in audio-only mode:** `UI/reader/ReadAloudAudioOnlyView.kt` maps the flat EPUB TOC to audio-player track titles and uses `GoToChapterAndPlay` / previous/next-and-play callbacks.
- **Standalone audiobook, Android:** separate `AudiobookPlayerViewModel` and shared `UI/audiobook/AudioPlayerContent.kt`. It has its own inline track list and track bottom sheet, lazy rows, current-track selection, and animated scroll-to-current. Titles derive from downloaded audio filenames (`Android UI/audiobook/AudiobookPlayerViewModel.kt:222–244`), not EPUB TOC. No bookmarks tab there.
- **Standalone audiobook, iOS:** `iOS UI/audiobook/AudiobookPlayerScreen.ios.kt` is an empty actual composable: **not supported by that entry point**.
- Collapsible hierarchy and a cleaner Contents layout can be shared equally on Android/iOS from preserved `level`. Android's descendant-filled `children` and iOS's empty `children` must not be treated as equivalent direct-child trees.
- Both EPUB controllers calculate current-file pages/words through the shared scripts. All-chapter positions/ticks and the current percentage Go action need additional iOS bridge/controller work for parity.

## 11. Scale, repaint, and problems

- No fixed maximum TOC length or demonstrated largest production TOC in the inspected code. 120+ entries are not explicitly limited; largest tested size/performance is **unsure**.
- Active chapters use `LazyColumn`, height capped at 560 dp; bookmarks at 620 dp; sheet content at 88% of available height. Lazy composition limits visible-row work, not initial list/model allocation.
- Current-row index lookup is a linear `indexOfFirst` per composed row; filtering scans the whole list. Android's flattened descendants in every `children` list add allocation for deeply nested TOCs.
- Chapter rows keyed solely by href can collide for duplicate links; ordinal/current lookup also resolves the first matching href. Multiple headings within one resource require fragment-aware location resolution.
- Active tab switch uses a plain `when`, with no explicit crossfade. Current-chapter scroll uses immediate `scrollToItem`, suitable for e-ink.
- `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/EmberBottomSheet.kt`: e-ink uses a outlined bottom popup without scrim or entrance animation; other displays use a modal bottom sheet. E-ink current row uses a 2 dp border rather than tinted fill; the jump card uses steppers rather than a continuously dragged slider.
- Title search still filters on each keystroke on e-ink; drag-reorder still causes repeated updates. Reorder animation/ghosting quality was not device-tested.
- The unused legacy TOC has animated scroll, animated text color, and rotating chevrons. Reusing its hierarchy logic does not mean its animation policy is appropriate for e-ink.
- **Double percent:** affected resources contain literal `%%`: `reader_overlay_current_chapter`, `reader_jump_location`, `reader_jump_minus`, `reader_jump_plus`. Call sites use Compose `stringResource`. This source pattern matches the reported double-percent symptom; exact platform formatter output was not runtime-tested. `bookPercent` itself returns an integer, not a `%` string.
- **Hard-coded text:** “Chapters”, “Bookmarks · count”, “— p”, “Saved page”, and reader bookmark accessibility descriptions are outside translation resources.
- **Misleading labels:** resource progress presented ambiguously as “You’re here”; placeholder page noise; EPUB locator positions called bookmark pages; persisted chapter index displayed as if live; tick/resource ordinal called Chapter.
- **Underused data:** levels are available but no grouping/collapse; `currentPage` and computed `currentIndex` are unused in active `ChaptersTab`.

## Availability summary

| Item | Available? | Notes |
|---|---|---|
| TOC title / href | Yes | Both platforms; missing title falls back to href. |
| TOC depth | Yes | Flat preorder preserves `level` on Android/iOS. |
| Clean direct-child tree | Derivable | Reconstruct from level; platform `children` fields differ. |
| Part / Interlude type | No | Only publisher titles and nesting; no semantic category. |
| Authored chapter number | Title only | No separate number field; current ordinals are app-generated. |
| Collapsible groups | Legacy only | `TocNode` helpers exist; active sheet only indents. |
| Current rendered page/count | Yes, nullable | Current document only; layout-dependent JS. |
| All-chapter rendered page counts | No | Requires pagination/rendering and layout invalidation. |
| Current document word count | Yes, nullable | In-memory href cache in both controllers. |
| All-resource words / characters | Derivable | Local EPUB text parser; background work, not row metadata. |
| Position spans / start progressions | Android service | Not per-row model; common iOS method is unimplemented. |
| Per-chapter narration duration | Derivable | SMIL intervals; no Contents duration map or subsection aggregation. |
| Audiobook file duration | Yes, nullable | Cached duration list; file ≠ guaranteed authored chapter. |
| Upfront device-voice duration | No | Voice/synthesis dependent. |
| Current-resource progress | Yes | Used for “You’re here,” currently ambiguously labeled. |
| Book progress | Yes, nullable | `totalProgression`; jump card uses it. |
| Verified per-chapter read states | No | Legacy before/current/after coloring is inference only. |
| Current-file text time left | Yes, nullable | `ReadingSpeedTracker` + words + WPM; not shown in Contents. |
| Whole-book text time left | No | No reader-state aggregate. |
| Auto-scroll to current | Yes, conditional | Exact href match and blank filter; immediate scroll. |
| Audio-preserving chapter tap | Yes, intended | Pause/stop → navigate → resume wrapper. |
| Chapter jump undo | Yes, temporary | Short snackbar, one previous position. |
| Bookmark jump return | No | Does not capture TOC undo origin. |
| Previous/next chapter | Yes | Flat TOC neighbors; not controls in active Contents. |
| Remove percentage jump card | Isolated UI removal | Retain general progression-navigation APIs used elsewhere. |
| Percentage card Go on iOS | No currently | Common method defaults to false despite other Swift progression paths. |
| Chapter-title filter | Yes | Immediate case-insensitive substring filter; separate from book search. |
| Bookmark title / locator / date | Yes | Rename shares locator-title field. |
| Bookmark excerpt / note / exact text anchor | No | Not stored in bookmark model/schema. |
| Bookmark resource/book progression | Yes | Stored; active row does not display it. |
| Bookmark add / rename / reorder / delete | Yes | Add via toolbar; delete immediate. |
| Bookmark undo | Add only | No deletion restore UI. |
| User highlight/notes list | No | Search/narration decorations are not saved annotations. |
| Recent location history | No | Single undo/origin states only. |
| Positions across linked copies | Elsewhere | Domain data exists; not a Contents tab. |
| Audiobook chapter list | Track list, Android | Separate player; filenames/file indices, not semantic EPUB chapters. |
| Standalone iOS audiobook screen | No currently | Actual composable is empty. |
| Lazy large-TOC rendering | Yes | No explicit max; largest validated TOC is unknown. |
| E-ink sheet without animation | Yes | Ember popup; immediate current-row scroll. |
| E-ink Enter-only chapter filtering | No | Every edit updates the title filter. |
