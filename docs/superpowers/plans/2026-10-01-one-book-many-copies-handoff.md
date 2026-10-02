# Handoff: "One Book, Many Copies" (StoryTellerKMP)

You are continuing an in-progress implementation of
`docs/superpowers/plans/2026-09-30-one-book-many-copies.md` in
`~/StudioProjects/StoryTellerKMP`. Read that plan first; it is the spec. This document tells you
what is already done, what was learned that the plan gets wrong, and what is left.

## Ground rules from the user (override the plan where they conflict)
- Work directly on `main`, in the current checkout (the user chose this). Do NOT create branches
  or worktrees.
- NEVER commit, push, merge or rebase without asking the user first. Nothing is committed yet.
- Do all remaining slices (2, 3, 4) back to back. Slice 5 is optional: ask before starting it.
- Write unit tests (kotlin.test, hand-written fakes, `// Given / // When / // Then`), but do NOT
  run anything on a device or emulator. Skip every "device check" section of the plan and say
  in your report that it was skipped.
- Supabase: local stack only (`scripts/supabase/reset.sh`, `scripts/supabase/test.sh`). Never
  touch the linked/remote project. Docker must be running (`open -a Docker`).
- Don't reuse anything from `feature/unified-library` or `backup/unified-library-*`.
- Keep UI changes functional and minimal; the user will do visual design later.
- Kotlin style: 100-char lines, 4 spaces, no wildcard imports, trailing commas, named lambda
  params (no `it`), no semicolons.
- The user's own uncommitted work is in the tree. Do NOT revert, reformat or overwrite it. Only
  make additive edits in these files:
  base-ui/.../EmberTokens.kt, ParrotTheme.kt, composeApp/... (navigation files,
  Platform.android.kt, StartupAuthRouteResolverTest.kt), feature/auth/domain/build.gradle.kts,
  feature/auth/domain/.../CheckAuthStateUseCase.kt (you DO need to edit this one; keep the user's
  changes), feature/books/ui/.../list/BooksListScreen.kt,
  feature/cloud-account/ui/.../CloudAccountScreen.kt, feature/home/ui/.../HomeNavigation.kt,
  feature/login/ui/.../LoginNavigation.kt, WelcomeScreen.kt, settings.gradle.kts,
  translations/src/commonMain/composeResources/values/strings.xml (you will edit it in slice 4;
  keep the user's edits), plus untracked files (literata font, docs/superpowers/, docs/*.md,
  feature/auth/domain/src/commonTest/, WelcomeReadAlongIllustration.kt,
  scripts/keep-onyx-awake.sh, tools/).
- Some deletions are already staged via `git rm` (ImportedBook.sq, LocalBookFile.sq,
  lib/database/api/.../importedbooks/*, LocalBookFileEntity.kt, LibraryBookMutation.kt,
  dao/importedbooks/*, LibraryBookQueriesTest.kt). That's intentional.
- "Verified" means you ran it and saw the output. Report exact commands and results.

## Progress: about 20% overall
- Slice 1 (backend): DONE and verified.
- Slice 2 (client, one ID): about 30% done. The database layer is done; nothing above it is.
- Slices 3 and 4: not started.
- THE APP DOES NOT BUILD RIGHT NOW. Every module that used the old DB API is broken until slice 2
  is finished. `lib/database/implementation` compiles and its tests pass.

## Slice 1: done (backend)
Files:
- NEW `supabase/migrations/20261001000000_parrot_cloud_client_book_ids.sql`
- NEW `supabase/tests/client_book_ids_test.sql` (20 checks, covering plan cases 1–8)
- The 9 old pgTAP files had their `cloud_books` insert column names changed to
  `source_content_hash`, `source_content_hash_algorithm`.

Verified: `scripts/supabase/reset.sh && scripts/supabase/test.sh` gives 11 files, 160 tests,
Result: PASS. The local stack currently runs this schema. A search of the live pg_proc bodies
found no `|| ':' ||` hash-built IDs, and `push_sync_changes` was the ONLY function that read
`cloud_books.content_hash`.

Backend contract the client must now match:
- `cloud_books.id` = client book UUID (no default any more). `content_hash*` has been renamed to
  `source_content_hash*`, which is nullable; `format` is nullable; `title` is required.
- The `library_book` upsert payload is read from `library_book_id` (UUID), `title`, `author`,
  `format`, `source_content_hash`, `source_content_hash_algorithm`, and `metadata` (object) or
  `metadata_json` (string). If a later update omits the hash, the stored hash is kept.
- `library_book` results:
  - accepted: `{mutation_id, status:'accepted', library_book_id, cloud_book_id, revision, change_id}`
  - duplicate: `{mutation_id, status:'duplicate', library_book_id, existing_book_id, payload}`
  - conflict: `{..., status:'conflict', library_book_id, cloud_book_id, revision, payload}`
  - rejected reasons: `invalid_library_book_id`, `book_identity_required` (no title),
    `library_book_id_conflict` (the ID belongs to another user).
  - Duplicate detection: a live book of the same user with the same source hash, OR a live
    `cloud_book_files` row (available/upload_pending/uploading) of the same user with that file
    hash.
- The payload for changes, conflicts and duplicates comes from the SQL helper
  `cloud_book_sync_payload(id)`: `{library_book_id, source_content_hash,
  source_content_hash_algorithm, title, author, format, remote_revision}`. There is NO metadata
  and NO `cloud_book_id` in it.
- `reading_position`: the book is `payload.library_book_id::uuid`. Rejected reasons:
  `invalid_library_book_id`, `library_book_not_found` (unknown or not owned),
  `position_required`. The stored and pulled payload is `{library_book_id, position}`, with NO
  `cloud_book_id`. accepted/conflict results include `library_book_id` and `cloud_book_id`.
- `reading_session`: unchanged.
- File RPCs are unchanged. `reserve_book_upload(cloud_book_id => <book UUID>, ...)`.
  `book_file` change payloads still use the key `cloud_book_id`, whose value is now the book ID.
  "Replace" is delete-then-reupload (no in-place replace).
- Tell the user again at the end: the remote project needs a DB reset, an emptied `book-files`
  bucket, and this migration applied, and only after slice 2 is merged.

## Slice 2: what's done (DB layer, verified)
Verified: `./gradlew :lib:database:implementation:testAndroidHostTest` passes (22 tests:
LibraryBookMergeTest 12, OneBookIdMigrationTest 2, MediaFileSizeTest 2, PositionBaselineTest 2,
ReadingProgressOutboxTest 2, SyncCheckpointDatabaseTest 1, DatabaseManagerLogMessagesTest 1).

SQLDelight (`lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/`):
- NEW `27.sqm`: resets the device library, Parrot and sync data, sets `position.library_book_id`
  to NULL, drops the old tables, and creates `library_books`, `device_files`,
  `cloud_book_file_state` and `cloud_file_transfers` as in the plan.
- NEW `DeviceFile.sq`: upsertDeviceFile, getDeviceFile, getDeviceFilesForBook,
  observeAllDeviceFiles, findDeviceFileByHash, deleteDeviceFile, deleteDeviceFilesForBook,
  setOriginForBook, moveDeviceFile.
- `LibraryBook.sq` rewritten: upsertLibraryBook, getLibraryBookById, observeLibraryBooks,
  findLibraryBookBySourceHash, countLibraryBooksWithDeviceFiles, deleteLibraryBook,
  updateLastOpenedAt.
- `CloudBookFileState.sq`: no `cloud_book_id`; added a hash index, findCloudFileByHash,
  getCloudBookFileStateById and deleteCloudBookFileStatesForBook.
- `CloudFileTransfer.sq`: no `cloud_book_id` or `local_source_uuid`; added
  moveNonTerminalTransfers and deleteTransfersForBook.
- Merge helper queries: Position.moveBookPosition, Favorite.mergeFavorite,
  Bookmark.moveBookmarks, ReadingSession.moveReadingSessions, SyncOutbox.getAllMutations and
  SyncOutbox.rewriteMutation.
- Unused leftover: `SyncOutbox.deletePendingMutationsForMissingLibraryBooks`. Delete it if
  nothing uses it.

DB API (`lib/database/api/.../library/`):
- `LibraryBookEntity` is now a data class: libraryBookId, title, author, description, coverPath,
  publicationDate, sourceContentHash, sourceContentHashAlgorithm, addedAt, lastOpenedAt,
  remoteRevision, deletedAt, metadataJson.
- `DeviceFileEntity` data class, with `ORIGIN_IMPORT` and `ORIGIN_CLOUD_DOWNLOAD`.
- `LibraryBooksDatabase`: upsert, observe, getById, findBySourceHash,
  countLibraryBooksWithDeviceFiles, updateLastOpenedAt, and two transactional methods:
  - `insertImportedBook(book, file, outboxEntry)`
  - `deleteBookFromDevice(id)`
- `DeviceFilesDatabase`: as in the plan, plus getDeviceFile.
- `LibraryBookMergeDatabase.mergeLibraryBook(fromId, intoId): List<String>`.
- The cloud entities dropped `cloudBookId` and `localSourceUuid`. `CloudFilesDatabase` gained
  `getFileStateById` and `findFileStateByHash`.

DB impl: `dao/library/LibraryBooksSqlDelightDao.kt` holds everything, including the internal
`AppDatabase.mergeLibraryBookRows()`. `LibraryBooksDatabaseImpl` implements all three
interfaces. `DatabaseModule` binds the three interfaces to one impl. The cloudfiles DAO has been
updated.

Merge rules as implemented:
- Positions: the one with the later `updated_at` wins (compared as strings).
- `remote_position`: the `fromId` row is deleted.
- Favorites: INSERT OR IGNORE, then delete the `fromId` row.
- Bookmarks and sessions: moved to `intoId`.
- Device files: moved, unless `intoId` already has that media type. In that case the `fromId`
  path is returned for the caller to delete.
- `cloud_book_file_state`: the `fromId` rows are deleted.
- Transfers: non-terminal ones are moved; terminal ones are deleted.
- Outbox:
  - The `library_book` mutation whose entity_id is `fromId` is deleted.
  - Other entries that mention `"fromId"` (quoted, which is safe for UUIDs) have their
    entity_id and payload rewritten.
- `library_books`: the `fromId` row is deleted.

Known issue to fix: `deleteBookFromDevice` removes pending outbox entries through
`deletePendingMutationsForEntity(cloud_user_id = null, ...)`, whose SQL is `cloud_user_id IS ?`.
So it only removes entries that aren't bound to an account yet. Add a query that ignores
`cloud_user_id`.

## Findings: where the plan is wrong or incomplete
1. The database is wiped on every schema change. `PlatformDatabaseModule.android.kt` and
   `PlatformDatabaseModule.ios.kt` delete the DB file whenever the stored schema version differs
   from `AppDatabase.Schema.version`. So `27.sqm` never runs on a real device. Every upgrade wipes
   ALL local DB data, Storyteller positions included (only what syncs from the server comes
   back). The plan's "Storyteller data survives" expectation is false. The user was asked and
   hasn't decided; current behaviour is kept. Mention it in your final report. The plan's
   "migrate from version 9" test was dropped: the old 1..26 migration chain doesn't even run on
   a real v0.4.5 schema (it fails with `no such table: bookmarks`).
2. The SQLDelight version is off by one from the plan. Do NOT set `version = 27` in
   `lib/database/implementation/build.gradle.kts`; keep `version = 26`. The app schema version
   was 27 at HEAD and is now 28, and `27.sqm` is the 27→28 step. The test fixture is
   `src/androidHostTest/resources/v27_schema.sql` (the HEAD schema before this change), and the
   test migrates `27 → AppDatabase.Schema.version`. `MediaFileSizeTest` now migrates `26 → 27`.
3. I7 is currently violated. `feature/sync/data/.../SyncBoundedPass.kt` pushes progress
   (`reading_position`) entries BEFORE library mutations. Reverse it: push library mutations
   first (so duplicates merge and rewrite the outbox), then re-select the progress entries from
   the DB before pushing them. Stale in-memory entries would carry the old ID.
   `ParrotCloudSyncAdapter.pushLibraryMutations` also re-selects between chunks; keep that.
4. The reader-cache check also exists elsewhere (I6).
   `feature/reader/domain/.../ObserveBookWithProgressUseCase.kt` also calls
   `readerSettingsRepository.isEbookCached` for every book. For library books, derive cached
   flags from `mediaResources[].localPath` there too, not only in
   ObserveAllBooksWithProgressUseCase.
5. `PositionEntity.libraryBookId` is a non-null String that defaults to `bookUuid`.
   `BooksSqlDelightDao` reads `library_book_id ?: book_uuid`, and `ServerPositionLocalDataSource`
   writes `libraryBookId ?: bookUuid`. I4 needs NULL for Storyteller and Audiobookshelf. Make it
   nullable end to end, and only set it (= bookUuid) for `LOCAL_SERVER_ID` positions. Check
   `DuplicatePositionRepair`, `ProgressSyncEngine` and the Storyteller and Audiobookshelf
   progress adapters, which read `position.libraryBookId`.
6. The Parrot progress payload will fail to decode. `ParrotCloudReadingPositionPayload` in
   `lib/server-parrot-cloud/.../ParrotCloudReaderRepository.kt` has a REQUIRED `cloud_book_id`,
   which the server no longer sends. Remove it, use `library_book_id` as the remote book ID
   everywhere (`ParrotCloudProgressTransport`, `ParrotCloudSyncAdapter`), and push only
   positions whose `library_book_id` is non-null and whose `library_books` row exists.
   `library_book_not_found` must mean retry later: `ProgressSyncEngine` already reschedules on
   `ProgressPushResult.Rejected`, so confirm it.
7. Detail download state for library books comes from the reader cache.
   `BookDetailViewModel.handleReadClick` requires `DownloadState.Cached`, and that state comes
   from `observeDownloadStateUseCase` (reader cache). `withCloudTransferStates()` only
   special-cases ParrotCloud books. For library books, derive Cached from `localPath` per media
   type.
8. `CurrentlyReading` is stored per user. The preference key is
   `PreferencesKey.UserScoped(userId, "CurrentlyReading")`, holding JSON
   `CurrentlyReadingLocalModel{serverId, bookUuid, bookType, bookTitle, coverUrl,
   totalProgression}`, which lives in feature/reader/data. books-data must not depend on
   reader-data. In `LibraryBookMergerImpl`, use `Preferences` plus `UserRegistry`
   (`getActiveProfileIdOrDefault()`), and rewrite `bookUuid` in the JSON when
   `serverId == "local"` and `bookUuid == fromId`. Add `projects.lib.preferences.api` to
   books-data.
9. File locations:
   - The iOS reader cache is `Caches/ebooks`, so `Documents/ebooks` is import-only. It's safe to
     delete once behind a preference flag.
   - The Android reader cache is `filesDir/ebooks`, which is shared with imports; leave it.
   - The new library dir is `filesDir/library` on Android and `Documents/library` on iOS.
   - Covers go in `imported_covers`.
   - Update `BookFileTransferFileStore.importedFilePath` on both platforms to use the library dir.

## Slice 2: remaining tasks (plan Tasks 2.3–2.6)
Every place that still uses removed APIs (fix all of them):
- feature/books/data:
  - AndroidFileImportManager, IosFileImportManager
  - source/ImportedBooksLocalSource.kt and ImportedBooksRoomDataSource.kt → replace with
    `LibraryLocalSource` and `LibraryLocalDataSource` (interfaces as in the plan)
  - model/ImportedBookLocalModel.kt (delete)
  - model/LibraryBookLocalModel.kt (remove the `"$algorithm:$hash"` ID)
  - model/LibraryBookJsonCodec.kt: new payload keys `library_book_id`, `title`, `author`,
    `format`, `source_content_hash`, `source_content_hash_algorithm`, `metadata_json`,
    `remote_revision`
  - transfer/BookFileTransferEngine.kt
  - transfer/DownloadFinalizer.kt
  - tests to rewrite: LibraryBookJsonCodecTest, LibraryBookLocalModelTest,
    BookFileTransferEngineTest (65 KB, heavy ImportedBooksDatabase fakes), DownloadFinalizerTest
  - add LibraryImportTest (the 5 cases in the plan)
- feature/books/domain:
  - FileImportManager: return the book ID or a LibraryBook, not LocalBook; drop the path helpers
    keyed by UUID
  - ImportEpubUseCase
  - BookFileTransferTransport.kt: `enqueueUpload` takes `libraryBookId` (plus media type), not
    `localBookUuid`
  - `BookFileUploadRequest`: `cloudBookId` becomes the book ID, and `localBookUuid` is used only
    as TUS metadata, so pass the book ID
  - `BookFileDownloadRequest.cloudBookId` and `CloudBookFileRecord.cloudBookId`: drop them, or
    set them to the book ID
  - StartBookFileUploadUseCase, RemoveBookFileDownloadUseCase
  - model/BookDomainModel.kt: the `libraryBookId` getter returns `uuid`; remove the hash-based ID
  - model/ServerBookExt.kt: `canonicalIdentity = libraryBookId ?: "$serverId:$uuid"`
  - model/ServerBookMapper.kt
- BookFileTransferEngine details:
  - importedBooksDatabase is used around lines 125, 199, 235, 268, 882 and 982. Replace each
    with `DeviceFilesDatabase` lookups by (libraryBookId, mediaType).
  - `enqueueUploadLocked` builds `"$algorithm:$contentHash"`. Replace it with the book ID.
    Replace "Book metadata must sync first" with `library_books.remote_revision != null`.
  - `createDownloadRequest` has an identity check, `file.libraryBookId != "${alg}:${hash}"`.
    Delete it.
  - `backupAll`: iterate device files with origin `import`.
  - `removeDownload`: delete that media type's device file and row.
  - `invalidateCloudFile`: delete `cloud_download` device files of that book and media type.
- DownloadFinalizer:
  - Keep the hash verification.
  - Write `device_files(bookId, mediaType, origin=cloud_download)` at the library path.
  - Make sure the `library_books` row exists (normally it arrives by pull). Fill a missing
    cover or description from the EPUB metadata.
  - Delete RestoredPositionEntity: the position is already keyed by the book ID.
- lib/server/implementation/.../ServerPositionLocalDataSource.kt: drop ImportedBooksDatabase and
  the hash fields in `LocalReadingPositionMutation`; handle libraryBookId as in finding 5.
- feature/auth/domain/.../CheckAuthStateUseCase.kt: replace
  `importedBooksDatabase.getImportedBooksCount()` with
  `libraryBooksDatabase.countLibraryBooksWithDeviceFiles()`, keeping the user's edits. Check its
  test in the untracked `feature/auth/domain/src/commonTest/`.
- feature/sync:
  - data/LibraryBookSyncApplier.kt: upsert by ID and preserve local-only fields (cover,
    description, addedAt, lastOpenedAt); a new row gets addedAt = now and no device file.
    `SyncLibraryBookSnapshot` loses `cloudBookId` and `contentHash`.
  - data/LocalBookUuidResolver.kt: becomes an identity lookup.
  - data/DuplicatePositionRepair.kt: simplify.
  - data/LibraryMutationSyncEngine.kt: add `STATUS_DUPLICATE`. Call merge, apply the returned
    payload as a remote book, then delete the entry (the merge may already have deleted it).
  - domain/LibraryMutationSyncTransport.kt: `SyncMutationResponse` gains `existingBookId`.
  - domain: NEW `LibraryBookMerger` interface: `suspend fun merge(fromId: String, intoId: String)`.
  - fix SyncBoundedPass ordering (finding 3).
  - tests: LibraryBookSyncApplierTest, LibraryMutationSyncEngineTest, DuplicatePositionRepairTest.
- feature/books/data: NEW `LibraryBookMergerImpl`. It calls `LibraryBookMergeDatabase`, deletes
  the returned paths through `BookFileTransferFileStore`, and rewrites CurrentlyReading
  (finding 8).
- lib/server-parrot-cloud:
  - ParrotCloudModels.kt: new payload shape; delete `ParrotCloudLibraryBookEntity` and the
    `toServerBook` that uses ImportedBookEntity.
  - ParrotCloudBooksRepository.kt: slice 2 lists books with Parrot state, `uuid =
    libraryBookId`, local paths from device files. Remove the hash join and the dead `saveBook`
    body.
  - ParrotCloudRepositoryFactories.kt: remove ImportedBooksDatabase.
  - ParrotCloudLibraryMutationApplier.kt, ParrotCloudLibraryMutationSyncTransport.kt: map
    `existing_book_id`; handle duplicate.
  - ParrotCloudBookFileChangeApplier.kt: `getLibraryBookById(payload.cloud_book_id)`.
  - ParrotCloudSyncAdapter.kt, ParrotCloudProgressTransport.kt, ParrotCloudReaderRepository.kt:
    finding 6; drop `DEFAULT_CONTENT_HASH_ALGORITHM` IDs.
  - ParrotCloudBookFileService.kt: `request.cloudBookId` becomes the book ID.
  - tests in src/commonTest.
- lib/server-local/LocalBooksRepository.kt and LocalBooksRepositoryFactory.kt: build from
  `LibraryLocalSource.observeLibrary()`, only books with device files, `uuid = libraryBookId`.
- feature/books/ui:
  - BookDetailViewModel/BookDetailScreen: mechanical removal of `localSourceUuid` and `origin`
  - BookUiModel/BookUiModelMapper
  - BooksListViewModel: auto-backup after import uses the returned book ID
- Other LocalBook users (renamed in slice 3; keep them compiling in slice 2):
  - feature/reader/domain InitializeReaderUseCase and GetCachedReadAloudBooksUseCase
  - feature/reader/ui/src/androidMain/.../auto/AutoMediaBrowser.kt
- Module test commands and iOS compiles are in the plan. books/domain, reader/domain, sync/data,
  server-local and server-parrot-cloud need `withHostTest {}` added when you add tests there.
  Finish with:
  - `./gradlew :androidApp:assembleDebug`
  - `:<module>:compileKotlinIosSimulatorArm64` for every changed module

## Slices 3 and 4
Follow the plan (Tasks 3.1–3.4 and 4.1–4.3) as written, plus:
- Move the `BookDetailViewModel.observeActiveCloudAccount()` logic (around lines 398–414,
  combining `cloudAccountRepository.observeAuthState()` with
  `cloudProfileLinkRepository.observeForLocalProfile(userRegistry.getActiveProfileIdOrDefault())`
  through `isActiveFor`) into the `ParrotCloudLibraryState` implementation.
- BookDetailScreen spots that branch on `LocalBook` or `isLocalBook`: around lines 597, 946,
  1025–1110, 1135–1142 and 1295.
- Strings: follow plan §1.6; add new keys at the bottom with `tools:ignore="MissingTranslation"`.
  Never touch `cloud_backup_attestation*` or "Book Backup Terms".
- Findings 4 and 7 apply to slice 3.

## Final report to the user
List:
- the commands you ran and their results (tests and builds),
- that no device testing was done (the user asked for none),
- that iOS was compiled only, not run,
- the database-wipe finding (1), with the choice they still need to make,
- the remote Supabase steps.

Then ask whether to commit.
