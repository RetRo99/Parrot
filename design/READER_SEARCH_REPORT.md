# Reader search — implementation report

Code audit, October 1, 2026. No app code changed. Speed, exact landing accuracy, keyboard behavior and device repaint frequency were not measured; these need runtime testing.

## Source map

Paths below are relative to the repository root. Later references use the file/class names from this list.

- Shared reader directory: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/`
  - `ReaderViewModel.kt`: intents at lines 325–327; search at 1828–1903; audio-preserving jumps and TOC undo at 2020–2112.
  - `ReaderOverlay.kt`: sheet wiring at 603–614; `ReaderSearchSheet` at 1497–1545; `ReaderMiniPlayer` at 1077–1125.
  - `ReaderSearchResult.kt`: complete shared result model.
  - `ReaderViewState.kt`: search visibility, query, results, loading and failure flags; current position and TOC state.
  - `ReaderSyncCoordinator.kt`: book-position → narration synchronization.
- `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/navigator/BookController.kt`: default `search()` returns an empty list.
- `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/navigator/AndroidBookController.kt`: `search()` at 219–245; `PositionUiModel.toAndroidLocator()` at 756–774.
- `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/service/AndroidEpubPublicationService.kt`: opens the local EPUB with the default Readium parser.
- `feature/reader/ui/src/iosMain/kotlin/com/retro99/reader/ui/navigator/IosBookController.kt`: cancellable Kotlin callback wrapper at 256–275.
- `feature/reader/ui/src/iosMain/kotlin/com/retro99/reader/ui/bridge/EpubReaderBridge.kt`: `search()` interface and `SearchResultLocator`.
- `iosApp/iosApp/ReadiumEpubReaderBridge.swift`: `goToPosition()` at 381–411; `search()` at 462–516; TOC flattening at 518–537.
- `base-ui/src/commonMain/kotlin/com/retro99/base/ui/compose/EmberBottomSheet.kt`: e-ink popup versus regular modal sheet.
- `translations/src/commonMain/composeResources/values/strings.xml`: search messages at 413–418.
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigation.kt`: audiobook routes use `AudiobookPlayerScreen`, not the EPUB reader.

Dependency behavior was checked against the actual pinned upstream source, not assumed from generic API documentation:

- Android: Readium **3.2.0**, pinned in `gradle/libs.versions.toml`. [StringSearchService.kt](https://github.com/readium/kotlin-toolkit/blob/3.2.0/readium/shared/src/main/java/org/readium/r2/shared/publication/services/search/StringSearchService.kt), [SearchService.kt](https://github.com/readium/kotlin-toolkit/blob/3.2.0/readium/shared/src/main/java/org/readium/r2/shared/publication/services/search/SearchService.kt), and [EpubParser.kt](https://github.com/readium/kotlin-toolkit/blob/3.2.0/readium/streamer/src/main/java/org/readium/r2/streamer/parser/epub/EpubParser.kt).
- iOS: Readium **3.6.0**, resolved in `iosApp/Podfile.lock`. [StringSearchService.swift](https://github.com/readium/swift-toolkit/blob/3.6.0/Sources/Shared/Publication/Services/Search/StringSearchService.swift), [SearchService.swift](https://github.com/readium/swift-toolkit/blob/3.6.0/Sources/Shared/Publication/Services/Search/SearchService.swift), and [EPUBParser.swift](https://github.com/readium/swift-toolkit/blob/3.6.0/Sources/Streamer/Parser/EPUB/EPUBParser.swift).

## How search works

- **Engine:** Readium publication search service, not a custom server search or WebView find-in-page. Default EPUB parsers install `StringSearchService`; it extracts text and scans reading-order resources sequentially.
- **Where:** on-device, against the opened EPUB. Android launches from `viewModelScope`; the engine's match/locator loop uses `Dispatchers.IO`. iOS launches an unstructured Swift `Task { @MainActor in ... }` and awaits Readium operations; this is not an explicitly detached/background task.
- **Delivery:** Readium yields resource-sized batches, but both app adapters collect the entire iterator before returning one list. No progressive results reach the UI.
- **Speed:** unsure; no in-book search benchmark or timing instrumentation found. Every new query scans resources again; no custom persistent search index or result cache. Long books and common one-character queries can produce substantial work and memory use.
- **Input:** trims leading/trailing whitespace for execution, retains the typed string in state. Any nonblank query, including one character, is accepted. Debounce is **250 ms** after the latest change (`ReaderViewModel.searchBook`). Loading state starts immediately, before debounce finishes.
- **Cancellation:** changing/clearing input cancels the Kotlin job; selecting a result also cancels it. Android closes its iterator in `finally`; cancellation is cooperative, not guaranteed immediate during a CPU-bound match loop.
- **Dismissal bug:** `toggleBookSearch()` cancels the job when **opening**, not closing. Closing clears query/results and loading/failure flags but leaves an active scan running. The query check normally prevents its results appearing after dismissal.
- **iOS limitation:** Kotlin cancellation stops accepting the callback, but no cancellation handler/token cancels the Swift task. Old native scans can continue after edits, dismissal or a subsequent search. Swift search also has no explicit iterator-close call.

## Matching

- No app UI, shared interface or adapter passes search options. Everything uses engine defaults.
- **Android API 24+:** ICU string search; default case-insensitive, diacritic-insensitive, substring matching (`wholeWord = false`). Case, diacritic and whole-word options are available in the engine, but not exposed by the app. ICU's implementation couples case-sensitive matching to diacritic sensitivity.
- **Android below API 24, if supported by the build/device:** engine fallback is `String.indexOf`, case-sensitive and accent-sensitive substring matching, with no supported options. This is an engine compatibility branch, not a claim that such devices are currently supported.
- **iOS:** native `String.range(of:)`, default case-insensitive and diacritic-insensitive substring matching. Engine supports case, diacritic, literal/exact and regex options. Whole-word is not advertised/implemented by this default algorithm.
- **Phrase/multi-word:** the entire trimmed query is searched as a contiguous string, not separate AND/OR terms. No token search, relevance ranking or fuzzy matching. Whitespace/punctuation variants are not explicitly normalized by the app for matching.
- **Regex:** not enabled in today's UI. Supported as an option by the default iOS engine, not by Android's default algorithms. Regex metacharacters typed today are ordinary search text.
- **Exact option:** iOS supports literal comparison; Android's default algorithms do not advertise an `exact` option. Do not infer cross-platform parity from the generic Readium options type.

## Result data and snippets

`ReaderSearchResult` contains exactly:

- `href: String`: publication resource path.
- `type: String`: media type.
- `title: String?`: Readium locator title.
- `progression: Double?`: within-resource progression.
- `position: Int?`: Readium position, when supplied; **not a guaranteed rendered page number**.
- `totalProgression: Double?`: whole-book progression, when supplied.
- `snippet: String`: flattened plain text.

Additional facts:

- Raw Readium results have `Locator.Text.before`, `.highlight` and `.after`. The matched text is available separately **before adaptation**; the engine internally knows its range. Shared results retain neither the separate fields nor range offsets nor the complete locator/text anchor.
- Both adapters join before/highlight/after with spaces and collapse whitespace. Android additionally trims each piece. This loses original match boundaries and can introduce spaces inside substring matches.
- Default engine snippet context is approximately **200 characters on each side**, extended to avoid cutting a word, plus the match itself. Not a 200-character total limit; counting differs between Kotlin and Swift.
- Engines center the extracted context around the match, but UI displays the snippet **from its beginning**, limited to **three lines with end ellipsis**. The match can therefore lie beyond the visible lines. There is no styled match highlight.
- Engines try the TOC title for the resource, then the reading-order link/locator title. If none exists, the shared title is null and the UI prints the hard-coded `Result 1`, `Result 2`, etc. There is no shared TOC fallback/enrichment; an empty non-null title also bypasses that fallback.
- No chapter index, search-hit ID, audio timestamp or rendered page field is stored in the result.

## Counts, order and current location

- Default engines scan reading-order resources and enumerate matches forward within each resource. The app preserves that order; no relevance sorting.
- Readium iterators maintain a running `resultCount`; the complete total is known only after successful completion. Adapters ignore that field. The shared list's size is available after return, but can represent **partial results** after a swallowed engine failure.
- UI shows no count. `reader_search_result_count` exists and is imported in `ReaderOverlay.kt`, but is unused.
- Grouping by resource is possible using `href`; UI currently has a flat list with repeated titles. Grouping by semantic chapter needs TOC mapping, since one resource may contain several TOC entries or a chapter may span resources. Do not group only by display title.
- `ReaderViewState.currentPosition` is kept updated through `bookController.currentLocator`; `tableOfContents` is also available. Before/after/nearest markers are feasible when comparable progression data exists, but not implemented. Null progression needs a fallback; resource/chapter position alone cannot locate an exact match within the resource.
- `totalProgression` from the default engines is estimated from extracted-text offset and resource position boundaries, not a precise laid-out page coordinate.

## After tapping a result

- Intent: `GoToSearchResult` → `navigateKeepingAudio` → `goToSearchResult` → `bookController.goToPosition`.
- Builds a `PositionUiModel` from href/type/title/progressions/position; chapter index is found by **exact href equality** with the TOC. Fragment-bearing TOC hrefs can fail this lookup.
- Sheet closes. Query and results remain in ViewModel memory after selection, so reopening can reuse them. Explicitly dismissing the sheet instead clears them. No persistence across reader sessions.
- **No search highlight on the page:** the click path never applies search decorations. Android and iOS have decoration support for narration, but it is not used for search.
- **Precision limitation:** navigation drops Readium's matched-text locator anchor. Android reconstructs a locator without `Locator.Text`; iOS does likewise and omits `totalProgression` on the ordinary valid-href path. Landing is progression/position-based, not anchored to the exact word; runtime accuracy is unsure.
- **Next/previous match:** not supported on the page or in the sheet. No selected-hit index or match-navigation intents.
- **Back to where I was:** not supported for search. TOC/progress jumps store `previousTocPosition`, and TOC undo calls `undoChapterNavigation`; search does not store an origin or populate that field. An existing TOC undo origin is not a search origin.

## Formats and playback

- eBook EPUB and read-along EPUB both use this search path; media overlays do not change the text-search engine.
- Audiobook-only books route to `AudiobookPlayerScreen`; no in-book transcript/audio search is provided here.
- No explicit reflowable-only search restriction. Search relies on extractable text, so fixed-layout EPUB with actual text can be searchable; image-only/scanned content has no OCR search. Layout/device behavior was not tested.
- Search sheet does not pause narration/TTS simply by opening or typing. It can include a mini-player footer.
- Selecting a result while `isListening && isPlaying` pauses narration or stops TTS, navigates, waits for a changed href/progression (with a timeout), then toggles playback to resume (`navigateKeepingAudio`).
- Narration synchronization observes new book locations and the first visible sentence (`ReaderSyncCoordinator.start`). TTS resumes at the new visible text. The intended audio destination is the landed reading location, **not necessarily the exact matched word**, because its anchor was discarded.
- If audio is paused/not actively listening, selection does not auto-start it. Location updates can still prepare/synchronize narration through the coordinator. Exact audio timing is unsure without runtime testing.

## History and UI states

- **Recent in-book searches:** not supported, per-book or global. Only transient query/results in `ReaderViewState`.
- Library search has separate saved recents (`PreferencesKey.RecentLibrarySearches`, `BooksListViewModel`); reader search does not use them.
- **Idle/blank:** “Search the text of this book”. No recents or suggestions.
- **Loading:** plain hard-coded “Searching…” text; no spinner or progress/count. Existing results are cleared as soon as input changes.
- **No matches:** “No matches found”.
- **Thrown exception:** ViewModel sets `bookSearchFailed`; UI says “Search isn’t available for this book”. No error detail, distinct transient-error message or retry control.
- **Not searchable/uninitialized:** Android returns an empty list for absent publication/service; iOS returns empty for absent publication or search-start failure. These show **no matches**, not the unavailable message. No shared `isSearchable` capability flag.
- **Engine iteration error:** Android ignores the `SearchTry` returned by `iterator.forEach`; iOS catches and suppresses iteration errors. Both can return partial/empty lists as successful results, with no incomplete-results indicator.

## E-ink

- `EmberBottomSheet` uses a static bottom-pinned popup with outline, no scrim and no sheet animation in e-ink mode. Regular mode uses `ModalBottomSheet`.
- No search spinner, result animation or progressively streamed batches. Still searches automatically after typing pauses; each edit updates the field, clears results and shows loading, then replaces the body when complete. No e-ink-specific submit-only mode/debounce.
- The text field's cursor/focus behavior may repaint; device-level frequency is unsure. When a mini-player footer is present, playback label/progress state can also update while search is open; no explicit e-ink throttling in that footer.

## Main implementation problems

- Match context/range is discarded, preventing reliable snippet emphasis and exact text-anchored navigation in the current shared API.
- Three-line leading snippets can hide the actual match despite match-centered engine context.
- Missing/blank titles are not enriched from shared TOC; numbered fallback and loading text are hard-coded English.
- No displayed count, grouping, location markers, selected-hit tracking, next/previous match or search-origin undo.
- Dismissal cancellation is inverted; iOS cancellation does not reach the native scan.
- Unsupported search, no matches and swallowed errors are conflated; partial results can look complete.
- Every nonblank input, including one character, triggers full scanning; adapters buffer all hits without a cap or streaming UI. Performance on long books is unmeasured.
- Cross-platform matching-option parity is not guaranteed; the app exposes none of the engine options.
- No in-book search-specific tests found under `feature/reader/ui/src/commonTest`.

## Capability summary

| Capability | Supported? | Notes |
|---|---|---|
| On-device EPUB full-text search | Yes | Default Readium publication service. |
| eBook / read-along | Yes | Same EPUB text path. |
| Audiobook-only / OCR | No | No transcript or image-text search here. |
| Fixed-layout EPUB text | Conditional | No layout gate; needs extractable text. Not runtime-tested. |
| Progressive results | Engine only | App waits for complete list. |
| Long-book speed | Unsure | No measured baseline; scans per query. |
| Cancel on query change | Partial | Kotlin job cancelled; native iOS task continues. |
| Cancel on sheet dismissal | No | Cancellation occurs on opening instead. |
| Minimum length / debounce | Yes | One nonblank character / 250 ms. |
| Case-/accent-insensitive matching | Yes, default | Android ICU and iOS; older Android engine fallback differs. |
| Substring / contiguous phrase | Yes | Not token AND/OR or fuzzy search. |
| Whole-word option | Android engine only | Not exposed; default iOS algorithm lacks it. |
| Regex option | iOS engine only | Not exposed/enabled in current UI. |
| Separate before/match/after text | Engine only | Flattened/discarded by adapters. |
| Highlighted snippets | No | Plain text, three lines, end ellipsis. |
| Exact text-anchored result navigation | No | Original locator text is discarded. |
| Page highlight after selection | No | Narration decorations are separate. |
| Chapter title | Partial | Nullable engine title; no shared enrichment. |
| Position / book percentage data | Partial | Nullable; position is not guaranteed screen page. |
| Reading-order results | Yes | Default engines; order preserved. |
| Total count | Data only | List size after return; hidden failures may make it partial. |
| Chapter grouping | Not implemented | Resource grouping feasible; chapter mapping needs care. |
| Before/after/nearest markers | Not implemented | Current position exists; nullable/estimated hit progression. |
| Query retained after selecting hit | Yes, transient | Explicit dismissal clears it; no saved history. |
| Next/previous match | No | No hit index or page controls. |
| Search-origin undo | No | TOC undo state is not populated by search. |
| Audio-aware result jump | Yes, intended | Pause/stop, navigate, resume; exact timing unverified. |
| Recent in-book searches | No | Library recents are separate. |
| Idle/loading/no-results/error states | Partial | Basic messages; capability/error/empty conflated. |
| Animation-free e-ink sheet | Yes | Popup; automatic typing and playback updates remain. |
