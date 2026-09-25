# Unified library — execution guide for an implementing AI

Status: use `docs/unified-library-implementation-notes.md` for current task
completion and evidence. This guide is the acceptance checklist; its original
"all tasks pending" status is obsolete.

Read `docs/unified-library-replicas-implementation-plan.md` first. That document
defines the agreed product behavior. This guide breaks implementation into small
tasks and supplies defaults so you do not need to invent the architecture.

## 1. How to execute this guide

1. Read repository instructions and inspect the working tree before editing.
2. Complete tasks T00–T12 in dependency order. Keep each task buildable. Do not
   attempt a repository-wide rename or replace all models in one pass. Make
   coherent changes, run the relevant checks, and record evidence as work
   progresses. Do not commit, push, merge, or rebase without explicit permission.
3. Extend existing infrastructure where suitable. Proposed names below are new
   working names; adapt naming to the codebase, keeping the specified boundaries.
4. For every completed task record changed files, verified behavior, checks run,
   and remaining gaps in the handoff format in section 8.
5. Tests must exercise observable invariants and failure cases, not merely assert
   that a new data class has fields. Reuse existing fixtures and test conventions.
6. A fake/test adapter proves extensibility; it does not prove a real backend
   upload or a two-device acceptance flow works.
7. If a necessary backend field or identity contract is missing, record the exact
   missing contract and implement its adapter/backend support in the appropriate
   task. Do not silently use a title, local path, or guessed identifier instead.
8. Continue independent tasks if a device/backend verification is unavailable.
   Report that verification as blocked, not passed. Ask the user only for a new
   product decision, credentials/environment access, or an incompatible contract
   that cannot be resolved from the repository and this plan.

## 2. Non-negotiable implementation rules

### Identity rules

- `groupId` identifies a displayed library book. Generate once and persist.
- Existing Cloud `libraryBookId` is currently hash-bound. Preserve its meaning.
- A source key identifies one server-native book in a profile and account scope.
- An asset identifies one rendition/version; a replica identifies one stored copy.
- Server-native IDs are not globally unique. Always include source/account scope.
- `groupId`, source ID, asset ID, replica ID, and transfer ID are not interchangeable.
  Prefer distinct Kotlin types at new API boundaries, following project patterns.
- Unknown hashes/algorithms do not establish equality. Do not group by title,
  author, filename, or ISBN alone.
- Group IDs must not change when repository order, cover selection, file presence,
  authentication, or transfer state changes.

### Layering rules

```text
UI → library use cases → library repository / pure grouping policy
                               ↓
                   local persistence + shared source contracts

server adapters → source repositories → persisted source snapshots
sync transport → sync machinery → domain change application → persistence
action execution → operation adapter / transfer engine → persistence
```

- Grouping must work without Cloud, network, or a running sync engine.
- Core grouping and action resolution must not switch on `ServerType`.
- Protocol translation belongs in adapters. Backend-specific policy belongs in
  adapter capabilities or progress contracts, not `if (ParrotCloud)` in Details.
- Reuse sync delivery, outbox, retry, and checkpoint infrastructure. Do not create
  a second implementation of those facilities for group synchronization.
- Avoid module dependency cycles: contracts go in an existing lower-level API
  module when consumed by both source adapters and feature/domain implementations.
- Do not turn a representative `BookDomainModel` into the authoritative source of
  group actions. It may provide display metadata only during migration.

### Storage and progress rules

- A location is available only when that concrete copy is usable or confirmed by
  its backend. Metadata existence is not remote-file availability.
- A source fetch error is not a deletion event.
- Ordinary remote deletion does not delete completed device copies.
- Device removal works for imports and downloads and requires no transfer-history row.
- Never delete a file once per source entry: several entries may share one replica.
- Manual grouping never rewrites file content IDs or combines incompatible positions.
- Exact Local/Cloud copies may share their existing compatible progress identity;
  other integrations retain adapter-owned progress semantics.
- Device paths and this-device presence are not portable synchronized state.

## 3. Starting file map

Paths below are relative to the repository root. `<package>` abbreviations in
later tasks refer to these exact directories, not literal filesystem paths.

| Alias | Directory |
| --- | --- |
| books-domain | `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/` |
| books-data | `feature/books/data/src/commonMain/kotlin/com/retro99/books/data/` |
| books-ui | `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/` |
| reader-domain | `feature/reader/domain/src/commonMain/kotlin/com/retro99/reader/domain/` |
| home-ui | `feature/home/ui/src/commonMain/kotlin/com/retro99/home/ui/` |
| server-api | `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/` |
| cloud-adapter | `lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/` |
| local-adapter | `lib/server-local/src/commonMain/kotlin/com/retro99/server/local/` |
| sync-data | `feature/sync/data/src/commonMain/kotlin/com/retro99/sync/data/` |
| database-api | `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/` |
| database-impl | `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/` |
| sql | `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/` |

Read these existing files before designing changes:

- books-domain: `model/ServerBookExt.kt`, `model/BookDomainModel.kt`,
  `usecase/GetBooksUseCase.kt`, `usecase/GetBookByUuidUseCase.kt`,
  `BookFileTransferTransport.kt`.
- reader-domain: `usecase/ObserveAllBooksWithProgressUseCase.kt`.
- server-api: `ServerBooksRepository.kt`, `AuthenticatedRepositoryProvider.kt`,
  `ServerCapabilities.kt`; inspect its existing media/resource contracts too.
- local-adapter: `LocalBooksRepository.kt`.
- cloud-adapter: `ParrotCloudModels.kt`, `ParrotCloudBooksRepository.kt`,
  `ParrotCloudBookFileChangeApplier.kt`, `ParrotCloudSyncAdapter.kt`,
  `ParrotCloudBookFileTransferTransport.kt`.
- books-data: `transfer/BookFileTransferEngine.kt`, `transfer/DownloadFinalizer.kt`.
- sync-data: `LibraryBookSyncApplier.kt`, `LibraryMutationSyncEngine.kt`,
  `ProgressSyncEngine.kt`.
- sql: `LibraryBook.sq`, `LocalBookFile.sq`, `ImportedBook.sq`, `Position.sq`,
  `CloudBookFileState.sq`, `CloudFileTransfer.sq`, `SyncOutbox.sq`.
- books-ui: `detail/BookDetailViewModel.kt`, `detail/BookDetailViewState.kt`,
  `detail/BookDetailIntent.kt`, `detail/BookDetailScreen.kt`,
  `list/BooksListViewModel.kt`.
- home-ui: `navigation/HomeDestination.kt`, `navigation/HomeNavigation.kt`.
- Backend: inspect relevant existing migrations and tests in `supabase/` before
  specifying feed, grouping, or deletion protocol extensions.

## 4. Contract and persistence blueprint

These are required concepts, not instructions to duplicate existing equivalent
types or to put every field in one table.

### Domain projections

`LibraryBookGroup` exposes:

- `groupId`, selected display metadata, and source memberships.
- Media choices, each resolving to a specific asset and progress owner.
- Device/remote replicas with independent availability and operation state.
- All relevant transfers, identified individually.
- Preferred metadata and media source references, when selected by the user.

`SourceBookRef` contains:

- Profile scope and portable source/account identity.
- Local connection reference for executing operations on this installation.
- Native book ID; existing content/Cloud identifiers as typed aliases.
- Metadata and identity evidence with provenance.
- Snapshot freshness and authoritative presence/removal information.

`MediaAsset` / `StorageReplica` must represent:

- Source-native media identity, rendition type, file format, and version/revision.
- Zero or more file resources for multi-file audiobooks/readaloud books.
- Optional verified fingerprint, including algorithm and fingerprint scope.
- A device storage reference OR a remote resource reference, not a path pretending
  to be a remote identifier.
- File availability separate from upload/download/delete activity.

Do not convert an unknown server's progress into a generic percentage for writes.
Carry a progress-owner/reference that its registered adapter can resolve.

### Persisted concepts

Implement additive tables/records for:

1. Groups: ID, profile scope, selected metadata/media references, lifecycle fields.
2. Membership: unique source-book identity → current group, provenance and revision.
3. Aliases/redirects: retired group ID → surviving group ID, scoped to profile.
4. Manual separations: explicit constraints between selected source memberships.
5. Identity evidence/associations: verified asset fingerprint or transfer link,
   including evidence provenance and source scope.
6. Replica ownership/associations where current file tables cannot express them.
7. Grouping operations and applied revisions, integrated with the existing outbox.

Required constraints:

- One current group per source-book membership within the profile.
- One physical device replica per actual owned file; multiple associations allowed.
- No alias loops and no self-redirects.
- Membership/merge/split transactions cannot leave orphaned active memberships.
- Local-file removal must not cascade-delete group identity or reading history.
- Account/profile boundaries apply to every identity lookup and operation.

Keep legacy `library_books` and hash-bound Cloud relationships during initial
migration. Do not rename their IDs to `groupId` or relax download integrity checks
just to make the new grouping work.

## 5. Ordered implementation tasks

### T00 — Establish the actual bug and extension boundaries

Dependencies: none.

Steps:

1. Trace the list path used by the reproduced duplicate row. Inspect both known
   aggregation consumers and any alternate server-filtered/search paths.
2. Trace one import's UUID, hash algorithm/hash, legacy library ID, upload transfer,
   returned Cloud book/file IDs, and both resulting `ServerBook` values.
3. Record the exact current aggregation key for each row. Do not call the mismatch
   confirmed unless a real trace or reproducing test demonstrates it.
4. Inspect `LibraryBookSyncApplier`: it can preserve an existing local library ID
   when applying a remote snapshot. Include that behavior in the trace.
5. Inventory adapter operations and existing dependency directions. Locate server
   connection identity, profile scoping, deletion cascades, and test/build tasks.
6. Record findings in `docs/unified-library-implementation-notes.md`, distinguishing
   observed facts, hypotheses, and blocked device/backend reproduction.

Done when: the failure is reproduced or the missing evidence is explicitly recorded,
and implementation targets are known. Do not make speculative legacy-data repairs
while the particular mismatch remains unconfirmed.

### T01 — Add shared identity and source contracts

Dependencies: T00 inventory.

Steps:

1. Add the identity/reference types and source normalization boundary described in
   section 4. Reuse existing fields rather than creating another source of truth.
2. Define optional identity evidence; distinguish file fingerprint, transfer
   association, and adapter-certified edition/asset evidence.
3. Define operation/progress adapter interfaces that use generic resource/source
   references. Keep Cloud wire fields behind the Cloud implementation.
4. Add normalization for current source repositories without changing list output yet.
5. Add a test adapter with an arbitrary source identity. It must register without
   adding an enum case to the library domain.

Tests: same native ID on two accounts remains distinct; missing evidence is valid;
unknown source type normalizes; file fingerprint and edition evidence stay distinct.

Done when: affected modules compile with old callers still working and contracts
have no library-domain dependency on concrete server implementations.

### T02 — Persist groups, memberships, and migration state

Dependencies: T01.

Steps:

1. Add database API, schema migrations, DAO implementations, and transaction entry
   points using the repository's existing database conventions.
2. Backfill a stable membership/group for each known source record. Reuse stored
   IDs on subsequent runs; never generate IDs in reactive list mapping.
3. Preserve unresolved source identities for later resolution on another device.
4. Add group aliases and manual separation persistence now, before automatic joins.
5. Audit cleanup SQL and import deletion for foreign-key/cascade or explicit cleanup
   that could remove progress or identity after device removal.
6. Record migration version and ensure restart/re-run recovery. Persist source
   snapshots or reuse existing caches so transient auth failures retain memberships.

Tests: upgrade populated databases; rerun backfill; two profiles; membership uniqueness;
group survives local-file removal; legacy Cloud IDs/positions/transfers are unchanged.

Done when: durable groups can be queried independently of network or sync.

### T03 — Implement deterministic identity resolution

Dependencies: T02.

Steps:

1. Implement a pure policy receiving persisted membership decisions, separations,
   scoped source identities, and trustworthy identity evidence.
2. First honor manual decisions. Next propose automatic joins from transfer links
   and compatible asset fingerprints. Keep metadata-only candidates separate.
3. Before each proposed join, check separation constraints against the complete
   prospective membership sets, not just the candidate pair.
4. Persist accepted joins transactionally; select a deterministic survivor from
   existing IDs and write redirects from retired IDs.
5. Record provenance. Never rewrite the original native/content identifiers.
6. Integrate reconciliation after source changes and backfill, not as a side effect
   of rendering a row. Reads may project groups but must not generate new IDs.

Specific tests:

- Local `legacy-X` and Cloud `legacy-Y` with the same verified compatible asset
  group together while keeping both legacy IDs.
- Same title but no evidence stays separate.
- Different fingerprint algorithms do not match.
- Input order and repeated emissions do not change IDs or membership.
- A↔B and B↔C evidence cannot bridge an explicit A↔C separation.
- Multiple media assets on one source remain intact after grouping.

Done when: all members are retained, joins are stable, and separations cannot be
undone by transitive automatic matching.

### T04 — Introduce shared list and Details queries

Dependencies: T03.

Steps:

1. Add an observable group repository and list/Details use cases. Prefer additive
   use cases during migration over changing every `BookDomainModel` consumer at once.
2. Replace `GetBooksUseCase` and `ObserveAllBooksWithProgressUseCase` projections
   with the shared grouping result, migrating their callers deliberately.
3. Audit search, server filters, continue reading, authors/series entry points,
   favorites, and collections. Match a group if any relevant member matches.
4. Keep stable metadata fallback and preserve source-specific metadata/progress.
5. Represent source failure/staleness separately; remove the error→empty-list
   assumption wherever it would falsely remove locations/groups.

Tests: one row retains two sources; filtering/search returns one group; unavailable
source stays known; representative metadata changes do not change action targets.

Done when: list and Details queries return the same group/membership model.

### T05 — Route by group and launch concrete media

Dependencies: T04.

Steps:

1. Add a new group-based Details destination in `HomeDestination.kt`. Keep the old
   serialized `(serverId, bookUuid)` destination as a compatibility entry point.
2. Resolve an old destination to membership then group, following group redirects.
3. Update list/search/series callers and `HomeNavigation.kt` to use the new route.
4. Update Details state to expose media choices, replicas, stale state, and transfer
   rows. Do not reduce this back to one source's `isLocal` flag.
5. Read/Listen resolves selected asset + usable replica + progress owner before
   creating the existing concrete reader/player request.
6. Prefer a usable device replica of the selected asset; do not switch editions
   merely because another edition is downloaded. Remember explicit media choice.

Tests: old route opens group; retired group route redirects; selecting audiobook
uses its native progress adapter; missing local path does not launch a reader.

Done when: users can reach all grouped sources from one Details screen.

### T06 — Resolve operations and observe all transfers generically

Dependencies: T01, T05.

Steps:

1. Implement a pure action resolver using selected asset/replica, adapter capability,
   connection/account state, and known availability. Return exact target references
   plus availability/reason; do not return only an unscoped action label.
2. Bridge existing native download/read/delete operations into this boundary.
3. Query upload destinations from registered operation adapters, filtered by
   current account and asset format. Preserve actual Cloud runtime upload rules.
4. Adapt Cloud-specific transfer request/result types behind the boundary. Retain
   existing persisted transfers and resume compatibility.
5. Observe every transfer associated with all group members. Deduplicate by
   transfer ID. Cancel/retry targets exactly that ID.
6. Keep remote availability and transfer state separate, especially replacement
   failures where an older remote file remains usable.

Tests: unknown fake adapter enables upload through registration; disabled capability
removes/disables it; two EPUB resources produce distinct targets; two simultaneous
transfers remain visible; local metadata selection does not hide Cloud progress.

Done when: core action resolution and Details contain no new server-type dispatch.

### T07 — Preserve group associations across uploads and downloads

Dependencies: T02, T03, T06.

Steps:

1. Persist upload intent with source asset, destination scope, and idempotency data
   before scheduling. Keep pending destination state separate from available replicas.
2. On completion, resolve/create the destination source and resource, record the
   transfer evidence, and reconcile membership in a coordinated transaction.
3. Handle feed-before-completion and completion-before-feed idempotently. Where a
   feed cannot yet correlate an item, retain pending evidence and reconcile when
   identifiers become available; do not invent equivalence from its title.
4. Download finalization still validates actual bytes and existing Cloud hash
   invariants. Then associate the verified device replica with the group/asset.
5. Reuse a verified existing device file rather than creating a duplicate physical
   replica. Preserve its ownership and provenance correctly.
6. After cancellation/deletion, reject stale completion using persisted operation
   generation/revision state. Never resurrect an intentionally removed replica.

Tests: retry, restart, duplicate completion, both feed/completion orderings,
hash mismatch, existing local file reuse, cancellation followed by late success.

Done when: import→upload and Cloud→download keep one stable book group.

### T08 — Implement safe replica-specific removal semantics

Dependencies: T06, T07.

Steps:

1. Add device-removal use case targeting a concrete owned storage reference. It
   must work without a download transfer and for `origin=import` files.
2. Audit file access and active transfers. Coordinate active reader/player use;
   if an upload still needs the file, expose the dependency and require an explicit
   cancellation or wait rather than removing its bytes mid-transfer.
3. Persist a removal intent, remove bytes, then finalize associations. Recovery
   must handle missing bytes or interrupted database updates idempotently.
4. Preserve group, progress, bookmarks, and unaffected replicas. Delete shared
   covers only when ownership/reference rules say they are unreferenced.
5. Change `deleteRemoteBackup()` so ordinary remote deletion preserves completed
   downloads. Change the remote-feed path too; fixing only the button is insufficient.
6. Split remote unavailability/in-flight staging cleanup from mandated local
   invalidation. Add a backend event reason if existing payloads cannot distinguish
   user deletion from takedown. Do not infer reason from `removed` alone.
7. Retained downloads keep provenance but are not automatically evicted by later
   ordinary deletion events referencing their former remote file.

Tests: remove original import; remove restored copy; shared local path removed once;
no transfer history; interrupted deletion; Cloud delete from either device retains
completed local copies; separately classified mandatory invalidation still works.

Done when: deleting one location cannot accidentally delete another location.

### T09 — Implement local manual merge and split

Dependencies: T03, T05.

Steps:

1. Merge use case accepts explicit source-member IDs and preferred display metadata,
   not only group IDs that may have changed since selection.
2. Transactionally validate profile scope, move selected memberships, write redirects
   only for emptied retired groups, and record explicit merge operation/provenance.
3. Split use case moves selected memberships to a new persisted group and records
   separations between moved and remaining members. Keep nonempty old groups intact.
4. Do not rewrite assets, progress, native metadata, or physical files during either
   operation. In-flight transfers follow their assets/source refs, not a stale row.
5. Add multi-select Merge UI and Details Manage grouped books UI using the existing
   intent/state and translation conventions.
6. Explain Read/Listen selection and separate progress when formats differ.

Tests: merge ebook+audiobook; split then refresh; merge two EPUB editions; merge/split
during a transfer; favorites/metadata projection after split; profile mismatch rejects.

Done when: merge/split works offline with sync disabled and survives restart.

### T10 — Synchronize grouping using the existing sync machinery

Dependencies: T09 and T00 portable-identity/backend inventory.

Steps:

1. Define versioned merge/split/preference operation payloads with operation IDs,
   explicit portable member references, and profile/account scope. Never send paths.
2. Commit a local grouping change and its outbox entry together. An unlinked profile
   must retain enough durable operation state to synchronize when linked later.
3. Add transport encoding and backend persistence/feed handling. Use existing sync
   delivery/retry machinery; domain operation application remains reusable offline.
4. Backend orders accepted operations by revision. Apply in revision order, dedup by
   operation ID, and rebase pending local operations over accepted state.
5. Resolve portable server/account identity on each device. Retain unresolved
   references when a server is not connected. Never match accounts solely by URL.
6. Implement explicit separation precedence and explicit merge overrides as defined
   in the main plan. Automatic reconciliation must not override accepted decisions.
7. Test stale group IDs, overlapping edits, tombstone/redirect replay, and new device
   snapshots. Handle unknown payload versions without corrupting existing state.

Tests: two device repositories converge after offline merge/split; duplicate delivery;
later accepted overlapping edit wins for named members; pending-operation rebase;
unlinked→linked profile; unresolved source later connects; unauthorized scope denied.

Done when: real sync integration carries grouping without embedding grouping logic
inside transport adapters or creating another retry/checkpoint engine.

### T11 — Prove extensibility and preserve existing integrations

Dependencies: T06–T10.

Steps:

1. Register an unknown test integration through production registration boundaries.
2. Verify its source can list, group by verified evidence, manually merge/split,
   show locations, select media, and resolve progress through its adapter.
3. Toggle fake upload/download support and verify the existing UI action model
   discovers it without editing grouping, Details, or core action dispatch.
4. Repeat with no identity evidence: no automatic matching, manual grouping works.
5. Run existing native progress and reader/download regression coverage for current
   adapters. Verify streaming, multi-file media, and disconnected-source behavior.
6. Document how a new integration implements/registers each required/optional
   contract, pointing at real code, not pseudocode that differs from implementation.

Done when: generic behavior is demonstrated, and current adapters still work.

### T12 — Run acceptance, review migrations, and finish documentation

Dependencies: T11.

Steps:

1. Execute the complete primary flow on a device and emulator using the same linked
   account. Record actual observed row counts, locations, file presence, and progress.
2. Delete a Cloud file from one device and confirm the other device retains its
   completed copy after sync. Repeat device removal and confirm Cloud remains.
3. Upgrade a populated pre-change database. Verify imported books, aliases, pending
   transfers, positions, bookmarks, and profile boundaries after restart.
4. Run affected builds/tests from section 7. Resolve failures attributable to changes.
5. Update historical documentation where it conflicts with the new deletion/identity
   contract. Mark tasks complete only with evidence; list blocked checks explicitly.

Done when: main-plan acceptance matrix is accounted for and implementation status
does not claim unavailable backend/device verification passed.

## 6. Fixture set to reuse across tasks

Use deterministic fixture values, not production account data:

| Fixture | Source / content | Intended relationship |
| --- | --- | --- |
| L1 | Device import, ebook, verified fingerprint H1 | Same asset as C1 |
| C1 | Cloud entry, ebook, H1, different native ID | Group with L1 |
| S1 | Arbitrary server entry, ebook, H1 with same fingerprint semantics | Group automatically when adapter exposes verified evidence |
| S2 | Same title as L1, no trustworthy evidence | Separate until manual merge |
| A1 | Audiobook, different asset/fingerprint | May manually join L1 group; separate listening progress |
| E2 | Different EPUB edition, H2 | May manually join; keep its own asset/locators |
| U1 | Unknown fake integration, no identity evidence | Ordinary standalone group with generic Details |
| U2 | Unknown fake integration, compatible H1 evidence | Automatically associates via the same resolver |

Use two device stores to test local presence: C1 exists on both; L1's physical
path exists only on device A until device B downloads. A path in A's data must
never make B's UI show “On this device.”

## 7. Verification instructions

- Inspect current Gradle task names instead of inventing them. Module project
  paths are declared in `settings.gradle.kts`; discover available tasks with
  `./gradlew :feature:books:domain:tasks --all` and the equivalent affected module.
- Run targeted domain/data tests first; database migration tests for schema work;
  adapter/sync tests for changed backend mappings; UI/module compilation for route
  and state changes. Use platform tasks actually present in this repository.
- Existing test starting points include `ServerBookExtTest`,
  `BookFileTransferEngineTest`, `DownloadFinalizerTest`,
  `LibraryBookSyncApplierTest`, `LibraryMutationSyncEngineTest`,
  `ProgressSyncEngineTest`, and database `LibraryBookQueriesTest`.
- Discover backend test setup in repository documentation/scripts before running
  the relevant tests in `supabase/tests/`. Local SQL tests alone do not verify
  authorization, feed ordering, or two-device synchronization.
- Check Android and iOS compilation for changes in shared contracts or file stores,
  subject to the available build environment. Record unavailable checks explicitly.
- Run `git diff --check` and inspect the diff for unintended changes.
- Do not rerun broad suites repeatedly once they pass unless later changes affect
  them. Keep exact commands and outcomes in the handoff.

## 8. Handoff template for each implementation session

Append/update `docs/unified-library-implementation-notes.md`:

```text
Task: Txx — title
Status: pending / in progress / implemented, verification blocked / verified
Behavior implemented:
Changed files:
Schema or protocol changes:
Tests/checks run (exact command + result):
Acceptance evidence:
Known gaps or blockers:
Next task and required context:
```

Do not mark all of a slice complete because its types compile. A slice is complete
only when its behavior and completion gate are satisfied. Keep the implementation
notes concise enough for the next AI session to use without rereading the entire
conversation.

## 9. Copyable starting prompt

> Implement the unified library following
> `docs/unified-library-replicas-implementation-plan.md` and
> `docs/unified-library-execution-guide.md`. Read repository instructions first.
> Start with T00, or the first unfinished task recorded in
> `docs/unified-library-implementation-notes.md`. Implement in dependency order,
> preserving working intermediate builds. The unified library is a separate
> domain alongside sync, with generic adapters, not server-type branches. Preserve
> legacy Cloud hash identities and native progress ownership. Do not infer missing
> identity from titles, conflate metadata with file availability, or remove device
> copies on ordinary Cloud deletion. Run appropriate checks and update the handoff
> notes with evidence and the next task. If runtime verification is unavailable,
> record the blocker and continue independent work; do not claim the check passed.
> I will review each change. Make one small, coherent change at a time, run the
> relevant checks, and present the changed files, behavior, and verification
> results in the implementation notes. Continue through T12 when the caller has
> asked for the complete feature; do not pause after each coherent change. Ask
> only for a missing product decision, credentials/environment access, or an
> incompatible contract that cannot be resolved from the repository and plan.
> Do not commit, push, merge, or rebase without explicit permission.
