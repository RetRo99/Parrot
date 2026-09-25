# Unified library implementation notes

## Current consolidated status — 2026-09-26 (T31)

This section supersedes forward-looking status and “remaining gaps” statements in
the dated development snapshots below. T21–T30 preserve checkpoint evidence;
T31 records connected-server and two-device Android acceptance after those
checkpoints. T00–T04 remain useful for the original diagnosis and implementation
history.

- **T24 linked backend:** `supabase migration list --linked` shows all 17 local
  migrations applied remotely through `20260925000002`. The five transaction-
  wrapped SQL suites were run through `supabase db query --linked --file` against
  that project: library grouping (49), book removal (55), abuse operations (29),
  account deletion (8), and storage policy (10) all passed: 151 assertions total.
  This uses the linked remote database; no Docker test runner was needed.
- **T24 Samsung acceptance:** Samsung `SM-S921B` (`RFCWC0SSVDM`) is linked to
  `rok.retar@gmail.com`, with Sync enabled. Search shows A and B as separate
  library groups, each with Local and Parrot Cloud sources. A's local EPUB remains
  available while its Cloud EPUB resource is unavailable. B's local EPUB opened
  and rendered “Chapter One.” After the test-only B EPUB was backed up with the
  rights confirmation, Cloud storage changed from 0 B to 1.4 KiB and Details
  showed “Backup complete.” Removing only B's local copy left its Cloud copy; a
  Cloud restore then showed “Restore complete,” made the Local source available
  again, and the restored EPUB rendered “Chapter One.” A subsequent manual Sync
  reported 0 sent, 2 received, and 0 pending at 2026-09-25 12:08 UTC. Confirming
  remote deletion made B's Cloud source unavailable while its Local source remained
  available; the on-device copy still opened in Reader and displayed “Chapter One.”
- **T24 live grouping sync:** A and B were merged in the Samsung UI. Manual Sync
  reported 1 sent, 1 received, and 0 pending at 12:31:27 UTC; the linked database
  recorded a `merge` decision with four members. B was then split back out. Manual
  Sync again reported 1 sent, 1 received, and 0 pending at 12:35:19 UTC; the linked
  database recorded a `split` decision with two selected and two retained members.
  The library list returned to separate A/B entries, and B still opened locally
  with “Chapter One” while its Cloud file remained unavailable.
- **T26–T27 Cloud deletion convergence:** Xiaomi received the remote deletion while
  keeping its Local EPUB readable. A clean emulator sync received 27 changes and
  showed no Cloud EPUB resource or Download action for the deleted book.
- **T24 emulator ANR (resolved at T27):** the emulator showed both acceptance
  books before navigation to Sync & Backup, then repeatedly displayed Android ANR dialogs for
  `system` and `com.retro99.parrot`. A reboot followed by an app launch reproduced
  the ANR, so fresh emulator convergence could not be checked. The captured stall
  was OkHttp's AndroidX Startup provider before `Application.onCreate`; with one
  trace under heavy emulator load, no source workaround is justified. App data was
  not cleared and the older Xiaomi was left untouched.

- **T28 Cloud host tests:** `:lib:cloud:implementation:testAndroidHostTest` passes
  all 35 tests. Host tests inject an in-memory PKCE cache and disable Android
  lifecycle callbacks; production retains Supabase's persistent cache and normal
  lifecycle behavior.
- **T30 Storyteller API routes and authentication:** The user verified that
  `/api/health` returns 200, `/api/v2/books` returns 401 before login, and
  `/api/v2/info` is an unmatched Next.js route. `StorytellerAuthenticator` now
  validates with `/api/health` and falls back to `/api/v2/books`; tests cover the
  healthy, auth-required (401/405), and missing-route cases. The focused Storyteller
  host tests and the combined library/settings tests plus Android debug assembly
  passed at that checkpoint. T31 below records completed in-place reauthentication,
  catalog refresh, download, and offline-reader checks.
- **T30 Storyteller token expiry:** The earlier TUS spike note incorrectly described
  `expires_in` as a reliable millisecond duration. Upstream's current token helper calculates
  `session.expires.valueOf() * 1000 - Date.now()`. Since `expires` is a `Date`, this
  multiplies an epoch-millisecond timestamp and returns a far-future value instead
  of a remaining duration. The Android client now ignores this field for local
  expiry and leaves `expiresAt` unset, so the server's rolling session check remains
  authoritative. Upstream evidence is pinned to
  [`auth.ts` at `b490daa`](https://gitlab.com/storyteller-platform/storyteller/-/blob/b490daa57cc41dd228339018d0131789cb6b2c6d/applications/web/src/auth/auth.ts#L312-318).
  Do not treat the existing expiry value as a reliable server lifetime until the
  upstream calculation is corrected or token expiry behavior is otherwise verified.
- **T30 Audiobookshelf pairing checkpoint:** Explicit pairing is implemented and
  guarded by the same Parrot Cloud account. The unpair blocker count reports matching
  memberships accurately. Xiaomi retains its existing memberships under its prior
  portable identity; T31 validates pairing independently between Samsung and the
  emulator without rebinding or resetting Xiaomi. Earlier authenticated server
  listing, EPUB download/open, cached audiobook playback, and HTTP range checks pass.
- **T31 Storyteller connected/offline acceptance:** On Samsung, reauthentication
  saved the new session under the existing server connection. The catalog refreshed,
  a 65.5 MB EPUB downloaded and rendered in Reader, and the downloaded book reopened
  with Wi-Fi and mobile data disabled. Both network settings were restored afterward.
  This check used local downloaded files and does not claim remote streaming.
- **T31 Audiobookshelf pairing and grouping convergence:** Samsung and the emulator
  paired under the same Parrot Cloud account and share portable Audiobookshelf
  identity fingerprints `bb25490a71` (backend) and `6105397b2b` (account). Samsung's
  two-book merge was hosted as revision 17; both devices applied it and showed one
  group. Samsung then split the pair as revision 18. After installing the repaired
  debug APK, both decision logs show revisions 17 and 18 applied, and both Books
  screens show Dracula and Pride and Prejudice as separate Audiobookshelf entries.
  The final groups match across devices: member fingerprints `ad74e40522` and
  `79396fd678` resolve to distinct groups. The 7-vs-6 membership count is one
  Samsung-only authoritative Removed tombstone, outside the pair. Samsung's two
  remaining outbox items are an unrelated reading-position update and a deferred
  non-portable Storyteller preference; they do not affect ABS grouping.
- **T31 Audiobookshelf unread-progress behavior:** Audiobookshelf v2.35.1 returns
  HTTP 404 from `/api/me/progress/{bookId}` when no progress record exists. The
  progress sync transport now treats only that 404 as an absent snapshot, and the
  reader repository returns `Ok(null)` for it; other errors still propagate. Focused
  regression tests cover both paths and verify that 401 remains an error. The host
  tests pass, and the rebuilt debug APK cleared the 404 during Sync now on both
  Samsung and the emulator.
- **T29 Samsung playback regression:** Samsung exposed a Back-navigation crash in
  `RoutineSyncScheduler.close()`. The worker now cancels before its channel, with a
  regression test for closing while the worker waits. Host tests and debug assembly
  pass, and Back now returns to Details without crashing. Reopening from the
  selection-less mini-player reconnects to the two-track session without a false
  missing-file error.
- **Out of scope:** iOS runtime testing and populated pre-unified-library upgrade
  testing remain excluded per the user's direction.

- **T00–T05:** The shared identity and persistence foundation, deterministic
  projection, shared list/Details routes, legacy-route resolution, and concrete
  reader targets are implemented. The local EPUB route was verified on emulator
  and Xiaomi; both imported fixtures opened in the Reader.
- **T06–T08:** Adapter-based operations, transfer observation, source/resource
  reconciliation, and replica-specific removal are implemented with unit-test
  coverage. Cloud transfer evidence also has a persisted-state recovery pass after
  identity promotion. Cached Storyteller/Audiobookshelf listings now report a later
  remote-fetch failure so their persisted snapshots become Unknown instead of
  remaining Present. T24 accepts live Cloud upload and restore of test fixture B
  on Samsung. T26 later verified remote deletion convergence while Xiaomi's
  Local EPUB remained readable.
- **T09:** Local merge and split are implemented. Emulator acceptance merged only
  fixtures A and B, saw both members in Group Details, split one member, and saw
  A and B return as separate list entries without a source-feed change.
- **T10:** The versioned Cloud grouping transport and backend migration are present.
  The RPC rejects malformed UUIDs per mutation, skips persistence for invalid
  mutation IDs, and rejects unknown version-1 payload fields. Its pgTAP fixture now
  has 49 assertions. `scripts/supabase/test.sh` was attempted on 2026-09-25; the
  CLI timed out connecting to local Postgres (`LegacyDbConnectError`) even though
  the TCP port accepted a connection. The existing storage-audit purge process was
  left untouched.
  Samsung has both A/B groups and its latest linked sync received two items with
  none pending; T27 later verified a clean emulator pull after the T24 ANR.
  Storyteller now derives a portable
  identity from the authenticated user UUID and persistent server UUID when both
  identity endpoints are supported; older servers remain connection-scoped.
  Audiobookshelf still has no trustworthy portable installation ID. Local books
  with a verified whole-file SHA-256 use a portable content identity; hashless or
  malformed-hash Local books remain connection-scoped.
- **T11:** A focused unknown-adapter integration test covers listing, evidence
  grouping, manual merge/split, media/progress ownership, operation execution, and
  capability changes. Storyteller/Audiobookshelf Details now uses a
  source/resource-scoped cache fallback, preventing collisions when connections
  reuse a native book ID. Legacy unscoped cache entries are deliberately not reused.
  Present-to-Unknown offline cache access is covered by focused Details tests; live
  connected-server download/open and the full streaming/multi-file/disconnected-source
  matrix remains incomplete for Storyteller and Audiobookshelf.
- **T12:** Android build/install and emulator/physical-device local EPUB checks
  passed again after the audit fixes. The latest APK launched on both devices, and
  Group Details opened the fixture EPUB in the Reader on the Xiaomi. A populated
  emulator database upgraded from schema v41 to v42 at T18; upgrading a populated
  pre-unified-library database remains untested and is not a required compatibility
  gate under the user's direction. T26 verified remote deletion on one device while
  preserving Xiaomi's Local copy; linked backend pgTAP now passes at T24. iOS
  runtime acceptance is out of scope per the user. See T12, T18, and T24 for exact
  evidence.
- **T13:** Shared Local-file removal now detaches each selected association and
  preserves bytes until the final owner is removed. The durable phase is
  `EffectApplied`; schema v40 migrates existing v39 removal intents. Repository,
  Local-adapter, and database migration tests pass. The latest debug APK installed
  on the emulator and Xiaomi with `adb install -r`, preserving their app data.
  Both apps launched; the emulator opened the local fixture from Group Details and
  rendered its Reader chapter. The connected-device schema version was not queried
  in this pass.
- **T14:** Group Details now discovers cached Storyteller/Audiobookshelf media from
  Present or Unknown snapshots without asking the source for live download
  availability. Opening revalidates the source/resource and completed cache before
  navigating, while new downloads still require a live Present snapshot. Multi-file
  downloads publish through staging; cancellation removes only the in-progress
  single-file cache or the multi-file staging bundle. `:feature:books:ui:allTests`,
  `:feature:reader:data:allTests`, `:feature:library:domain:allTests`, and
  `:androidApp:assembleDebug` pass. The Cloud-feed Details regression uses a fake
  adapter; linked-account/two-device Cloud acceptance later passed at T24–T27.
- **T15:** Re-audited the seven external review findings. The reported compilation,
  merge/split projection refresh, generic Details operations, transfer identity,
  and Local-specific removal issues are resolved in current source. An iOS
  cancel/retry handoff race was fixed and Reader data tests pass. At the T15
  checkpoint, Audiobookshelf lacked a trustworthy portable installation identity
  and Parrot Cloud lacked explicit book-deletion tombstones. T16 adds tombstones for
  requested removals; partial-list omissions still cannot be treated as deletions.
- **T16:** Added a confirmed, source-level whole-book removal operation for Parrot
  Cloud. It removes known Cloud files before queuing the book tombstone; active
  uploads block cleanup, while already-deleting files can be retried. A server-side
  file/upload race schedules a 30-second retry. Explicit deletion tombstones flow
  through the partial listing contract, and duplicate pending/dispatched deletes
  are coalesced. Details refreshes capabilities and invalidates stale confirmations
  when the source revision changes. See the
  [integration guide](unified-library-integration-guide.md) for the distinction
  between whole-book removal and per-resource deletion.
- **T17:** The Android compile blocker was a missing import for the existing
  `upsertLibraryBookRow` DAO extension; SQLDelight generation was intact. The
  Android build passes; the focused sync, books-data, and Parrot Cloud suites
  reported success and were UP-TO-DATE in the final recheck. The APK installed and
  launched on the emulator and Xiaomi with app data preserved. On
  both devices, Local Group Details opened the fixture EPUB and the Reader rendered
  “Chapter One.” Live Cloud/two-device acceptance, pgTAP, Audiobookshelf portable
  identity, populated legacy-database upgrade, iOS app runtime, and the full
  connected/disconnected media regression matrix remain open.
- **T18:** Closed plan-coverage gaps in per-source progress, Group Details progress,
  group-aware series/favorites, and collection preservation. Collections now flow
  through the Storyteller API/cache, source snapshots, and unified projection;
  SQLDelight schema v42 adds the snapshot collection relation. The Local/Cloud
  progress resolver preserves the legacy shared `libraryBookId` behavior while
  preferring scoped identities. Focused module tests and the combined verification
  passed. A populated emulator database upgraded in place from v41 to v42 with
  existing library data counts preserved; the installed app opened Group Details
  and rendered the fixture EPUB's “Chapter One.” The physical device was not
  attached during this pass. Live-service and platform acceptance gaps remain open.
- **Performance follow-up:** Group Details reloads the full group projection through
  `getGroup()` for each emission from `observeGroups()`. The first projection reads
  memberships and source snapshots; it does not scan all 9,323 identity-evidence
  rows. This duplicate load is unnecessary, but the audit did not show it caused
  the emulator's long Details wait. The emulator was under substantial runtime and
  memory pressure. Keep the delay as an open UX caveat if it reproduces on an
  otherwise idle device; functional Details and Reader acceptance completed.
- **T21:** The Android debug variant now inherits the hosted Supabase configuration
  for live acceptance; no release APK was built. The emulator is linked and reports
  `PGRST202` because the hosted schema lacks
  `public.push_library_group_decisions(client_cursor, mutations)`. Xiaomi showed
  the linked account and five pending sync items after a manual sync attempt. Cloud
  upload and two-device grouping acceptance therefore remain blocked. A read-only
  migration check found six local migrations unapplied remotely. The live screen
  exposed a `%s` timestamp placeholder; it is fixed to `%1$s` and now renders the
  supplied timestamp on Xiaomi. iOS testing remains deferred per the user.
- **T23:** Fixed Continue Reading's Android launch-argument corruption by passing
  one typed `ReaderViewModelArgs` parameter to `ReaderViewModel`; nullable String
  parameters could otherwise resolve to the first String parameter in Koin. The
  existing debug APK opened the saved acceptance EPUB and rendered its chapter on
  emulator `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`. No release build was
  needed because debug and release both inherit the hosted Supabase configuration.
  This closes the reproduced reader failure only; hosted Cloud sync is still blocked
  by the unapplied migrations.

The Gradle unit tests and builds recorded below establish code-level behavior only.
They do not imply Cloud service or two-device sync acceptance. The worktree is
intentionally left uncommitted.

## T00 — Establish the actual bug and extension boundaries

Date: 2026-09-24.
Historical status at T00: implemented, verification blocked for the reported
device flow. Acceptance state was updated on 2026-09-25 above and in T12.
T00's execution-guide gate permits explicitly recorded missing reproduction evidence.
The current implementation status is summarized at the top of this document.

### Behavior implemented / changed files

- Added a legacy aggregation characterization to
  `feature/books/domain/src/commonTest/kotlin/com/retro99/books/domain/model/ServerBookExtTest.kt`:
  identical hashes with different non-null library IDs produce two entries in
  either input order. This records current behavior, not the desired grouping policy.
- Added this handoff. No production, schema, or protocol changes in T00.
- Initial `git status --short` showed only the two user-supplied planning documents
  as untracked. No `AGENTS.md` was found in the repository or checked parent directories.

### Observed list paths

Paths below use the module names and package directories mapped in execution-guide §3.

- `books-ui/list/BooksListViewModel.kt:262` calls
  `reader-domain/usecase/ObserveAllBooksWithProgressUseCase.kt`, which combines
  authenticated repositories, flattens their results, and calls `aggregateBookReplicas()`.
  `books-domain/usecase/GetBooksUseCase.kt` is the other aggregation consumer.
- `books-ui/list/BooksListViewState.kt` applies search, server-type and quick filters
  **after** aggregation. Thus a retained Local representative can hide matching
  Cloud membership from filters. Feature-source search found no direct caller of
  repository `searchBooks()`; native search remains part of the repository API.
- Author/series book use cases and `SeriesListViewModel` use `GetBooksUseCase`;
  `GetCachedReadAloudBooksUseCase` also consumes it. Reading-list progress filters
  operate on the already aggregated books. These are T04 migration targets.
- Both aggregation consumers turn repository errors into empty lists. Removal of
  an authenticated repository can also remove its rows. Persisted snapshots must
  outlive authentication/fetch errors.
- `ServerBookExt.kt` selects exactly one key:
  `libraryBookId ?: hash-with-algorithm ?: "$serverId:$uuid"`.
  Its legacy missing-algorithm fallback is `sha-256-v1`. It selects the first Local
  member, otherwise the first member, discarding the other source objects.
- Details currently resolves one `(serverId, uuid)` via `GetBookByUuidUseCase`.
  `BookDomainModel`, Details state/intents, and `HomeDestination.BookDetail` carry
  one source. T04/T05 must introduce an additive group projection and compatible route.

### Import → metadata sync → upload → feed identity trace (code-derived)

The following symbols are explanatory placeholders, **not captured device values**:
`U` = imported UUID; `H` = whole-file SHA-256; `K = sha-256-v1:H`;
`C` = returned Cloud book UUID; `F` = returned Cloud file UUID; `T` = transfer UUID.

1. Android/iOS `FileImportManager` implementations generate `U`, copy the EPUB
   into their imported store, and hash the stored bytes. `ContentHash.android.kt`
   streams bytes through Java SHA-256; `ContentHash.ios.kt` streams bytes through
   CommonCrypto SHA-256. Both label this whole-file digest `sha-256-v1` (not a
   manifest hash). Imported-book fields retain `U`, `H`, algorithm, format and path.
2. `ImportedBooksRoomDataSource.saveImportedBook` derives `K` through
   `LibraryBookLocalModel.kt`. `ImportedBooksSqlDelightDao` transactionally writes
   imported book, library record, local-file mapping and library-book outbox entry.
3. `LibraryMutationSyncEngine` delivers metadata through the adapter.
   `LibraryBookSyncApplier.applyAccepted` attaches `C` to the existing local record
   while preserving its ID/metadata. `applyRemote` first looks up by `C`, then
   algorithm+hash, and preserves the matched record ID; only absent a match does
   it use the incoming snapshot ID. An incoming different ID alone is therefore
   insufficient evidence of a stored mismatch.
4. `BookFileTransferEngine.enqueueUploadLocked` rehashes the actual file, derives
   `K`, requires its library record and `C`, persists `T` with `U`, `K`, `C`, media,
   hash and attestation, then schedules work. Reservation supplies `F`; completion
   writes the completed transfer and file-state record. These writes currently
   carry no durable group/source association. Existing transfer IDs are reused in
   several retry/already-available paths.
5. `ParrotCloudSyncAdapter` dispatches metadata to `LibraryBookSyncApplier` and
   file changes to `ParrotCloudBookFileChangeApplier`. The latter resolves `C` to
   the stored library ID before writing file state; unresolved books currently
   cause an early return. This is relevant to T07 event-order reconciliation.
6. `LocalBooksRepository` emits `ServerBook(uuid=U, libraryBookId=K, hash=H,
   algorithm=sha-256-v1, isLocal=true)`. `ParrotCloudBooksRepository` lists stored
   library rows having a Cloud ID; `ParrotCloudModels` emits
   `ServerBook(uuid=C, libraryBookId=stored ID, hash=stored hash, isLocal=false)`.
   It can expose the same imported path, matching the import by `K == stored ID`.
   Metadata alone yields no available remote resource.
7. In the normal code-derived case both aggregation keys are **K**, producing one
   Local representative. In the new test, Local key is
   **`sha-256-v1:content-hash`**, Cloud key is **`legacy-library-id`** despite both
   exposing `content-hash` / `sha-256-v1`; the result is two entries.

**Diagnosis boundary:** the test proves the conditional differing-ID mechanism.
It does not prove the reported import/upload created that state. No failing-device
database, source emission trace, or linked-account reproduction was supplied or
captured. Actual `U/H/K/C/F/T`, both emitted source records, the clicked route,
and row counts across refresh/restart remain missing. Do not repair legacy IDs
based on this fixture. Capture sanitized values at these boundaries when that
environment is available; never capture credentials or signed transfer URLs.

### Adapter, identity, and dependency inventory

- Put shared new contracts in `lib/server/api`: it currently depends on base/network
  APIs and has no books-domain dependency. Books domain already exports server API;
  books data depends on domain/database/server API. Local adapter depends on books
  data/domain; Cloud adapter depends on books domain and sync data/domain. A reverse
  dependency from library core to those adapters would undermine this boundary.
- Repository registration uses injected factory lists, but
  `CompositeRepositoryFactory` indexes them by `ServerType`. New library source,
  operation and progress registrations need an arbitrary adapter identity; do not
  extend enum dispatch into the grouping core. `ServerBook.serverType` is nullable.
- `ServerConfig` has installation connection UUID, type and URL, but no portable
  authenticated account/backend identity. `UserProfile.id` is local;
  `CloudProfileLink` maps it to `cloudUserId`. `DatabaseManager` switches separate
  profile databases; `ProfileDatabaseSession.withProfile` guards active-profile
  operations. Preserve explicit profile/account scopes at new boundaries.
- **Missing portability contract:** stable backend/account identity and portable
  membership resolution for non-Cloud sources (and portable import associations).
  Neither a local connection ID nor a URL establishes this. T01 must represent
  unresolved identity honestly; T10 must implement transport/resolution support.
- `MediaResource` has media type, optional local path/hash, availability and a
  Cloud-specific file ID. It lacks generic native resource ID, fingerprint scope,
  version, portable source/account scope and progress owner. Native multi-file
  paths must become resources rather than treating media type as a unique ID.

| Current integration | Existing operation/progress boundaries to bridge |
| --- | --- |
| Local | Import/file removal through platform `FileImportManager`; read local paths; `LocalReaderRepository` uses `ServerPositionLocalSource`. Removal is separate from native reader-cache deletion. |
| Cloud | Registered upload/download/deletion transports in books domain, implemented in `lib/server-parrot-cloud`; engine indexes instances by `serverId`. Upload transport advertises `supportsUpload=true`; static `ServerCapabilities` does not, so static flags are insufficient. Preserve profile/auth linkage and backend runtime rejection rules. |
| Storyteller | Native cached repository and reader adapter; existing media file paths feed `DownloadMediaUseCase` / `BookDownloadManager` / platform `EbookFileDownloader`. Bridge these operations and native progress rather than requiring the Cloud transfer contract. |
| Audiobookshelf | Cached source repository, native reader adapter, item-file routes using inode IDs; audiobook resources are currently encoded as `|`-joined file paths by `AudiobookshelfLibraryItemMapper`. Shared downloader handles multiple files. `saveBook` explicitly rejects upload. |

Audiobookshelf's documented [`POST /login` response](https://api.audiobookshelf.org/#login)
contains the authenticated `user.id`, `userDefaultLibraryId`, and `serverSettings.id` plus
`serverSettings.version`; it does not document a durable server-instance ID. The example
`serverSettings.id` (`server-settings`) identifies the settings object, not the installation.
The authenticator stores `user.id` in `ServerCredentials.accountId`, but Audiobookshelf's
portable source identity stays unresolved until a durable backend ID is available: account IDs
alone can repeat across independent installations.

Native reader-cache removal exists through `DeleteMediaCacheUseCase`; generic
`ServerBooksRepository` exposes no remote-delete operation. Details currently
branches on Cloud for backup/download/removal and observes transfers for one book.
The future resolver must retain native media/progress targets independently of
the metadata representative. Full streaming/reader/device regressions belong to T11.

### Persistence, deletion, and backend targets

- SQLDelight configuration is version **25**. Extend database API/DAO transactions
  and migrations additively. Keep `library_books`, `local_book_files`, imports,
  positions, Cloud file state, transfers and outbox identities intact.
- Reviewed legacy identity/file/position tables have no declared foreign-key
  cascade tying device removal to history. Explicit cleanup is significant:
  `ImportedBooksSqlDelightDao.deleteImportedBook` deletes the local-file mapping
  and import, then `deleteOrphanedLibraryBookState` deletes unreferenced unsynced
  library records and their library-book outbox rows. It does not directly delete
  positions/bookmarks. T02/T08 must preserve identity even with no files remaining.
- `removeDownload` requires a download-transfer row and deletes imported bytes only
  for `origin=cloud_download`. `deleteRemoteBackup` calls `invalidateCloudFile`;
  the feed calls it on `none`, `removed`, and `deleting`. It deletes matching restored
  bytes/covers/imports and transfer records. Both paths must change in T08.
- Download finalization validates algorithm, actual bytes, and
  `libraryBookId == algorithm:hash`, also checking matched library identity.
  Group IDs must never replace these keys or relax these checks.
- Reuse `SyncOutbox` dispatch/retry/sequence state, `LibraryMutationSyncEngine`,
  `ProgressSyncEngine` and existing adapter pull/change delivery. Unassigned outbox
  mutations can bind to an account later. Group operations must use these facilities.
- Backend `20260922000000_parrot_cloud.sql` scopes content uniqueness by account +
  algorithm + hash and uses account-owned book/file foreign keys and RLS.
  `20260922000001_parrot_cloud_rpcs.sql` handles library-book/position mutations;
  grouping operations and portable source references require protocol support.
- `20260924000002_parrot_cloud_delete_incomplete_backup.sql` emits file lifecycle
  statuses for ordinary deletion. The consumed `ParrotCloudBookFilePayload` has no
  deletion-reason field. Audit reasons are not a client invalidation contract:
  T08 needs an explicit ordinary-deletion versus mandatory-invalidation feed reason.
- Backend tests use transactional pgTAP fixtures and authenticated JWT role scope;
  inspected `rls_isolation_test.sql` and `non_available_book_delete_test.sql`.
  Repository setup documents `supabase start` then `supabase test db` in
  `docs/parrot-cloud-upload-enablement-implementation-plan.md`. Not run for T00;
  SQL fixtures do not establish actual two-device behavior.

### Tests/checks run

- `./gradlew :feature:books:domain:tasks --all :feature:sync:data:tasks --all`
  — passed. Both modules expose `iosSimulatorArm64Test`, `compileAndroidMain`,
  and `compileKotlinIosSimulatorArm64`; neither currently configures Android host tests.
  Books data and database implementation configure host tests; discover their exact
  tasks before the corresponding changes.
- `./gradlew :feature:books:domain:iosSimulatorArm64Test --tests '*ServerBookExtTest*' :feature:sync:data:iosSimulatorArm64Test --tests '*LibraryBookSyncApplierTest*'`
  — passed before edits (4 aggregation/mapping tests, 2 sync-applier tests).
- `./gradlew :feature:books:domain:iosSimulatorArm64Test --tests '*ServerBookExtTest*'`
  — passed after the characterization was added: 5 tests, 0 failures/errors/skips.
  XML reports also confirm 2 passing sync-applier tests from the baseline run.
- `git diff --check` — passed; inspected the test diff and new notes.
- `git diff --no-index --check /dev/null docs/unified-library-implementation-notes.md`
  — no whitespace diagnostics; exit 1 reflects the new-file difference from
  `/dev/null` (explicitly checks the untracked handoff file).

### Acceptance evidence / remaining gaps

Existing tests verify same-key Local preference and preservation of an existing
library ID on remote application. New characterization covers differing priority
keys. No real upload, refresh/restart, device/emulator row counts, or cross-device
deletion acceptance is claimed. Android application compilation and backend tests
were not run for this test/documentation-only change.

### Next task and required context

T00 was approved; continued with **T01 — shared identity and source contracts**.
Start with distinct scoped IDs/references, optional provenance-bearing evidence,
resource/progress ownership and generic adapter registration in `lib/server/api`.
Normalize existing sources additively; missing account portability must remain
unresolved rather than guessed. Add unknown-adapter and cross-account tests. Keep
legacy list behavior and hash-bound Cloud transfer/progress contracts while the
new durable group layer is built in T02/T03.

## T01 — Shared identity and source contracts (change 1)

Date: 2026-09-24.
Status: in progress. This is the scoped identity/evidence foundation, not completion
of T01's normalization, operation/progress, or adapter-registration gates.

### Behavior implemented

- Added typed local profile, arbitrary adapter-registration, connection, native-book,
  legacy-library and transfer IDs in the lower-level server API module.
- `SourceBookKey` scopes a native book to profile, adapter, backend and account.
  A portable account identity is separate from the local execution connection in
  `SourceBookRef`; disconnected/reconnected references can retain the membership key.
- Explicit unresolved identities remain local to their connection. They cannot
  silently substitute another connection or be constructed with no connection.
  Portable references may have no local connection. These are local domain types,
  not a synchronization payload: T10 must resolve profile scope and encode only
  portable identities.
- Optional evidence is represented by separate fingerprint, completed-transfer and
  adapter-certified edition/asset variants. Resource references retain source scope,
  native resource ID and optional revision as provenance. Fingerprints carry scope,
  algorithm (possibly unknown) and verification state; they do not decide equality.
  Completed transfer evidence rejects cross-profile associations.
- No enum-based server dispatch or new module dependency was introduced. Existing
  Cloud aliases remain distinct from future displayed group IDs.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/SourceBookRef.kt`
- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/SourceIdentityEvidence.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/SourceBookRefTest.kt`
- `lib/server/api/build.gradle.kts` — enable the existing Kotlin common-test dependency.
- `docs/unified-library-implementation-notes.md`

Schema or protocol changes: none.

### Tests/checks run

- `./gradlew :lib:server:api:tasks --all` — passed; confirmed simulator-test and
  Android/iOS compile task names.
- `./gradlew :lib:server:api:iosSimulatorArm64Test :lib:server:api:compileAndroidMain :feature:books:domain:compileAndroidMain :feature:books:domain:compileKotlinIosSimulatorArm64`
  — passed. XML report confirms 4 tests, 0 failures/errors/skips. Server API and
  its books-domain consumer compiled for Android and iOS simulator. Existing
  coroutine opt-in warnings in `GetBooksUseCase` / `GetSeriesUseCase` remain.
- `git diff --check` — passed. Read back all new Kotlin files and inspected the
  Gradle diff; separately checked the untracked files for whitespace diagnostics
  using `git diff --no-index --check /dev/null <path>` for each new file.

### Acceptance evidence / known gaps

Four passing tests cover membership-key isolation across profiles/adapters/backends/accounts,
stable lookup after connection changes, unresolved-connection rejection and
cross-profile transfer rejection.
There is no claimed runtime normalization or generic operation dispatch yet.
Portable identities still require real adapter contracts; this foundation does not
invent backend/account identifiers for existing integrations. The reported device
duplicate remains untraced as recorded in T00.

### Next task and required context

After review, continue **T01, change 2**: source snapshot/normalization and generic
registration boundaries using these keys, with optional identity evidence and
explicit freshness/presence. Normalize current repositories and exercise an unknown
test adapter without a `ServerType` addition. Add operation/progress contracts in
subsequent coherent T01 changes. Group/media/replica IDs and projections still need
their dedicated boundaries; T02 must not begin until all T01 gates are met.

## T01 — Shared identity and source contracts (change 2)

Date: 2026-09-24.
Status: in progress. Source snapshot and generic registration boundaries are added;
existing source normalization remains for the next reviewed change.

### Behavior implemented

- Added a normalized source snapshot containing source-owned metadata, exact native
  resources, optional evidence, and explicit freshness/presence status. It contains
  no group ID and makes no grouping decision.
- Presence is `Present`, `Removed`, or `Unknown`; only an authoritative snapshot may
  report removal. A fetch error can therefore remain unknown/stale rather than being
  converted into deletion.
- Device storage and remote resource references are distinct opaque types. A single
  normalized resource cannot advertise both locations; separate replicas/resources
  must describe those concrete copies. Device storage references are documented as
  installation-local and non-portable.
- Added `LibrarySourceAdapter` and `LibrarySourceAdapterRegistry`. Adapter input is
  opaque, allowing existing `ServerBook` wrappers and future native cached records.
- Added the production `DefaultLibrarySourceAdapterRegistry`, built from an injected
  adapter list and indexed by arbitrary `LibraryAdapterId`. It rejects duplicate IDs
  and contains no `ServerType` switch or concrete integration imports.
- Added an unknown fake integration test. It registers and normalizes with no enum
  change and valid empty evidence; missing and duplicate registrations fail explicitly.
  Existing list behavior remains unchanged.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibrarySourceAdapter.kt`
- `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibrarySourceAdapterRegistry.kt`
- `lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/DefaultLibrarySourceAdapterRegistryTest.kt`
- `lib/server/implementation/build.gradle.kts`
- `docs/unified-library-implementation-notes.md`

Schema or protocol changes: none.

### Tests/checks run

- `./gradlew :lib:server:implementation:tasks --all` — passed; confirmed available
  iOS simulator test and Android compile tasks.
- `./gradlew :lib:server:implementation:iosSimulatorArm64Test :lib:server:implementation:compileAndroidMain :lib:server:api:iosSimulatorArm64Test`
  — passed. Registry suite: 3 tests; identity suite: 4 tests; 0 failures,
  errors, or skips. API and implementation compiled for Android and iOS simulator.
  Existing coroutine opt-in/no-cast warnings in `ServerRegistryImpl` remain.
- `git diff --check` — passed. New-file whitespace checks produced no diagnostics.

### Acceptance evidence / known gaps

Three passing registry tests exercise the production boundary with an arbitrary
adapter ID, proving
registration itself does not require `ServerType`. This does not yet prove current
Local/Cloud/Storyteller/Audiobookshelf normalization or operation/progress routing.
The snapshot permits empty evidence and unavailable local execution, but persistence
of stale/unresolved snapshots starts in T02.

### Next task and required context

After review, continue **T01, change 3**: add a `ServerBook` normalization wrapper
and register current adapter-specific identity/resource mappings without changing
list output. Local and Cloud may expose verified whole-file evidence only where the
existing `sha-256-v1` byte semantics are established; other adapters must retain
unknown/no evidence. Never derive native resource identity from media type alone.

## T01 — Shared identity and source contracts (change 3)

Date: 2026-09-24.
Status: in progress. Current source adapters now share an additive normalization
bridge; operation and progress adapter contracts remain before T01 completion.

### Behavior implemented

- Added `ServerBookSourceRecord` as the migration bridge for current repositories.
  Callers must provide the scoped source reference, exact native resources, evidence,
  and source status. The bridge copies display metadata from `ServerBook` but never
  derives identity from title, cover, URL, media type, or list order.
- Added a shared `ServerBookLibrarySourceAdapter` translator. It verifies adapter
  ownership and rejects resources/evidence belonging to another source book before
  producing the generic snapshot.
- Registered Local, Parrot Cloud, Storyteller, and Audiobookshelf source adapters
  through the same injected `LibrarySourceAdapter` boundary as the unknown test
  integration. The registry remains generic; current adapter IDs are declared only
  in their implementation modules.
- No list caller uses the new projection yet, so existing output and navigation are
  unchanged. No Cloud IDs, hashes, transfers, progress keys, or database rows changed.
- Extended identity tests to demonstrate file fingerprint and adapter-certified
  edition evidence remain different variants.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/ServerBookSourceAdapter.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/ServerBookSourceAdapterTest.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/SourceBookRefTest.kt`
- `lib/server-local/src/commonMain/kotlin/com/retro99/server/local/LocalLibrarySourceAdapter.kt`
- `lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudLibrarySourceAdapter.kt`
- `lib/server-storyteller/src/commonMain/kotlin/com/retro99/server/storyteller/StorytellerLibrarySourceAdapter.kt`
- `lib/server-audiobookshelf/src/commonMain/kotlin/com/retro99/server/audiobookshelf/AudiobookshelfLibrarySourceAdapter.kt`
- `docs/unified-library-implementation-notes.md`

Schema or protocol changes: none.

### Tests/checks run

- `./gradlew :lib:server:api:iosSimulatorArm64Test :lib:server-local:compileAndroidMain :lib:server-local:compileKotlinIosSimulatorArm64 :lib:server-parrot-cloud:compileAndroidMain :lib:server-parrot-cloud:compileKotlinIosSimulatorArm64 :lib:server-storyteller:compileAndroidMain :lib:server-storyteller:compileKotlinIosSimulatorArm64 :lib:server-audiobookshelf:compileAndroidMain :lib:server-audiobookshelf:compileKotlinIosSimulatorArm64`
  — passed. All four adapter modules compiled for Android and iOS simulator; the
  server API normalization suite had 3 passing tests. Existing unrelated opt-in,
  serialization, expect/actual, and interop warnings remain.
- `./gradlew :lib:server:api:iosSimulatorArm64Test` — passed after adding the final
  evidence-kind test. XML reports: 3 normalization tests + 5 identity/evidence tests,
  with 0 failures/errors/skips.
- `git diff --check` — passed.

### Acceptance evidence / known gaps

Tests validate metadata/resource preservation and reject cross-book resources and
wrong-adapter records. Concrete adapters are registered, but creation of scoped
records still needs an orchestration boundary with the active profile/account and
adapter-owned resource IDs. That work should coincide with persisted source snapshots
in T02 rather than guessing portable identities now. Existing integrations do not
yet publish automatic evidence through this bridge.

### Next task and required context

After review, continue **T01, change 4**: generic operation capability/target and
native progress-owner contracts plus registries. Keep Cloud reservation/attestation
wire fields behind the Cloud adapter and preserve the existing transfer manager API
for old callers. Test arbitrary adapter registration and distinct progress ownership.

## T01 — Shared identity and source contracts (change 4)

Date: 2026-09-24.
Status: in progress. Generic operation/progress boundaries and registries are added;
bridging current implementations and completing T01 verification remain.

### Behavior implemented

- Added typed media-asset and storage-replica IDs plus generic operation kinds.
- Operation availability separates support/runtime usability from the target and
  requires a reason when unavailable. This is an adapter contract, not yet T06's
  group-level action resolver.
- Operation requests carry a stable operation ID, selected asset and exact device,
  remote, or upload-destination target. Every target retains scoped source and native
  resource references; upload destinations explicitly name portable destination
  account scope before a remote replica exists.
- Added backend-neutral operation execution results. No Cloud reservation, upload
  URL, attestation, file ID, or finalize payload leaked into the shared API.
- Added adapter-owned progress references and native value variants for ebook
  locators, audiobook timestamps and opaque adapter values. Unknown native data can
  round-trip as adapter-owned payload; it is not converted to percentage for writes.
- Added operation/progress adapter registries using injected arbitrary adapter IDs.
  Duplicate registrations fail; source, operation and progress adapters remain
  independently optional for an integration.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryOperationAdapter.kt`
- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryProgressAdapter.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/LibraryOperationAndProgressTest.kt`
- `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibraryOperationAdapterRegistry.kt`
- `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibraryProgressAdapterRegistry.kt`
- `lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/DefaultLibraryCapabilityRegistryTest.kt`
- `docs/unified-library-implementation-notes.md`

Schema or protocol changes: none.

### Tests/checks run

- `./gradlew :lib:server:api:iosSimulatorArm64Test :lib:server:api:compileAndroidMain :lib:server:implementation:iosSimulatorArm64Test :lib:server:implementation:compileAndroidMain :feature:books:domain:compileKotlinIosSimulatorArm64 :feature:books:domain:compileAndroidMain`
  — passed. API suites: 3 source-normalization, 5 identity/evidence and 4
  operation/progress tests. Implementation suites: 3 source-registry and 2
  capability-registry tests. All had 0 failures/errors/skips. API, implementation,
  and books-domain consumer compiled for Android and iOS simulator. Existing
  coroutine warnings in `ServerRegistryImpl`, `GetBooksUseCase`, and
  `GetSeriesUseCase` remain.
- `git diff --check` — passed; new-file whitespace checks produced no diagnostics.

### Acceptance evidence / known gaps

Tests cover exact-target source validation, required unavailable reasons, opaque
native-progress retention, progress-owner adapter scope, arbitrary registration and
duplicate rejection. This does not claim current Cloud/native operations execute via
the new boundary: old transfer and reader callers remain intact. T06 will implement
resolution and execution bridges after group-based Details exists.

### Next task and required context

After review, finish **T01, change 5** by adapting current progress ownership and
operation capability discovery without changing existing execution. Add compile and
regression coverage demonstrating current adapters coexist with the generic registry,
then assess the T01 completion gate before T02 persistence work.

## T01 — Shared identity and source contracts (change 5)

Date: 2026-09-24.
Status: implemented and verified. This completes T01's contract, normalization,
extensibility-test, compatibility-build, and no-concrete-domain-dependency gates.

### Behavior implemented

- Corrected operation targets and progress owners to retain `SourceBookRef`, not
  only its portable membership key. This keeps portable identity separate from the
  installation-local connection required to execute native repository operations.
- Exact operation requests now reject disconnected source references even when the
  portable member remains valid. Native progress resolution has the same guard.
- Upload targets carry both portable destination account identity and explicit local
  destination connection. No URL or local connection is promoted into portable identity.
- Existing execution paths remain unchanged. Current reader and transfer APIs can be
  bridged later using the retained connection ID without changing their Cloud/native
  identity contracts.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryOperationAdapter.kt`
- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryProgressAdapter.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/LibraryOperationAndProgressTest.kt`
- `docs/unified-library-implementation-notes.md`

Schema or protocol changes: none.

### Tests/checks run

- `./gradlew :lib:server:api:iosSimulatorArm64Test :lib:server:api:compileAndroidMain :lib:server:implementation:iosSimulatorArm64Test :lib:server:implementation:compileAndroidMain :lib:server-local:compileAndroidMain :lib:server-parrot-cloud:compileAndroidMain :lib:server-storyteller:compileAndroidMain :lib:server-audiobookshelf:compileAndroidMain`
  — passed. API suites: 5 operation/progress, 3 source normalization and 5
  identity/evidence tests. Implementation suites: 2 capability-registry and 3
  source-registry tests. All had 0 failures/errors/skips. API, implementation and
  all four current adapter modules compiled for Android. API/implementation tests
  compiled and ran on iOS simulator. Existing unrelated warnings remain.
- `git diff --check` — passed; new-file whitespace checks produced no diagnostics.

### Acceptance evidence / known gaps

The added test rejects disconnected operation and progress execution while preserving
the disconnected portable source reference itself. T01 intentionally does not change
list output, persist snapshots, resolve group actions, or execute through the new
adapters. Those belong to T02/T04/T06. Existing current-adapter normalization requires
the future snapshot orchestration to supply active profile/account/resource scope;
the contract refuses to guess it.

### Next task and required context

After review, begin **T02 — persist groups, memberships, and migration state**.
Add SQLDelight schema/API/DAO transactions for stable groups, scoped memberships,
aliases, separations, evidence associations and migration state. Backfill from durable
source records, never reactive list mapping. Preserve legacy `library_books`, Cloud
hash IDs, positions and transfers, and audit explicit orphan cleanup before migration.

## T02 — Persist groups, memberships, and migration state (change 1)

Date: 2026-09-24.
Status: in progress. This change adds only the initial group/membership schema and
upgrade path. No runtime writer or backfill has been added yet.

### Behavior implemented

- Added profile-scoped `library_groups` with an opaque, durable `group_id` independent
  of legacy hash-bound `library_book_id`.
- Added `library_group_memberships` with a composite source key: profile, adapter,
  identity kind, backend/account or unresolved connection, and native book ID.
  A portable membership can retain an optional local execution connection; an
  unresolved membership must retain its own connection. The primary key prevents
  silently assigning the same scoped source to two groups.
- Added a group lookup and membership queries. SQL constraints reject malformed
  portable/unresolved identity shapes. Group creation and membership insertion are
  separate SQL primitives; the upcoming DAO must wrap them in one transaction and
  check the active profile.
- Added migration `26.sqm` and advanced SQLDelight schema version from 25 to 27.
  This repository's migration generator applies `N.sqm` when upgrading from version
  `N` to `N+1`. The existing `25.sqm` therefore runs on the 25 → 26 step, and the
  new group migration runs on 26 → 27. Neither migration rewrites existing tables.

### Changed files

- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/LibraryGroup.sq`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/26.sqm`
- `lib/database/implementation/build.gradle.kts`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupQueriesTest.kt`
- `docs/unified-library-implementation-notes.md`

Schema changes: two additive tables and one index. Protocol changes: none.

### Tests/checks run

- `./gradlew :lib:database:implementation:testAndroidHostTest --tests '*LibraryGroupQueriesTest*' :lib:database:implementation:compileKotlinIosSimulatorArm64`
  — passed after correcting the schema version. Three host tests cover scoped
  uniqueness, unresolved identity constraints, and a 25 → 27 migration preserving
  a preexisting legacy library row. Android and iOS simulator implementation
  compilation passed.
- `./gradlew :lib:database:implementation:verifyCommonMainAppDatabaseMigration`
  — passed; generated migration schema matches the current CREATE statements.
- `git diff --check` — passed for tracked changes. New untracked files were also
  inspected; no unrelated dirty files were edited.

### Acceptance evidence / known gaps

These tests establish schema constraints and migration execution, not a populated
database backfill or durable query through the application. SQLite foreign-key
enforcement and active-profile validation must be handled by the DAO boundary;
source snapshots, aliases, separations, evidence, migration state, and orphan
cleanup still need implementation. This change does not alter list behavior.

### Next task and required context

After review, continue **T02, change 2**: add database API and a profile-guarded
transactional DAO for creating/looking up groups and scoped memberships. Do not
promote legacy library IDs to group IDs. Then add aliases, separations, evidence,
and migration-state storage before backfill and T02's completion checks.

## T02 — Persist groups, memberships, and migration state (change 2)

Date: 2026-09-24.
Status: in progress. The initial group/membership API and DAO are implemented;
aliases, separations, evidence, source snapshots, and backfill are still pending.

### Behavior implemented

- Added a distinct `LibraryGroupId` and `LibraryGroupsDatabase` API. Its source
  membership parameters use the scoped `SourceBookKey`/`SourceBookRef` contracts
  rather than a legacy Cloud hash ID.
- Added a SQLDelight DAO registered through `DatabaseModule`. Every read/write runs
  in `ProfileDatabaseSession.withProfile`, which checks the active profile in the
  production `DatabaseManager`. Creating a group and its first membership is one
  transaction. A repeat source returns its original group; a proposed group-ID
  collision is rejected without adding a membership.
- Portable identity persists backend/account separately from the installation's
  execution connection. Re-observing a member updates the execution connection
  without changing its group; a missing new legacy alias does not erase the old
  alias. The query rebuilds the exact typed source reference.
- Database API now exposes the shared server identity types as an API dependency.
  The server API has no database dependency, so this introduces no module cycle.
  Existing list and transfer callers are unchanged.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryGroupId.kt`
- `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryGroupsDatabase.kt`
- `lib/database/api/build.gradle.kts`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/LibraryGroup.sq`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibraryGroupsSqlDelightDao.kt`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/di/DatabaseModule.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupsSqlDelightDaoTest.kt`
- `docs/unified-library-implementation-notes.md`

SQL query change: refresh the local source connection/alias on replay. No schema
migration or protocol change in this slice.

### Tests/checks run

- `./gradlew :lib:database:implementation:testAndroidHostTest --tests '*LibraryGroupsSqlDelightDaoTest*' --tests '*LibraryGroupQueriesTest*' :lib:database:implementation:compileKotlinIosSimulatorArm64 :lib:database:implementation:verifyCommonMainAppDatabaseMigration`
  — passed after correcting a generated SQLDelight parameter name. The DAO suite
  covers replay without an orphan, connection refresh, collision rejection,
  inactive-profile rejection, account/profile isolation, and group survival after
  existing orphan cleanup removes a legacy library row. The prior schema suite
  and Android/iOS simulator compilation passed; migration verification passed.

### Acceptance evidence / known gaps

The DAO works against an in-memory SQLDelight database. The profile check in this
test uses a fake `ProfileDatabaseSession`; production registration uses
`DatabaseManager.withProfile`. No runtime source feeds call this DAO yet. Existing
group reads are point queries, not the eventual observable library projection.
Source snapshots and a migration/backfill version are absent, so this is not T02
completion or proof of the import/upload acceptance flow.

### Next task and required context

After review, continue **T02, change 3**: persist aliases/redirects, manual
separations, and evidence associations with profile scope and transactional API.
Then add migration state and source snapshots before idempotent backfill from
durable source records. Preserve existing legacy rows and their explicit cleanup
behavior until that migration is verified.

## T02 — Persist groups, memberships, and migration state (change 3)

Date: 2026-09-24.
Status: in progress. Redirects and manual separation records are durable. Evidence
associations remain for the next change because they require exact durable resource
references, which the current group schema does not yet have.

### Behavior implemented

- Added profile-scoped group aliases. A DAO transaction checks that both group
  identities exist, rejects a second conflicting target, and prevents a new alias
  from closing a cycle. Lookup follows chains to a surviving group while detecting
  any corrupt cycle already stored. Replaying the same alias is idempotent.
- Added immutable manual separation pairs that retain the complete scoped native
  book identities even when no membership is currently present. The DAO orders
  each pair, rejects self/cross-profile pairs, and treats reversed replay as the
  same decision. It stores a decision ID for later sync integration.
- Added a versioned, length-prefixed *local storage* codec for separation source
  keys. Its fields include adapter, account/backend or unresolved connection, and
  native book ID. This encoding is not a portable sync payload.
- Added `27.sqm` and moved SQLDelight version 27 → 28. The migration creates only
  the alias and separation tables. Existing groups, memberships, legacy library
  rows, progress, and transfer tables are unchanged.

### Changed files

- `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryGroupsDatabase.kt`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/LibraryGroupDecision.sq`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/27.sqm`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibrarySourceKeyCodec.kt`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibraryGroupsSqlDelightDao.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupsSqlDelightDaoTest.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupQueriesTest.kt`
- `lib/database/implementation/build.gradle.kts`
- `docs/unified-library-implementation-notes.md`

Schema change: two additive tables. Protocol change: none.

### Tests/checks run

- `./gradlew :lib:database:implementation:testAndroidHostTest --tests '*LibraryGroupsSqlDelightDaoTest*' --tests '*LibraryGroupQueriesTest*' :lib:database:implementation:compileKotlinIosSimulatorArm64 :lib:database:implementation:verifyCommonMainAppDatabaseMigration`
  — passed. Six DAO tests and three schema tests have no failures/errors/skips.
  New assertions cover alias-chain lookup, cycle/conflicting-target rejection,
  symmetric separation replay, scope-preserving source-key round trips, and the
  25 → 28 upgrade with the new decision tables. Android/iOS simulator compilation
  and SQLDelight schema verification passed.
- `git diff --check` — passed for tracked changes; newly added files were inspected
  for overlong Kotlin lines and whitespace.

### Acceptance evidence / known gaps

Alias insertion and separation insertion use SQLDelight transactions under the
active-profile session. The current separation table records a blocking decision;
it does not yet model an explicit later merge that clears or supersedes it, nor a
backend-assigned revision. T03/T05 must add the ordered decision behavior with
the existing outbox. No grouping policy or list caller reads these records yet.
Evidence associations still need durable source-resource identity, and source
snapshot/migration state/backfill are absent. T02 is not complete.

### Next task and required context

After review, continue **T02, change 4**: add durable, profile-scoped source
resource references and evidence associations for fingerprints, completed
transfers, and adapter-certified identities. Preserve evidence kind, verification,
algorithm, resource revision, both transfer endpoints, and provenance; never infer
equality from storage alone. Then add source snapshots/migration state and backfill.

## T02 — Persist groups, memberships, and migration state (change 4)

Date: 2026-09-24.
Status: in progress. Exact resource references and optional identity evidence are
durable; source snapshots, migration state, and backfill remain.

### Behavior implemented

- Added profile-scoped source-resource rows, unique by scoped source key, native
  resource ID, and exact optional revision. Null and empty revisions stay distinct.
  These rows identify resources; they do not claim availability or file ownership.
- Added separate stored evidence variants for file fingerprint, completed transfer,
  and adapter-certified edition/asset identity. SQL shape constraints prevent one
  row from combining variant-specific fields. A completed transfer stores both
  exact resource endpoints and its transfer ID. Fingerprints retain nullable
  algorithm, hash, scope, and verification; certified evidence retains namespace,
  identity, and certified kind.
- Every evidence row retains an explicit provenance kind/reference and observation
  time. Evidence IDs are immutable: identical replay is ignored, conflicting reuse
  is rejected. Retirement hides evidence from active queries without deleting its
  audit row; replay does not reactivate a retired row.
- Added `LibraryEvidenceDatabase` and a profile-guarded SQLDelight DAO registered
  through `DatabaseModule`. Recording evidence and any new endpoint resources is
  one transaction. Storage makes no equality or automatic grouping decision.
- Added `28.sqm` and advanced SQLDelight version 28 → 29. Existing group and
  legacy identity tables are unchanged.

### Changed files

- `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryEvidenceDatabase.kt`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/LibraryEvidence.sq`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/28.sqm`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibraryEvidenceSqlDelightDao.kt`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/di/DatabaseModule.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryEvidenceSqlDelightDaoTest.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupQueriesTest.kt`
- `lib/database/implementation/build.gradle.kts`
- `docs/unified-library-implementation-notes.md`

Schema change: resource and evidence tables plus an active-evidence index.
Protocol change: none.

### Tests/checks run

- `./gradlew :lib:database:implementation:testAndroidHostTest --tests '*LibraryEvidenceSqlDelightDaoTest*' --tests '*LibraryGroupQueriesTest*' :lib:database:implementation:compileKotlinIosSimulatorArm64 :lib:database:implementation:verifyCommonMainAppDatabaseMigration`
  — passed. Three evidence DAO tests and three schema tests have no failures,
  errors, or skips. They cover profile/account scope, missing versus empty resource
  revision, round trips for all evidence kinds including unknown/unverified hash
  semantics, idempotent replay, conflicting evidence IDs, cross-profile transfer
  rejection, retirement, and upgrade from version 25 through 29. Android/iOS
  simulator compilation and migration verification passed.

### Acceptance evidence / known gaps

The DAO stores evidence and only exposes active rows, but no source feed writes
through it yet. Source snapshot orchestration must retire evidence that becomes
stale or is authoritatively removed; otherwise an old verified fingerprint could
incorrectly influence T03. There is no persisted freshness/presence state,
migration version, or backfill. Cross-device grouping and the reported duplicate
remain unverified. T02 is not complete.

### Next task and required context

After review, continue **T02, change 5**: persist normalized source snapshots with
explicit freshness/presence, exact resources, and metadata. Add migration state
and idempotent backfill from durable source records. Keep authentication/fetch
errors stale rather than converting them into removal, and preserve existing
legacy Cloud IDs, positions, and transfers.

## T02 — Persist groups, memberships, and migration state (change 5)

Date: 2026-09-24.
Status: implemented and verified. Source snapshots, profile-scoped migration state,
and resumable idempotent group backfill are now wired into the live projection path.

### Behavior implemented

- Added source snapshots containing scoped source references, metadata, media
  resources, freshness/presence, and optional evidence. The current Local and Cloud
  adapters provide verified whole-file SHA-256 evidence only when the source
  resource reports the supported `sha-256-v1` algorithm.
- Snapshot writes ensure a stable group membership in the same database
  transaction. Unknown/error observations retain the prior metadata, resources,
  and active evidence; only authoritative removal retires evidence and marks the
  source removed.
- Authoritative refresh replaces source-owned metadata/resources and retires
  evidence for resources no longer present. Non-authoritative feeds cannot claim
  missing resources were deleted.
- Added batched `library_migration_state` checkpoints and idempotent group
  backfill. The live library projection resumes this backfill before subscribing
  to current repositories; each new feed snapshot also creates its group
  transactionally.
- Added the missing publication date field, group join argument, and round-trip
  coverage for series, tags, media types, and publication date.
- Repaired the populated-version-30 migration fixture so it preserves its
  `library_source_snapshots` row while upgrading through versions 31 and 32.

### Changed files

- `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibrarySourceSnapshotsDatabase.kt`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/LibrarySourceSnapshot.sq`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/30.sqm`
- `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/31.sqm`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibrarySourceSnapshotsSqlDelightDao.kt`
- `feature/library/data/src/commonMain/kotlin/com/retro99/library/data/LibraryGroupingDataRepository.kt`
- `lib/server-*/src/commonMain/kotlin/.../*LibrarySourceAdapter.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibrarySourceSnapshotsSqlDelightDaoTest.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupQueriesTest.kt`

Schema version: 32. Legacy Cloud IDs, positions, transfers, and import records are
not rewritten. Protocol changes: none.

### Tests/checks run

- Database host suites for source snapshots, group DAOs, evidence, and migrations
  passed. Migration verification and database implementation iOS simulator
  compilation passed.
- `:feature:library:data:compileAndroidMain` and
  `:feature:library:data:compileKotlinIosSimulatorArm64` passed after wiring the
  resumable backfill ahead of live source observation.
- `:feature:library:domain:allTests` passed.
- `git diff --check` passed after the integration fix.

### Acceptance evidence / known gaps

The database tests establish stale-safe persistence and interrupted/replayed
backfill over saved snapshots. Existing books become snapshots when a connected
repository emits its cached/current feed; disconnected sources retain already
saved snapshots as unknown. This does not establish that every legacy integration
exposes an offline cache on startup. Device import/upload/restart and cross-device
behavior remain unverified.

### Next task and required context

Proceed to **T03 — deterministic identity resolution**. Reconcile only persisted
memberships and provenance-bearing evidence; write merges and redirects
transactionally, and ensure manual separations block transitive joins over entire
prospective member sets.

## T03 — Implement deterministic identity resolution

Date: 2026-09-24.
Status: implemented and verified. The resolver is pure; persistence integration
applies its stale-safe merge plan and redirects.

### Behavior implemented

- Added deterministic candidates from verified, compatible whole-asset
  fingerprints, adapter-certified identities, and completed transfer links.
  Titles and unknown/unverified/mismatched fingerprints do not create candidates.
- Sorted evidence candidates and source identities before resolution. The survivor
  is the smallest existing group ID; merges retain all memberships and evidence
  provenance and redirect retired IDs.
- Checked separation constraints against the complete prospective member sets,
  preventing transitive evidence from undoing a manual separation.
- Reconciliation reads persisted memberships, separations, and active evidence;
  it writes merges only through the profile-guarded group DAO transaction. Reads
  and list rendering do not generate IDs or mutate groups.
- Group merge replay verifies redirects are complete; stale plans fail and must be
  recomputed instead of moving changed membership silently.

### Changed files

- `feature/library/domain/src/commonMain/kotlin/com/retro99/library/domain/grouping/LibraryIdentityResolver.kt`
- `feature/library/domain/src/commonTest/kotlin/com/retro99/library/domain/grouping/LibraryIdentityResolverTest.kt`
- `feature/library/data/src/commonMain/kotlin/com/retro99/library/data/LibraryGroupingDataRepository.kt`
- `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryGroupsDatabase.kt`
- `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/library/LibraryGroupsSqlDelightDao.kt`
- `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/LibraryGroupsSqlDelightDaoTest.kt`

### Tests/checks run

- `:feature:library:domain:allTests` passed, including compatible evidence,
  unknown evidence, scope/algorithm mismatch, transitive separation, determinism,
  and profile isolation.
- Database group/evidence/source snapshot host tests and schema verification
  passed, including stale merge rejection and idempotent redirects.

### Acceptance evidence / known gaps

Automatic grouping is persisted locally and independent of network/sync. Manual
merge/split decisions are not implemented yet; those are T09/T10. Real adapter
evidence and actual cross-device account identity have not been accepted on-device.

### Next task and required context

Finish **T04 — shared list and Details queries** by routing Details through the same
persisted group/membership projection. Preserve the legacy `(serverId, bookUuid)`
entry point and show all known members/resources even when one source is stale.

## T04 — Shared list and Details projections

Date: 2026-09-24.
Historical status at the T04 checkpoint: implemented and compile/test verified.
Operation selection and legacy-route redirection were then future T05/T06 work;
see the later sections for their completion status.

### Behavior implemented

- The books list and continue-reading projection now observe the same persisted
  group projection. Group rows combine stable metadata and media types while
  preserving member IDs, alternate titles, source types, and group IDs for filters
  and Details routing.
- Search and server/media filters match any grouped member's metadata and source
  type. Unknown/stale members remain visible with saved metadata; a group
  disappears only after every source was authoritatively removed.
- Added a group-based Details destination. List and series callers use it for
  persisted groups, while the serialized legacy `(serverId, bookUuid)` route
  remains available. Legacy Details also observes and displays shared source and
  resource information.
- Group Details lists source presence and exact resource availability. Its
  Read/Listen action is offered only when the chosen resource is device-present,
  the source snapshot is fresh/present, and a local storage reference exists.
- Added focused domain/UI tests for one-row grouping, metadata/search/filter
  projection, unavailable-source retention, and reader-target eligibility.
- Imported generated Compose string accessors for the new UI. This fixed an
  initial compile failure where resource generation succeeded but the new Kotlin
  files had not imported the generated top-level accessors.

### Changed files

- `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/model/UnifiedServerBook.kt`
- `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/ObserveUnifiedServerBooksUseCase.kt`
- `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/GetBooksUseCase.kt`
- `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/LibraryGroupDetailScreen.kt`
- `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/LibraryGroupDetailViewModel.kt`
- `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/LibraryGroupLocationsSection.kt`
- `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailViewModel.kt`
- `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/navigation/HomeNavigation.kt`
- `feature/library/domain/src/commonMain/kotlin/com/retro99/library/domain/projection/LibraryReaderTarget.kt`
- `translations/src/commonMain/composeResources/values/strings.xml`

Protocol/schema changes: none.

### Tests/checks run

- `:feature:library:domain:allTests` passed, including reader-target tests for
  fresh device resources and rejected stale, missing, or remote resources.
- Android and iOS simulator compilation passed for Books UI and Home UI after the
  Compose accessor imports were corrected.
- `feature:books:ui/src/commonTest/kotlin/com/retro99/books/ui/list/BooksListUnifiedLibraryTest.kt`
  covers search, server, and media filters across grouped metadata.
- At the T04 checkpoint, no device or rendered-UI acceptance had been run; later
  device acceptance is recorded in T05 and T12.

### Acceptance evidence / remaining gaps (historical snapshot)

At this point, the old destination was known to remain valid, but concrete
resource handoff and generic operation flows had not yet been verified. Those
items were subsequently implemented; see T05–T08 and the current consolidated
status above.

### Next task and required context

Continue **T05 — route by group and launch concrete media**. Validate that the
selected native reader/player request uses the selected resource and its progress
owner, and add coverage for a redirected group ID and a missing device file.
Then proceed into T06.

## T06 — Initial upload-target resolver slice (historical checkpoint)

Date: 2026-09-24.
Status: adapter contract and pure resolver were implemented first; runtime
operation and transfer flows were added later. Current coverage and remaining
acceptance are recorded below.

### Behavior implemented

- Operation adapter registries enumerate adapters deterministically, including
  arbitrary adapter IDs without adding a `ServerType` case.
- Operation adapters can propose upload destinations for a request bound to an
  exact asset, device replica, source resource, and format. Each candidate retains
  its destination adapter, account, connection, and accepted format.
- The library domain resolver filters proposals by profile, source-replica
  availability, registered adapter, connected source and destination connections,
  authenticated destination account, accepted format, and reported Upload capability.
- Every rejected source or candidate has an explicit unavailable reason; exact
  resource and replica identities survive resolution so same-format resources are
  not collapsed.

### Changed files

- `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/library/LibraryOperationAdapter.kt`
- `lib/server/api/src/commonTest/kotlin/com/retro99/server/api/library/LibraryOperationAndProgressTest.kt`
- `lib/server/implementation/src/commonMain/kotlin/com/retro99/server/implementation/library/DefaultLibraryOperationAdapterRegistry.kt`
- `lib/server/implementation/src/commonTest/kotlin/com/retro99/server/implementation/library/DefaultLibraryCapabilityRegistryTest.kt`
- `lib/server/implementation/build.gradle.kts`
- `feature/library/domain/src/commonMain/kotlin/com/retro99/library/domain/operation/LibraryUploadTargetResolver.kt`
- `feature/library/domain/src/commonTest/kotlin/com/retro99/library/domain/operation/LibraryUploadTargetResolverTest.kt`

### Tests/checks run

- `:lib:server:api:allTests` passed.
- `:lib:server:implementation:allTests` passed, including arbitrary adapter
  enumeration and upload proposal retrieval.
- `:feature:library:domain:allTests` passed, including disabled capability,
  unregistered/disconnected destination, account/profile/format filtering, and two
  same-format resources retaining distinct targets.
- `git diff --check` passed.

### Current status

The initial gaps recorded here were subsequently addressed: Group Details now
discovers and executes adapter-backed operations, observes transfers for every
member, and supports exact resource targets. Parrot Cloud implements upload
proposal and execution. Current remaining gaps are the T11 regression matrix and
T12 live Cloud/two-device acceptance; see the sections below.

## T05 — Route by group and launch concrete media

Date: 2026-09-25.
Status: implemented; emulator and Xiaomi local EPUB acceptance passed.

### Behavior implemented

- Added a group-based Details destination while retaining the serialized legacy
  `(serverId, bookUuid)` route. The legacy route resolves source membership,
  follows group redirects, and replaces itself with the canonical group route.
- Group Details exposes known source snapshots and resource availability, and
  resolves a concrete resource, device replica, and native progress owner before
  launching a reader/player. It does not use the displayed metadata member as a
  substitute for the selected source.
- Group Details discovers adapter-backed downloads and existing native reader/cache
  paths. Progress and transfer ownership remain source-specific.
- Fixed imported-local identity projection to match the live book by exact resource
  ID when a portable content-hash membership key differs from its imported UUID.
  Legacy Details uses the same resource-ID match for redirection.

### Tests and acceptance

- `:feature:books:domain:iosSimulatorArm64Test --tests '*UnifiedServerBookTest*'`
  passed the portable-local-key regression.
- `:feature:books:ui:compileAndroidMain` passed.
- On emulator `emulator-5556`, fixtures A and B opened Group Details; **Open eBook**
  opened the Reader, which rendered Chapter One and fixture A text.
- On Xiaomi `7TEULNB6JJTK75Y5` (`2602BPC18G`), both A and B opened Group Details
  and the Reader displayed their distinct fixture text. No app fatal exception
  appeared in the checked logcat. Screenshots were captured as
  `/tmp/unified-xiaomi-detail-a.png`, `/tmp/unified-xiaomi-reader-a.png`,
  `/tmp/unified-xiaomi-detail-b.png`, and `/tmp/unified-xiaomi-reader-b.png`.
- Cloud-only remote playback/download, Storyteller/Audiobookshelf native playback
  parity, and iOS runtime behavior were not exercised in this acceptance run.

## T06 — Resolve operations and observe transfers generically

Date: 2026-09-25.
Status: adapter-driven operation discovery and execution are implemented; broader
unknown-adapter and live-service acceptance remains T11/T12.

### Behavior implemented

- Operation adapter registries enumerate arbitrary adapter IDs without expanding
  `ServerType`. Capabilities and exact source/resource/destination references are
  checked when resolving upload, download, and removal actions.
- Group Details resolves upload targets by account, profile, connection, asset
  format, and adapter capability. It displays eligible destinations and transfer
  state associated with every group member.
- Parrot Cloud supplies upload destination proposals and executes its upload
  transport while retaining Cloud runtime policy and hash/file identity checks.
- Device replica removal is capability-driven; UI and ViewModel do not require the
  adapter ID to equal Local. The use case persists and recovers removal intent.
- Resource details now use the full member-card width, with actions stacked below;
  this avoids one-character wrapping when a resource has several actions.

### Tests/checks

- `:lib:server:api:allTests`, `:lib:server:implementation:allTests`, and
  `:feature:library:domain:allTests` passed for adapter registration, target
  filtering, and exact same-format resource identity.
- Focused Details operation and management tests are under
  `feature/books/ui/src/commonTest/kotlin/com/retro99/books/ui/detail/`.
- `:feature:books:ui:compileAndroidMain` passed after the final resource-row layout
  fix. A linked-account Cloud operation was not exercised.

## T07 — Preserve group associations across uploads and downloads

Date: 2026-09-25.
Status: implementation and focused regression coverage present; live Cloud
transfer acceptance is incomplete.

### Behavior implemented

- Upload/download transfer records retain exact source, destination, asset, hash,
  and transfer identity. Existing verified local files are reused; download
  finalization keeps its byte/hash/library-ID safeguards.
- Cloud feed and transfer completion orderings are reconciled idempotently. Feed
  changes arriving before the corresponding book/file identity is available are
  retained for replay rather than silently discarded.
- After Cloud identity promotion and pending-feed replay, the sync adapter scans
  completed transfers against persisted available file state and exact Cloud book
  metadata. It repairs eligible legacy unresolved evidence without relying on a
  newly replayed `available` event or on session-only identity.
- The recovery pass uses existing evidence validation with explicit profile scope;
  transfer evidence is not inferred from titles or unverified filenames.

### Tests/checks

- `:lib:server-parrot-cloud:iosSimulatorArm64Test --no-daemon` passed 45 tests,
  including persisted-state evidence rekeying, idempotency, and rejection of
  identity available only in the current session.
- `:lib:server-parrot-cloud:compileAndroidMain` passed.
- `BookFileTransferEngineTest` and `DownloadFinalizerTest` cover retry/restart,
  duplicate enqueue, late cancellation, verified local-file reuse, and hash
  validation; `ParrotCloudBookFileChangeApplierTest` covers feed/completion order.
- Earlier focused transfer tests passed on Android host with forced task rerun.
  No linked-account Cloud transfer was run on either device.

## T08 — Implement safe replica-specific removal semantics

Date: 2026-09-25.
Status: implementation and unit coverage present; cross-device deletion acceptance
is unverified.

### Behavior implemented

- Device removal addresses a concrete owned storage reference, records recoverable
  removal state, and preserves group membership, reading progress, bookmarks, and
  unrelated replicas. It works independently of a Cloud download transfer.
- Ordinary remote-file deletion preserves completed device downloads. Mandatory
  invalidation uses its separately classified path and may remove restored bytes.
- File/resource tombstones remove stale remote availability while preserving book
  history. A Cloud listing marked partial does not treat an omitted book as an
  authoritative deletion.
- Device-removal availability comes from registered operation capability and
  target ownership rather than a hard-coded Local adapter test.

### Tests/checks

- Focused coverage exists in `LibraryReplicaRemovalDataRepositoryTest`,
  `LibraryReplicaRemovalSqlDelightDaoTest`, `BookFileTransferEngineTest`, and
  `ParrotCloudBookFileChangeApplierTest` for removal recovery, tombstones, ordinary
  deletion retention, and mandatory invalidation.
- No two-device deletion/retention run was possible without an authenticated shared
  Cloud account.

## T09 — Implement local manual merge and split

Date: 2026-09-25.
Status: local persistence, UI, and active-observer refresh are implemented and
unit tested; merge/split was also exercised on emulator.

### Behavior implemented

- Merge accepts explicit source-member IDs and preferred display metadata. Split
  moves selected members to a new group and records separations without rewriting
  native metadata, assets, physical files, or progress.
- Repository projections observe group, membership, and decision changes as well as
  source updates. An existing list collector can reflect merge/split without a
  source-feed emission.
- The list provides merge selection and metadata choice; Group Details provides
  member selection and confirmation before separating selected sources.

### Tests and acceptance

- `LibraryGroupingDataRepositoryTest` exercises merge/split while source feeds stay
  unchanged; Books UI tests cover selection constraints and merge metadata choice.
- Emulator acceptance selected only **Unified Library Acceptance A** and **B**,
  merged them, observed both source cards in Group Details, split A, and returned
  to the list where A and B appeared separately. The Details screen remained on
  its prior route until navigation back; the active list projection showed the
  updated rows without an app restart.
- Existing books and generated EPUB fixtures were retained.

## T10 — Synchronize grouping using the existing sync machinery

Date: 2026-09-25.
Status: versioned Cloud payload, outbox/transport, and backend persistence are
implemented; backend and two-device convergence remain unverified.

### Behavior implemented

- Versioned portable grouping decisions include operation identity and explicit
  membership references. Local mutations persist with sync outbox state; transport
  uses existing mutation delivery and backend revision ordering.
- Payload encoding defers a decision if any member lacks trustworthy portable
  identity. Remote application also rejects unresolved members; the system does
  not substitute connection UUIDs, URLs, usernames, or native IDs from unrelated
  installations.
- Cloud account identity can be promoted after verified profile linkage. T07's
  persisted-state transfer-evidence recovery handles eligible older evidence after
  promotion.

### Tests/checks and limits

- Payload/mapping, account scope, deferred-identity, and sync-applier tests are in
  `feature/sync/domain`, `lib/server-parrot-cloud`, and `feature/library/data`.
- `LibraryGroupsSqlDelightDaoTest` verifies stale-revision suppression and applies
  accepted local merge/split echoes after earlier remote membership changes,
  including duplicate-echo idempotence. The SQL fixture also verifies a stale-base
  mutation is serialized at the next account revision. These checks cover client
  rebase semantics and backend ordering separately; they are not a live two-device
  convergence test.
- Backend migration is
  `supabase/migrations/20260924000009_parrot_cloud_library_group_decisions.sql`;
  the pgTAP fixture is `supabase/tests/library_group_decisions_test.sql`.
- `scripts/supabase/test.sh` was attempted after TCP port 54322 accepted a
  connection. Supabase CLI still failed with `LegacyDbConnectError`: connecting to
  `127.0.0.1` as `postgres` to database `postgres` timed out. No SQL assertions
  ran. The existing storage-audit purge process was left untouched. No shared
  account was linked, so Cloud two-device convergence, deletion, and device-removal
  retention were not exercised.
- The backend validation follow-up adds per-mutation handling for invalid outer and
  payload UUIDs, including valid siblings in the same batch, and rejects unknown
  top-level version-1 fields. `library_group_decisions_test.sql` has 49 assertions;
  the local database remained unavailable, so the SQL suite is source-reviewed but
  not executed.
- Storyteller and Audiobookshelf do not expose a durable backend-instance plus
  account identity in their current contracts. Their grouping decisions are
  deliberately deferred from cross-device sync until a trustworthy identity or an
  explicit verified binding flow exists. Local grouping continues to work.
- The current RPC accepts grouping payload version 1 only. The client rejects an
  unsupported incoming version before writing it; `SyncPullEngine` checkpoints
  only after a page applies, so the local state and cursor remain unchanged. If a
  future server sends a version this client does not understand, that page blocks
  further pull progress until the client is upgraded. No future-version negotiation
  test exists; no such payload can be emitted by the current RPC.

## T11 — Prove extensibility and preserve existing integrations

Date: 2026-09-25.
Status: unknown-adapter behavior is covered across focused integration and
Details tests; broader existing adapter regressions are not proven as one complete
acceptance matrix.

### Verified coverage

- `UnknownLibraryIntegrationTest` covers an arbitrary source through listing and
  verified-evidence grouping, manual merge/split, audiobook resource/progress
  ownership, upload target resolution, operation execution, and capability toggles.
- `LibraryGroupDetailOperationTargetTest` covers unknown-adapter Details/download/
  open action selection. Current Local, Storyteller, Audiobookshelf, and Cloud
  source/operation adapters also have focused contract tests. Storyteller and
  Audiobookshelf do not claim a native reader-cache key; Details uses a
  source/resource-scoped fallback. A target test verifies that matching native
  book IDs from different connections do not collide; a two-server download/open
  device regression is not yet recorded.
- The resolver keeps entries with unknown identity in separate groups while still
  allowing manual grouping.

### Not proven as a complete matrix

- All current adapter modules passed their `allTests` tasks, and sync/data/UI
  module suites passed. `:feature:reader:ui:allTests` and
  `:feature:reader:domain:allTests` also passed with `--rerun-tasks`; these ran
  Android host and iOS Simulator test tasks. The full streaming, multi-file
  audiobook, disconnected-source, and legacy reader regression matrix was not run
  together.
- Audiobookshelf has no trustworthy portable installation identity, so its
  cross-device grouping decisions remain deferred.

## T12 — Acceptance, migrations, and documentation

Date: 2026-09-25.
Status: Android local-import acceptance and migration unit coverage passed; full
acceptance remains partial with explicit environmental blockers.

### Verified

- `:androidApp:assembleDebug` passed. The acceptance APK was installed with
  `adb install -r` on emulator `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`,
  preserving app data. After the audit fixes, the app was rebuilt and reinstalled
  on both devices, launched on both, and showed no AndroidRuntime fatal exception.
  Group Details opened the local fixture and launched it in the Reader on the Xiaomi.
- Both generated fixture EPUBs imported on the Xiaomi; both appeared in Books,
  opened Group Details, and opened the Reader. Their chapter text was distinct, and
  checked logcat had no app fatal exception.
- Emulator verified Reader launch for A, plus local merge/split and active list
  projection refresh for only fixtures A and B.
- `:lib:database:implementation:testAndroidHostTest --tests
  'com.retro99.database.implementation.DatabaseMigrationPreservationTest'
  --rerun-tasks --no-daemon` passed one test:
  `migrationFromVersion25PreservesUserAndPendingSyncRows`. This is migration fixture
  evidence, not a populated legacy database device-upgrade acceptance.
- The affected books UI/home UI/domain suites and library/sync/server adapter
  suites passed their `allTests` tasks with `--rerun-tasks`, including iOS Simulator
  unit suites. This included the cache-failure listing regression and native cache
  key assertions. The final Android app build passed, and both connected devices
  accepted the APK update with `adb install -r`.
- `./gradlew :feature:reader:ui:allTests :feature:reader:domain:allTests
  --rerun-tasks --no-daemon` passed (`BUILD SUCCESSFUL`, 47 seconds), including
  Android host and iOS Simulator test tasks. This does not establish an iOS app
  launch or the complete combined streaming/multi-file/disconnected-source matrix.

### Still open

- Shared-account Cloud sync and deletion/retention across both devices.
- pgTAP execution; `scripts/supabase/test.sh` timed out connecting to local
  Postgres despite the TCP port accepting connections. The storage-audit purge
  process remains untouched.
- End-to-end Cloud download, Local reappearance, and Reader open with a linked
  Cloud account.
- A populated pre-change database upgrade/restart on a device, including positions,
  bookmarks, pending transfers, profile boundaries, and aliases.
  The connected emulator and Xiaomi were preserved with their existing app data;
  the other phone AVD also has an existing 19 GiB data image and was not treated as
  disposable. A fresh API 36 AVD plus an isolated v25 build is the safe path. Host
  free space was 18 GiB at the last check; recheck before creating that AVD.
- iOS runtime acceptance and the complete streaming/multi-file regression matrix.
  `xcodebuild -showdestinations -workspace iosApp/iosApp.xcworkspace -scheme iosApp`
  reports the iOS 26.5 platform is not installed; the available iOS 26.2 simulators
  are shut down and the connected iPhone is unavailable. iOS Simulator unit suites
  passed, but they do not cover app launch, import, navigation, or reader rendering.
- Trustworthy portable identity for Audiobookshelf sync. Storyteller identity is
  available only from servers that expose both supported UUID endpoints.
- Source snapshots now persist the submillisecond nanosecond remainder (schema v41)
  and compare full `Instant` precision. The reverse-arrival same-millisecond regression,
  v40-to-v41 row-preservation migration test, and SQLDelight migration verification pass.
  Exact-equal instants still have no sound tie-breaker: `revision` is nullable and opaque,
  and the source contract exposes no ordered sequence, so an exact tie remains last-arrival-wins.

No commit, push, merge, rebase, app-data clear, or fixture removal was performed.

## T13 — Detach shared local replicas and migrate recovery phase

Date: 2026-09-25.
Status: implementation, focused tests, app installation, and emulator route acceptance
pass. A direct populated v39-to-v40 device migration assertion remains open.

### Changes

- Device replica removal now executes the owning operation adapter for both shared
  and final storage owners. The selected owner is always detached; the adapter
  receives `deleteStorageBytes = false` while another source references the path,
  and `true` for its final owner.
- Renamed the durable `BytesRemoved` phase to `EffectApplied`, since the adapter
  may detach an association while preserving shared bytes. SQLDelight schema v40
  rebuilds the intent table, maps existing `BytesRemoved` rows to `EffectApplied`,
  and recreates both indexes.
- Corrected T11 cache notes: Storyteller and Audiobookshelf use the
  source/resource-scoped fallback. Matching native book IDs from different
  connections are tested to produce distinct cache keys.
- Storyteller now reports a portable identity only when authenticated `/api/v2/user`
  and `/api/v2/server/details` responses both contain canonical UUIDs. An older or
  unsupported server remains unresolved.

### Verification

- `:feature:library:data:allTests :lib:server-local:allTests --rerun-tasks
  --no-daemon` passed, including the shared-owner request assertion (`false`) and
  final-owner request assertion (`true`). The Local adapter suite covers retaining
  file bytes while removing the imported-book association.
- `:lib:database:implementation:verifyCommonMainAppDatabaseMigration
  :lib:database:implementation:testAndroidHostTest` passed. The v39 migration test
  verifies all removal-intent fields survive, `BytesRemoved` maps to
  `EffectApplied`, and `Requested` remains unchanged.
- `:lib:server-storyteller:allTests` passed, including valid server/account IDs,
  older servers, missing or malformed IDs, and failed authenticated lookup.
- `:feature:books:ui:allTests` passed. The Cloud resource regression drives a
  mutable source feed through the real grouping repository and into Books List and
  Group Details. Its source adapter is a fake; concrete Cloud adapter mapping and
  live linked-account behavior are not established by this test.
- `:feature:books:ui:allTests` also passed after adding the reader-cache and
  Audiobookshelf Details regressions. `:feature:reader:data:allTests` and
  `:feature:library:domain:allTests` passed in the same focused validation run.
- `:androidApp:assembleDebug` passed. The latest APK installed with `adb install -r`
  on emulator `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`; both apps launched
  without clearing app data. On the emulator, the fixture opened Group Details and
  its EPUB chapter text rendered in Reader.

### Still open

- Assert a populated v39-to-v40 profile upgrade and restarted data on a device. Both
  devices opened after the data-preserving install, but the schema version was not
  queried during this pass.
- The Cloud feed-to-Group-Details regression uses a fake Cloud source adapter; live
  linked-account behavior and the concrete adapter mapping remain unverified.
- Cloud/two-device service acceptance, Audiobookshelf portable identity, iOS runtime,
  and the full combined regression matrix remain open as recorded in T10–T12.

## T14 — Offline reader-cache access and multi-file cancellation

Date: 2026-09-25.
Status: source/resource-scoped cached opening, transactional multi-file downloads,
focused tests, Android build, and emulator acceptance pass.

### Changes

- Group Details discovers Storyteller and Audiobookshelf reader-cache targets when
  a persisted source snapshot is `Present` or `Unknown`. It does not call live
  operation availability to discover an existing cache. Downloads still require a
  `Present` snapshot and a successful availability check.
- Opening cached media rechecks the active profile, group membership, exact source
  resource, completed download state, prepared local file, and latest group before
  navigating. Progress remains owned by the native source adapter and book ID.
- Multi-file reader downloads write into per-attempt staging directories and publish
  only after every file succeeds. Failure and cancellation clean staging files.
  Android and iOS managers preserve a multi-file canonical bundle on cancellation
  while still removing an active single-file partial cache.
- Added a two-file Audiobookshelf-shaped Details regression and tests for Present to
  Unknown cache access, incomplete cache state, removed/changed resources, and
  cancellation/retry behavior.

### Verification

- `:feature:books:ui:allTests` passed, including the Audiobookshelf Details to
  Reader regression with two audio-file resources and native progress ownership.
- `:feature:reader:data:allTests` passed, including failed download retry and
  cancelled replacement preservation. `:feature:library:domain:allTests` passed.
- `:androidApp:assembleDebug` passed and installed successfully on both connected
  Android devices with `adb install -r`. The emulator opened a fixture from Group
  Details and displayed its chapter body in Reader.

### Still open

- The offline Storyteller/Audiobookshelf path is verified by focused UI tests; a
  live connected server that becomes unavailable after a completed download was
  not available for device acceptance.
- Linked Cloud account/two-device sync, remote download and deletion acceptance,
  pgTAP execution, Audiobookshelf portable installation identity, iOS app runtime,
  populated legacy device upgrade, and the full combined regression matrix remain
  open. See T10–T13 for constraints and prior evidence.

## T15 — Re-audit integration findings and close reader retry handoff

Date: 2026-09-25.
Status: review findings 1–3, 6, and 7 are resolved and covered by current source or
focused tests. Finding 4 remains limited by source identity contracts; finding 5
remains open for Cloud omissions without a server completeness/tombstone contract.

### Findings and changes

- Fingerprint branches now narrow to `BookFingerprint` and `FileFingerprint`
  independently; the Cloud source adapter overrides an open snapshot method.
- Existing group observers reload when group, preference, separation, source
  snapshot, or projection data changes. A repository test keeps source feeds
  unchanged while an existing collector receives merge and split results.
- Group Details resolves adapter-driven downloads, opens supported native reader
  caches, and discovers device-removal support through operation capabilities.
  Live Cloud account behavior remains unverified; its current download adapter
  supports ebook and read-aloud files.
- Complete source listings reconcile omitted books to `Removed`; explicit
  tombstones do the same for partial feeds. Authoritative snapshots mark omitted
  resources unavailable and retire their identity evidence while retaining resource
  history. Parrot Cloud has no complete-list contract, so it continues to use the
  safe partial default. Audiobookshelf likewise stays unresolved until its server
  exposes a trustworthy stable instance identity.
- Transfer evidence now uses a matching Cloud membership or authenticated account
  identity and can reassociate previously unresolved evidence after account linking.
- iOS now keeps the cancellation marker under `activeJobsMutex` through pending
  retry job registration. A later cancel either removes a queued retry or sees and
  cancels the registered job. Android's reviewed stop path already serializes
  external starts and stop checks on the main thread and reserves pending worker
  starts; no Android change was needed.

### Verification

- `:feature:library:domain:allTests :feature:library:data:allTests
  :feature:books:ui:allTests :lib:database:implementation:verifyCommonMainAppDatabaseMigration
  :lib:database:implementation:testAndroidHostTest --rerun-tasks --no-daemon` passed.
- After the final iOS cancellation handoff and cleanup changes,
  `:feature:reader:data:allTests --rerun-tasks --no-daemon` passed, including the
  iOS Simulator test target.
- `:feature:sync:domain:allTests :feature:sync:data:allTests
  :lib:server-local:allTests :lib:server-storyteller:allTests
  :lib:server-audiobookshelf:allTests :lib:server-parrot-cloud:allTests
  --rerun-tasks --no-daemon` passed, covering deferred identity payloads, source
  identity promotion, and transfer evidence reassociation.
- `:androidApp:assembleDebug` passed. Its APK installed with `adb install -r` on
  `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`, without clearing app data. Both
  processes launched and the checked AndroidRuntime error logs were empty.
- At the device-upgrade check, host space was 17 GiB, below the existing 19 GiB
  AVD data image size. No new AVD was created, and neither connected device was
  wiped for the populated legacy-database upgrade check.

### Still open

- Audiobookshelf grouping decisions with unresolved source identity remain deferred
  from cross-device sync. Obtaining a trustworthy portable server-instance ID is
  an upstream identity requirement; a username or base URL is not treated as a
  sufficient substitute.
- Parrot Cloud's books repository does not declare its local change feed complete,
  and the feed has no book-level removal tombstone. Exact file-removal events are
  handled, but unobserved list omissions cannot be reconciled safely.
- Live linked-account Cloud download, two-device grouping convergence, remote
  deletion/retention acceptance, and pgTAP execution remain open. Local Postgres was
  not tested because the storage-audit and Docker inspection processes were still
  live.
- iOS app runtime acceptance, populated legacy-database device upgrade, and the
  complete streaming/multi-file/disconnected-source acceptance matrix remain open.

## T16 — Add confirmed Cloud book removal and revalidate source revisions

Date: 2026-09-25.
Status: implementation, focused tests, combined module regression, Android build,
and local EPUB device smoke tests pass. Linked-account Cloud service acceptance and
the previously recorded upstream/platform limits remain open.

### Changes

- Added the book-level `RemoveRemoteBook` operation separately from media-resource
  deletion. Group Details asks for confirmation and preserves downloaded device
  copies and native reading/listening progress.
- Parrot Cloud validates the active linked account, Cloud book identity, content
  identity, and remote revision. Whole-book cleanup removes known Cloud files first
  and queues a book tombstone once those files are gone. Active uploads block the
  cleanup. `deleting` files can be retried, and a server-side file/upload race
  returns an uncached `retryable` result with a 30-second delay. Repeated pending or
  dispatched deletes return as already queued; a `conflict_preserved` entry remains
  intact while a new pending attempt is added.
- Added the source snapshot revision to the remote-removal target identity. A
  revision change refreshes action availability and causes an already-open
  confirmation to fail its current-target comparison.
- Expanded the [integration guide](unified-library-integration-guide.md) with the
  book-level versus resource-level operation contract.

### Verification

- The combined `allTests` run passed for server API/implementation, library domain
  and data, books domain and UI, sync domain and data, reader data/domain/UI, Local,
  Storyteller, Audiobookshelf, and Parrot Cloud adapters. SQLDelight migration
  verification and Android host migration tests passed in the same run.
- After the revision-freshness change, `:feature:books:ui:allTests` passed; both
  revision-only capability refresh and stale-confirmation regressions ran on the
  iOS Simulator test target.
- The final `:androidApp:assembleDebug` passed. `adb install -r` succeeded on
  emulator `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`, preserving app data.
  Both launched the updated app. Group Details showed the local source and device
  replica, and the fixture opened in Reader with its “Chapter One” text rendered on
  both devices. Recent `AndroidRuntime` logs contained no fatal exception.
- Read-only `PRAGMA user_version` checks returned schema version 41 for the default
  user database on both devices. Neither device was cleared or downgraded to test a
  legacy migration.

### Still open

- Linked-account Cloud upload, remote book deletion, device-copy retention,
  second-device download/open, and two-device merge/split convergence remain
  unverified against a live account. Adapter/service tests do not replace this
  acceptance.
- pgTAP remains unverified because the local Postgres test attempt timed out and
  storage-audit/Docker inspection processes were left running. Do not stop those
  processes to force a retry.
- Audiobookshelf still needs a trustworthy server-installation identity for
  cross-device grouping. Parrot Cloud listings remain Partial, so omission-only
  changes cannot safely remove old books; explicit `library_book_deleted`
  tombstones are handled by the new path.
- A populated pre-change database upgrade/restart on a device remains open. Both
  connected user databases are already at schema 41; the configured AVD data image
  is larger than current free host space, so no disposable AVD was created.
- iOS app runtime acceptance and live connected-server streaming/multi-file/
  disconnected-source acceptance remain open; iOS Simulator unit suites passed.

## T17 — Restore the Android build and repeat device smoke checks

Date: 2026-09-25.
Status: implementation and Android smoke checks passed; external service and
platform acceptance remained open.

### Changes

- Fixed the Android compile blocker by importing the existing
  `upsertLibraryBookRow` DAO extension. SQLDelight generation was intact.
- No schema or protocol changes were made in this task.

### Verification

- The focused sync, books-data, and Parrot Cloud suites reported success in the
  final recheck and were UP-TO-DATE. The Android build passed.
- The APK installed with `adb install -r` and launched on `emulator-5556` and
  Xiaomi `7TEULNB6JJTK75Y5`, preserving app data. Group Details opened the Local
  fixture EPUB and the Reader rendered “Chapter One” on both devices.
- Live Cloud/two-device acceptance, pgTAP, Audiobookshelf portable identity,
  populated legacy-database upgrade, iOS app runtime, and the full
  connected/disconnected media matrix remained open.

## T18 — Close plan-coverage gaps and verify the v41-to-v42 upgrade

Date: 2026-09-25.
Status: focused implementation verification passed. Live-service, physical-device,
legacy-baseline, and iOS app acceptance remain open.

### Changes

- Completed group-aware series/favorite behavior and preserved collections through
  the Storyteller API/cache, source snapshots, and unified per-source projection.
  Added SQLDelight schema v42 and migration `41.sqm` for snapshot collections.
- Restored Local/Cloud progress sharing by legacy `libraryBookId` while preferring
  server-scoped identities and rejecting ambiguous cross-source UUID matches.
- Added per-source, per-media progress to each Group Details resource row.

### Verification

- The following combined command passed, including Kotlin Multiplatform tests,
  migration verification, and the Android debug build:

  ```sh
  ./gradlew :lib:server:api:allTests :lib:server-storyteller:allTests \
      :feature:books:data:allTests :feature:books:domain:allTests \
      :feature:books:ui:allTests :feature:reader:domain:allTests \
      :feature:library:domain:allTests :feature:library:data:allTests \
      :feature:sync:domain:allTests :feature:sync:data:allTests \
      :lib:database:implementation:allTests \
      :lib:database:implementation:verifyCommonMainAppDatabaseMigration \
      :lib:server-parrot-cloud:allTests :androidApp:assembleDebug --no-daemon
  ```

- The emulator database was schema v41 before install. The v42 APK installed with
  `adb install -r`, preserving data; after launch, the database reported schema
  v42 (49 tables became 50). Existing rows were preserved: `imported_books=6`,
  `library_books=6`, `library_groups=14`, `library_group_memberships=10`,
  `library_source_snapshots=10`, `library_source_snapshot_resources=10`, and
  `library_identity_evidence=9323`.
- Group Details rendered the Local source and device copy. Opening its fixture EPUB
  rendered “Chapter One.” No `AndroidRuntime` errors appeared in the checked logcat
  window. The physical Android device was not attached for this pass.

### Still open

- Live linked-account Cloud upload/download, cross-device grouping convergence,
  remote deletion/retention, and the backend pgTAP suite remain unverified. The last
  local Postgres run timed out; the existing Docker/storage-audit processes remain
  untouched.
- Audiobookshelf has no trustworthy portable server-instance ID; it cannot safely
  synchronize those membership references across installations until that identity
  is supplied upstream. Partial Cloud listing omissions also cannot prove deletion
  without an explicit tombstone.
- A populated pre-unified-library database upgrade, iOS app runtime acceptance, and
  the full connected/disconnected streaming and multi-file matrix remain open.

## T19 — Review Group Details loading latency

Date: 2026-09-25.
Status: functional behavior passed; the emulator latency cause is not isolated.

### Findings

- `LibraryGroupDetailViewModel.observeGroup()` observes the complete projected group
  list, then calls `getGroup()` on every list emission. `getGroup()` resolves the
  requested ID and rebuilds the same full projection before the ViewModel updates
  its state. This is redundant work; a single-group observation could avoid the
  repeated projection while preserving alias resolution.
- The initial projection loads memberships and source snapshots. It does not read
  the 9,323 identity-evidence rows. With 10 memberships and 10 snapshots in the
  emulator database, the duplicate projection alone is unlikely to explain a
  wait of tens of seconds.
- Automatic evidence reconciliation separately reads active evidence after source
  list updates, with additional resource lookups per row. The emulator had repeated
  garbage collections, queued-work warnings, skipped frames, high app CPU, and low
  free memory. Device pressure is a plausible contributor, but the available logs
  do not prove it caused the delay.

### Acceptance

- Group Details eventually rendered, and opening the fixture reached the Reader and
  rendered “Chapter One.” Functional acceptance is complete for this flow.
- Xiaomi `7TEULNB6JJTK75Y5` reconnected after T18. The existing debug APK installed
  with `adb install -r` without clearing app data; the app launched, Group Details
  showed the local source and device copy, and the fixture opened in Reader with
  “Chapter One” rendered. The checked `AndroidRuntime` error log was empty.
- The existing evidence does not establish a reproducible latency failure on an
  otherwise idle device. Keep that UX caveat open and profile again if the delay
  reproduces under controlled device load.

## T20 — Finish adapter identity promotion and Android-only verification

Date: 2026-09-25.
Status: the remaining local genericity and migration-fixture work passed Android
host tests and an Android app build. Live Cloud, backend, and connected-server
acceptance remain open. iOS testing is deferred per the user's direction.

### Changes

- Replaced the Parrot Cloud-specific identity-promotion branch in
  `LibraryGroupingDataRepository.saveSourceBooks()` with the optional
  `LibrarySourceIdentityPromotionAdapter` capability. The repository scopes
  promotion to the matching profile, adapter, and connection, validates the
  returned portable identity, and preserves conflicts without overwriting
  existing snapshots. Parrot Cloud implements the capability only for its own
  backend; an arbitrary adapter is covered by the conflict regression.
- Enabled Android host/JVM test targets for the library domain and data modules
  so their common tests can run without iOS targets. Added the Cloud Account
  domain as a test-only dependency where the existing common tests reference its
  API; production dependencies are unchanged.
- Expanded the v25 database migration fixture with legacy book/import/file,
  reading-position, bookmark, Cloud transfer, outbox, and sync-checkpoint rows.
  Added a v38 fixture for group and source aliases, which are introduced by
  later migrations. No production schema or migration was changed.

### Verification

- Android host tests passed:

  ```sh
  ./gradlew :feature:library:data:testAndroidHostTest \
      :feature:library:domain:testAndroidHostTest
  ```

  Results: 17 data tests and 34 domain tests passed; none failed or were skipped.
- The focused populated migration tests passed on Android host/JVM:

  ```sh
  ./gradlew :lib:database:implementation:testAndroidHostTest \
      --tests 'com.retro99.database.implementation.DatabaseMigrationPreservationTest' \
      --rerun-tasks
  ```

  Result: 5 tests passed. Historical v25 schemas do not yet contain the Cloud
  origin columns added by migration 25, so the fixture verifies the migration
  defaults. A separate v38 baseline covers aliases added later.
- The Android app build passed with
  `./gradlew :androidApp:assembleDebug --no-daemon`. The APK installed with
  `adb install -r` on emulator `emulator-5556` and Xiaomi `7TEULNB6JJTK75Y5`;
  both retained their app data, launched, and showed the existing acceptance
  fixtures in the library list.
- Both devices' Sync & Backup screens showed a Cloud refresh-unavailable state.
  The Android auth model maps that banner to `CloudAuthState.RefreshUnavailable`
  when session refresh fails while a stored account ID exists. The Google sign-in
  Xiaomi browser remained on “Checking your network connection”; no account was selected
  and no credentials were entered. No Cloud data was changed.
- No iOS builds, tests, or runtime checks were run in this round.

### Still open

- Live Cloud upload/download/deletion, same-account two-device convergence, and
  linked-profile grouping synchronization require successful Cloud
  reauthentication and service access. The current Android sign-in flow did not
  get past its browser network check.
- The 49-assertion pgTAP suite remains unverified: the local Postgres health
  endpoint timed out in the earlier attempt, and the existing Docker/storage
  audit processes were left untouched.
- A populated legacy migration now has host/JVM fixture coverage, but neither
  connected device has a populated pre-unified-library database for an on-device
  upgrade check. The Xiaomi's separate v26 database has no checked book or
  progress rows.
- The connected/disconnected streaming, multi-file, and legacy-reader regression
  matrix still needs its remaining local/connected-server scenarios.
- Audiobookshelf grouping decisions still require a trustworthy portable
  server-instance identity before they can synchronize safely across devices.
- iOS app/runtime acceptance is intentionally out of scope for now.

## T21 — Verify hosted Cloud configuration on Android

Date: 2026-09-25.
Status: hosted-account connection is present on the emulator and Xiaomi. The
hosted grouping RPC is missing, so Cloud mutation and convergence acceptance is
blocked. iOS testing is deferred per the user's direction.

### Changes

- Removed the debug-only localhost Supabase overrides from
  `androidApp/src/debug/AndroidManifest.xml`; the debug variant inherits the
  hosted Supabase configuration from the main manifest for continued acceptance.
  No release APK was built.
- Fixed the Cloud sync status timestamp resource to use the indexed `%1$s`
  placeholder. Compose Multiplatform had rendered the original `%s` literally;
  the existing call sites already supplied the timestamp.

### Verification

- `./gradlew :androidApp:assembleDebug --no-daemon` passed with the hosted debug
  configuration. `adb install -r` succeeded on Xiaomi
  `7TEULNB6JJTK75Y5` and emulator `emulator-5556`; both launched the updated APK
  with existing app data preserved.
- On Xiaomi, Settings → Sync & Backup showed a connected Cloud account and sync
  enabled; the account was already linked, so no new Google sign-in was needed.
  After one explicit “Sync now” attempt the UI reported 0 sent, 0
  received, 5 pending, and displayed a timestamp instead of the literal `%s`.
  The sync toggle was not changed.
- On the emulator, Settings → Sync & Backup reproduced the hosted API error:
  `PGRST202` for the missing
  `public.push_library_group_decisions(client_cursor, mutations)` function.
  The locally present migration is
  `supabase/migrations/20260924000009_parrot_cloud_library_group_decisions.sql`;
  it has not been deployed to the hosted project. Read-only
  `supabase migration list --linked` showed the hosted project through
  `20260924000006`; six migrations from `20260924000007` through
  `20260925000002` are unapplied remotely. They update Cloud upload, orphan-GC,
  and deletion RPCs, add grouping sync, and add deletion/reimport tombstone
  handling. No migration was deployed.
- The generated EPUB `/tmp/unified-library-cloud-acceptance-20260925-084706.epub`
  was imported locally on the emulator. Its explicit Cloud upload could not queue
  because Cloud book metadata sync is blocked by the missing RPC. The existing
  Cloud demo rows `Test 123` and `Fundamental Accessibility Tests: Basic
  Functionality` have no downloadable file resources. No successful upload or
  deletion was observed.
- The expanded Android host-test run reported 436 tests across 91 suites, with
  zero failures, errors, or skips. No iOS build or test was run.

### Still open

- Review and deploy the six pending migrations to the hosted project, then verify
  the grouping RPC and repeat Cloud upload/download/deletion, linked-profile
  grouping sync, and same-account two-device convergence. The local 49-assertion
  pgTAP suite is still unverified because the earlier local Postgres connection
  timed out; no backend deployment was performed in this pass.
- Audiobookshelf grouping cannot synchronize safely across installations until a
  trustworthy portable server identity or verified binding contract is available.
  Cloud listing omissions still cannot prove deletion; explicit tombstones are
  handled.
- The connected/disconnected streaming, multi-file, and legacy-reader matrix is
  incomplete. The Xiaomi currently has no configured Storyteller or Audiobookshelf
  test server. A populated pre-unified-library database upgrade also has host/JVM
  fixture coverage but no device acceptance.
- iOS app/runtime acceptance remains out of scope for now.

## T22 — Review hosted backend gate and current-reading route

Date: 2026-09-25.
Status: debug-to-hosted configuration is verified; live Cloud acceptance is
blocked by unapplied hosted migrations. No code changes or backend deployment.

### Findings and verification

- The hosted Supabase URL is supplied by the main Android manifest;
  `androidApp/src/debug/AndroidManifest.xml` has no localhost override. The
  emulator's Sync & Backup screen reached the hosted API and reproduced the
  missing `push_library_group_decisions(client_cursor, mutations)` RPC. A
  release APK is unnecessary for hosted-backend testing and was not built.
- Read-only `supabase migration list --linked` showed the hosted project at
  `20260924000006`, with six local migrations pending through `20260925000002`.
  Migration `20260924000009` adds the missing grouping RPC. The other migrations
  change upload/orphan cleanup, file invalidation, whole-book deletion, stale
  upsert handling, and explicit reimport/progress tombstones. Review also found
  that `20260924000007` drops `cloud_book_uploads.tus_upload_id`. No migration was
  deployed because this changes the linked hosted schema and Cloud behavior.
- The 49-assertion pgTAP suite remains unverified. An isolated Supabase project
  with unique ports is feasible, but the Docker API stalled during read-only
  checks. The existing `purge-audit-local.sh` process and Supabase stack were left
  untouched.
- On the emulator, Continue Reading for `Unified Library Acceptance A` showed
  `Ebook file not found: local`. A read-only database query showed the imported
  row's full app-private EPUB path and the file exists. The current-reading route
  passes server ID and book UUID without a reader selection; the local repository
  normally resolves that row to its stored path. The literal `local` failure is
  therefore unresolved and was not treated as a confirmed source-code defect.
- The currently-reading preference stores server ID, book UUID, media type, title,
  cover, and progress, but not the selected resource/replica. Continue Reading
  therefore cannot restore an explicit cached-replica selection. This is a
  separate coverage/design gap; it does not explain the emulator's local-row
  failure. No code was changed from this inconclusive observation.
- The populated v25-to-v42 migration has host/JVM fixture coverage, but neither
  connected device is disposable and both already run schema v42. The data volume
  has about 13 GiB free at 97% usage, so no additional worktree or AVD was created
  for this round.

### Still open

- Deploy and validate the six migrations against the hosted project, then repeat
  Cloud upload/download/deletion and same-account two-device grouping convergence.
  Deployment needs explicit approval because it changes the remote database and
  Cloud sync behavior.
- Run the isolated pgTAP suite when Docker responds, and resolve the unexplained
  Continue Reading error with a focused device/database trace.
- Complete populated pre-unified device-upgrade and connected/disconnected
  streaming, multi-file, and legacy-reader acceptance. The Xiaomi has no configured
  Storyteller or Audiobookshelf server.
- Audiobookshelf cross-device grouping remains deferred pending a trustworthy
  portable server identity or a verified binding contract. iOS app/runtime checks
  remain out of scope per the user's direction.

## T23 — Verify Continue Reading parameter fix on Android

Date: 2026-09-25.
Status: the reproduced Continue Reading launch failure is fixed and passes on the
emulator and Xiaomi. The hosted Cloud migration gate remains open.

### Findings and verification

- `ReaderScreen` now packages launch values into a single `ReaderViewModelArgs`
  instance, and `ReaderViewModel` receives that typed argument. This avoids Koin's
  positional fallback matching nullable String arguments to `serverId` when a slot
  is null. See
  `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/reader/ReaderScreen.kt`
  and `ReaderViewModel.kt`.
- After force-stopping and relaunching the emulator app without clearing data,
  Continue Reading opened `Unified Library Acceptance A` and rendered “Chapter One.”
  The app had first recovered to Books from the splash screen; no startup crash was
  observed.
- The same `androidApp-debug.apk` was installed on Xiaomi with `adb install -r`,
  preserving its data. Continue Reading opened `Acceptance A` and rendered the
  fixture chapter text. Neither device log showed `Ebook file not found: local` or
  a reader crash after the fix.
- The existing debug APK was built by the preceding verification pass with
  `:androidApp:assembleDebug`; no release APK was built. Debug already reaches the
  hosted Supabase project, so release is not required for live-backend testing.

### Still open

- The six unapplied hosted Supabase migrations and the 49-assertion pgTAP suite.
- Cloud upload/download/deletion and same-account two-device grouping convergence.
- Populated pre-unified-library device upgrade and connected/disconnected streaming,
  multi-file, and legacy-reader acceptance.
- Audiobookshelf cross-device grouping pending a trustworthy portable server identity.
- iOS app/runtime testing remains out of scope per the user's direction.

## T24 — Verify linked backend and Cloud transfer on Samsung

Date: 2026-09-25.
Status: all current SQL acceptance suites pass on linked Supabase. Samsung
confirms separate A/B groups and completes a Cloud backup, local-copy removal,
Cloud restore, and Reader open for fixture B. Fresh emulator convergence remains
unverified because Android repeatedly reported an ANR.

### Findings and verification

- `supabase migration list --linked` showed all 17 local migrations applied
  remotely through `20260925000002`.
- The transaction-wrapped tests passed through `supabase db query --linked
  --file`: `library_group_decisions_test.sql` (49 assertions),
  `library_book_removal_test.sql` (55), `abuse_operations_test.sql` (29),
  `account_deletion_test.sql` (8), and `storage_policy_test.sql` (10). The final
  assertion in each suite passed, for 151 assertions total. This did not use
  Docker or the previously stalled `supabase test db --linked` runner.
- Samsung `SM-S921B` (`RFCWC0SSVDM`) runs app version 0.4.5 (21) and is linked to
  `rok.retar@gmail.com` with Sync enabled. A and B appear as separate library
  groups, each with Local and Parrot Cloud entries. A's Cloud EPUB resource is
  unavailable while the Local EPUB remains available. No new A Cloud upload was
  queued; this preserves the existing removal/unavailable-file scenario.
- For B, Group Details showed its Cloud EPUB available remotely and the local
  source available. The synthetic test EPUB was backed up after confirming the
  rights attestation. The Cloud storage meter changed from 0 B to 1.4 KiB and the
  transfer record showed `Backup complete`.
- Removing B's device copy left the library group and Cloud source in place. The
  Local source then showed unavailable. Downloading the remote EPUB restored the
  Local source; the transfer record showed `Restore complete`. Opening it rendered
  “Chapter One” and the fixture text in Reader.
- After backup, Settings → Sync & Backup reported 0 sent, 2 received, and 0
  pending at `2026-09-25T12:08:37Z`; Cloud storage showed 1.4 KiB of 5.0 GiB used.
- Merged A and B in the Books list, then ran Sync & Backup. Sync reported 1 sent,
  1 received, and 0 pending at `2026-09-25T12:31:27Z`. A read-only linked-project
  query confirmed a `merge` decision at `12:31:26Z` with four portable members.
- Split B back out of the merged group and synced again. Sync reported 1 sent, 1
  received, and 0 pending at `2026-09-25T12:35:19Z`. The linked database recorded a
  `split` decision at `12:35:18Z` with two selected and two retained members. The
  Books search again showed A and B as separate groups.
- The Samsung also listed Storyteller catalog entries, but Group Details for
  `A Psalm for the Wild-Built` showed only `Last known source details` even after a
  library refresh. Its logged-in profile can reach the server, but current file
  availability could not be established. No Storyteller file was downloaded, and
  Server Management showed no Audiobookshelf profile.
- Confirmed `Delete remote copy` for B. Group Details then showed the Parrot Cloud
  source as `Unavailable` while the Local source remained `Source available` and
  `On this device`. Opening B from Details still launched Reader and displayed
  `Chapter One`, confirming remote deletion retained the device copy and it remains
  readable.
- The emulator showed both fixture books before Sync & Backup navigation. Android
  then repeatedly displayed ANR dialogs for `system` and `com.retro99.parrot`.
  Rebooting the emulator and launching the app reproduced the ANR. No app data was
  cleared and no APK was reinstalled. The older Xiaomi was not used.
- A read-only emulator audit attributed the startup ANR to OkHttp platform
  initialization in OkHttp's AndroidX Startup provider, before
  `ParrotApplication.onCreate`; DropBox also reported failed startup and the emulator
  was under high CPU pressure. The app separately creates a Coil client eagerly
  after Application startup, but deferring it would not fix this captured stall.
  Moving OkHttp initialization into Application startup still performs the same
  synchronous work, and background initialization risks request/context races. With
  only one trace under heavy emulator pressure, no source workaround is justified.
  No source fix, APK install, or data reset was performed on the emulator.

### Remaining acceptance

- Fresh emulator pull/convergence after the latest Cloud sync remains unverified
  because of the emulator ANR. Cloud deletion observed on a second device also
  remains open; the Xiaomi was left untouched in this pass.
- Connected Storyteller/Audiobookshelf download/open, streaming, multi-file, and
  disconnected-source scenarios remain incomplete. Samsung's Storyteller entry
  showed only last-known source details. Audiobookshelf still lacks a trustworthy
  portable installation identity for cross-device grouping.
- A populated pre-unified-library device upgrade is untested and is not a required
  compatibility gate under the user's direction. iOS runtime testing remains out
  of scope.

## T25 — Verify Cloud split convergence and connected server reachability

Date: 2026-09-25.
Status: the Xiaomi split decision converged to Samsung and is recorded at hosted
revision 9. StoryTeller web login succeeds in Brave, but the supplied web host does
not expose the `/api/v2/info` endpoint required by the Android StoryTeller adapter.
Connected-source transfer and cross-device Cloud deletion acceptance remain open.

### Findings and verification

- On Xiaomi, confirmed `Separate` for the selected Cloud B source. Sync completed
  with 1 sent, 1 received, and 0 pending at `2026-09-25T14:04:34.142383Z`.
- A read-only linked Supabase query showed revision 9 as a `split`: Cloud B is the
  only selected member in its new group, and Cloud A is the retained member in its
  prior group. No file or progress mutation was part of this decision.
- Samsung Sync & Backup received 1 decision and reported 0 sent, 1 received, and
  0 pending at `2026-09-25T14:07:20.711801Z`. Its local decision log marks revision
  9 as applied. The current membership rows place Cloud A and Cloud B in separate
  groups; a Books search displays both Cloud entries separately alongside their
  existing Local entries.
- The supplied account signs in successfully to `https://books.retar.si/login`
  in Brave and loads the StoryTeller catalog. A read-only visit to
  `https://books.retar.si/api/v2/info` returns the website's 404 page, rather than
  the API response expected by the Android adapter. The separate `server.retar.si`
  host opens Proxmox, so no credentials were sent there. No Android server profile
  was added with an unverified base URL.
- System DNS maps `books.retar.si` to a private LAN address whose HTTP and HTTPS
  ports refused direct Mac requests, even though Brave can load the web catalog.
  This means shell reachability is not a reliable substitute for the browser
  session, but it does not establish a usable Android API endpoint.
- Docker Desktop lists the local `audiobookshelf` container as running on
  `localhost:13378`, but its HTTP endpoint did not return headers or a body within
  five seconds. The Docker CLI also stalled on `docker ps`; the Docker Desktop logs
  panel showed no log output. No Audiobookshelf login or transfer test was run.
- Xiaomi's display came back on to an active system call, so it was left untouched
  after the sync. The second-device remote-deletion retention check remains open.

### Remaining acceptance

- Provide the Android StoryTeller API base URL that serves `/api/v2/info`, or expose
  that API route on the supplied web host. Then verify source refresh, download/open,
  available media types, and disconnected-source behavior on Android.
- Restore a responsive Audiobookshelf endpoint before testing file discovery,
  download/open, streaming, multiple files, and disconnected behavior. Cross-device
  grouping still requires a trustworthy portable Audiobookshelf installation ID.
- Complete the Cloud deletion-on-one-device/local-readable-on-another scenario once
  Xiaomi is available and has a local copy of fixture B.
- Restore the Xiaomi animation scales to `1.0` after device work. The emulator's
  startup ANR and iOS runtime testing remain out of scope for this pass.

## T26 — Verify Xiaomi retains the local EPUB after Cloud file deletion

Date: 2026-09-25.
Status: Cloud file removal converged to Xiaomi, and its Local EPUB remains readable.
The Xiaomi animation scales were restored to `1.0`.

### Findings and verification

- Xiaomi `2602BPC18G` (`7TEULNB6JJTK75Y5`) runs app version 0.4.5 (21), is signed
  into the test Cloud account, and completed manual sync at
  `2026-09-25T15:04:39.654381Z` with 0 sent, 0 received, and 1 pending.
- Linked Supabase read-only queries found the Cloud book metadata for fixture B,
  no remaining `cloud_book_files` row, and `sync_changes` event 60 for its `book_file`
  with operation `delete` and status `removed`. Xiaomi's local Parrot Cloud cursor
  was 68, past that event.
- A read-only Xiaomi database snapshot showed the Cloud source metadata as `Present`
  with no resources, and the Local source resource as `DevicePresent`. The local
  Cloud file-state table had no remaining row for B. The source-presence badge is
  separate from per-resource availability: Details renders the source label and
  then lists only its current resources. This is why the Cloud source still appears
  after its EPUB is deleted.
- Group Details offered B's local EPUB as `On this device`. I selected the Local
  source for this check, opened it, and verified the Reader displayed `Chapter One`
  and the fixture text. This closes T25's cross-device deletion and local-read check.
- Sync's pending-mutation count is separate from the Cloud `book_file` feed. The
  local outbox contained conflict-preserved `library_book` and `reading_position`
  entries; neither represents the deleted Cloud EPUB.
- Restored all three Xiaomi animation scales (`window_animation_scale`,
  `transition_animation_scale`, and `animator_duration_scale`) to `1.0` and verified
  the values.

### Remaining acceptance

- No reachable StoryTeller Android API base URL has been verified. The adapter
  validates `{baseUrl}/api/v2/info`; the supplied web login working in Brave does
  not establish that API route. Do not enter the demo credentials until the actual
  API origin is reachable from Android.
- The local Audiobookshelf endpoint at `localhost:13378` has not returned an HTTP
  response. File discovery, download/open, streaming, multi-file, and disconnected
  source behavior remain untested; portable Audiobookshelf identity also remains
  unresolved.
- Emulator startup repeatedly produced an ANR during T24. A fresh emulator
  convergence pass remains unverified. iOS runtime testing remains out of scope.
- `:lib:cloud:implementation:testAndroidHostTest` still has 10 failures out of 35
  because Supabase Auth's default PKCE cache requests Android settings context on
  the host JVM. `CloudSessionStateTest` and the Parrot Cloud host suite pass; the
  focused session-state test remains the scoped host verification.

## T27 — Verify clean emulator convergence with the current APK

Date: 2026-09-25.
Status: a clean emulator install receives the current Cloud state and no longer
shows the deleted Cloud EPUB. Xiaomi retains and opens its separate Local copy.

### Findings and verification

- The emulator originally ran version 0.4.5 (21) installed at 13:14 local, before
  Cloud file deletion event 60 at 14:23 local. Its old sync had advanced the local
  cursor past that event, leaving a stale `Available remotely` resource snapshot.
- Installed the current `androidApp-debug.apk` with `adb install -r`, then cleared
  only emulator app data because its cursor had already passed the deletion event.
  Xiaomi, Samsung, and hosted Supabase data were left intact.
- Re-linked the test Cloud account on the emulator and enabled Sync. The clean
  initial sync completed at `2026-09-25T15:23:30.636993Z` with 0 sent, 27 received,
  and 0 pending.
- The Books list shows fixture A and B as separate groups. B's Group Details retains
  its Parrot Cloud source metadata but shows no EPUB resource or Download action,
  matching the deleted remote file. No local B copy was created on the emulator.
- The Xiaomi test remains the paired retention check: its Local B EPUB is
  `DevicePresent`, and opening it rendered `Chapter One` and the fixture text.
- The startup ANR did not recur after clearing the old emulator data and launching
  the current APK. iOS runtime testing remains out of scope.

### Remaining acceptance

- StoryTeller's supplied web login works in Brave, but no Android API origin serving
  `/api/v2/info` is currently reachable. Continue after the demo server/API host is
  restored or its actual Android-reachable base URL is provided.
- Audiobookshelf's configured Mac port `13378` accepts a TCP connection from
  Xiaomi at `192.168.1.67:13378`, but HTTP requests to `/` and `/status` time out.
  Docker state remains unconfirmed because a bounded `docker ps` also timed out.
  Reader, streaming, multiple-file, and disconnected-source tests remain open.
- The full Cloud host test task still fails 10 of 35 tests due to Supabase Auth's
  default PKCE cache needing Android settings context. Keep persistent PKCE storage
  in production; make the host test configuration injectable or run that coverage
  on Android before considering the validation gate complete.

## T28 — Close Cloud host tests and recheck connected-server reachability

Date: 2026-09-25.
Status: Cloud host tests now pass. The Xiaomi confirms the supplied Storyteller
web host does not expose the Android adapter's required API route; Audiobookshelf
still accepts TCP but returns no HTTP response.

### Findings and verification

- `:lib:cloud:implementation:testAndroidHostTest` passes all 35 tests, and
  `CloudSessionStateTest` passes 2 of 2. Provider tests inject
  `MemoryCodeVerifierCache` and disable Android lifecycle callbacks for host JVM
  tests. Both overrides default to null, preserving Supabase's persistent PKCE
  cache and lifecycle defaults in production. `git diff --check` passes.
- On Xiaomi (`2602BPC18G`), `books.retar.si` resolves to `192.168.1.104` and
  responds to the device's network probe. Opening
  `https://books.retar.si/api/v2/info` in Samsung Internet renders the Storyteller
  site's 404 page, “This page could not be found.” No demo credentials were sent to
  this endpoint. The Android adapter requires this exact route relative to its
  configured base URL, so the web-login URL is not a verified Android API origin.
- Docker Desktop shows the local `audiobookshelf` container as Running and publishes
  port `13378:80`. The Mac listens on port 13378 and the Xiaomi can establish TCP,
  but a direct HTTP `/status` request from the phone returned no bytes. Mac HTTP
  requests also timed out. Docker CLI status/log requests and a direct Engine API
  `/_ping` timed out, so the container's internal health could not be inspected.
  No container or volume was restarted or reset.
- The official Audiobookshelf API and source audit found no portable installation
  ID: login exposes a user ID and static `server-settings` ID, while status and
  health routes expose no instance identity. `AudiobookshelfBooksRepository`
  therefore continues returning no portable account identity. Safe cross-device
  grouping needs an upstream identity or an explicit verified binding flow.

### Remaining acceptance

- Obtain the Android Storyteller API base URL that serves `/api/v2/info`. Then
  verify connected refresh, download/open, streaming, and disconnected-source
  behavior on Android.
- Restore a responsive Audiobookshelf HTTP service before testing discovery,
  download/open, streaming, multiple files, and disconnected behavior. These local
  Android flows now pass; define and implement an explicit cross-device binding
  contract before claiming portable Audiobookshelf grouping.
- iOS runtime testing and populated pre-unified-library upgrade testing remain out
  of scope per the user's direction.

## T30 — Correct Storyteller API validation and record current blockers

Date: 2026-09-25.
Status: the false `/api/v2/info` validation failure is fixed in the Android adapter.
The remaining Storyteller acceptance is authentication and media behavior after a
valid session is saved under the existing server connection.

### Findings and verification

- The Storyteller web app does not define `/api/v2/info`. Its unmatched Next.js route
  returns an HTML 404, which is unrelated to server health. The user's route probes
  found `/api/health` returning 200 and `/api/v2/books` returning 401 before login.
- `StorytellerAuthenticator.validateServer()` now tries `/api/health` first and
  falls back to `/api/v2/books`. A successful health response, successful books
  response, or 401/405 from the protected books route confirms the server; two 404s
  reject it. `StorytellerAuthenticatorTest` covers these responses.
- `./gradlew --rerun-tasks :lib:server-storyteller:testAndroidHostTest` passes.
  The combined forced run of Storyteller, library data, settings UI host tests, and
  `:androidApp:assembleDebug` also completes successfully.
- The earlier Samsung `/api/v2/books` 401 is separate from server validation. The
  safe reauthentication path must save new credentials under the existing server ID
  so cached library snapshots remain attached to that connection.
- An earlier version of the local TUS spike note described `expires_in` as a
  reliable millisecond duration; that note is now corrected. Upstream token creation returns it from
  `session.expires.valueOf() * 1000 - Date.now()`. `expires` is a JavaScript `Date`,
  so that value is not a valid remaining duration. The client now leaves saved
  local expiry unset. Storyteller validates its rolling database session server-side.
  The upstream route and helper are visible at the pinned source revision linked
  above; a live login and catalog request are still needed to confirm the supplied
  server's current behavior.
- This Mac's current request to `192.168.1.115:8001` fails to connect. Samsung can
  ping `192.168.1.115`; HTTP reachability and login from the current device session
  were not confirmed in this pass.

### Remaining acceptance

- Complete and test in-place Storyteller reauthentication without replacing the
  server ID, then verify a live listing refresh on Samsung.
- Verify Storyteller download, local open, and offline access separately. The
  current Android reader path uses local files; do not report remote streaming as
  supported without a separate streaming implementation and test.
- Complete Audiobookshelf cross-device pairing on clean test state or design and
  implement a transactional migration for Xiaomi's existing portable memberships.
- iOS runtime testing remains out of scope per the user's direction.

## T29 — Verify audiobook mini-player reconnection and Storyteller host

Date: 2026-09-25.
Status: Samsung's cached Audiobookshelf playback and mini-player route pass after a
shutdown-race fix. The newly supplied Storyteller host is reachable from Samsung,
but its expected Android API route still returns the web app's 404 page.

### Findings and verification

- Installed the rebuilt debug APK on Samsung `SM-S921B` (`RFCWC0SSVDM`). The
  Audiobookshelf Chapter 1 fixture loaded tracks `01` and `02`; playback advanced
  from track 1 to track 2. Returning to Details exposed the mini-player. Reopening
  from that selection-less mini-player reconnected to the existing two-track player
  and showed no `Audio files not found` error.
- The first Back attempt crashed on `ClosedReceiveChannelException` from
  `RoutineSyncScheduler.close()`: closing `dirtySignals` resumed the worker's
  suspended `receive()` before cancellation took effect. Shutdown now cancels the
  worker before cancelling the channel. Added
  `closingWhileWaitingForAnotherDirtySignalCancelsWorkerSafely` to
  `RoutineSyncSchedulerTest`.
- `:feature:sync:domain:testAndroidHostTest`,
  `:feature:reader:ui:testAndroidHostTest`, and `:androidApp:assembleDebug` pass.
  `git diff --check` passes. The rebuilt APK was installed on Samsung, and repeated
  Back navigation returned to Details without an app crash.
- The local `audiobookshelf` container is Running; `http://127.0.0.1:13378/status`
  returns HTTP 200 and reports server version 2.35.1. In the preceding authenticated
  Samsung checks, listing returned six valid books (one missing duplicate was
  filtered), both audio range requests returned HTTP 206, and the Sherlock Holmes
  EPUB downloaded and opened in Readium.
- The user supplied `http://192.168.1.115:8001`. Samsung ping and TCP checks succeed,
  but `GET /api/v2/info` returns HTTP 404 with a Next.js page. The Mac cannot ping or
  connect to that host. Xiaomi was temporarily off during this check. No credentials
  were sent.

### Remaining acceptance

- Expose the Storyteller Android API route at the supplied host or provide the
  Android API base URL that serves `/api/v2/info`. Then verify connected refresh,
  download/open, streaming, and disconnected-source behavior on Android.
- Decide the Audiobookshelf binding scope, pairing/revocation behavior, and how to
  reconcile a portable group decision already pulled before binding. The current
  promotion DAO rejects an existing portable membership, and choosing a merge policy
  without that contract could discard a device's grouping decision.
- iOS runtime testing and populated pre-unified-library upgrade testing remain out
  of scope per the user's direction.
