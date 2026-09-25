# Unified library books, locations, and transfers

Status: implementation is in progress; current code and verification status are in
the companion implementation notes.
Date: 2026-09-24.

Implementation companion: [Unified library execution guide](unified-library-execution-guide.md).
Read this plan for product and architecture decisions, then follow the companion's
ordered tasks, concrete invariants, and handoff format. Neither document records
completed application work unless a task is explicitly marked verified.

## 1. Goal and confirmed product decisions

Represent a book once in the library, with all its known server entries and
device files accessible from that row and its Details screen.

Confirmed with the user:

- Build a server-independent library domain alongside the sync machinery, with
  separate responsibilities and shared source identity contracts. Existing and
  future server integrations participate through adapters; the library core must
  not enumerate supported server types or require sync to run.
- Automatically group proven copies; never group by title alone.
- Include manual merge and unmerge. A user may group an EPUB and an audiobook
  under one row with separate Read and Listen choices.
- Persist manual grouping locally and synchronize it through Parrot Cloud for
  linked profiles. Unlinked profiles can use grouping locally.
- Deleting a Cloud file keeps downloaded device copies. Device-file removal is
  a separate operation.
- Upload destinations come from implemented, currently usable transports.
  Parrot Cloud is supported today; Storyteller upload is a future integration,
  not a permanent exclusion from the model or UI.

Primary acceptance flow:

1. Import an EPUB.
2. Manually upload it to Parrot Cloud.
3. See one row showing both device and Cloud locations.
4. Remove the device copy; the Cloud file and book progress remain.
5. On an emulator linked to the same account, find that book, download it, and
   open it with its compatible reading position.

### Architectural acceptance: adding another server

Local, Parrot Cloud, Storyteller, and Audiobookshelf are current adapter examples
and regression targets, not a fixed set built into the design.

A new integration supplies the existing sync/repository contracts and, where
needed, adapter contracts for:

- Portable source/account identity and server-native book/resource identifiers.
- Metadata, media assets, availability, revisions, and deletion events.
- Optional identity evidence with explicit matching semantics.
- Supported operations by asset/format, including runtime availability and reasons.
- Reader/player resolution and progress ownership/compatibility.

Once registered, its books participate in the same grouping, manual merge/split,
Details, location display, and action-resolution pipeline. Reliable identity
evidence enables automatic matching; its absence leaves books separate but
manually mergeable. An optional upload adapter enables the existing “Store a copy
on…” action without a new server-specific UI path.

Adding an integration must not require edits to the grouping algorithm, group
schema, Details layout, or core action dispatch to add a server-type branch.
Protocol-specific behavior belongs in adapters. Backend-specific transfer and
progress semantics still require implementation in that integration.

### Relationship to the sync machine

The unified library is a separate domain, not a feature implemented inside the
sync engine and not another sync engine with similar code.

- Library domain: owns groups, memberships, identity resolution, manual merge and
  split rules, and book/location/action projections over persisted source data.
- Sync machinery: exchanges supported source changes and grouping operations,
  manages delivery/retry/checkpoints, and applies accepted changes through domain
  persistence boundaries. It does not decide which entries represent one book.
- Server adapters: translate backend identities, resources, capabilities, and
  progress semantics into shared contracts.
- Transfer machinery: executes file operations against concrete replicas and
  destinations, then records results and source associations for library queries.

Local imports and merge/split actions work with sync disabled and without any
Cloud account. List and Details query local observable repositories; they do not
depend on a sync run completing. Incoming synchronized changes update those same
repositories and cause the same library projections to refresh.

Reuse existing source registration and persistence contracts where appropriate.
Use the existing outbox, delivery, retry, and revision infrastructure to transport
grouping changes, rather than duplicating those mechanisms. The library domain
defines grouping operations and their conflict semantics; the sync layer delivers
and orders them. Audit concrete dependency boundaries in Slice 0 before deciding
which existing abstractions need extension.

Parrot Cloud is the current transport for synchronizing manual grouping decisions;
the grouping domain itself must not depend on it. The layers share an extensible
adapter approach, not ownership of each other's business rules.

## 2. Verified current implementation and diagnosis boundary

Relevant existing code:

| Area | Current behavior / implementation |
| --- | --- |
| Aggregation | `feature/books/domain/.../model/ServerBookExt.kt` groups by a priority-selected identity and returns one `ServerBook`, preferring Local. |
| Library list | `feature/books/domain/.../usecase/GetBooksUseCase.kt` uses that aggregation; repository errors become empty lists. |
| Reading list | `feature/reader/domain/.../usecase/ObserveAllBooksWithProgressUseCase.kt` also uses that aggregation. |
| Identity mapping | `lib/server-local/.../LocalBooksRepository.kt` derives library identity from the hash; `lib/server-parrot-cloud/.../ParrotCloudModels.kt` uses the stored library record ID and can also expose a local file path. |
| Details lookup | `feature/books/domain/.../usecase/GetBookByUuidUseCase.kt` loads one `(serverId, uuid)` entry. |
| Domain model | `BookDomainModel.kt` represents one source and exposes one `serverId` and `uuid`. |
| Storage schema | `LibraryBook.sq` contains a single format, hash, and Cloud book ID per library record. It cannot directly represent arbitrary manual grouping. |
| Download invariant | `DownloadFinalizer.kt` and `BookFileTransferEngine.kt` validate that existing Cloud `libraryBookId` equals `algorithm:hash`. |
| Transfer API | `BookFileTransferTransport.kt` has transport capabilities, but requests/results and deletion contracts still contain Cloud-specific identifiers. |
| Local removal | `BookFileTransferEngine.removeDownload()` requires a download transfer and removes only files with `origin=cloud_download`. |
| Cloud deletion | `deleteRemoteBackup()` calls `invalidateCloudFile()`, which deletes restored device copies. |
| Cloud change feed | `ParrotCloudBookFileChangeApplier.kt` also invalidates device copies on `deleting`, `removed`, or `none` states. |
| Navigation | `feature/home/ui/.../navigation/HomeDestination.kt` and `HomeNavigation.kt` carry Details routes. |

Two visible rows are not explained by lossy aggregation alone. If they pass
through the current aggregator, their selected keys differ. The actual duplicate
must be reproduced before choosing a data repair; an ID mismatch is a hypothesis,
not yet a confirmed root cause. The same file hash does not currently join two
entries when both have different non-null library IDs.

Existing transfer/enablement plans contain historical assumptions. This document
defines the intended grouping and deletion behavior; implementation must inspect
current code rather than treating historical status tables as current evidence.

## 3. Separate displayed-book identity from content and storage identity

Introduce a durable aggregation layer above current source records. Working
names below are deliberately distinct from the existing hash-bound
`libraryBookId` field:

### LibraryBookGroup

- `groupId`: opaque, persistent ID used for row keys and new Details routes.
- Memberships, preferred metadata source, and preferred reading/listening source.
- Aggregated media choices, locations, and transfers derived from members.
- Stable across upload, download, authentication changes, and local-file removal.

### SourceBookRef

- A server-native book identity, scoped by profile, stable server/account
  identity, and server-native book ID.
- Preserves imported UUID, Cloud book ID, Storyteller ID, or Audiobookshelf ID.
- Retains source metadata and server-specific progress ownership.
- Existing Cloud hash-bound library IDs remain content/source aliases, not new
  group IDs. Never pass a group ID to a legacy Cloud endpoint expecting a hash ID.

### MediaAsset and StorageReplica

- An asset is a specific ebook, audiobook, or readaloud rendition/version.
- A replica is a concrete copy of that asset on this device or a server.
- Preserve remote resource/file ID, media type, format, hash algorithm and hash,
  size, revision, and origin. Multi-file books need resource paths/manifest data;
  media type alone is not a unique file key.
- A device replica has its own stable local storage reference and verified path.
  Local paths are device-specific and are never synchronized as availability on
  another device.
- Multiple source entries can refer to one device replica. Do not duplicate file
  ownership merely because the Cloud mapper and Local mapper expose the same path.
- A manual merge can contain several different assets of the same media type.
  Offer source/version selection instead of silently treating them as identical.

Keep this layer additive initially. Avoid rewriting Cloud IDs, transfer records,
or progress keys simply to make two rows appear together.

## 4. Identity resolution and repair

Resolution order:

1. Apply persisted manual membership decisions and explicit separation rules.
2. Follow recorded import/upload/download associations.
3. Match compatible assets using a verified content hash with the same known
   algorithm and semantics. Validate existing hash implementations before using
   them as equivalence evidence; a manifest hash and a single-file hash differ.
4. Use other server identifiers only through an adapter rule that establishes
   edition/asset identity. ISBN, title, author, or filename alone do not establish
   identical files or interchangeable reading positions.
5. Otherwise retain a separate group. Missing identity is not a match.

Persist association provenance: manual, transfer, verified hash, or approved
server identity. Missing hash algorithms are unknown unless a documented legacy
migration can establish the algorithm; do not guess for new records.

For existing installations:

- Backfill groups and memberships idempotently from current source records.
- Reconcile proven Local/Cloud duplicates using transfer mappings and compatible
  hash identities, even when their legacy IDs differ.
- Preserve old identities as aliases; do not mutate hash-bound Cloud keys to an
  arbitrary group ID. Repair incorrect underlying content records separately.
- Keep unresolved conflicts separate and available for manual merging.
- Group merges choose a deterministic surviving ID and retain redirects from
  retired group IDs so routes, selections, and delayed sync still resolve.
- Backfill must preserve transfers, progress, bookmarks, and source metadata.

## 5. Manual merge, unmerge, and cross-device synchronization

UI:

- Multi-select library rows → Merge books → choose preferred display metadata.
- Details → Manage grouped books → move selected source entries into a new group.
- Explain that merging changes presentation; it does not convert files, delete
  copies, or synchronize positions across different editions or formats.
- Unmerging creates a persisted separation override, so the automatic matcher
  does not immediately join those source entries again on refresh or another device.

Persistence and sync:

- Add profile-scoped group, membership, alias/redirect, and separation records.
- Store merge/split operations transactionally with the local sync outbox.
- Synchronize logical source identities, membership overrides, and metadata
  preferences through Parrot Cloud; keep device paths and presence local.
- Do not assume a local `serverId` means the same server on another device.
  Establish portable connection identity using stable backend identity and account
  scope, or synchronized connection identity. URLs alone are insufficient.
- If another device cannot resolve a source reference, retain the membership as
  unresolved until that server is connected; do not fabricate an available file.
- When linking an existing profile, upload its grouping operations idempotently
  and reconcile with existing account state. Never merge across profile/account
  boundaries.

Conflict rule proposed for implementation:

- Give each operation a unique ID; backend assigns an ordered revision.
- Apply explicit membership assignments in server revision order, not device
  wall-clock order. Later accepted overlapping edits win for affected members.
- An explicit separation blocks automatic regrouping until an explicit merge
  overrides it. A merge must name its affected members, not accidentally include
  future members through a stale group alias.
- Clients rebase pending operations on the accepted snapshot; operations and
  aliases must remain idempotent and redirects acyclic.
- Add backend persistence, authenticated profile/account scoping, feed support,
  and old-client compatibility before claiming cross-device grouping is complete.

## 6. Unified queries, navigation, and metadata

- Introduce one observable group repository/resolver used by library lists,
  search, reading/continue lists, and Details.
- Replace lossy `aggregateBookReplicas()` results with group projections retaining
  all source references, assets, and replicas.
- Use `groupId` for list keys and Details navigation. Keep a compatibility resolver
  for old `(serverId, uuid)` routes and single-source books.
- Reader/player launch still resolves a concrete asset, file, and progress owner.
- Server filtering tests group membership: one row if any member matches. Details
  can show all locations even when entered through a filtered list.
- Search returns one row per group when any member matches.
- Keep source metadata intact. Prefer the user-selected source; otherwise use a
  deterministic fallback, retaining useful metadata when a source goes offline.
- Audit favorites, collections, authors/series navigation, recent history, and
  bookmarks. Aggregate row projections without destructively combining source
  records; retain enough provenance to split groups again.
- A fetch/authentication error marks a source unavailable or stale, not deleted.
  Cache the last known membership and distinguish loading, empty, and failed
  results. Only authoritative removal detaches a source.

## 7. Actions and transport boundaries

Resolve actions from the selected asset/replica and current destination state,
not from the source chosen to supply the title or cover.

| Action | Required behavior |
| --- | --- |
| Read / Listen | Resolve a compatible rendition; prefer a verified usable device file. Preserve native streaming where supported. Use remembered source choice or ask when several incompatible renditions qualify. |
| Download | Select an available remote asset and an implemented download adapter; finish verification before advertising device availability. |
| Remove from this device | Select actual device storage, including original imports; remove device bytes and storage associations, retaining group identity, remote copies, and progress. |
| Delete from a server | Select the exact remote file/resource; ordinary deletion preserves completed device copies. Label with the destination name. |
| Store a copy on… | Select a usable device asset and a connected destination whose transport supports that media/format and permits upload for the current account. |

Implementation requirements:

- Centralize action availability in a resolver. Consult actual adapters,
  authentication/account linkage, media support, and runtime operation capability.
  Static `ServerCapabilities` flags alone are insufficient.
- Provide generic source/destination and resource references at the domain
  boundary. Keep Cloud reservation, attestation, storage-path, and finalize
  details inside the Parrot Cloud adapter.
- Adapt existing Storyteller/Audiobookshelf read/download/delete paths where
  implemented. Do not assume lack of a new transfer interface means an existing
  server operation is unsupported; inventory and bridge current paths.
- Parrot Cloud uploads work in this delivery. Future Storyteller upload should
  require an adapter and capability registration, not a new Details action model.
- Upload completion records the remote source/asset association before publishing
  the refreshed row. Handle completion and feed events in either order.
- Persist idempotency and association before scheduling work. Retry or process
  restart must not create another group or an unintended extra remote file.
- Remote-to-remote copying is not implicit. If no device source exists, require a
  supported download first and then upload the chosen asset.
- Keep destination replacement explicit; a manual group may contain two EPUBs
  that cannot both occupy an existing single-file Cloud slot.

## 8. Device removal and Cloud deletion semantics

Implement local storage removal independently of transfer-history existence and
`origin=cloud_download`. Keep import removal and restored-file removal consistent.

- Resolve file ownership and active reader/player use before removal. Coordinate
  dependent transfers explicitly; never remove an upload source mid-read silently.
- Make deletion restart-safe: mark removal intent, remove bytes, finalize database
  associations, and reconcile after failures. Do not advertise a missing file.
- Preserve identity/progress even after the last device file disappears. A group
  with no known files can remain as metadata/history with “No available copy.”

For ordinary Cloud deletion:

- Change both direct `deleteRemoteBackup()` handling and remote change-feed
  handling so they mark the remote copy absent and keep completed device files.
- Separate cancellation of in-flight downloads/staging cleanup from eviction of
  successfully downloaded copies.
- Preserve origin/provenance for a kept download without treating its old Cloud
  file reference as permission to delete it later.
- Existing mandated invalidation/takedown handling needs a distinct event reason.
  If the feed cannot distinguish it from user deletion, add protocol support;
  do not reuse the current unconditional invalidation path for both cases.

## 9. Locations, transfer state, and progress

Details shows locations per asset, with separate states for known availability
and transfer activity:

- Device: available, missing, downloading, removing, or failed operation.
- Remote: available, pending upload, uploading, deleting, absent, or unknown/stale.
- Failed replacement must not conceal an older still-available remote revision.
- Cloud metadata without a finalized file is not “In Parrot Cloud.”
- Observe transfers across every member/source of the group, keyed by transfer ID
  and exact resource. Merging or changing the display source must not hide progress.
- Retry/cancel targets an exact transfer, not an arbitrary first transfer for a
  book/media type. Prevent stale completion from resurrecting a deleted replica.

Reading progress remains independent of presentation grouping:

- Exact Local/Cloud copies use their existing compatible content identity and
  progress synchronization rules.
- Each integration declares its native progress ownership and compatibility.
  Existing Storyteller and Audiobookshelf behavior is retained through adapters,
  rather than server-type exceptions in the grouping layer.
- Manual grouping does not make EPUB locators portable across different files,
  nor convert reading percentages into audiobook timestamps.
- Show progress per reading/listening choice; the row uses the last selected
  compatible choice, not the maximum percentage across all members.
- Splitting groups preserves each source's progress, bookmarks, and annotations.

## 10. Delivery sequence and completion gates

### Slice 0 — Reproduce and inventory

- Capture sanitized source IDs, hash algorithms/hashes, media types, and aggregation
  keys for the duplicate import/upload case, including refresh and restart.
- Trace import → metadata sync → upload → feed → list; establish whether mismatched
  keys, a separate list path, or stale emissions cause the observed duplicate.
- Inventory read/download/delete adapters, server identity portability, native
  progress routing, and schema cleanup/cascade behavior for all server types.
- Add a failing regression at the actual boundary once the cause is established.

Gate: confirmed failure mechanism and concrete migration/adapter touchpoints.

### Slice 1 — Durable grouping and existing-data migration

- Add group/source/asset/replica identity and persistence, aliases, provenance, and
  manual separation representation.
- Implement safe automatic resolution and idempotent backfill through shared
  source contracts, adapting current integrations to those contracts.
- Keep legacy Cloud content IDs and transfer/progress contracts intact.

Gate: proven copies resolve to one stable group; uncertain books remain separate;
migration preserves existing reading and transfer data.

### Slice 2 — Server-independent list and Details

- Replace both current aggregation consumers and audit other list/search routes.
- Add group navigation with legacy-route compatibility, metadata selection,
  media selection, locations, stale-source handling, and group-wide transfers.
- Bridge all implemented native reader/download operations.

Gate: one row and one Details screen retain every matched source through the
shared contracts, without losing adapter-owned reader behavior. Validate current
integrations and an otherwise unknown fake server type.

### Slice 3 — Replica-targeted storage actions

- Add operation resolution and generic transport boundaries.
- Persist upload/download associations atomically with relevant state changes.
- Implement removal of original imports as well as restored files.
- Separate ordinary Cloud deletion from local eviction in both direct and feed
  paths; extend backend event semantics if necessary.

Gate: the primary import → upload → remove → emulator download/open flow passes,
and Cloud deletion retains completed device copies on all synchronized devices.

### Slice 4 — Manual merge/unmerge and synchronized decisions

- Ship merge/split UI and preferred metadata/media choices.
- Add backend grouping sync, portable server references, revisions, conflict
  resolution, and local outbox integration.
- Retain unresolved remote memberships until the corresponding server connects.

Gate: phone and emulator converge after online/offline merge and split operations;
ebook+audiobook grouping offers distinct Read/Listen choices and separate progress.

### Slice 5 — Migration and integration hardening

- Validate restart, retry, cancellation, account/profile isolation, source
  disconnection/reconnection, file replacement, and existing-install upgrades.
- Document source, identity-evidence, progress, and operation contracts. Register
  a fake new integration through the same extension points used by real servers
  and verify listing, grouping, manual merge/split, Details, and action resolution.
  Enable its fake upload capability and verify destination discovery without
  changing core code or UI. Disable identity evidence and verify safe fallback.
- Review applicable historical docs to avoid conflicting deletion/identity guidance.

The complete delivery includes all slices, the generic contracts, and adaptation
of existing integrations to them. New server types and new operation transports
are adapter work, not additional unified-library implementations.

## 11. Required verification matrix

| Scenario | Expected result |
| --- | --- |
| Import → upload → refresh/restart | One stable row, two locations, transfer remains visible. |
| Existing duplicate with proven transfer/hash relationship | Backfill groups it without rewriting Cloud content IDs or losing progress. |
| Different library aliases but identical verified assets | Resolver associates them despite differing priority-selected old keys. |
| Same title, different files; unknown/mismatched hash algorithms | No automatic merge. |
| Remove original import after upload | Device bytes removed; Cloud file and progress retained. |
| Restore on emulator | Correct asset verified; one row and usable compatible progress. |
| Delete Cloud after restore, locally and from another device | Completed device copy remains readable; remote location becomes absent. |
| Cloud metadata exists but upload pending/failed | No false remote availability. |
| Merge Local EPUB + Storyteller entry + Audiobookshelf audio | One row, all sources accessible, separate compatible progress owners. |
| Two different EPUBs manually merged | Both assets selectable; no silent file/progress replacement. |
| Split, refresh, restart, sync | Separation persists; automatic matching does not undo it. |
| Concurrent offline merge/split | Deterministic convergence; no redirect cycles or orphaned memberships. |
| Missing server connection on second device | Unresolved membership retained without invented availability. |
| Upload retry / duplicate feed / reversed completion order | No duplicate group or unintended remote copy. |
| Auth failure or server outage | Cached grouping remains; source is stale/unavailable, not deleted. |
| Local and Cloud source entries point at one local path | One device replica; removal happens once. |
| Multiple resources/transfers under one media type | Actions target exact resources and transfer IDs. |
| Profile/account switch | No cross-profile grouping, file-action, or progress leakage. |
| Register an unknown fake server adapter | Listing, grouping, Details, and manual merge work without server-type branches. |
| Enable upload in that adapter | Existing destination picker and transfer action work without core/UI changes. |
| Adapter offers no reliable matching evidence | Books remain separate automatically and can still be manually merged. |
| Schema migration interrupted/re-run | Recoverable, idempotent state with preserved records. |

Use domain/repository tests for identity, grouping, operation targeting and progress
routing; database migration tests for preservation and aliases; sync/backend tests
for authorization and convergence; and device/emulator acceptance for real file and
reader behavior. Run the affected module builds and existing relevant suites during
implementation. The companion implementation notes record which checks have run,
which remain open, and any environment blockers.
