# Parrot Cloud server implementation plan

## Status

This document is the target implementation plan for Parrot Cloud.

The product is not live, so implementation uses a clean cutover:

- Do not migrate existing local or Supabase development data.
- Do not preserve the current position-only Cloud API.
- Do not dual-write to old and new schemas.
- Do not add compatibility adapters for old Cloud records.
- Reset development databases and storage when the target schema lands.
- Remove `initialMergeCompleted`, initial-snapshot preferences, and the old
  snapshot path rather than carrying migration-only state into the new design.
- Replace or squash development Supabase migrations instead of layering the
  target schema over the position-only schema.

This plan supersedes conflicting decisions in
`docs/sync-and-backup-implementation-plan.md`, especially the decisions to keep
Cloud outside `ServerRegistry` and to treat book-file backup as an unrelated
system.

## 1. Goal

Make Parrot Cloud a first-class book server alongside Storyteller,
Audiobookshelf, and Local.

Parrot Cloud starts by synchronizing metadata and reading progress. It later
stores complete book files without changing book identity or creating a second
Cloud integration.

From the rest of the application, Parrot Cloud must use the same server-facing
contracts, navigation, library presentation, reader behavior, and status UI as
other servers. Its internal implementation may use Supabase Auth, Postgres,
Storage, a durable local outbox, revisions, and a cursor-based change feed.

## 2. Product behavior

### Progress-only release

- A signed-in Cloud account appears as a registered Parrot Cloud server.
- Imported books can create Cloud book records containing identity and metadata.
- Reading positions synchronize automatically between devices.
- A device with the same local file attaches it by hash algorithm and hash.
- A device without the file retains the book and progress as unavailable.
- The UI states clearly that the file is not backed up yet.

### Full-book release

- A progress-only Cloud book can be upgraded by uploading its file.
- The existing Cloud book ID and reading position remain unchanged.
- Other devices can download the file through the normal server book flow.
- Upload and download progress is visible and cancellable.
- Local deletion and Cloud deletion are distinct actions.

### Non-goals

- Public sharing, discovery, or a public book catalog.
- Cross-account deduplication of copyrighted file contents.
- Treating a failed upload as an available Cloud book.
- Depending on background execution for correctness.
- Reimplementing Supabase session storage inside `ServerCredentials`.

## 3. Architectural decisions

### 3.1 Parrot Cloud is a server type

Add `ServerType.ParrotCloud` and a stable server ID such as `parrot-cloud`.
Register one Parrot Cloud server for each linked local profile.

Parrot Cloud participates in:

- `ServerRegistry`
- `AuthenticatedRepositoryProvider`
- `BooksRepositoryFactory`
- `ReaderRepositoryFactory`
- Server capabilities and server-management UI
- Aggregate book and progress use cases

`AuthenticatedRepositoryProvider` currently creates every repository type for
every authenticated server. Change it to request only repositories supported by
the server capabilities. Until then, Parrot Cloud must provide a valid empty
series repository so observing series cannot fail.

The Cloud server configuration is application-managed. Users do not enter a
base URL or create multiple Parrot Cloud configurations. Exclude managed server
types from the generic URL/username/password login picker and update every
exhaustive `ServerType` branch when the enum value is added.

### 3.2 Authentication remains provider-owned

Supabase Auth remains the source of truth for the Cloud session. Do not copy
Supabase access and refresh tokens into `ServerCredentials`.

Introduce an authentication-provider abstraction that lets `ServerRegistry`
obtain auth state from either:

- Persisted `ServerCredentials` for Storyteller and Audiobookshelf.
- `CloudAccountRepository` session state for Parrot Cloud.
- The existing local-server authentication behavior for Local.

The registry must expose a uniform `ServerAuthState` regardless of the backing
provider. Signing out of Cloud removes or deactivates the Parrot Cloud server
entry without affecting other servers.

Do not implement Parrot Cloud through the current `ServerAuthenticator`
contract because that contract returns `ServerCredentials`. Add a
`ServerAuthStateProvider` selected by `ServerType`; retain the current
authenticator path behind the provider for credential-based servers.

Make profile links observable and add explicit unlink/deactivate operations.
Define sign-out separately from unlink:

- Sign-out clears the Supabase session and marks the managed server unauthenticated.
- Unlink also removes the profile association and profile-scoped Cloud cursor.
- Neither action discards unsent mutations without explicit confirmation.
- Switching local profiles cancels active work and activates only that profile's
  link, session, server entry, cursor, and outbox scope.

### 3.3 One book identity, multiple file locations

Use the existing separation between `library_books` and `local_book_files` as
the storage foundation, but promote the canonical library record into the
domain, navigation, reader, and position layers:

```text
LibraryBook
├── local file replica, optional
└── Parrot Cloud file replica, optional
```

Do not create one visible Local book and another visible Cloud book for the same
content. The aggregate library must resolve them to one book by Cloud ID or
content identity.

Add a nullable `cloud_book_id` to the local canonical library-book record. Keep
the imported-file UUID device-local. Use the content hash only to discover an
existing Cloud identity; after attachment, use `cloud_book_id` for all remote
relationships.

The current app routes by `(serverId, uuid)` and concatenates server results.
Replace that presentation identity with:

```text
LibraryBook
├── libraryBookId, stable app identity
├── metadata
├── position keyed by libraryBookId
└── replicas
    ├── LocalReplica(importedBookUuid, localPath)
    └── CloudReplica(cloudBookId, fileAvailability)
```

Navigation and reader use `libraryBookId`. A replica selector chooses the local
file for reading, the Cloud replica for download, and the appropriate remote
repository for synchronization. Server-native books that are not mirrored keep
a single replica and continue to route to their existing server.

Make `library_books` the source for canonical library presentation. Add database
queries, a repository, and mappers for metadata-only records; the current Local
repository observes only imported files and cannot display a Cloud placeholder.

Store positions by canonical `libraryBookId`, not imported-book UUID. This lets
progress exist before a local file. Remove the old pending-position workaround
once canonical position storage is in place.

All hash lookup APIs and indexes must use both `content_hash_algorithm` and
`content_hash`. Define deterministic metadata precedence: user-edited canonical
metadata, then Cloud metadata, then local extraction. Replica metadata must not
produce a second visible item.

### 3.4 Book files are optional Cloud resources

A Cloud book exists independently from its stored file:

```text
CloudBook
├── identity and metadata
├── reading position
└── file resource, optional
```

Use these file states:

```text
none
upload_pending
uploading
available
upload_failed
deleting
```

Only `available` means another device may download the file. Prefer deriving
availability from a finalized file record rather than trusting a client flag.

### 3.5 Shared behavior, server-specific transport

Parrot Cloud implements `ServerBooksRepository` and `ServerReaderRepository` so
normal library and reader flows do not special-case Cloud.

Retain a Cloud sync adapter for account-wide reconciliation because the current
server interfaces operate on individual books and cannot express:

- Pull all changes after a cursor.
- Push a durable batch of offline mutations.
- Report aggregate pending and completed counts.
- Retry interrupted work after process death.

The sync adapter is internal to the Parrot Cloud server implementation. It is
not a separate user-facing account system.

## 4. Target modules

Create a dedicated server module group:

```text
lib/server-parrot-cloud
├── ParrotCloudAuthStateProvider
├── ParrotCloudServerRegistrar
├── ParrotCloudBooksRepository
├── ParrotCloudReaderRepository
├── ParrotCloudSyncAdapter
├── ParrotCloudBooksRepositoryFactory
├── ParrotCloudReaderRepositoryFactory
├── remote sources and API models
└── DI module
```

Keep these existing responsibilities:

```text
lib/cloud/implementation
└── Supabase client, configuration, session storage, and callback handling

feature/cloud-account
└── Registration, sign-in, account recovery, and account details UI

feature/sync
└── Shared sync orchestration, observable status, triggers, and manual sync
```

Replace the current position-only Cloud reconciliation with a new implementation
in `lib/server-parrot-cloud`. Reuse proven outbox, mutex, and serialization
primitives where suitable, but do not preserve the old content-hash entity
protocol. Keep generic trigger orchestration in `feature/sync`.

## 5. Domain contracts

### 5.1 Server capabilities

Extend `ServerCapabilities` with capabilities needed by Cloud rather than
inferring them from file paths:

```text
supportsBookUpload
supportsBookDownload
supportsBookDeletion
supportsAutomaticSync
supportsOfflineMutationQueue
supportsCloudFileStatus
```

Parrot Cloud initially reports progress synchronization and metadata support.
Enable upload/download capabilities only when the backend and UI are complete.

### 5.2 Book model

Introduce a canonical app-facing book model and explicit server replicas. Keep
`ServerBook` as repository input where useful, but do not make its one
`serverId` represent a merged book. The canonical model needs:

```text
libraryBookId
contentHash
contentHashAlgorithm
metadata
position
replicas
```

Each replica needs:

```text
serverId
serverBookId
localAvailability
remoteFileAvailability
remoteRevision
mediaResources
```

Update aggregate use cases to group replicas by `cloud_book_id`, then composite
content hash while attachment is pending. Define stable source precedence and
route actions to a selected replica rather than flattening repository results.

Do not overload `ebookFilepath` to mean both a local file and an authenticated
remote download endpoint. Add an explicit media resource model containing:

```text
mediaType
localPath
remoteAvailability
size
contentHash
```

The UI should consume this model to choose Open, Download, Upload, Retry, or
Unavailable actions.

### 5.3 Sync status

Replace the one-shot result-only UI with an observable status:

```text
Disabled
Idle(lastSuccessfulAt)
Running(phase, completedItems, totalItems)
Offline(pendingCount)
Failed(error, pendingCount, canRetry)
Completed(pushedCount, pulledCount, pendingCount, completedAt)
```

Use explicit phases:

```text
preparing
pulling
applying
uploading_changes
uploading_files
downloading_files
finalizing
```

Unknown totals use an indeterminate indicator. Known file transfers expose byte
progress in addition to item progress.

## 6. Backend model

Replace the current position-only development schema with a normalized target
schema. Exact SQL belongs in Supabase schema files, but the required model is:

### `cloud_books`

```text
id uuid primary key
cloud_user_id uuid not null
content_hash text not null
content_hash_algorithm text not null
title text not null
author text nullable
format text not null
metadata jsonb not null
revision bigint not null
created_at timestamptz not null
updated_at timestamptz not null
deleted_at timestamptz nullable
unique(cloud_user_id, content_hash_algorithm, content_hash)
```

### `cloud_book_files`

```text
id uuid primary key
cloud_book_id uuid not null references cloud_books(id) on delete cascade
cloud_user_id uuid not null
storage_path text not null
size_bytes bigint not null
content_hash text not null
content_hash_algorithm text not null
media_type text not null
status text not null
revision bigint not null
created_at timestamptz not null
updated_at timestamptz not null
unique(cloud_book_id, media_type)
```

Only finalized rows with `status = available` are downloadable.

### `reading_positions`

```text
cloud_book_id uuid primary key references cloud_books(id) on delete cascade
cloud_user_id uuid not null
payload jsonb not null
revision bigint not null
updated_at timestamptz not null
```

Do not use a device-local imported-book UUID. Enforce account consistency with
composite ownership constraints or derive ownership through `cloud_books`; never
trust a duplicated client-provided `cloud_user_id`.

### Sync protocol tables

Keep the concepts represented by:

```text
sync_changes
sync_mutations
```

Every accepted metadata, position, file-state, and deletion mutation writes a
change-feed entry in the same transaction. Mutation IDs remain idempotency keys.

Define versioned RPC contracts before implementing repositories:

```text
push_sync_changes(mutations, client_cursor)
pull_sync_changes(cursor, limit)
reserve_book_upload(cloud_book_id, media_type, size, hash)
finalize_book_upload(upload_id, object_version, size, hash)
cancel_book_upload(upload_id)
create_book_download(cloud_book_file_id)
```

For each contract, specify request and response models, authorization,
idempotency retention, revision allocation, conflict responses, cursor expiry,
pagination boundaries, and transaction boundaries. Metadata upsert returns the
canonical Cloud book ID so dependent mutations can be rewritten before upload.

Resurrect a tombstoned book with the same ID when the same account intentionally
imports the same content again. Do not insert a second row that conflicts with
the per-account content identity.

### Storage layout

Use a private bucket and account-scoped paths:

```text
users/{cloudUserId}/books/{cloudBookId}/{fileId}
```

Do not expose permanent public URLs. Generate authenticated or short-lived
download access after ownership and entitlement checks.

## 7. Progress-only to stored-book lifecycle

### 7.1 First progress upload

1. Save the reading position locally.
2. Ensure the local canonical book has a content hash.
3. Enqueue a book-metadata upsert if no `cloud_book_id` is attached.
4. Push the metadata mutation.
5. Store the returned Cloud book ID locally.
6. Push the position using that Cloud book ID.
7. Leave the Cloud file state as `none`.

The backend may resolve a simultaneous first upload from two devices through the
per-user content-hash uniqueness constraint. Both clients receive the same Cloud
book identity.

Local lookup and backend requests always carry the hash algorithm with the hash.

### 7.2 Second device without a file

1. Pull the Cloud book metadata and position.
2. Create or update the local canonical library-book record.
3. Look for a matching `local_book_files` association by hash algorithm and hash.
4. If no file exists, retain the book as unavailable.
5. Display its metadata and progress only where the product intends to show
   Cloud library placeholders.
6. Disable Open until the user imports the file or a Cloud file becomes
   available.

### 7.3 Importing an identical local file

1. Hash the imported file.
2. Find the existing Cloud-backed canonical book by hash algorithm and hash.
3. Add a `local_book_files` association to that book.
4. Apply the already-downloaded position.
5. Do not create another visible book or Cloud book record.

### 7.4 Uploading the complete file later

1. Resolve the existing `cloud_book_id`.
2. Reserve storage and enforce quota before transfer.
3. Create a resumable upload session.
4. Set local transfer state to `upload_pending`, then `uploading`.
5. Upload to a temporary object path.
6. Verify byte count and content hash.
7. Finalize the storage object and file row atomically from the client's
   perspective.
8. Emit a file-available change-feed event.
9. Mark the local state `available` only after finalization succeeds.
10. Clean up abandoned temporary objects asynchronously.

The existing Cloud book and position are unchanged throughout this transition.

### 7.5 Downloading on another device

1. Pull the file-available change.
2. Show a Download action for the existing book.
3. Download to a temporary local path with resumable transfer where supported.
4. Verify the content hash.
5. Move the verified file into app-managed storage.
6. Add the local file association.
7. Reuse the existing Cloud book and reading position.

## 8. Automatic synchronization

Introduce a shared `SyncCoordinator` that asks each authenticated server with
automatic-sync capability to synchronize.

Trigger Parrot Cloud sync on:

- Successful Cloud sign-in and profile linking.
- Application launch after session restoration.
- Foreground resume with throttling.
- Connectivity restoration.
- Manual refresh.
- Debounced foreground mutations.
- Best-effort platform background work.

Correctness must rely on the durable outbox and future foreground runs, not on a
background task being guaranteed.

Use a per-server mutex so overlapping triggers join or skip the active run. Use
bounded batches, exponential backoff with jitter, and coalesce repeated position
updates for the same book while preserving the latest unsent value.

Extend the outbox database API with eligible-at queries, failure recording,
attempt count, next-attempt time, permanent rejection, and entity-level
coalescing. Do not leave retry columns unused.

## 9. Repository behavior

### `ParrotCloudBooksRepository`

- `getBooks()` observes locally cached Cloud records and refreshes through the
  sync adapter.
- `getBook()` observes one cached Cloud book and may request targeted refresh.
- `saveBook()` creates or updates metadata through the durable outbox.
- `searchBooks()` searches cached Cloud metadata first; remote search is optional.
- File upload and deletion use explicit transfer use cases rather than hiding a
  long-running transfer inside `saveBook()`.

### `ParrotCloudReaderRepository`

- `savePosition()` commits the local position and outbox mutation atomically.
- `getPosition()` returns cached state and triggers reconciliation when suitable.
- `getRemotePosition()` performs a targeted remote read when conflict UI requires
  an authoritative value.
- Applying a pulled remote position must not enqueue another mutation.

### Other servers

Do not rewrite Storyteller or Audiobookshelf networking as part of the initial
Cloud server cutover. Add shared sync/status interfaces without changing their
observable behavior. A later slice may give them durable position outboxes.

## 10. UI and navigation

### Server management

Show Parrot Cloud in the same server-management area with:

- Account email.
- Authentication state.
- Sync enabled state.
- Current sync phase.
- Last successful sync.
- Pending changes.
- Storage usage and quota when file backup exists.
- Sync now and sign-out actions.

Cloud account registration and recovery may still open a dedicated screen, but
successful authentication returns to a new common server-details screen. That
screen does not exist today and must be added as part of this work.

The managed Cloud registration coordinator observes both active-profile changes
and profile-link changes. It upserts or deactivates the Cloud `ServerConfig`
deterministically and never relies only on the currently visible account screen.

### Library and book detail

Represent one book card regardless of local/Cloud replicas. Relevant states are:

| Local file | Cloud file | UI behavior |
| --- | --- | --- |
| Yes | No | Open; optionally Back up |
| Yes | Uploading | Open; show upload progress and cancel |
| Yes | Yes | Open; show backed-up state |
| No | No | Show unavailable/progress-only state |
| No | Yes | Show Download |
| No | Downloading | Show progress and cancel |

Do not label metadata-only books as backed up.

### Detailed progress

The server details screen shows aggregate sync progress. Book detail shows
per-file transfer progress. Reader progress continues to show local/remote
conflicts where user choice is required.

On completion, retain pushed, pulled, pending, and failed counts instead of
discarding `SyncResult.Completed` details.

## 11. Deletion semantics

Provide separate actions:

- Remove download: delete only the local file association and local bytes.
- Delete Cloud backup: delete the Cloud file while retaining metadata/progress.
- Remove from Cloud library: tombstone metadata, position, and file for all
  devices after confirmation.
- Delete account: remove all account data and storage according to retention
  policy.

Use tombstones for synchronized deletion so an offline device cannot recreate a
deleted record with a stale upsert. Define a supported offline window; devices
older than it perform full reconciliation before uploading changes.

Reimporting intentionally resurrects the tombstoned canonical record and keeps
its Cloud ID. It does not create a duplicate identity.

## 12. Security, policy, and legal release gate

Technical implementation does not establish legal permission to store books.
Before enabling production file backup, obtain jurisdiction-appropriate legal
review covering user-provided copyrighted files, DRM, platform terms, takedown
processes, and retention obligations.

The product and backend must enforce:

- Private, per-user storage only.
- No public links, sharing, indexing, or discovery.
- Row-level security on all metadata and sync tables.
- Storage ownership checks independent of object-path naming.
- Signed or authenticated downloads.
- Server-enforced file-size, account-quota, and request limits.
- Account export and deletion.
- Temporary-upload expiration and cleanup.
- Audit events without logging titles, file contents, tokens, or signed URLs.
- A documented abuse and takedown process before public availability.

Keep file-upload capability behind a server-controlled feature flag until this
gate is complete.

## 13. Implementation slices

### Slice 1: Canonical library and server contracts

- Add `ServerType.ParrotCloud` and capabilities.
- Exclude managed server types from generic login.
- Add provider-backed authentication to `ServerRegistry`.
- Make profile links observable and add unlink/deactivate operations.
- Introduce canonical books, replicas, and canonical position identity.
- Replace aggregate list concatenation with deterministic replica aggregation.
- Add presentation queries for metadata-only canonical books.
- Filter repository provisioning by server capabilities.

Exit criteria:

- Existing Local, Storyteller, and Audiobookshelf books still open correctly.
- Navigation and progress use canonical identity without replica ambiguity.
- Metadata-only canonical books can be represented without a local file.
- Android and iOS builds compile.

### Slice 2: Parrot Cloud server and target schema

- Replace development Supabase tables with the target schema.
- Add local `cloud_book_id` and explicit replica/availability fields.
- Extend server book/media models.
- Change local hash queries and keys to `(hash algorithm, hash)`.
- Create `lib/server-parrot-cloud`, auth provider, registrar, repositories, and
  factories before registering Cloud as authenticated.
- Provide capability-filtered or valid empty series behavior.
- Replace the old sync binding with the minimum `ParrotCloudSyncAdapter` needed
  for metadata push, pull, cursor persistence, and Cloud ID attachment in the
  same change that replaces the backend protocol.
- Implement metadata upsert/pull and Cloud ID attachment.
- Add the new common server-details screen and destination.
- Reset local development databases instead of migrating old rows.
- Clear old cursor/link/snapshot preferences and reset development Storage.

Exit criteria:

- Signed-in Cloud appears as one authenticated server.
- Cloud sign-out and unlink have distinct tested behavior.
- A local imported book can attach to one Cloud book by hash algorithm and hash.
- The aggregate library never shows Local and Cloud duplicates.
- Two devices converge on the same Cloud book ID.

### Slice 3: Progress synchronization through the server

- Extend `ParrotCloudSyncAdapter` with reading-position reconciliation.
- Preserve transactional local position plus outbox writes.
- Support metadata-before-position dependency ordering.
- Apply remote changes without creating upload loops.

Exit criteria:

- Reading progress round-trips between Android and iOS.
- A device without the file retains progress safely.
- Importing the same file applies the pending progress.
- Duplicate mutation submission is idempotent.

### Slice 4: Automatic sync and detailed status

- Add shared trigger coordination.
- Add launch, resume, connectivity, mutation, and manual triggers.
- Add outbox retry scheduling, failure recording, and position coalescing.
- Expose observable phases and counts.
- Show last success, pending work, errors, and retry.
- Add best-effort platform background execution.

Exit criteria:

- Normal reading changes synchronize without pressing Sync now.
- Process termination leaves work retryable.
- The UI never reports synced while eligible mutations remain pending.

### Slice 5: Full-book upload

- Create private Storage bucket and policies.
- Add quota reservation and upload-session backend operations.
- Implement resumable upload, verification, finalization, and cleanup.
- Add Back up, progress, cancel, and retry UI.
- Emit file-state changes through the normal change feed.
- Persist transfer sessions and state so retries survive process termination.

Exit criteria:

- A progress-only book upgrades to available without changing identity.
- Failed/interrupted uploads never appear downloadable.
- Cross-account access tests fail at table, function, and storage layers.

### Slice 6: Restore and download

- Implement authenticated/resumable download.
- Verify files before moving them into app storage.
- Add a Cloud-authenticated or signed-URL transfer client independent of
  `ServerCredentials`.
- Add a verified download finalizer that atomically creates imported-book and
  local-file associations after moving the file.
- Reuse UI state patterns and foreground behavior, but persist transfer state
  instead of relying on the current in-memory state holder.
- Add equivalent durable background transfer behavior where supported on iOS.
- Separate local removal from Cloud deletion.

Exit criteria:

- A fresh device can list, download, and open an available Cloud book.
- The restored position is attached to the downloaded file.
- Cancellation and process interruption do not leave readable partial files.

### Slice 7: Deletion, operations, and release gate

- Implement tombstones and stale-device rebootstrap.
- Implement storage usage, quotas, retention, export, and account deletion.
- Add operational metrics and alerts.
- Complete legal, privacy, store-policy, and abuse-process review.
- Enable file upload only after the release gate passes.

## 14. Required tests

### Domain and data

- Content hash maps a local file to the expected Cloud book.
- Concurrent first uploads converge through the uniqueness constraint.
- Metadata is acknowledged before dependent position/file mutations.
- Repeated mutation IDs return the prior result.
- Remote application does not enqueue a new mutation.
- Newer pending local progress is not cleared by an older acknowledgement.
- Cursor pagination cannot skip or duplicate observable state.
- Tombstones beat stale offline updates.
- Progress-only books upgrade without changing Cloud ID.
- Local and Cloud replicas produce one aggregate library item.
- Navigation and reader actions select the correct replica.
- Progress remains addressable before any local file exists.
- Hash lookups distinguish algorithms.
- Sign-out, unlink, account switch, and profile switch isolate pending work.

### File transfer

- Upload success, cancellation, timeout, retry, and process termination.
- Byte count and content-hash mismatch rejection.
- Temporary object cleanup.
- Quota exceeded before and during transfer.
- Download verification and atomic local finalization.
- Local removal preserves Cloud data.
- Cloud-file deletion preserves metadata/progress when requested.

### Security

- Cross-account reads, writes, RPC calls, and storage access are denied.
- Forged `cloud_user_id` values are ignored or rejected.
- Signed URLs expire and cannot access another account's object.
- Deleted and disabled accounts cannot create transfer sessions.

### Backend integration

- Resetting the local Supabase stack produces the complete target schema.
- RPC contract tests cover conflicts, cursor expiry, pagination, and idempotency.
- PostgreSQL tests cover RLS for every table and function.
- Storage tests cover upload, download, finalization, cancellation, and isolation.

### UI

- Progress-only, uploading, available, downloading, failed, and unavailable
  states render correctly.
- Aggregate sync counts and phases update while work runs.
- Local and Cloud copies never render as duplicate books.
- Sign-out, account switch, and profile switch cancel or isolate active work.

## 15. Verification commands

Run focused tests for each changed module, then compile the Android app:

```bash
./gradlew :lib:server-parrot-cloud:allTests
./gradlew :feature:sync:domain:allTests
./gradlew :feature:sync:data:allTests
./gradlew :composeApp:assembleDebug
```

Add repository scripts for local Supabase reset plus automated database, RPC,
RLS, and Storage integration tests. Run those scripts in CI and before accepting
backend schema changes; Gradle compilation alone is not backend verification.

Use the actual generated module task names if they differ. Verify iOS through an
appropriate framework or app build; do not run `assembleXCFramework`.

Complete two-device testing for every release slice. At minimum test Android to
Android, iOS to iOS, Android to iOS, offline edits, interrupted transfers, and
account switching.

## 16. Definition of done

Parrot Cloud is considered a normal server when:

- It is represented and authenticated through the common server architecture.
- Its books participate in common library and reader flows.
- Progress synchronizes automatically and reliably through durable mutations.
- One canonical book can have local and Cloud file replicas without duplication.
- A progress-only book can gain a Cloud file without changing identity.
- Sync and transfer status are observable in meaningful detail.
- Full-book restore works on both platforms.
- Security, deletion, operations, and legal release gates are complete.
