# Book details screen — investigation report

Factual survey of what the Book details screen can show and do today, for the redesign. Everything below is from code at HEAD. File references use paths relative to the repo root.

**Core files**

| Concern | File |
|---|---|
| Screen (all UI) | `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailScreen.kt` (1943 lines) |
| ViewModel | `.../detail/BookDetailViewModel.kt` |
| State / intents | `.../detail/BookDetailViewState.kt`, `.../detail/BookDetailIntent.kt` |
| Action-visibility rules | `.../detail/LibraryBookActions.kt` |
| Open-with-prompts logic | `.../detail/BookDetailOpenPrompt.kt` |
| UI models | `feature/books/ui/.../model/BookUiModel.kt`, `BookUiModelMapper.kt`, `BookProgressInfoUiModel.kt`, `LinkedCopyUiModel.kt` |
| Domain models | `feature/books/domain/.../model/BookDomainModel.kt`, `BookType.kt`, `BookHome.kt`, `links/` |
| Progress models | `feature/reader/domain/.../model/PositionDomainModel.kt`, `BookmarkDomainModel.kt` |
| Strings | `translations/src/commonMain/composeResources/values/strings.xml` |

Note: the repo is mid-refactor ("One book, many copies", `docs/superpowers/plans/2026-09-30-one-book-many-copies.md` + `2026-10-01-one-book-many-copies-handoff.md`). The description below reflects the current code, not the plan.

---

## 1. Book types

**What kinds can open this screen**

- Any book in the system: **Storyteller server books**, **Audiobookshelf (ABS) server books**, and **"your library" books** (imported local files and/or Parrot Cloud copies — one and the same entity). See `BookDomainModel` (sealed: `StorytellerBook | LibraryBook`) and `BookUiModel` (same split).
- Media within a book: `BookType` enum (`feature/books/domain/.../model/BookType.kt`): **`EBOOK`** (plain ebook), **`AUDIOBOOK`** (audio-only), **`READALOUD`** ("EPUB with embedded media overlays that synchronize professional audio narration with text" — SMIL-based synced narration; loaded via `feature/reader/ui/.../media/smil/`).
- A book can carry **any combination** of the three: `StorytellerBook{ebook, audiobook, readaloud}`; `LibraryBook.mediaResources: List<MediaResourceUiModel>` with `mediaType` "ebook"/"audiobook"/"readaloud".
- **PDF: not supported** (no type/format anywhere; import hint says "Import EPUB or audio files from this device", `books_import_hint`).
- **Device TTS (text-to-speech read-aloud)**: exists but is a *reader feature*, not a book type — `feature/reader/ui/.../tts/` (`TtsReadAloudEngine`, `TtsVoicePreparationState`, neural voice packages, `SetTtsEnabled` reader setting). In the reader's audio sheet the two listen sources are presented as **"Narration" ("Synced with the text") vs "Device voice" ("Reads the text with your phone's voice")**, switchable via `ReaderIntent.SwitchListenSource(ListenSource.NARRATION | DEVICE_VOICE)`; "Not available for this book" when a source lacks media. Nothing about TTS appears on the detail screen.

**How the code tells them apart**

- `BookType` (per media item) + `hasEbook/hasAudiobook/hasReadaloud` (`BookUiModel`).
- Book "kind": the `BookUiModel`/`BookDomainModel` sealed split (`LibraryBook` vs `StorytellerBook`), plus `BookHome` enum (`ThisDevice, ParrotCloud, Storyteller, Audiobookshelf`, `BookHome.kt`) derived from `mediaResources`/`serverType`.
- `ServerType` (`base/.../ServerType.kt`): `Storyteller, Audiobookshelf, ParrotCloud, Local`; `LOCAL_SERVER_ID = "local"`, `PARROT_CLOUD_SERVER_ID = "parrot-cloud"`.

**What the screen shows/hides per type**

- Primary button picks one format in priority order **ebook → readaloud → audiobook** (`PrimaryMediaAction`, `BookDetailScreen.kt` ~1018).
- If more than one format: "Other formats" section with one card per format (`MediaActionButtons`). If exactly one: no format card at all, only `SingleFormatHousekeeping` ("Remove download from this device").
- **Library-book-only UI** (`BookUiModel.LibraryBook`): header status line ("In Parrot Cloud · Downloaded" / "In Parrot Cloud · Not downloaded" / "Only on this device"), "Add to Parrot Cloud", "Remove from Parrot Cloud", transfer status card, "Delete from this device". Server books get none of these; their remove-download goes through the reader cache.
- Library books carry **no series, tags, rating, or subtitle** (`LibraryBook.series = emptyList()`, `tags = emptyList()`, `rating = null`, `subtitle = null`) — those sections simply don't render for them.
- **Read-aloud generation status is not surfaced.** `ReadaloudDomainModel` carries `status, currentStage, stageProgress, queuePosition, restartPending` ("being prepared"), but `hasReadaloud = readaloud != null` — a narration still being generated shows as a normal ReadALoud format.

---

## 2. Sources & copies

**Where a book can come from**

- **Parrot Cloud** (cloud copy of a library book), **this device** (imported file), **Storyteller** server, **Audiobookshelf** server (`BookHome`, `ServerType`).
- For *linking*, "your library" (device + Parrot Cloud) counts as **one** source: `CopySource { Library, Storyteller, Audiobookshelf }` (`links/CopyKey.kt`).

**Same book in several sources — the model**

- One logical book = `BookLink(linkId, members: Set<CopyKey>)` (`links/BookLink.kt`). A `CopyKey` is a portable id: `library:<bookId>`, `storyteller:<uuid>`, `audiobookshelf:<itemId>`. A copy belongs to at most one link; a link holds at most one copy per source (`SameSourceLinkException`).
- The chosen entry carries the others as `BookDomainModel.linkedCopies: List<LinkedCopy>` (key, serverId, uuid, title, home, hasEbook/hasAudiobook/hasReadaloud, isDownloaded). Primary-copy choice: `links/PrimaryCopy.kt` (`groupLinkedBooks`, `choosePrimary`).
- Ebook + audiobook of the same work are **not separate entries**: they are media types on one entry. Entries only split across *sources*.

**What "Same book as…" does**

- UI: `LinkedCopiesSection` (`books/ui/links/LinkedCopiesSection.kt`) → `OnSameBookAsClicked` → LinkPicker screen (`links/LinkPickerScreen.kt`, title "Choose the same book on another server"). It lists `linkPickerCandidates` — books from *other* sources not already linked — with search.
- Picking one calls `LinkBooksUseCase` → `BookLinksDataRepository.link()` (`feature/books/data/.../links/BookLinksDataRepository.kt`): merges the two links (older `linkId` survives), writes `BookLinkEntity` in the local DB **and queues `SyncOutboxEntry` ("book_link") in one transaction**. Comment: links "are stored on this device and queued for Parrot Cloud; they never change anything on another server."
- **Undo** = "Not the same book" on a linked-copy row → `UnlinkCopyConfirmationDialog` ("Not the same book?" — "'…' on … will show as a separate book again.") → `UnlinkCopyUseCase` → `unlink()`: removes the copy from the link, **records a `Never` link decision for every remaining pair** (that pair is never suggested again), soft-deletes the link if fewer than 2 members remain, queues outbox entries.
- Related flows: link suggestions banner ("%1$d books may be the same across your servers · Review", `LinkSuggestionsBanner.kt`) → `LinkReviewScreen` ("Same book?" — Link / Not the same book / Skip / "Link all %d confident matches"). Decisions: `LinkDecisionType { Never, Skip }` (`links/LinkDecision.kt`).

**The dark "In Parrot Cloud" box under the remove buttons**

- It is `BookBackupProgressSection` (`BookDetailScreen.kt` ~1795): a rounded card (`surfaceVariant` at 0.55 alpha — the "dark box") listing the book's **Parrot Cloud file transfer** state, shown whenever `bookFileTransfers` is non-empty.
- **Not a dropdown, not a source picker, not tappable.** It shows one transfer at a time (first active, else first non-cancelled): status text, a progress bar + byte counts ("12 MB / 34 MB") while active, and small text buttons depending on state: **Cancel upload/restore**, **Retry upload/restore**, **Replace file in Parrot Cloud** (only for `file_exists` upload failures).
- State strings: "Waiting to upload", "Uploading to Parrot Cloud · N%", "Finishing upload…", **"In Parrot Cloud"** (completed upload — this is the text in the box you saw), "Upload failed: …", "Upload cancelled", "Restore queued", "Restoring · N%", "Verifying restored file…", "Restore complete", "Restore failed: …", "Replacing file…", "Transfer status unavailable." (`cloud_backup_*` / `cloud_download_*` strings).
- Model: `BookFileTransfer{transferId, direction "upload"|"download", mediaType, state: pending|transferring|verifying|finalizing|completed|failed|cancelled, bytesTransferred, totalBytes, attemptCount, lastError}` (`feature/books/domain/.../BookFileTransferTransport.kt`).

---

## 3. Actions — everything that exists

### On the detail screen (intents in `BookDetailIntent.kt`)

| # | Action | Where | What it does | Destructive | Network | Confirmation |
|---|---|---|---|---|---|---|
| 1 | Back | Top bar arrow | Pops the nav backstack | no | no | no |
| 2 | Favorite / unfavorite | Top bar heart | `ToggleFavoriteUseCase` (local + synced) | no | no | no |
| 3 | Read / Listen / Continue reading (N%) / Continue listening (N%) | Primary button when cached | `handleReadClick(type)` → opens Reader (`HomeNavigationIntent.RequestOpenReader`) | no | no* | — |
| 4 | Download to read / Download to listen | Primary button or format card when not cached | Library: `StartBookFileDownloadUseCase` (Parrot restore). Server: `DownloadMediaUseCase` (reader cache) | no | yes | no |
| 5 | Cancel download | Format card while downloading ("Cancel" + ✕) | `CancelBookFileTransferUseCase` / `CancelDownloadUseCase` | no | no | no |
| 6 | Remove download from this device / "Delete" (cache) | Single-format: text button "Remove download from this device". Multi-format: small red "Delete" in each cached card | Library: `RemoveBookFileDownloadUseCase` (deletes device file; book, position and cloud copy stay). Server: `DeleteMediaCacheUseCase` | semi (re-downloadable) | no | yes — "Delete Downloaded Content?" (`DeleteCacheConfirmationDialog`) |
| 7 | Add to Parrot Cloud | Outlined button ("Add to Parrot Cloud"), library books with a device copy and no cloud copy, Parrot active | `StartBookFileUploadUseCase` per media type; records rights attestation | no | yes | yes — `BackupRightsConfirmationDialog` with checkbox "I have the right to back up this file…" |
| 8 | Cancel upload | Transfer card | `CancelBookFileTransferUseCase` | no | no | no |
| 9 | Retry upload / Retry restore | Transfer card | `RetryBookFileTransferUseCase` | no | yes | no |
| 10 | Replace file in Parrot Cloud | Transfer card, upload failed with `file_exists` | `deleteRemoteBackup` then retry upload (keeps original attestation) | **yes** (overwrites cloud file) | yes | yes — `ReplaceCloudBackupConfirmationDialog` |
| 11 | Remove from Parrot Cloud | Outlined button under "Add to Parrot Cloud" | `RemoveFromParrotCloudUseCase`: device files re-origined to "import" first, then cloud copies deleted per media type | **yes** (cloud copy gone forever; device copy kept) | yes | yes — `RemoveFromParrotConfirmationDialog` ("…will be removed from Parrot Cloud and your other devices[. It stays on this device.]") |
| 12 | Delete from this device | Bottom of screen, only when the book is *only* on this device | `DeleteBookFromDeviceUseCase` — removes book + files; navigates back on success | **yes, irreversible** | no | yes — `DeleteLocalBookConfirmationDialog` ("This can't be undone.") |
| 13 | Same book as… | Outlined button in "Also in" area | Opens LinkPicker → `LinkBooksUseCase` (see §2) | no | no (device + outbox) | no |
| 14 | Not the same book | Text button per linked-copy row | `UnlinkCopyUseCase` (see §2) | semi (records Never decisions) | no | yes — `UnlinkCopyConfirmationDialog` |
| 15 | Open (linked copy) | Text button per linked-copy row | Pushes that copy's own BookDetail | no | no | no |
| 16 | Reading positions | "Reading positions" button (only with linked copies) | Positions screen (see below) | semi | sometimes | via apply sheet |
| 17 | Use This Device / Use Server | Inline in Reading Progress (conflict), and in `PositionConflictDialog` when opening | `ResolvePositionConflictUseCase.useLocal` (re-stamped + queued sync) / `useRemote` (local only) | semi (overwrites the other position) | no (queued) | the dialog itself |
| 18 | Continue / Stay here / Compare all | `LinkedResumeDialog` before opening | Continue: offered position becomes this copy's (`ResolveLinkedResumeUseCase.continueFrom`). Stay here: dismissal recorded (`LinkedResumeDismissals`, never re-offered). Compare all: opens Positions, writes nothing | no | no | the dialog itself |
| 19 | Series chip | Series section | Navigates to SeriesDetail | no | no | no |
| 20 | Tag chip | Tags section | **Nothing** — `OnTagClicked` is `// TODO: Navigate to tag filter` | — | — | — |
| 21 | Show more / Show less | Description | Expands description >200 chars | no | no | no |
| 22 | Retry (load error) | `OnRetryClicked` intent exists | Re-subscribes the book flow — **but no UI element dispatches it** (see §8) | no | no | — |

\* Opening itself is offline; `FindLinkedResumeUseCase` may fetch other copies' remote positions first.

**Positions screen** (`books/ui/positions/PositionsScreen.kt`, "Where you are in this book"): one row per linked copy — copy label ("Storyteller (read-aloud)"), position ("Chapter · 42%" or "2 h 13 min of 10 h 2 min · 42%"), source ("Read on this device 2 days ago" / "From Parrot Cloud …" / "Set from …"), "Latest" badge, "May be out of date", text excerpt. Select a row → "Use this position" → bottom sheet "Move these copies to this position?" with per-target checkboxes, previews ("same place" / "about 42%"), warnings ("This would move X to the start of the book." / "…almost finished." / "Can't update X yet.") → **Apply** writes the translated position into the other copies (`ApplyPositionUseCase` → `WriteCopyPositionUseCase`), result: "Updated X" / "Couldn't update X". No undo.

### Elsewhere (per single book)

- **Library list** (`books/ui/list/BooksListScreen.kt`): tap card → BookDetail; favorite toggle on card. **No long-press or per-item context menu exists** (no `combinedClickable`/per-book `DropdownMenu` in `feature/books/ui`). List-level: Import book, "Add all books on this device to Parrot Cloud" (`BackupAllBooksUseCase`, with attestation + confirm), filters, sort, list/grid.
- **Reader** (`feature/reader/ui/.../reader/ReaderIntent.kt`): search in book, TOC + chapter navigation (with undo), **bookmarks** (add, undo delete, rename, reorder, delete, go to, prev/next), **listen** (StartListening, switch listen source Narration⇄Device voice, play/pause, seek, skip ±10 s, prev/next sentence & chapter, speed 0.5–2.0x, sleep timer with presets + custom + "End of audio"), **TTS read-aloud** (enable, voice selection, neural voice package download/update/delete/retry/cancel, Supertonic terms acceptance, voice preview, rate/pitch), audio-only mode, reader settings, position-conflict resolution, linked-resume prompt (same dialogs as detail).
- **Continue reading shelf** (library list header, `feature/home/ui/.../ContinueReadingButton.kt`): "Resume" → Reader; overflow "Clear" → `ClearCurrentlyReading` — **no confirmation**.
- **Destructive actions without confirmation anywhere**: bookmark delete (reader, trash icon — no dialog, no undo snackbar), "Clear" continue reading, cancel in-flight upload/download.
- **Series detail** (`books/ui/series/detail/`): tap book → BookDetail, favorite. No series/book editing.
- **Not supported anywhere** (searched): mark as finished / unread · reset progress (beyond manual position apply) · edit metadata (title, author, cover) · add to / change / remove from series · user rating · share / export / open-with (`FileSharer` exists but is used only for Diagnostics logs) · remove a book from a Storyteller/ABS server · stream without downloading (opening requires `DownloadState.Cached`; `handleReadClick` no-ops otherwise) · "start over" · per-book "sync now" (only global "Sync now" in Sync & Backup settings) · delete individual bookmarks from the detail screen.

---

## 4. Metadata available

Shown on the detail screen vs. available in models. "Filled per source" = Storyteller / Audiobookshelf / Library (imported or Parrot-restored).

| Field | In models | Shown on detail | Filled per source |
|---|---|---|---|
| Description | yes (`description`) | yes, "Description" section | Storyteller yes · ABS yes (`metadata.description`) · Library yes (from EPUB metadata). **Arrives as HTML** from publishers; `plainTextDescription()` (`BookDetailScreen.kt` ~1435) strips tags/entities, then it is **rendered as Markdown** (multiplatform-markdown). Collapse at >200 chars to 140 dp + "Show more". Typical lengths: not measurable from code — unsure |
| Series name + number | `SeriesDomainModel(name, position: Double?)` | chips "Name #3" | Storyteller only (`series`). ABS series on the wire (`ServerBookSeries(name, sequence)`); Library: **not supported** (always empty) |
| Authors | `PersonDomainModel[]` / `author: String?` | "Author" + joined names (static text) | Storyteller list · ABS single `authorName` · Library single `author` |
| Narrators | `StorytellerBook.narrators`, ABS `narratorName` | **not shown** (not mapped into `BookUiModel`) | Storyteller + ABS only |
| Duration (audio) | `ServerBook.audioDurationMs`, `audioTrackDurationsMs`; positions `totalDurationMs`/`bookTimeMs` | **not shown** (only in Positions rows) | ABS yes; Storyteller via position only |
| Page count | **not supported** (no field anywhere) | — | — |
| Chapters | ABS wire `numChapters`/`chapters`; `PositionDomainModel.totalChapters` | not on detail (Positions shows "Chapter X") | ABS + position data |
| File size | `MediaResource.size`, `MediaFileDomainModel.size`, `CloudBookFileEntity.sizeBytes` | only in the transfer card (bytes) | all |
| Format | `mediaType` string + Parrot `format` | format cards "eBook" / "Audio" / "ReadALoud" | all |
| Language | `StorytellerBook.language`, `ServerBook.language` | **not shown** (used in link suggestions) | Storyteller + ABS |
| Publisher | ABS wire only (`AudiobookshelfBookMetadataApiModel.publisher`), dropped at mapping | **not supported** in domain | — |
| Published year | `publicationDate: String?` | "Published · 2021" (year only; placeholder dates like `0101-01-01` hidden — `formatPublicationDate`) | Storyteller + ABS + Library |
| ISBN / ASIN | `isbn`, `asin` on domain models | **not shown** (link suggestions only) | Storyteller/ABS; Library keeps `isbn` in `LibraryBookMetadata` JSON |
| Tags / genres | `TagDomainModel[]` | chips (tap does nothing) | Storyteller tags · ABS genres on wire · Library: **not supported** |
| Date added | `addedAt` / `createdAt` | not shown (sort option) | all |
| Last opened | `lastOpenedAt` | not shown | all |
| Rating | `rating: Float?` | star + number in header (read-only) | Storyteller only; ABS/Library: **not supported** |
| Cover | `coverUrl` (+ `ebookCoverUrl`/`audiobookCoverUrl` for Storyteller, `coverPath` locally for Library) | 120 dp cover + full-screen blurred backdrop (60 dp blur). No full-screen viewer, no resolution info | all |

---

## 5. Progress & reading data

- **Stored per book (one position record)** — `PositionDomainModel` / `ServerPosition` / `PositionEntity`: locator (`locatorHref/Type/Title/Target`, `cssSelector`), audio (`audioTimestampMs`, `chapterIndex`, `bookTimeMs`), `progression`, `totalChapters`, `totalDurationMs`, `totalProgression`, `position`, `origin` (`user|restore|remote|linked_copy|manual`), `observedAt`, `textAnchor` (text excerpt around an ebook place, device-only). Plus a separate **remote baseline** row (`PositionDatabase.getRemotePositionByBookUuid`). Reading and listening positions live in the same record shape (ebook fields vs audio fields fill in).
- **Detail shows**: one progress bar + percent (`displayProgression`, local preferred); on conflict two bars (Local / Remote) + "Use This Device" / "Use Server" (`ReadingProgressSection`). Section hidden when progress is 0.
- **Time left (read/listen)**: **not stored or shown** (durations exist for audio; reading time estimates only in reader settings' WPM).
- **Finished date / finished flag**: **not supported** (only ABS's wire `isFinished`/`finishedAt` in `AudiobookshelfMediaProgressApiModel`, not mapped up).
- **Total reading time / sessions**: yes, in data — `ReadingSessionDatabase` (`ReadingSessionEntity`: start/end, `durationMs`, `pagesRead`, progressions, WPM) and `BookReadingStatsEntity` (`totalDurationMs`, `sessionCount`); `StatisticsRepository.getSessionsByBook(bookUuid)` exists. **Not surfaced per book anywhere** (only the aggregate Statistics screen).
- **Read ↔ listen syncing ("read-along")** — yes, three mechanisms in `feature/reader/domain`:
  1. **Position translation** between copies/formats: `TranslatePositionUseCase` (`translate(source, position, target, others)`) maps ebook ↔ audio ↔ readaloud via file text + **SMIL timing bridges** (`reader/domain/translate/`), with confidence `Exact | High | Approximate`.
  2. **Automatic propagation**: `PropagateToLinkedCopiesUseCase` — after a `user` position is saved, other linked copies are moved to the same place (setting-gated: `LinkedCopyPropagationSetting`, "Update linked copies"; guarded against loops/overwrites; written with `origin = linked_copy`). Called from the audiobook player and headless playback sessions.
  3. **Manual apply**: Positions screen (see §3).
  Plus the **Linked resume prompt** (`FindLinkedResumeUseCase` — "the latest real reading wins, never the furthest"; ≥60 s newer and a different place) offered when opening from detail or in the reader.
- **Bookmarks**: yes — `BookmarkDomainModel` (locator + progression + title + `sortOrder`), full CRUD in the reader (`ObserveBookmarksUseCase`, `AddBookmarkUseCase`, `DeleteBookmarkUseCase`, `UpdateBookmarkTitleUseCase`, `ReorderBookmarksUseCase`), synced to Parrot Cloud (outbox type "bookmark"). **Count per book: no query exists**; nothing on the detail screen shows bookmarks.
- **Highlights / notes**: **not supported** — no models/persistence anywhere (only reader highlight-color styling during playback).

---

## 6. States

| State | In state (`BookDetailViewState`) | UI today |
|---|---|---|
| Loading | `isLoading = true` | `LoadingScreen` |
| Load error (server gone / signed out) | `error: AppError` (e.g. `NotFoundError("Server not found: …")` from `ObserveBookWithProgressUseCase`) | **Nothing renders** — the `when` in `BookDetailScreen` has no error branch (see §8) |
| Not downloaded | `DownloadState.Idle` | "Download to read/listen" (primary) / "Download" row on cards |
| Downloading (with %) | `DownloadState.Downloading(progress 0..1?)` | Primary: "Downloading… N%" + spinner (disabled); card: ring (+N%), "Cancel" |
| Downloaded / cached | `DownloadState.Cached` | "Read"/"Listen"/"Continue … (N%)"; card "Ready" + "Delete" |
| Download failed (server books) | `DownloadState.Failed` | Primary label "Couldn't download. Tap to try again." (tap retries) |
| Parrot transfer active (upload with %) | `bookFileTransfers` state `pending|transferring|verifying|finalizing` + `replacingBackupTransferId` | Dark card: "Waiting to upload" / "Uploading to Parrot Cloud · N%" / "Finishing upload…" / "Replacing file…", byte counts, Cancel/(Retry) |
| Parrot restore (download with %) | same, `direction == "download"` | "Restore queued" / "Restoring · N%" / "Verifying restored file…" |
| In Parrot Cloud | transfer `completed` | Card text "In Parrot Cloud"; header "In Parrot Cloud · Downloaded/Not downloaded" |
| Upload/restore failed | transfer `failed` + `lastError` (`file_exists`, `quota_exceeded`, `content_blocked`, `attestation_required`, `verify_failed`, …) | "Upload failed: <reason>" / "Restore failed: <reason>" + Retry, or "Replace file in Parrot Cloud" for `file_exists`; `bookFileTransferError` → snackbar |
| Uploading/pending cloud availability | `MediaResourceUiModel.remoteAvailability` = `UploadPending | Uploading` | Header keeps "In Parrot Cloud"; download button inert (see §8) |
| Sync conflict (progress) | `progressInfo.hasConflict` (UI: \|local−remote\| > 0.01) + `isResolvingConflict`, `conflictResolutionError` | Two progress bars + "Use This Device"/"Use Server" (disabled while resolving); before opening: `PositionConflictDialog`; errors → snackbar |
| Linked-resume offer | `linkedResumeOffer` | `LinkedResumeDialog` ("Continue from …?"), not dismissible without choosing |
| Narration (read-aloud) available | `book.hasReadaloud` | ReadALoud format card. **"Not available / being prepared" is not represented** (server-side `ReadaloudDomainModel.status/stageProgress/queuePosition` not surfaced) |
| TTS voice preparing | not on this screen (reader: `TtsVoicePreparationState Idle/Running/Complete/Failed`) | reader-only |
| Server offline | no explicit state | loads fail → blank; transfers sit in pending/failed with backoff auto-retry (max 10 attempts) |
| Signed out / Parrot not active | `parrotActive` (`ParrotCloudLibraryState.observeIsActive()`) | "Add to Parrot Cloud" hidden (and upload-retry hidden); everything else unchanged |
| File missing / corrupt | `MediaFileDomainModel.missing` exists but is not surfaced; hash mismatch surfaces as a transfer error | snackbar only |
| Empty formats | `primaryType == null` (no media at all) | Primary button absent; only housekeeping/metadata remain |

---

## 7. Navigation

**Tappable on the screen**

- Back arrow → backstack pop (`requestBack("toolbar_back")`; system back uses `"system_back"`).
- Primary/secondary media buttons → **Reader** (`HomeNavigationIntent.RequestOpenReader` → `HomeDestination.Reader(serverId, bookUuid, bookType, linkedResumeResolved)`) or download actions.
- Series chip → **SeriesDetail** (`HomeDestination.SeriesDetail`).
- Tag chip → **nothing** (TODO).
- Author, cover, rating, publication date → **not tappable** (no author page link, no full-screen cover).
- "Same book as…" → **LinkPicker** (`HomeDestination.LinkPicker`).
- Linked copy "Open" → pushes **another BookDetail** (`onNavigateToBookDetail(serverId, uuid)`).
- "Reading positions" → **Positions** (`HomeDestination.Positions`); "Compare all" in the resume dialog also goes there.
- Delete from this device → after success calls `onBack()`.

**Where this screen is reachable from**

- Library list (`BooksListScreen` → `OnBookClicked`) — including **library search results** (search lives inside BooksListScreen).
- Series detail (`SeriesDetailScreen` → `OnBookClicked`).
- Itself (linked copy "Open").
- Author detail would navigate here (`AuthorDetailScreen`), but `HomeDestination.AuthorsList`/`AuthorDetail` are **commented out** in `feature/home/ui/.../HomeDestination.kt` — currently unreachable.
- **Continue reading** shelf/bubble: opens the **Reader directly**, never BookDetail; the floating continue bubble is explicitly hidden on BookDetail (`hidesContinueBubble` — "it would cover the progress bar it duplicates").
- **Notifications**: never reach BookDetail. The Android media/playback notification deep-links `parrot://reader?serverId=…&bookUuid=…&bookType=…` (`MediaPlaybackService.kt` + `DeepLinkHandler.kt`) → **Reader** directly. Only three places construct `HomeDestination.BookDetail`: BooksList, SeriesDetail, BookDetail's own linked-copy open.

**Back** goes to the previous entry of the single Home backstack (`BottomSheetNavDisplay`). Bottom bar stays visible on BookDetail (only Reader/Settings hide it).

---

## 8. Current problems noticed

- **Load error = blank screen.** `BookDetailScreen`'s `when` handles `isLoading` and `book != null` only; an error with no cached book renders nothing, and `OnRetryClicked` is never dispatched by any UI element.
- **Inert download button.** When `remoteAvailability` is `UploadPending`/`Uploading`, `libraryActions.download = false`, but the button still reads "Download to read" and tapping silently does nothing (no disabled state).
- **Duplicate status info.** "In Parrot Cloud" appears twice (header status line and the completed transfer card); "Downloaded/Ready" appears in the header and on each card.
- **One transfer visible.** `BookBackupProgressSection` shows only the first active (else first) transfer; a multi-format book with two transfers hides the other. Cancelled transfers vanish entirely (no history).
- **Inconsistent remove-download affordance.** Single-format = full-width "Remove download from this device" text button; multi-format = tiny red "Delete" inside each card — same action family, very different weight and wording.
- **Add / Remove adjacency.** "Add to Parrot Cloud" and "Remove from Parrot Cloud" are stacked outlined buttons with similar styling; the destructive one sits right under the constructive one. The Add button also uses a download-style icon (`Icons.Outlined.Download`) for an upload.
- **Unlink side effect is hidden.** "Not the same book" also records `Never` decisions for every pair (the pair is never auto-suggested again) — the confirm dialog only says the book "will show as a separate book again".
- **Forced dialog.** `LinkedResumeDialog` has `onDismissRequest = {}` (must choose), and "Compare all" clears the pending open without opening the book — surprising.
- **Dead tags.** Tag chips look interactive but `OnTagClicked` is a TODO no-op.
- **Hardcoded English.** `BookHeader` passes literal `"Author"` as the metadata label while every other string goes through `StringRes`.
- **Read-aloud "being prepared" invisible.** `hasReadaloud = readaloud != null` regardless of the server generation status — a not-yet-generated narration shows as an available ReadALoud format.
- **Conflict threshold mismatch.** Domain `hasConflict` = any difference in progression; UI `BookProgressInfoUiModel.hasConflict` = >1 % difference. Under 1 % the screen shows a single local-preferring bar and silently ignores the remote.
- **Everything is one file / one state bag.** `BookDetailScreen.kt` (1943 lines) with a 25-parameter `BookDetailScreenContent`; `libraryMediaActions` is computed in state but never read by the screen (its `open` flag is never rendered anywhere at all).
- **E-ink-unfriendly.** Full-screen 60 dp blurred cover backdrop + 0.75 alpha scrim, dominant-color palette swap per book, spring cover-entrance animation, 600 ms animated progress bars, `animateContentSize` description, `AnimatedContent` heart, `TextAutoSize` shrinking labels — all heavy repaint; plus low-contrast alpha surfaces (`surfaceVariant` 0.55 card, `primaryContainer` cards).
- **Cross-source inconsistency in the same layout.** Library books lack series/tags/rating so sections pop in and out depending on source; date-added and last-opened exist in the model but are never shown anywhere on this screen.

---

## Summary table

| Action | Exists? | Where today | Destructive? | Needs network | Notes |
|---|---|---|---|---|---|
| Read (ebook) | yes | Detail primary/format card, Reader | no | no (cached) | Only when cached; else "Download to read" |
| Listen (audiobook / read-aloud) | yes | Detail primary/format card | no | no (cached) | read-aloud = synced narration (SMIL) |
| Device TTS read-aloud | yes | Reader only (settings + voice sheet) | no | yes (voice package download) | Not on detail; "being prepared" state exists in reader |
| Continue vs start over | partial | "Continue reading/listening (N%)" on button | no | no | **Start over: not supported** |
| Download (per format) | yes | Detail | no | yes | Library = Parrot restore; server = reader cache |
| Stream without downloading | **not supported** | — | — | — | `handleReadClick` requires `DownloadState.Cached` |
| Cancel download | yes | Format card / transfer card | no | no | |
| Remove download / delete cache | yes | "Remove download from this device" or per-card "Delete" | semi | no | Confirm dialog; re-downloadable |
| Upload to Parrot Cloud | yes | "Add to Parrot Cloud" | no | yes | Attestation checkbox required |
| Cancel / Retry upload | yes | Transfer card | no | retry: yes | |
| Replace file in Parrot Cloud | yes | Transfer card (`file_exists` failure) | **yes** | yes | Confirm; overwrites cloud file |
| Remove from Parrot Cloud | yes | "Remove from Parrot Cloud" button | **yes** | yes | Confirm; keeps device copy |
| Remove from server (Storyteller/ABS) | **not supported** | — | — | — | |
| Delete local file / book | yes | "Delete from this device" (library, device-only books) | **yes, irreversible** | no | Confirm; navigates back |
| Favorite | yes | Detail top bar; library cards | no | no | Synced via outbox |
| Mark as finished / unread | **not supported** | — | — | — | ABS `isFinished` on wire only |
| Reset progress | **not supported** | — | — | — | Nearest: Positions → "Use this position" |
| Change position manually | yes | Positions screen ("Use this position" + Apply) | semi | sometimes | No undo; warnings for start/end collapse |
| Resolve progress conflict | yes | Detail progress section + `PositionConflictDialog` | semi | no (queued) | "Use This Device" / "Use Server" |
| Continue from linked copy | yes | `LinkedResumeDialog` on open | no | sometimes | Continue / Stay here / Compare all |
| Add to / change series | **not supported** | — | — | — | Storyteller metadata is read-only here |
| Edit metadata (title/author/cover) | **not supported** | — | — | — | |
| User rating | **not supported** | — | — | — | Storyteller rating is display-only |
| Link "Same book as…" | yes | Detail → LinkPicker | no | no (queued to Parrot) | Merges links; portable `CopyKey`s |
| Unlink "Not the same book" | yes | Linked-copy row (confirm) | semi | no (queued) | Also records Never-decisions |
| Open linked copy | yes | Linked-copy row | no | no | Pushes another BookDetail |
| Reading positions | yes | "Reading positions" (needs linked copies) | semi | sometimes | Apply-to-others sheet |
| Share / export / open file | **not supported** | — | — | — | `FileSharer` used only in Diagnostics |
| Sync now (per book) | **not supported** | Global in Sync & Backup settings | no | yes | |
| Tag filter | partial | Tag chips | — | — | Click handler is a TODO no-op |
| Bookmarks (per book) | yes | Reader only | no | no (queued) | No count shown anywhere; not on detail. **Bookmark delete has no confirm and no undo** |
| Clear from Continue reading | yes | Continue-reading shelf overflow "Clear" | semi | no | No confirmation |
| Highlights / notes | **not supported** | — | — | — | |
| Per-book statistics | partial | Data only (`getSessionsByBook`) | — | — | Not shown in any UI |
| Cover full-screen view | **not supported** | — | — | — | |
| Author page navigation | **not supported** (unreachable) | AuthorDetail code exists, destination commented out | — | — | Author text is not tappable |
