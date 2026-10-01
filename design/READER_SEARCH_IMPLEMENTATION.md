# Reader search implementation

Based on `READER_SEARCH_REPORT.md`, the supplied screenshots, and the approved plan.
The referenced `design/ember/READER_SEARCH_PROMPT.md` and named PNG files were not present in this checkout.

## Changes

- `ReaderSearchResult.kt` retains separate before/match/after strings, the original serialized Readium locator, a reading-order index, and a search-session ID.
- `BookController.kt`, `AndroidBookController.kt`, `IosBookController.kt`, `EpubReaderBridge.kt`, and `ReadiumEpubReaderBridge.swift` provide batch streaming, completion/error delivery, capability reporting, anchored navigation, and separate search decorations. Kotlin cancellation reaches the Swift task and iterator. Swift batch acknowledgements provide backpressure.
- `ReaderSearchPresentation.kt` resolves shared TOC chapters using resource order and platform-resolved fragment boundaries. A precomputed resolver avoids repeated TOC traversal. It also calculates the before/after split without inventing a percentage when location data is missing.
- `ReaderViewModel.kt`, `ReaderViewState.kt`, and `ReaderIntent.kt` manage query debounce/submit, cancellation, running counts, partial failures, the first 500 retained results, previous/next selection, an independent return origin, and per-book/profile recent queries.
- `ReaderSearchSheet.kt` replaces the old inline search UI; `ReaderOverlay.kt` integrates the find bar and suppresses the normal overlay while searching. `SearchAnchorResolver.kt` supplements Readium decorations with foreground styling and resolves the containing sentence for audio resume.
- `Preferences.kt` adds a server/book-specific recent-search key. The reader UI Gradle file adds preference dependencies and the same HTML parser used by Readium.
- Translation resources contain all new UI labels, quantity-aware match/chapter counts, and capability/error messages.
- `ReaderSearchPresentationTest.kt` covers fragment titles, preceding TOC fallback, blank/filename titles, grouping identity, reading order, location splits, untouched match boundaries, and a 10,000-entry TOC regression.

## Follow-up design changes requested during Xiaomi testing

- The sheet now keeps a fixed height across idle, loading, results and empty states instead of resizing to content.
- Match text uses the primary ink color with a stronger accent background; cards have a distinct fill.
- The ambiguous “YOU ARE HERE” divider was replaced by explicit reading-position context and a collapsed “Ahead of your reading position” section. Later snippets and chapter titles require an explicit reveal. “Nearest to me” explicitly reveals and selects the later-results region.
- The reference reading position remains separate from temporary result navigation.
- The closed ahead section displays no hidden count and is present regardless of whether later matches exist. Until reveal, sheet summaries and chapter counts use only earlier retained hits; recent-query totals are omitted to avoid indirect disclosure.
- Singular match/chapter counts and literal percent formatting were corrected.

## Verification and limitations

- Android builds, reader host tests, and Kotlin iOS simulator compilation have passed during implementation.
- Installed debug builds on Xiaomi 2602BPC18G, Android 16/API 36.
- A long-book search on Oathbringer returned 945 matches in 136 chapters, with the first-500 notice.
- Device testing exposed an ANR in per-hit TOC resolution. Its stack trace identified the shared chapter resolver in the ViewModel batch update. Resolution is now precomputed, and batch grouping runs on `Dispatchers.Default` with cancellation checks. The same long-book query completed after reinstalling the fix.
- Full Xcode simulator packaging is blocked by the absent iOS 26.5 simulator runtime. CocoaPods dependencies compiled with a target-only build, and the Readium bridges and related playback Swift files passed standalone type-checking for the app’s actual iOS 18.2 deployment target. Whole-app standalone type-checking encounters an unrelated KotlinUnit/Void mismatch in `iOSApp.swift`.
- Full runtime verification of exact landing, cross-chapter previous/next, return origin, active narration/TTS, accented phrases, image-only/fixed-layout publications, and e-ink submit-only behavior remains outstanding. The user was also operating the Xiaomi; automated input was stopped to avoid conflicting with their testing.
- Readium resource batches are themselves buffered by the engine; the app retains only 500 navigation hits but still scans/counts the remaining matches.
- Cancellation is cooperative inside native Readium resource processing; cancelling tasks cannot guarantee immediate interruption of an already-running resource read.
- Search percentages and fragment progression are text-offset estimates, not rendered-page coordinates. Audio sentence targeting requires a resolvable sentence/SMIL mapping; otherwise the existing visible-location resume remains the fallback.
- iOS 15/16 need inline text-slice styling because CSS Custom Highlights are unavailable. Modern WebViews use CSS Custom Highlights without modifying EPUB markup. Both paths require runtime validation on iOS.

Unrelated pre-existing working-tree changes were preserved.
