# Bookmarks, highlights & notes — Step 1 investigation and plan

Code audit, October 4, 2026. No app code changed. This answers Step 1 of `design/ember/BOOKMARKS_PROMPT.md` and waits for an OK before Step 2.

Note: the prompt refers to `design/ember/DESIGN_SYSTEM.md`, which is not in the repo or the upload. The Ember tokens live in code (`base-ui/.../compose/EmberTokens.kt`). That file has no highlight colours yet (amber, rose, sage, sky). I'll add them as tokens for day, night and e-ink, using the screenshot colours as a starting point.

---

## 1. Today: model, storage, sync

**Model.** `bookmarks` table (`lib/database/.../Bookmark.sq`), reached through `BookmarkEntity`, `BookmarkLocalModel`, `BookmarkDomainModel` and `BookmarkUiModel`:

| column | notes |
|---|---|
| `id` | client generated (`ReaderViewModel.addBookmark` uses a nanosecond-suffix id) |
| `book_uuid` | the local book id; for library books this equals `library_book_id` |
| `locator_href`, `locator_type`, `locator_title` | Readium locator. The title is the chapter title, and you can rename it |
| `progression`, `total_progression`, `chapter_index`, `position` | page-based. **No text and no text anchor** |
| `created_at`, `sort_order` | manual ordering |
| `remote_revision`, `deleted_at` | sync columns. Deletes are already soft (tombstones) |

**UI.** `ContentsBookmarkList.kt` (459 lines) on the "Bookmarks" tab of `ReaderContentsSheet`. It supports rename, delete with Undo, previous/next bookmark, the dashed "This page is bookmarked" box, and "already bookmarked" / "save failed" snackbars. A page "matches" a bookmark by position (`bookmarkMatchesPosition`).

**Sync: bookmarks don't sync today, although they look as if they should.**
- Every add/rename/delete/reorder writes a `SyncOutboxEntry` with `entity_type = "bookmark"` (`ReaderLocalDataSource.toSyncOutboxEntry`).
- `ParrotCloudSyncAdapter` doesn't list `bookmark` as unsupported, so the entries go to `push_sync_changes` on the library-mutation channel.
- The server RPC (`20261003000000_parrot_cloud_security_hardening.sql`) has no `bookmark` branch. It answers `rejected / unsupported_mutation`.
- `LibraryMutationSyncEngine.scheduleRetry` then retries with backoff capped at 2⁶ s. So every bookmark mutation **is retried about once a minute forever** and never reaches another device. There is no pull side either (`applyRemoteChange` has no bookmark case).
- Storyteller and Audiobookshelf adapters explicitly skip `bookmark`.

**What we can migrate.** Every local bookmark row, but only from the device it was made on. Nothing was ever stored server-side, so there is nothing to migrate in the cloud. From each row we can carry over href, progression, total progression, position, chapter title, created time and tombstones. The text snippet doesn't exist yet. It is resolved lazily (see §3.4). The stuck `bookmark` outbox entries are deleted in the same migration.

---

## 2. Readium: what each platform gives us

Pinned versions: Android **kotlin-toolkit 3.2.0**, iOS **swift-toolkit 3.6.0**. Both already use the decoration API (read-aloud highlight, search hits, settings preview), so groups, custom templates and alpha handling are proven in this codebase.

| Need | Android 3.2 | iOS 3.6 | Parity |
|---|---|---|---|
| Selection locator (href, progression, `text.before/highlight/after`) + rect | `SelectableNavigator.currentSelection()` | `SelectableNavigator.currentSelection` | ✅ |
| Replace the system menu with our toolbar | `Configuration.selectionActionModeCallback`: a callback that shows an empty menu, while we draw a Compose toolbar | `editingActions` + `SelectableNavigatorDelegate.navigator(_:shouldShowMenuForSelection:) → false`, then we draw our own view | ⚠️ see below |
| Clear the selection after acting | `clearSelection()` | `clearSelection()` | ✅ |
| Draw highlights | `DecorableNavigator.applyDecorations(group)` | same | ✅ |
| Tap on a highlight → detail | `addDecorationListener(group)` → `onDecorationActivated(event)` with rect | `observeDecorationInteractions(inGroup:)` | ✅ |
| Note marker at the end of a highlight | custom `HtmlDecorationTemplate` | custom `HTMLDecorationTemplate` | ✅ |
| E-ink 2dp underline | `Decoration.Style.Underline` / custom template | same | ✅ |
| Layout-independent text anchor | Locator `text` quote + progression. Readium's own JS anchors decorations by text quote, so it survives font size and layout changes | same | ✅ |
| First sentence on the page (bookmark snippet) | `firstVisibleElementLocator()`, then `publication.content(locator)` with a sentence tokenizer (the same Content API the TTS uses) | same APIs | ✅ |
| Does this page contain anchor X? (ribbon, filled icon) | not in the API; we run our own JS through `evaluateJavascript`, resolving the quote to a DOM range and testing it against the viewport | same through `evaluateJavaScript` (already used in the bridge) | ✅ custom JS on both |

**Things that can't be done, or can't be done the same way on iOS:**
1. **Selection-change events on iOS.** iOS doesn't report each drag of a selection handle. It asks `shouldShowMenuForSelection` when the edit menu would appear (after long-press, and again when a handle drag ends). That's enough to place our toolbar, but the toolbar only moves once a drag ends, not during it. Android behaves the same through ActionMode.
2. **Accessibility of highlights inside the page.** Decorations are drawn in a separate layer, so TalkBack and VoiceOver can't announce "highlighted" while reading book text on either platform. Doing that means injecting ARIA spans into the book's DOM, which is fragile and changes text offsets. I propose announcing highlights only on the ribbon, rows, sheets and the decoration tap target. "Highlights announced with their note" inside the text **is not achievable cleanly on either platform**.
3. **Highlights across chapters** (two spine resources) can't work on either platform: a WebView selection can't cross resources. **Across a page break** inside one chapter works when the selection already spans it, for example a long-pressed sentence that runs onto the next page. Dragging a handle past the page edge to turn the page isn't supported by Readium on either platform.
4. **Search for the selection** reuses the in-book search. The iOS search scan can't be cancelled (known from `READER_SEARCH_REPORT.md`). Not new, but it affects this entry point too.

---

## 3. Proposed design

### 3.1 Book identity
We use the existing **`CopyKey`** (`library:<libraryBookId>`, `storyteller:<uuid>`, `audiobookshelf:<itemId>`). It is portable across devices and is already what book links store. Each item stores `book_key` plus the local `book_uuid` for joins. It also stores a **title/author snapshot**, so the "Notes & highlights" screen can list books this device has never seen.

**Linked copies** (for example a library EPUB linked to a Storyteller copy): the per-book list shows items from every copy in the link. Opening one resolves its href in the open copy, falls back to finding the quote within that href, then to total progression. ⚠️ Risk: different EPUB builds of the same book can differ in hrefs and text.

### 3.2 Local table `saved_items` (new, replaces `bookmarks`)

```
id TEXT PK                 -- UUID v4, client generated (never derived)
book_key TEXT NOT NULL     -- CopyKey
book_uuid TEXT NOT NULL
book_title TEXT, book_author TEXT        -- display snapshot
type TEXT NOT NULL         -- 'bookmark' | 'highlight'
href TEXT NOT NULL, media_type TEXT
progression REAL, total_progression REAL, position INTEGER
chapter_title TEXT
text_before TEXT, text_quote TEXT, text_after TEXT   -- anchor; quote = snippet for bookmarks
color TEXT                 -- 'amber'|'rose'|'sage'|'sky', highlights only
note TEXT                  -- null/empty = no note
audio_href TEXT, audio_ms INTEGER        -- listening bookmarks (media overlay clip)
snippet_pending INTEGER NOT NULL DEFAULT 0   -- migrated rows waiting for a snippet
created_at TEXT, updated_at TEXT NOT NULL, deleted_at TEXT
remote_revision INTEGER
INDEX (book_key, deleted_at), INDEX (book_uuid), INDEX (updated_at)
```
"Notes" is a filter (`note IS NOT NULL AND note <> ''`), not a type, as the prompt asks. **Book order** sorts by `total_progression`, then href and progression, then `created_at`.

### 3.3 Parrot Cloud sync
- **Table `public.cloud_saved_items`**: `(cloud_user_id, item_id uuid)` PK, `book_key text`, `payload jsonb`, `client_updated_at timestamptz`, `deleted_at timestamptz`, `revision bigint`, `updated_at`. Row-level security matches the other tables (owner only; writes only through the RPC).
- **Push:** a new `saved_item` branch in `push_sync_changes` for `upsert` and `delete`. It goes on the existing library-mutation channel, using the outbox, idempotency through `sync_mutations`, and the per-account advisory lock.
- **Conflicts: last-write-wins per item on `updatedAt`.** The server accepts an incoming change only if its `client_updated_at` is newer than the stored one. Ties go to the lexicographically larger mutation id. Otherwise it returns `conflict` with the stored payload, and the client applies it. The id is client generated and is the primary key, so **edits on two devices can never duplicate an item**. `client_updated_at` more than 5 minutes in the future is clamped to server time, so a device with a wrong clock can't win forever.
- **Deletes** are tombstones: the payload is kept, `deleted_at` is set, and LWW applies in the same way, so a later edit on another device can bring an item back. Tombstones are garbage-collected after **180 days**.
- **Pull:** each accepted change writes a `sync_changes` row (`entity_type = 'saved_item'`). The client adds a `saved_item` case to `applyRemoteChange` that runs the same LWW comparison locally.
- **Limits** (the RPC already caps each mutation at **8 KiB**): quote ≤ **1,500 chars** (longer selections are refused with "That's too long to highlight"), note ≤ **2,000 chars**, before/after context ≤ 64 chars each, and **50,000 items per account**. Worst case with multi-byte text: 1,500 + 2,000 chars ≈ 7 KiB, which fits.
- **Offline:** already handled. The outbox queues changes and the bounded pass drains them.
- **Signed out:** outbox entries are written with `cloudUserId = null`. On sign-in, `SyncOutboxPreflight.bindUnassignedMutations` binds them to the account, they upload, and LWW merges them with the server copy. Nothing new is needed beyond including `saved_item` in that path. *Open question:* signing out today clears other synced data (`DataClearable`). I propose saved items follow the same rule as reading positions.
- **Sync state words** come from the existing sync status (signed in + outbox empty → "✓ Synced to Parrot Cloud"; pending + offline → "Will sync when you're online"; signed out → "Sign in to sync across devices").
- **Storyteller / Audiobookshelf books** sync through Parrot Cloud by `book_key`, the same as library books. They must not depend on the I4 rule ("only library books sync"), which positions use today.

### 3.4 Migrating existing bookmarks
A migration (`34.sqm`) copies `bookmarks` rows into `saved_items` with `type='bookmark'`, `snippet_pending=1` and `updated_at = created_at`. `book_key` is `library:<book_uuid>` when a `library_books` row exists. For other books, the key is resolved the next time the book opens. The migration then deletes the `bookmark` outbox entries and drops `bookmarks`. **Lazy snippet:** when a book opens, each pending item is resolved through the Content API at its locator (first sentence). It fills the quote and anchor, clears the flag and syncs. Until then, rows show the chapter title. Custom bookmark titles become the note, so nothing typed is lost. Manual `sort_order` is dropped because the new lists use book order.

### 3.5 Storyteller / Audiobookshelf
- **Audiobookshelf** has a bookmark API only for audiobook items (`/api/me/item/:id/bookmark`: time + title). Its ebook reader keeps no highlights on the server. ABS audiobooks open in `AudiobookPlayerScreen`, not the EPUB reader. **Proposal:** don't write to ABS. Importing ABS audio bookmarks (read-only, as listening bookmarks) could come later.
- **Storyteller:** the API we use has no bookmark or highlight endpoints (the adapter only syncs positions). **Proposal:** Parrot Cloud only, by `book_key`.

### 3.6 Module and composables
A new **`feature/saved`** module (`domain` / `data` / `ui`), because the reader, Book details and Settings/Library all use it. The reader keeps only the in-page parts. The old bookmark use cases are removed.

- Reader (`feature/reader/ui`): `BookmarkToggleButton`, `SavedUndoBar` ("Bookmarked · Add note · Undo"), `PageRibbon`, progress ticks inside `BookProgress`, `SelectionToolbar` (colour row + Note/Copy/Search/Share; placed below the selection, or above it if there's no room), and `BookController` additions (`selections` flow, `applySavedDecorations`, `savedDecorationTaps`, `firstVisibleSentence()`, `anchorsOnPage()`, `clearSelection()`), implemented in `AndroidBookController` and `ReadiumEpubReaderBridge.swift` / `IosBookController`.
- `feature/saved/ui`: `SavedItemDetailSheet`, `NoteEditorSheet` (with the "Discard this note?" confirmation), `SavedListContent` (filter chips, chapter groups, `SavedItemRow`, edit mode, `SavedSyncFooter`, `SavedEmptyState`) used both inside `ReaderContentsSheet` ("Saved · N") and full screen, `ExportSheet` + `SavedExportFormatter` (text/Markdown in book order), `NotesHighlightsScreen` (search, chips, Latest, By book), and the "Saved · 19" row for Book details.

### 3.7 Commit order
1. Supabase: `cloud_saved_items`, the `saved_item` push branch, the tombstone GC job, and pgTAP tests (LWW, ties, future clamp, tombstone, limits).
2. Database: `saved_items` + `34.sqm` migration (with an androidHostTest from a v33 snapshot) + DAO.
3. `feature/saved` domain/data: repository, outbox payloads, the Parrot Cloud applier (push/pull/conflict), highlight merge logic, export formatter, and unit tests.
4. Platform bridge: selection, decorations, taps, first-visible-sentence and anchor-on-page JS for Android, then iOS.
5. Reader: bookmark toggle, undo bar, ribbon, ticks, listening bookmark.
6. Selection toolbar, highlight create/merge, Note/Copy/Search/Share.
7. Detail and note sheets.
8. Contents "Saved" tab: filters, rows, edit/delete + Undo, empty state, sync footer.
9. Export sheet.
10. "Notes & highlights" screen + Book details row + Settings/Library entry.
11. E-ink pass, accessibility labels, analytics events, strings.
12. Remove the old bookmark code, then a docs/QA checklist.

---

## 4. Expensive or risky parts of the prompt

1. **Merging overlapping highlights** needs exact range comparison. Text quotes and progression alone are too coarse. I'll resolve both anchors to DOM ranges in JS and compare boundary points (custom JS on both platforms). If two devices merge different overlaps while offline, both results survive. That's acceptable, but it isn't a perfect merge.
2. **Highlight announcements inside book text** (§2.2): not achievable cleanly. I propose the alternatives above.
3. **"Listening, 4:12:08"**: a real audio timestamp exists only for read-aloud books with media overlays (Storyteller). TTS listening has no stable clock. There I store the sentence anchor and resume TTS from it, and the row shows "listening" without a time. Pure audiobooks (`AudiobookPlayerScreen`) aren't part of this reader. Should they get the bookmark button too? (Separate scope.)
4. **Ribbon and filled icon** need a JS viewport check after every page turn and font change. That's cheap, but it adds a round-trip, so I'll debounce it with the existing locator flow.
5. **Linked copies** with different EPUB builds may not resolve exactly (§3.1).
6. **Server limits** cap the length of highlights and notes (§3.3).
7. **Opening an item for a book not on this device** reuses the existing download flow. Storyteller/ABS books need that server to be signed in on this device. If it isn't, we show "Sign in to <server> to open this book".
8. **Builds:** this container has **no Android SDK and no Xcode**. I can run the shared/JVM unit tests and the Supabase SQL tests here. The Android and iOS builds and the on-device checks in Step 3 (font-size anchoring, two-device offline edits, all three themes, 500+ items) need a machine with the SDKs or CI.

## 5. Decisions I need from you
1. OK to replace `bookmarks` with `saved_items` and drop manual ordering and custom titles (titles become notes)?
2. When signing out: keep saved items on the device, or clear them like other synced data?
3. Should items from linked copies appear together in one book's list?
4. Is the ABS audiobook player in scope for listening bookmarks, and should ABS audio bookmarks be imported?
5. Limits: quote 1,500 / note 2,000 chars / 50k items per account / 180-day tombstones. Do these work for you?
