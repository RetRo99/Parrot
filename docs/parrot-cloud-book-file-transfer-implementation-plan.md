# Parrot Cloud book-file storage & sync — implementation plan

## Status

Draft implementation plan for uploading and downloading book files (epub now;
audiobook media designed-for) to Parrot Cloud, so imported books follow the user
across devices — joining the metadata + reading-progress sync that already works.

This plan implements **PC-plan Slice 5 (Full-book upload)**, **PC-plan Slice 6
(Restore and download)** and the file part of **PC-plan Slice 7 (Deletion,
operations, release gate)** of `docs/parrot-cloud-server-implementation-plan.md`
(cited below as "PC-plan"), plus the media-resource model of PC-plan §5.2, the
file states of PC-plan §3.4, and — per decision #10 — the observable status model
of PC-plan §5.3. Read that document first; this plan defers to it.

### Corrections to assumptions (verified in repo)

- `cloud_book_files` **already exists** in
  `supabase/migrations/20260922000000_parrot_cloud.sql:23-37`
  (`id, cloud_book_id, cloud_user_id, storage_path, size_bytes,
  content_hash(+algorithm), media_type, status, revision`,
  `unique (cloud_book_id, media_type)`, RLS owner-select). It is unused by Kotlin
  code. Genuinely missing: the four transfer RPCs, the Storage bucket + policies,
  quota, block-list, and audit.
- `RemoteFileAvailability`
  (`lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksRepository.kt:77-84`)
  maps 1:1 onto `cloud_book_files.status` + "no row" — the server state machine is
  an exact fit:

  | `cloud_book_files` | `RemoteFileAvailability` |
  |---|---|
  | *(no row)* | `None` |
  | `upload_pending` | `UploadPending` |
  | `uploading` | `Uploading` |
  | `upload_failed` | `UploadFailed` |
  | `available` | `Available` |
  | `deleting` | `Deleting` |

## Slice ordering

Server → observable status model → client upload → client download →
abuse/copyright.

Status is its own slice (Slice 2, added by decision #10) so the transfer slices
report `uploading_files` / `downloading_files` through the real PC-plan §5.3
model from day one instead of growing a parallel surface that a later redesign
would have to absorb.

Abuse/copyright is a first-class slice (Slice 5: attestation program, takedown
path, block-list ops, audit, invalidation, release gate), but its **enforcement
primitives cannot be sliced out of the server RPC layer**: `reserve_book_upload`
is simultaneously the quota gate, the block-list gate, and the
attestation-recording point. Those hooks land in Slice 1 (server) and the minimal
attestation capture needed to call `reserve_book_upload` lands in Slice 3's Back
up dialog. The coverage matrix below ensures nothing abuse-related is an
afterthought. Upload ships **disabled** until Slice 5 + the legal gate pass
(PC-plan §12).

### Abuse / copyright coverage matrix

| Requirement | Enforcement (S1) | Capture/UI | Response tooling (S5) |
|---|---|---|---|
| Rights attestation + ToS | `rights_attestation` param required + recorded by `reserve_book_upload` | Back up dialog checkbox (S3); full program: import-time, auto-backup toggle, registration (S5) | Attestation record retrievable for abuse response |
| Takedown path | status lifecycle + `book_file` change events (S1) | — | `admin_takedown_book_file`, ops CLI, `Deleting` → cache invalidation, runbook |
| Block-list by content hash | checks in `reserve`/`finalize`/`create_download` (S1) | — | `cloud_content_blocklist` + `admin_block_content_hash` ops |
| Per-user quota | `reserve_book_upload` reservation arithmetic (S1) | quota error surfacing (S3), usage row (S5) | ops usage inspection |
| Non-goals (no scanning/fingerprinting, no share links, no cross-account dedupe) | design constraints (private bucket, per-account dedupe only); restated in S3 & S5 | — | documented in runbook (`docs/parrot-cloud-abuse-runbook.md`) |

---

## Slice 1 — Server: storage bucket, `cloud_book_files` lifecycle, transfer RPCs, abuse enforcement

### Scope

Private Storage bucket + policies; extend `cloud_book_files` to the PC-plan §3.4
state machine **and to per-file rows** (decision #5: add `relative_path` +
`file_name`, replace `unique (cloud_book_id, media_type)` with
`unique (cloud_book_id, media_type, relative_path)` — deviation from PC-plan §6,
absorbed by the dev-DB reset of decision #11; `relative_path = ''` for
single-file epubs); new tables for upload sessions (quota reservations), per-user
quota, content block-list, and audit; the four RPCs (`reserve_book_upload`,
`finalize_book_upload`, `cancel_book_upload`, `create_book_download`), all
**per-file** (each call targets one `cloud_book_files` row); file-state
change-feed events (`entity_type = 'book_file'`) written transactionally with
every state transition.

Per PC-plan §3.4 ("prefer deriving availability from a finalized file record
rather than trusting a client flag"): file availability is **server-derived** —
clients never push file state through `push_sync_changes`; they only observe it
via `pull_sync_changes`. (Deliberate deviation from PC-plan §6, which
contemplates file-state mutations in the push protocol; §3.4's preference plus
server-side `reserve`/`finalize`/takedown make client pushes unnecessary.)

### Files & modules

| Path | Action |
|---|---|
| squashed `supabase/migrations/20260922000000_parrot_cloud.sql` | Canonical **schema** file (decision #11): all tables — `cloud_book_files` per-file rework (D5), `cloud_book_uploads`, `cloud_user_storage`, `cloud_content_blocklist`, `cloud_file_audit_events` |
| squashed `supabase/migrations/20260922000001_parrot_cloud_rpcs.sql` (absorbs `_sync_safety.sql`) | Canonical **RPCs** file: existing `push_sync_changes`/`pull_sync_changes` + the four transfer RPCs, `security definer`, `grant execute to authenticated` only |
| new `supabase/migrations/20260922000002_parrot_cloud_storage.sql` | Canonical **storage + policies** file: bucket `book-files` (private) + storage RLS policies + service-role cleanup grants |
| new `supabase/tests/book_files_test.sql`, `supabase/tests/rls_isolation_test.sql` | pgTAP contract/RLS tests |
| new `scripts/supabase/reset.sh`, `scripts/supabase/test.sh`, `scripts/supabase/seed.sql` | Local Supabase reset + test harness (required by PC-plan §15; nothing exists today) |

*Migration note* (decision #11, decided: **squash while pre-launch**): the
Supabase side is organized as the three canonical files above; Slice 5 folds its
ops functions into the RPCs file. Every schema-changing slice ends with a dev
DB + Storage reset via `scripts/supabase/reset.sh`. A shared hosted dev project
whose migration history predates the squash is reconciled with
`supabase migration repair` or simply reset (PC-plan policy). Client SQLDelight
stays strictly additive (local databases carry user data).

### Data model & state machine

```text
cloud_book_files.status:  upload_pending → uploading → available → deleting → (row removed/tombstoned)
                                    ↘ upload_failed ──retry──► upload_pending
```

New tables (shape):

```text
cloud_book_uploads        -- upload sessions = quota reservations
  upload_id uuid pk, cloud_book_id, cloud_user_id, media_type, relative_path, file_name,
  size_bytes, content_hash, content_hash_algorithm,
  rights_attestation jsonb,             -- {attested_at, tos_version, attestation_version}
  storage_path text, tus_upload_id text,
  status ('reserved'|'finalized'|'cancelled'|'expired'),
  expires_at timestamptz, created_at, updated_at
  unique (cloud_book_id, media_type, relative_path) where status = 'reserved'   -- partial unique

cloud_user_storage        -- quota accounting (row per account)
  cloud_user_id pk, quota_bytes bigint, reserved_bytes bigint, used_bytes bigint, updated_at

cloud_content_blocklist   -- re-upload prevention for specifically identified files
  content_hash_algorithm text, content_hash text, pk(algorithm, hash),
  reason text, created_by text, created_at

cloud_file_audit_events   -- abuse response log (no titles, contents, tokens, or signed URLs)
  id, cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
  content_hash_algorithm, content_hash, action, reason, actor, created_at
```

Storage layout (PC-plan "Storage layout"): bucket `book-files`, object key
`users/{cloudUserId}/books/{cloudBookId}/{fileId}` where `fileId` =
`cloud_book_files.id`. **No public URLs, ever.**

RPC contracts (all `security definer`, actor = `auth.uid()`, all re-verify
ownership through `cloud_books`/`cloud_book_files` — never trust client-supplied
`cloud_user_id`):

**`reserve_book_upload(cloud_book_id, media_type, relative_path, file_name, size_bytes, content_hash_algorithm, content_hash, rights_attestation)`**

1. Reject `cloud_book_not_owned` / book tombstoned.
2. Reject `content_blocked` (block-list lookup on `(algorithm, hash)`).
3. Reject `quota_exceeded` when
   `used_bytes + reserved_bytes + size_bytes > quota_bytes`; response includes
   `used_bytes`/`quota_bytes` for UI.
4. Dedupe (content hash = cross-device identity): existing `available` row with
   **same** hash for `(cloud_book_id, media_type, relative_path)` → return
   `already_available` + the existing file record, **no reservation, no second
   object**. Existing row with different hash → `file_exists` (replacement
   semantics: decision #8).
5. Otherwise: insert `cloud_book_uploads` (`reserved`, `expires_at = now() + 24h`),
   upsert `cloud_book_files` row `status = 'upload_pending'`,
   `reserved_bytes += size_bytes`, write `book_file` change event
   (status `upload_pending`), record attestation + audit event — one transaction.
6. Return `{upload_id, status, storage_path, upload_url (TUS), expires_at}`.

*Idempotency*: a repeated reserve for the same
`(cloud_book_id, media_type, relative_path, hash)` with a non-terminal session
returns the **existing** session — natural idempotence, no `sync_mutations` row
needed (mutation-ID idempotency stays where it is: metadata/position pushes).

**`finalize_book_upload(upload_id, size_bytes, content_hash)`**

- Verify session exists, owned, not expired (`upload_expired`), object present at
  `storage_path`.
- **Size is server-verified** against `storage.objects.metadata->>'size'`;
  `size_mismatch` → session `failed`, reservation released, file row
  `upload_failed`. Hash is client-computed during streaming and recorded.
  **TUS `Upload-Checksum` is not supported** by either backend's tusd engine
  (spike finding — silently ignored), so client-side hashing is the *only*
  verification: the client hash rides the TUS `metadata` round-trip and is
  recorded at finalize; it is enforced at every download (Slice 4 verifies
  before finalize-to-local).
- Re-check block-list (covers the reserve→finalize window).
- One transaction: file row → `available` (revision++), session → `finalized`,
  `reserved_bytes -= size`, `used_bytes += size`, `book_file` change event
  (status `available`), audit event.

**`cancel_book_upload(upload_id)`** — idempotent; release reservation, session →
`cancelled`, delete the never-available `cloud_book_files` row, emit `book_file`
change (status `none`), audit.

**`create_book_download(cloud_book_file_id)`** — requires `status = 'available'`,
ownership, and a block-list re-check (takedown/block must kill downloads even for
stale clients). Records audit + returns the short-lived download grant
(decision #2) with `{storage_path, size_bytes, content_hash(+algorithm),
media_type}`.

Storage policies (defense in depth; PC-plan §12 "storage ownership checks
independent of object-path naming"):

- `insert/update` on bucket `book-files` allowed only when a non-terminal
  `cloud_book_uploads` row exists with matching `storage_path` and
  `cloud_user_id = auth.uid()` — **checked against the authoritative table, not
  the path prefix**.
- `select` allowed only when `cloud_book_files` maps the object to a book owned by
  `auth.uid()` and `status = 'available'` (product path is the signed URL from
  `create_book_download`; the policy covers direct authenticated access).
- No `delete` policy for `authenticated`; abandoned-object GC and takedowns run as
  service role (on-access lazy expiry of `expires_at < now()` sessions + ops
  cron; PC-plan §7.4 step 10).

### Failure & retry semantics

- Reservation TTL (24 h) with lazy expiry at RPC touch + scheduled GC; expired
  reservations release quota and delete orphan objects.
- `finalize` is idempotent per `upload_id` (second call on a `finalized` session
  returns the prior result).
- Quota arithmetic is transactional inside the RPCs
  (reservation/commit/release), so a crashed client can never leak quota
  permanently.
- Change events are written in the same transaction as state transitions →
  change feed is the authoritative availability signal.

### Testing strategy

- pgTAP in `supabase/tests/`: reserve idempotence & dedupe (`already_available`),
  quota math (before/during), block-list rejections, finalize `size_mismatch`,
  cancel idempotence, expiry, `create_book_download` rejections.
- RLS isolation suite: cross-account denies at **table, function, and storage**
  layers (PC-plan Slice 5 exit criteria), forged `cloud_user_id` ignored/rejected,
  signed URLs scoped to owner objects.
- Harness: `scripts/supabase/reset.sh && scripts/supabase/test.sh` in CI (Gradle
  alone is not backend verification — PC-plan §15).

### Rollout & risks

- Dev Supabase reset required (migration decision #11).
- Risk: the client-side `createSignedUrl` mint path (decision #2) — validate
  with an early spike.
- Risk: storage-policy join to `cloud_book_files` on every object access — small
  tables, per-user, acceptable; index `cloud_book_files(storage_path)`.
- `supportsBookUpload` stays `false` in
  `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerCapabilities.kt`
  throughout this slice.

---

## Slice 2 — Observable sync & transfer status (PC-plan §5.3)

### Scope

Implement the PC-plan §5.3 observable status model end-to-end and define the
port through which the transfer slices (3–4) report `uploading_files` /
`downloading_files` phases and byte progress. Decision #10 pulled this forward:
transfers must report through the real status model from day one, not through a
parallel surface. Metadata/progress sync is the only status producer in this
slice; the transfer producer lands in Slice 3, wired to the same model.

- New status model (PC-plan §5.3): `Disabled / Idle(lastSuccessfulAt) /
  Running(phase, completedItems, totalItems) / Offline(pendingCount) /
  Failed(error, pendingCount, canRetry) / Completed(pushedCount, pulledCount,
  pendingCount, completedAt)` with phases `preparing, pulling, applying,
  uploading_changes, uploading_files, downloading_files, finalizing`. Unknown
  totals → indeterminate; known file transfers expose byte progress in addition
  to item progress.
- Thread phase reporting through `SyncDataRepository` + `SyncBoundedPass` (they
  know the stage order: preparing → pulling → applying → uploading_changes →
  (second pull) → finalizing).
- Retain `SyncResult.Completed` counts instead of discarding (PC-plan §10).
- Transfer status port: `FileTransferStatusSource` (observable aggregate of
  active/queued transfers + bytes), injected as `List<FileTransferStatusSource>` —
  precedent: `SyncDataRepository(destinations: List<SyncDestination>)`. The
  coordinator merges sync phase + transfer sources into one `SyncStatus` stream;
  with no sources registered (this slice's exit state) `uploading_files` /
  `downloading_files` simply never occur.
- Migrate every existing `SyncStatus` consumer to the new model (grep-driven
  list); the one-shot result-style display is replaced (PC-plan §5.3).

### Files & modules

| Path | Action |
|---|---|
| `feature/sync/domain/src/commonMain/kotlin/com/retro99/sync/domain/SyncStatus.kt` | rewrite to the §5.3 model |
| `feature/sync/domain/src/commonMain/kotlin/com/retro99/sync/domain/SyncResult.kt` | `Completed` retains pushed/pulled/pending/failed counts |
| new `feature/sync/domain/src/commonMain/kotlin/com/retro99/sync/domain/FileTransferStatusSource.kt` | transfer port: `data class FileTransferStatus(phase, activeItems, totalItems, bytesTransferred, totalBytes?)` + `fun observe(): Flow<FileTransferStatus?>` |
| `feature/sync/data/src/commonMain/kotlin/com/retro99/sync/data/SyncDataRepository.kt` | phase emission; merge `List<FileTransferStatusSource>` into the status flow |
| `feature/sync/data/src/commonMain/kotlin/com/retro99/sync/data/SyncBoundedPass.kt` | phase callbacks (preparing/pulling/applying/uploading_changes/finalizing) |
| `feature/sync/domain/src/commonMain/kotlin/com/retro99/sync/domain/usecase/ObserveSyncStatusUseCase.kt` | signature unchanged; consumers migrate |
| `feature/cloud-account/ui/src/commonMain/kotlin/com/retro99/cloudaccount/ui/CloudAccountScreen.kt`, `CloudAccountViewModel.kt` (+ remaining `SyncStatus` consumers) | render new states, phases, counts |

### Data model & state machine changes

No new tables — status is derived from live state + `sync_checkpoints`
diagnostics (`status`, `pending_mutation_count`, `last_successful_at`,
`last_error` already exist since migration 23). `SyncStatus` becomes the single
external state machine:

```text
Disabled
Idle(lastSuccessfulAt)
Offline(pendingCount)
Running(preparing|pulling|applying|uploading_changes|uploading_files|downloading_files|finalizing,
        completedItems, totalItems?)
Completed(pushedCount, pulledCount, pendingCount, completedAt)
Failed(error, pendingCount, canRetry)
```

### Failure & retry semantics

Mechanics unchanged (outbox retry/backoff untouched). `Failed` surfaces
`canRetry` from attempt state and `pendingCount` from
`SyncOutboxPreflight.pendingCount`. A transfer source in `failed` state
contributes to `Failed(...)` and per-book detail — never silently dropped.

### Testing strategy

Extend the existing `feature/sync/data/src/commonTest` suites in place (same
conventions: `kotlin.test` + `runTest`, per-file fakes, event-order lists):

- `SyncBoundedPassTest` — assert phase order via the existing `events`-list style.
- `SyncDataRepositoryTest` — status emission per state; counts retained in
  `Completed`; transfer-source merging (fake `FileTransferStatusSource` yields
  `uploading_files` with byte progress); `canRetry`/`pendingCount` on `Failed`.
- Domain tests for merge precedence (active transfer vs idle sync, multiple
  sources).

### Exit criteria

- Metadata sync reports phases + counts end-to-end in the UI.
- A fake `FileTransferStatusSource` produces `uploading_files` /
  `downloading_files` with byte progress in tests.
- All `SyncStatus` consumers migrated;
  `./gradlew :feature:sync:domain:allTests :feature:sync:data:allTests :composeApp:assembleDebug`
  green.

### Rollout & risks

- Touches every `SyncStatus` consumer — start with a grep-driven migration list;
  product is pre-launch, so cut over fully (no adapter strata).
- PC-plan's dedicated server-details screen is out of scope here (PC-plan Slice 2
  territory); `CloudAccountScreen` is the status surface in this plan.

---

## Slice 3 — Client: media-resource model, durable transfer store, upload state machine, Back up UI

### Scope

- PC-plan §5.2 **media resource model** — stop overloading `ebookFilepath` (which
  today means "server-relative API path" for Storyteller/Audiobookshelf and
  "local absolute path" for Local; `ServerBooksRepository.kt:55-58`). New explicit
  model; legacy servers unchanged.
- Durable client state: mirror of cloud file status + a `cloud_file_transfers`
  transfer table (outbox-grade durability).
- Server-neutral **transfer seam**: a shared `BookFileTransferEngine` (durable
  state machine, retry, verification) over a per-server `FileTransferTransport`
  with `TransferTransportCapabilities` — mirroring the established
  `ProgressSyncTransport` / `ProgressTransportCapabilities` pattern
  (`feature/sync/domain/src/commonMain/kotlin/com/retro99/sync/domain/ProgressSyncTransport.kt`).
  Parrot Cloud uses the `ByteOffset` resume mode; the record is shaped as a
  tri-state resume model (`None`/`ChunkIndexed`/`ByteOffset`) plus
  `supportsClientSuppliedId`, `supportsReplaceInPlace`, and request-size hints —
  see "Forward compatibility" for why.
  The Parrot Cloud transport is deliberately *not* built on `NetworkClient`
  (that one is server-token/bearer + baseUrl-relative,
  `lib/network/implementation/.../ServerHttpClientFactory.kt`; cloud transfer auth
  is the Supabase session and URLs are absolute). Future Storyteller/Audiobookshelf
  transports plug into the same seam (see "Forward compatibility" below).
- Upload state machine: reserve → stream upload to object path → finalize →
  change-feed/pull confirms `Available`. Verification split (Slice 1 contract):
  **size is server-verified at finalize**; the **content hash is computed
  client-side during streaming and recorded** at finalize (enforced at every
  download; server-side checksum impossible — `Upload-Checksum` unsupported on
  both backends per spike findings). Survives process death; cancel/retry mapped
  to `RemoteFileAvailability`.
- Auto-backup hook (decision #7): when the opt-in Sync & Backup toggle is on and
  an attestation exists, imports enqueue uploads automatically; the bulk
  **Back up all existing** action queues transfers for every not-yet-backed-up
  book. The **Replace backup** action (decision #8) chains delete → upload
  behind one confirm for `file_exists` conflicts.
- Back up / progress / cancel / retry UI + the minimal rights-attestation
  checkbox in the backup dialog (enforcement dependency for Slice 5's attestation
  program).
- Applying `book_file` change-feed events.
- The upload engine reports `uploading_files` phase + byte progress through the
  `FileTransferStatusSource` port (Slice 2) — transfer state is also rendered
  per-book from the durable table.

**Upload transport**: one in-repo **TUS client** shared across backends
(decision #1). TUS is spoken by both Supabase Storage (`/upload/resumable`) and
Storyteller (`POST /api/v2/books/upload` + `PATCH` at `Upload-Offset`), so a
single ~200–300-line client (`TusUploadSession`: `POST` create → `Location`,
`HEAD` → `Upload-Offset`, `PATCH` chunk) serves Parrot Cloud now and Storyteller
later. Byte-offset resume (persist `tus_upload_url` + offset in
`cloud_file_transfers`; resume after process death), per-chunk retry, server-side
assembly at completion. Chunked re-try (restart the whole transfer per attempt)
is kept as the **non-resumable transport mode** — required later for
Audiobookshelf's one-shot multipart `POST /api/upload` — but is not acceptable as
the only mode: it is O(file) per drop and effectively unuploadable for
audiobook-sized media. supabase-kt `storage-kt` is still added, but for
`createSignedUrl`/bucket administration only, not as the transfer engine.

**Logical, not physical, temp path** (decision #3; deviation from PC-plan §7.4
step 5 "upload to a temporary object path"): the TUS endpoint materializes the
object at `users/{uid}/books/{bookId}/{fileId}` only on completion, and Supabase
Storage has no atomic server-side object move callable from SQL. The temp/final
distinction is preserved **logically**: nothing is downloadable until
`status = 'available'` (storage policy + `create_book_download` both gate on it).
Abandoned uploads are GC'd by session expiry.

**Multi-server safety**: every `cloud_file_transfers` row is keyed by `server_id`
(target server). The engine routes to the `FileTransferTransport` registered for
that server and gates behavior by `TransferTransportCapabilities` (e.g. the
resume mode); cancel, retry, and recovery operate strictly within one server's
transport and rows. Parrot Cloud transfers never route through
`BookDownloadManager`/`EbookFileDownloader`, and Storyteller/Audiobookshelf
downloads never touch the transfer engine — a failure or cancellation in one
transport cannot mark another server's work failed.

### Files & modules

| Path | Action |
|---|---|
| `gradle/libs.versions.toml` | add `supabase-storage` artifact (`io.github.jan-tennert.supabase:storage-kt`, same `supabase = "3.4.1"` ref) |
| `lib/cloud/implementation/build.gradle.kts` | add storage dependency |
| `lib/cloud/implementation/src/commonMain/kotlin/com/retro99/cloud/implementation/SupabaseClientProvider.kt` | `install(Storage)` alongside `Postgrest`/`Auth` |
| new `lib/cloud/implementation/src/commonMain/kotlin/com/retro99/cloud/implementation/transfer/CloudFileTransferClient.kt` (+ `TusUploadSession.kt`, `TusUploadClient.kt`) | Parrot Cloud transfer transport: shared TUS client (`POST`/`HEAD`/`PATCH`, offset resume) + streaming download, **independent of `NetworkClient`/`ServerCredentials`** (Supabase session auth, absolute/signed URLs); uploads from a local path with progress. `TusUploadClient` is written server-neutral (bucket/endpoint/auth injected) for reuse by a future Storyteller transport |
| `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerBooksRepository.kt` | add `mediaResources: List<MediaResource>` to `ServerBook` |
| new `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/MediaResource.kt` | `data class MediaResource(mediaType: String, localPath: String?, remoteAvailability: RemoteFileAvailability, size: Long?, contentHash: String?, contentHashAlgorithm: String?)` (PC-plan §5.2 fields + `contentHashAlgorithm`, per the hash-pair rule of PC-plan §3.3) |
| `lib/server/api/src/commonMain/kotlin/com/retro99/server/api/ServerCapabilities.kt` | capability source; flip `supportsBookUpload/BookDownload/BookDeletion` for `ServerType.ParrotCloud` only behind the server-controlled flag (Slice 5 enables) |
| new `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/CloudBookFile.sq`, `CloudFileTransfer.sq` + `24.sqm`; `lib/database/implementation/build.gradle.kts` (`version = 24`) | durable client state (shapes below) |
| new `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/cloudfiles/CloudBookFileEntity.kt`, `CloudFileTransferEntity.kt`, `CloudFilesDatabase.kt` | API surface in the style of `SyncOutboxDatabase` |
| new `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/dao/cloudfiles/CloudFilesSqlDelightDao.kt` + `CloudFilesDatabaseImpl.kt` | transaction helpers in the style of `dao/importedbooks/ImportedBooksSqlDelightDao.kt` |
| new `lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudBookFileService.kt` + `ParrotCloudBookFileModels.kt` | Postgrest RPC wrappers/wire models (pattern: `ParrotCloudLibraryMutationSyncTransport.kt`) |
| `lib/server-parrot-cloud/src/commonMain/kotlin/com/retro99/server/parrotcloud/ParrotCloudSyncAdapter.kt`, `ParrotCloudSyncPageAdapter.kt` | dispatch new `entity_type = 'book_file'` changes → new `ParrotCloudBookFileChangeApplier.kt` |
| new `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/BookFileTransferManager.kt` | transfer interface (sits beside `FileImportManager.kt`) |
| new `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/StartBookFileUploadUseCase.kt`, `BackupAllBooksUseCase.kt`, `CancelBookFileTransferUseCase.kt`, `RetryBookFileTransferUseCase.kt`, `ObserveBookFileTransferUseCase.kt` | explicit transfer use cases (PC-plan §9: not hidden inside `saveBook()`); `BackupAllBooksUseCase` implements decision #7's bulk action |
| new `feature/books/data/src/commonMain/kotlin/com/retro99/books/data/transfer/BookFileTransferEngine.kt` | upload state machine + retry scheduling + incremental hash during streaming (`Sha256Digest` in `feature/books/data/.../ContentHash.kt`; whole-file `calculateFileContentHash` for verification passes); transport-agnostic — drives a `FileTransferTransport` seam (server-neutral, capability-flagged like `ProgressTransportCapabilities`) so Storyteller/Audiobookshelf transports can plug in later |
| `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/model/BookDomainModel.kt`, `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/model/ServerBookMapper.kt` | map `mediaResources` into the domain model |
| `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/BookDetailScreen.kt`, `BookDetailViewModel.kt`, `BookDetailIntent.kt`, `BookDetailViewState.kt` | Back up action + progress + cancel + retry + **Replace backup** (decision #8: `file_exists` exit chaining delete → upload behind one confirm) + attestation checkbox (dialog pattern: `DeleteLocalBookConfirmationDialog` at `BookDetailScreen.kt:1159`; intent pattern: `OnDeleteLocalBookClicked/Confirmed/Dismissed` at `BookDetailViewModel.kt:120-131`) |
| `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/model/BookUiModel.kt`, `BookUiModelMapper.kt` | surface media-resource states |
| `translations/src/commonMain/composeResources/values/strings.xml` | new `cloud_backup_*` keys (snake_case convention, `tools:ignore="MissingTranslation"`) |
| `composeApp/src/commonMain/kotlin/com/retro99/parrot/SyncTriggerBridge.kt` / new `TransferRecoveryInitializer` (`AppInitializer` binding, pattern `SyncTriggerInitializer`) | process-death recovery pass |

### Data model & state machine changes

```text
cloud_book_file_state                 -- local mirror of cloud_book_files (change feed + finalize ack)
  library_book_id TEXT, cloud_book_id TEXT, cloud_book_file_id TEXT,
  media_type TEXT, relative_path TEXT, file_name TEXT,
  status TEXT,                       -- → RemoteFileAvailability (per file)
  size_bytes INTEGER, content_hash TEXT, content_hash_algorithm TEXT,
  remote_revision INTEGER, updated_at TEXT
  PRIMARY KEY (library_book_id, media_type, relative_path)

cloud_file_transfers                  -- durable transfer sessions (survives process death)
  transfer_id TEXT PK, server_id TEXT,  -- target server (parrot-cloud today)
  direction TEXT ('upload'|'download'),
  library_book_id TEXT, cloud_book_id TEXT, cloud_book_file_id TEXT, media_type TEXT,
  local_source_uuid TEXT,             -- upload source imported_book uuid
  staging_path TEXT,                  -- download .part path (Slice 4)
  size_bytes INTEGER, bytes_transferred INTEGER,
  content_hash TEXT, content_hash_algorithm TEXT,
  tus_upload_url TEXT,                -- upload resume anchor
  tus_expires_at TEXT,                -- from Upload-Expires; 404/410 → recreate session (spike finding)
  rights_attestation TEXT, state TEXT,
  attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, last_error TEXT,
  created_at TEXT, updated_at TEXT
```

Durable transfer state machine (states map onto the existing enum for UI; with
per-file rows — decision #5 — the book-level `RemoteFileAvailability` shown in
the library/detail UI is an **aggregate over the book's file rows**:
any `Uploading`/`UploadPending` wins over `Available`, any `Deleting` wins over
all, `Available` only when every file row is `Available`; today's single-epub
books have exactly one row so the aggregate is the row):

| `cloud_file_transfers.state` | `RemoteFileAvailability` shown | Notes |
|---|---|---|
| `pending` | `UploadPending` | reserved or awaiting retry eligibility |
| `transferring` | `Uploading` | TUS PATCH in flight; offset persisted after each chunk |
| `verifying` / `finalizing` | `Uploading` | finalize RPC in flight |
| `completed` | `Available` | set from finalize ack; **confirmed/revised by change feed** (server is authoritative) |
| `failed` | `UploadFailed` | permanent rejection or attempts exhausted; user Retry → `pending` |
| `cancelled` | `None` | `cancel_book_upload` issued |

```text
Pending ──start──► Reserving ──► Uploading(TUS) ──► Verifying ──► Finalizing ──► Completed
   ▲                  │               │  transient error                          │
   └──retry───────────┴───────────────┘ (backoff, resume at offset)               │
                       permanent (quota_exceeded / content_blocked / size_mismatch)
                       ──► Failed ──user retry──► Pending
Any non-terminal ──cancel──► Cancelled (+ cancel_book_upload) ──► None
Completed ──change feed deleting──► Deleting ──change feed──► None   (Slice 5 drives this edge)
```

- Dedupe (constraint: content hash = cross-device identity): device B importing
  the same file resolves the same `library_book_id` (`"$algorithm:$hash"`) and
  `cloud_book_id` (via `library_books.cloud_book_id` + change feed), sees
  `cloud_book_file_state.status = available`, and **skips upload entirely**;
  `reserve_book_upload`'s `already_available` is the server-side backstop. One
  `cloud_book_id`, one stored object per account. **Per-account only** —
  cross-account dedupe of copyrighted bytes is an explicit non-goal (PC-plan
  Non-goals).
- Progress-only → backed-up upgrade keeps `cloud_book_id` and position untouched
  (PC-plan §7.4).

### Failure & retry semantics

- Retry scheduling mirrors `LibraryMutationSyncEngine.scheduleRetry`
  (`feature/sync/data/.../LibraryMutationSyncEngine.kt:96-109`): exponential
  `2^min(attemptCount, 6)` seconds, server `retryAfterMillis` wins when present;
  eligibility via `next_attempt_at`, same shape as `sync_outbox`'s
  `getEligibleMutations`.
- Transient (IO/timeout/5xx): stay in `transferring`, resume TUS at persisted
  offset (`HEAD` → `Upload-Offset` on restart). Upload-URL expiry (Supabase sends
  `Upload-Expires`; afterwards `HEAD`/`PATCH` → 404/410) → recreate the TUS
  session under the same reservation and re-upload; persist `tus_expires_at`
  (spike finding: Storyteller sessions never expire; Supabase's real window must
  be measured live). Offset conflict (409) → adopt the server's `Upload-Offset`
  and continue.
- Permanent (contract rejections: `quota_exceeded`, `content_blocked`,
  `size_mismatch`, `file_exists`): `failed` with reason surfaced in UI; no
  auto-retry. `file_exists` additionally offers the **Replace backup** exit
  (decision #8: chained delete → upload).
- Process death: recovery pass resets `verifying`/`finalizing` to resumable
  points (re-call `finalize_book_upload` — idempotent; re-`HEAD` TUS session) and
  re-queues `pending` rows eligible by `next_attempt_at`.
- Cancel: cooperative — abort TUS, delete partial state, call
  `cancel_book_upload`, transition `cancelled` (late progress after cancel is
  dropped, mirroring `DownloadStateHolder.cancelledDownloads` semantics but
  durable).

### Testing strategy

Follow `feature/sync/data/src/commonTest` conventions exactly: `kotlin.test` +
`runTest`, **per-file private fakes** implementing `CloudFilesDatabase`, the RPC
service, and the transfer client with recorded calls, `CompletableDeferred` gates
for interleavings (pattern: `SyncDataRepositoryTest.kt`), event-order lists
(pattern: `SyncBoundedPassTest.kt`). No shared base classes.

- new `feature/books/data/src/commonTest/kotlin/com/retro99/books/data/transfer/BookFileTransferEngineTest.kt` — happy path, transient retry w/ backoff, offset resume, permanent rejection mapping, cancel, process-death recovery, dedupe skip.
- new `lib/server-parrot-cloud/src/commonTest/.../ParrotCloudBookFileChangeApplierTest.kt` (pattern: `ParrotCloudLibraryMutationApplierTest.kt`) — `book_file` events map to `RemoteFileAvailability`; remote application never enqueues mutations.
- SQLDelight query tests in `lib/database/implementation/src/androidHostTest` with `JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)` (pattern: `LibraryBookQueriesTest.kt`, `SyncCheckpointDatabaseTest.kt`).
- Hash plumbing reuses `feature/books/data/src/commonTest/.../ContentHashTest.kt` vectors.

### Rollout & risks

- Ships dark: `supportsBookUpload` remains `false`; Back up UI renders only when
  capability + server flag are on (Slice 5 flips).
- Risks: the in-repo TUS client must match both servers' tusd dialects (offset
  mismatch recovery, session-URL lifetime) and needs platform file-source glue on
  Kotlin/Native/iOS (decision #1; the protocol spike validates these). iOS upload
  longevity is in-process only — acceptable per PC-plan non-goal "depending on
  background execution for correctness" (durable state lets the next foreground
  run finish the job).

---

## Slice 4 — Client: download / restore, verified finalizer, second-device open

### Scope

- Download path: `create_book_download` (authorize + audit + metadata) →
  client-minted short-lived signed URL via `createSignedUrl` (decision #2) →
  streaming download with **HTTP Range resume** → SHA-256 verify → **atomic
  finalizer** (move into app-managed storage + create associations) — PC-plan
  §7.5 and PC-plan Slice 6.
- **Storage placement** (decision #4): cloud-downloaded files are **durable local
  replicas in the import store** — Android `filesDir/ebooks`, iOS
  **`NSDocumentDirectory/ebooks`** (the `IosFileImportManager` dir), *not*
  `NSCachesDirectory`. Rationale: the finalizer creates `imported_books` +
  `local_book_files` rows pointing at `filePath`; purgeable caches would strand
  those rows at random (iOS purges `Caches` without warning), and "restore" is a
  user-elected durable download, semantically an import. (On Android the
  distinction collapses — the reader cache is `filesDir/ebooks`, the same
  directory as the import store — so the decision is iOS-shaped; deletion flows
  resolve by path and must not assume separate namespaces.) Transfers stage in a
  temp area (`cacheDir` / `NSTemporaryDirectory`) as `{name}.part` (staging
  precedent: `TtsModelManager`'s `.part` pattern) and only move into place after
  hash verification — exit criterion "cancellation and process interruption do
  not leave readable partial files" falls out of verify-then-move.
- Second-device open fix: after finalize the book gains a local replica and must
  open — today a Parrot Cloud book fails with `"Book has no $bookType file"`
  (`feature/reader/domain/.../usecase/InitializeReaderUseCase.kt:80` via
  `ServerBookMapper` producing a `StorytellerBook` with null `filepath`).
- **Don't break Storyteller/Audiobookshelf**: `EbookFileDownloader`,
  `DownloadPathExt.parseDownloadPath`, `BookDownloadManager`/
  `DownloadForegroundService` and their `ServerNetworkClientProvider` bearer path
  are untouched; Parrot Cloud downloads run through `BookFileTransferEngine`
  (routing keyed off `ServerType.ParrotCloud`/`mediaResources`). Parrot Cloud
  cannot reuse `parseDownloadPath`: it joins the path against the per-server
  `baseUrl` and re-emits query params (`URLBuilder(baseUrl).path(path)` in
  `KtorNetworkClient`), which breaks for signed absolute URLs — hence the
  separate transfer path. `PrepareEbookUseCase`/
  `ReaderDataRepository.prepareEbook` stays cache-only (its "Ebook not cached"
  error targets `BookDownloadManager`-cached server books); the restored book
  resolves as a `LocalBook` (`initializeImportedBook` reads `filePath` directly)
  and never hits it.
- "Remove download" (delete local replica + bytes, keep Cloud data) as a deletion
  verb.
- The download engine reports `downloading_files` phase + byte progress through
  the `FileTransferStatusSource` port (Slice 2).

### Files & modules

| Path | Action |
|---|---|
| `lib/cloud/implementation/.../transfer/CloudFileTransferClient.kt` | download side: signed-URL streaming to staging path with `Range:` resume + progress |
| `lib/server-parrot-cloud/.../ParrotCloudBookFileService.kt` | `create_book_download` call |
| new `feature/books/data/src/commonMain/kotlin/com/retro99/books/data/transfer/DownloadFinalizer.kt` | verify-then-move + association transaction |
| `feature/books/data/src/commonMain/kotlin/com/retro99/books/data/source/ImportedBooksRoomDataSource.kt`, `lib/database/api/.../ImportedBooksDatabase.kt`, `lib/database/implementation/.../dao/importedbooks/ImportedBooksSqlDelightDao.kt` | add `saveRestoredBookWithLibraryMapping(...)` variant of `upsertImportedBookWithLibraryMapping` (same 4-table transaction; **skips the `library_book` outbox enqueue** when the canonical row is already synced — unlike import, restore must not create upload loops); extend `imported_books` (migration `24.sqm`) with `origin TEXT ('import'\|'cloud_download')` + `cloud_book_file_id TEXT` for provenance (takedown invalidation in Slice 5, "Remove download") |
| `feature/books/domain/.../usecase/GetBookByUuidUseCase.kt`, `feature/books/domain/.../model/ServerBookMapper.kt`, `feature/reader/domain/.../usecase/InitializeReaderUseCase.kt` | replica selection: canonical book with a local file replica maps to `BookDomainModel.LocalBook` and opens via `initializeImportedBook` (PC-plan §3.3); a Parrot Cloud book without a local replica renders the Download state instead of erroring |
| `feature/books/ui/.../detail/BookDetailViewModel.kt` / `BookDetailScreen.kt` | Download / progress / cancel routed to `BookFileTransferManager` for Parrot Cloud (`DownloadMediaUseCase`+`BookDownloadManager` path unchanged for other servers); "Remove download" action |
| `feature/books/data/.../transfer/BookFileTransferEngine.kt` | download half of the state machine (`pending → transferring → verifying → finalizing → completed`, `failed`, `cancelled`) |

### Data model & state machine changes

Download states ride the same `cloud_file_transfers` table
(`direction = 'download'`); `staging_path` holds the `.part`;
`bytes_transferred` drives resume. Remote availability
(`RemoteFileAvailability`) is unchanged by downloads — it describes the *remote*
replica; per-book download progress is local transfer state (PC-plan §10
"No | Downloading | Show progress and cancel" is a local UI state). The
`MediaResource` model gains `localPath` population after finalization.

Finalizer transaction (atomic from the user's perspective):

1. verify SHA-256 of staged file against `content_hash` from
   `create_book_download` (`sha-256-v1` — same `calculateFileContentHash` used at
   import, so cross-device identity is exact);
2. move `.part` → `filesDir/ebooks` / `Documents/ebooks` as a fresh
   `{uuid}_{bookType}.epub` (imported-file UUID stays device-local — PC-plan
   §3.3);
3. DB transaction: `imported_books` (with `origin='cloud_download'`,
   `cloud_book_file_id`) + `local_book_files` + link to existing `library_books`
   row (by `cloud_book_id`, fallback `(algorithm, hash)`) + transfer `completed`;
   then attach the reading position **explicitly**: look up the canonical
   position by `library_book_id` (`getPositionByLibraryBookId`) and write it for
   the fresh imported-book UUID. Note: `position` is keyed by `book_uuid` with a
   nullable `library_book_id` today (`Position.sq`), so nothing attaches
   "automatically"; PC-plan §3.3's canonical position identity is a separate
   target change and is **not assumed here** (PC-plan §7.5 step 7 still holds at
   product level: the restored book reuses the existing position).

### Failure & retry semantics

- Interrupted download: `.part` + durable `bytes_transferred`; resume with
  `Range: bytes={n}-`. Signed URL expiry mid-download (`401/403`) →
  re-authorize via `create_book_download`, client-mint a fresh URL via
  `createSignedUrl`, continue at offset (signed URLs are short-lived *access
  grants*, not session state — never persisted; PC-plan §12 requires they are
  never *logged*).
- Hash mismatch or truncated file → delete `.part`, `failed` (`verify_failed`),
  retry restarts from zero.
- Cancel → delete `.part`, `cancelled`. Process death → recovery resumes at
  `bytes_transferred` or resets if staging is gone.
- Cache-purge reconciliation is unnecessary by construction (files live in the
  import store); a cheap startup reconciliation dropping `imported_books` rows
  whose `filePath` vanished remains a safety net.

### Testing strategy

Same conventions as Slice 3 (`feature/sync/data/src/commonTest` style). New:

- `BookFileTransferEngineTest` download cases — resume at offset, URL-expiry
  re-authorize + re-mint + resume, hash-mismatch rejection **and** assertion that
  no file exists at the final path after any failure, cancel leaves no partials.
- `DownloadFinalizerTest` — association transaction correctness (imported row +
  local_book_file + library link, no outbox enqueue for already-synced canonical
  rows), local dedupe: finalizing when a local import with the same content hash
  exists attaches instead of storing duplicate bytes; position lookup by
  `library_book_id` is written for the fresh UUID (no implicit attachment).
- Replica-selection tests for `GetBookByUuidUseCase`/mapper (local replica wins
  for Open; missing replica → Download state, never `"Book has no … file"`).
- Manual two-device matrix (PC-plan §15): Android↔Android, iOS↔iOS,
  Android↔iOS, offline edits, interrupted transfers, account switching.

### Rollout & risks

- Depends on S1 (`create_book_download`) and S3 (transfer store/engine).
- Risks: signed-URL TTL vs very large media (mitigated by re-mint + Range; TTL
  tuning in decision #6); multi-file audiobooks have no cloud packaging yet
  (decision #5 — downloads are single-object epubs today, matching the import
  flow); iOS background download (URLSession background) deferred — parity with
  `BookDownloadManagerImpl` iOS behavior today.

---

## Slice 5 — Abuse & copyright: attestation & ToS, takedown, block-list ops, audit, release gate

### Scope

The first-class abuse slice: full attestation/ToS capture program, admin takedown
path with client-side invalidation, block-list operations, audit retention for
abuse response, user "Delete Cloud backup", quota visibility, and the legal
release gate that enables file backup (PC-plan §12).

**Safe-harbor posture** (product constraints, not legal advice): private per-user
storage only; no public sharing, links, discovery, or indexing; no cross-account
content dedupe; no content fingerprinting, matching against external databases,
or proactive scanning (explicit **non-goals**); takedown is reactive to identified
files via hash block-list. Legal review of user-provided copyrighted files, DRM,
platform terms, takedown process, and retention obligations gates production
enablement.

### Files & modules

| Path | Action |
|---|---|
| folded into the canonical RPCs migration file (decision #11) | `admin_takedown_book_file(cloud_book_file_id, reason, actor)` + `admin_block_content_hash(algorithm, hash, reason, actor)` + `admin_unblock_content_hash(algorithm, hash, reason, actor)` (service-role only), user `delete_book_file(cloud_book_file_id)`, `complete_book_file_deletion(cloud_book_file_id)`, and `get_storage_usage()` RPC |
| `scripts/supabase/ops/takedown.sh` | service-role ops wrapper (find by content hash or file id, remove via Storage API, and finalize deletion) |
| `docs/parrot-cloud-abuse-runbook.md` (draft exists) + `docs/parrot-cloud-legal-text-draft.md` (complete draft; §F positions resolved) | finalize documented abuse & takedown process (required before public availability — PC-plan §12), incl. log retention; close the §6 reinstatement gap; legal copy goes through counsel verification & sign-off (legal text §F resolved positions, §I constants) before enablement |
| `lib/server-parrot-cloud/.../ParrotCloudBookFileChangeApplier.kt` + `feature/books/data/.../transfer/` | takedown invalidation: `deleting`/`removed` events → `RemoteFileAvailability.Deleting` → **delete app-provisioned replica bytes + reader cache**, flip to `None` |
| `feature/books/ui/.../detail/BookDetailScreen.kt`, `BookDetailViewModel.kt` | "Delete Cloud backup" action + confirm (keeps metadata/progress) — distinct from "Remove download" (S4) |
| new `feature/cloud-account/domain/src/commonMain/kotlin/com/retro99/cloudaccount/domain/UploadRightsAttestationRepository.kt` (+ data impl beside `SupabaseCloudAccountDataRepository.kt`) | versioned attestation record per `(localProfileId, cloudUserId)` — `{attested_at, tos_version, attestation_version}` |
| `feature/cloud-account/ui/.../CloudAccountScreen.kt`, `CloudAccountViewModel.kt` | ToS acceptance + auto-backup enablement + storage usage/quota row (dialog pattern: `LinkProfileConfirmationDialog` at `CloudAccountScreen.kt:217`) |
| `feature/books/ui/.../list/BooksListScreen.kt`, `BooksListViewModel.kt` | import-time attestation touchpoint |
| `lib/server/api/.../ServerCapabilities.kt` + server-controlled flag resolution (`CloudConfiguration`/`lib/cloud/implementation`) | enable `supportsBookUpload/BookDownload/BookDeletion` after the gate |

### Upload-rights attestation & ToS — exact UI/flow touchpoints

Legal text is out of scope here; these are the mechanics:

1. **Manual backup** — `feature/books/ui/.../detail/BookDetailScreen.kt` /
   `BookDetailViewModel.kt`: new `OnBackupClicked / OnBackupConfirmed /
   OnBackupDismissed` intents (mirroring `OnDeleteLocalBookClicked…` at
   `BookDetailViewModel.kt:120-131`). The backup confirmation dialog contains the
   rights attestation checkbox; Confirm is disabled until checked. Confirmed →
   record attestation → `StartBookFileUploadUseCase` sends it as
   `rights_attestation` on `reserve_book_upload`.
2. **Import flow** — `feature/books/ui/.../list/BooksListScreen.kt` (FileKit
   picker + FAB, `BooksListIntent.OnImportBook`) →
   `BooksListViewModel.importBook()`: when auto-backup is armed and no attestation
   exists, show the attestation dialog before enqueueing the upload (import
   itself remains local-first and is never blocked).
3. **ToS at account creation** — `feature/cloud-account/ui/.../CloudAccountScreen.kt`
   registration form → `CloudAccountRepository.register(...)`: ToS acceptance
   required to submit.
4. **Auto-backup enablement** — `CloudAccountScreen` (Sync & Backup; entry
   `AppSettingsScreen.kt:337`): enabling auto-backup requires the same
   attestation + records the current `tos_version`; re-attestation is demanded
   only when `attestation_version`/`tos_version` advances.
5. **Record** — `UploadRightsAttestationRepository` (per linked profile/account),
   serialized into `cloud_book_uploads.rights_attestation` so every stored object
   is tied to an attestation for abuse response.

### Takedown path (ops → clients)

`admin_takedown_book_file(cloud_book_file_id, reason, actor)` — service role only:

1. delete the Storage object (bucket `book-files`, path from
   `cloud_book_files.storage_path`);
2. `cloud_book_files.status = 'deleting'` (then remove/tombstone the row) and
   block future uploads of this hash (optionally auto-insert into
   `cloud_content_blocklist`);
3. emit `book_file` change event (status `deleting`/`removed`) in the same
   transaction — clients pull it on next sync;
4. write `cloud_file_audit_events` rows (`action='takedown'`, reason code, actor).

Client invalidation on receiving `deleting`/`removed`: set
`RemoteFileAvailability.Deleting` → `None`, and **delete app-provisioned copies** —
files with `imported_books.origin = 'cloud_download'` matching
`cloud_book_file_id` (bytes + rows, via the "Remove download" machinery) and any
reader cache entries (on Android the reader cache is the import-store directory —
resolve by path, don't assume separate namespaces). **User-imported originals
are not deleted** (decision #9) —
they are the user's own files outside the service's custody and are not "cached
copies" of our storage. Local transfer rows for that file are cancelled.

**Logs for abuse response** (`cloud_file_audit_events`, retained per runbook):
who (cloud user id) uploaded which `content_hash(+algorithm)` when, attestation
reference/version, download grants (count/time, **never the URL**),
cancellations, takedowns (reason + admin actor), block-list changes. Client
analytics for transfers must not log titles, contents, tokens, or URLs (PC-plan
§12) — audit existing `BookDownload*`/`BookCacheDeleted` analytics events in
`BookDetailViewModel.kt` and strip anything sensitive.

### Block-list by content hash

`cloud_content_blocklist (algorithm, hash) → reason, created_by, created_at`;
managed by `admin_block_content_hash` / ops script. Enforcement (Slice 1) at
**all three gates**: `reserve_book_upload` (re-upload prevention),
`finalize_book_upload` (closes the reserve→finalize window),
`create_book_download` (kills access for already-stored files until takedown
completes). Block-list lookups always carry `(algorithm, hash)`. Inverse:
`admin_unblock_content_hash` (reinstatement after counter-notice — runbook §6);
unblocked content re-enters via clean re-upload from the user's local copy.

### Quota

Enforcement is S1's `reserve_book_upload`. This slice adds visibility:
`get_storage_usage()` → `CloudAccountScreen` row ("Storage usage and quota when
file backup exists", PC-plan §10), plus clear `quota_exceeded` error copy in the
Back up flow.

### Data model & state machine

Server-side table shapes (`cloud_content_blocklist`, `cloud_file_audit_events`,
`cloud_user_storage`) live in Slice 1's data model. The ops-driven state machine
extends the `cloud_book_files.status` chain (Slice 1) with takedown edges from
every state:

```text
upload_pending / uploading / upload_failed / available
 └─────────────────────────────────────► deleting ──► (row removed/tombstoned)
block-list: row present = blocked (no states); admin_unblock deletes the row (audited)
```

Client-side mirror: `book_file` change events drive
`RemoteFileAvailability.Deleting → None` plus replica eviction (state diagram in
Slice 3; eviction reuses the "Remove download" machinery). Reinstatement after
unblock is a clean user re-upload (local bytes survive per decision #9).

### Failure & retry semantics

- Takedown is idempotent (object already gone → still mark + event + audit).
- Client invalidation must be crash-safe: process the change event like any
  pulled change (checkpointed via `SyncPullEngine`), with eviction retried on
  next sync until rows/bytes are confirmed gone.
- Block-list enforcement failures are hard rejections (no retry) surfaced as
  `UploadFailed` with reason.

### Testing strategy

- pgTAP: admin RPCs reject `authenticated`; takedown emits exactly one
  `book_file` change; block-list defeats reserve/finalize/create_download;
  `delete_book_file` preserves `cloud_books`/`reading_positions` (PC-plan
  file-vs-metadata deletion semantics).
- Client (sync/data test style): change-applier invalidation deletes only
  `origin='cloud_download'` replicas and preserves imports; upload blocked after
  takedown; attestation repository versioning tests.
- UI: attestation dialog gating (confirm disabled until checked), ToS touchpoints
  present in import/backup/auto-backup/registration flows.

### Rollout & risks

- This slice completes the **release gate**: legal review (PC-plan §12),
  documented takedown process, store-policy review for user-uploaded copyrighted
  content. Only then flip the server-controlled flag +
  `supportsBookUpload/BookDownload/BookDeletion` for `ServerType.ParrotCloud`.
- Risks: platform (Play/App Store) policy on user-uploaded copyrighted content —
  the private-locker posture + takedown process is the mitigation; takedown
  responsiveness expectations; retention-period decisions for audit rows.

---

## Forward compatibility: uploads to Storyteller & Audiobookshelf

Out of scope for these slices, but verified facts that shaped decision #1 and the
transport seam (no implementation here):

- **Storyteller speaks TUS — and the book UUID is client-minted.** Upload =
  `POST /api/v2/books/upload` with `Tus-Resumable`, `Upload-Length`,
  `Upload-Metadata` (base64 pairs: `bookUuid`, `filename`, `filetype`,
  `collection`, `totalFiles`) → `Location` → `PATCH` at `Upload-Offset`
  (`Content-Type: application/offset+octet-stream`). **`relativePath` is NOT read
  by this route** (spike finding) — multi-file batches use flat `filename`s + a
  `totalFiles` counter (send `totalFiles`, **not** `totalAudioFiles` — internal
  key inconsistency); `relativePath` exists only on `replace-asset/upload`.
  Chunk size is **advisory only** (`GET
  /api/v2/settings/maxUploadChunkSize` ← `STORYTELLER_MAX_UPLOAD_CHUNK_SIZE`;
  larger PATCHes are accepted; units ambiguous — verify live). Metadata is
  validated **late**: bad metadata fails the *final* PATCH with `405` after all
  bytes are transferred — client must self-validate and treat hook errors as
  fatal. Upload sessions **never expire** server-side (no FileStore expiry, no
  GC of `/data/uploads`); the upload lock is single-node (`MemoryLocker`) —
  client-side single-flight per URL. **There is no REST
  "create book" call**: the client generates the UUID v4 and passes it as
  `bookUuid` in TUS metadata (missing → `405` at TUS finish); the DB record is
  materialized server-side by the scanner (`bookUuidHint`) when the upload
  finishes (multi-file: once `totalFiles` arrive, or explicitly via
  `POST /api/v2/books/upload/finalize`). `POST /api/v2/books` is a
  *server-filesystem import*, not part of the upload flow. `POST
  /api/v2/books/{uuid}/process` is **alignment processing** (readaloud sync;
  `409` unless both ebook and audiobook are attached) — not an ingest trigger.
  Deletion: `DELETE /api/v2/books/{uuid}` (add `preventReImport=true` when
  replacing). **Replace in place exists**: `…/books/{id}/replace-asset[/upload
  [/finalize]]` swaps one media asset keeping the book uuid. Auth = bearer token
  (same `ServerCredentials` model as its downloads). The shared `TusUploadClient`
  targets this endpoint as-is. Repo note: source moved
  `smoores/storyteller` (archived) → `storyteller-platform/storyteller`
  (fast-moving; pin the contract on `applications/web/src/app/api/v2/**/route.ts`
  at build time).
- **Audiobookshelf upload = one-shot multipart, and the chunked variant is
  unmerged.** Shipped surface (verified against `server/controllers/
  MiscController.js` + `ApiRouter.js`, v2.36.1): `POST /api/upload` with form
  fields `title`/`library`/`folder` (required), `author`/`series` (optional) +
  file parts (keys ignored; one request = one book → `<folder>/<author>/
  <series>/<title>`); response is **`200` with empty body — no item id** (the
  item appears after the watcher scan → future adapter needs a "resolve item
  after scan" phase). No server-side size cap, but proxies bite (nginx default
  `client_max_body_size 1m` → `413`; Cloudflare 100 MB; body timeouts) — the
  engine's non-resumable restart-retry mode must surface 413/timeout as a
  distinct "needs chunked transport" outcome. **No dedupe/idempotency** —
  same-path re-uploads merge into one item (merge hazard; upstream bug #4943);
  replace = `DELETE /api/items/:id?hard=1` + re-upload (new item id, progress
  lost). The chunked/resumable endpoints (`POST /api/upload/chunk`, `GET
  /api/upload/chunk/:uploadId/:fileIndex`, `POST /api/upload/finalize`, 5 MB
  client chunks + resume-by-skip) exist **only in open PR #5305** — unmerged, in
  no release; there is no version to target. If it merges, **feature-detect**
  (`GET /api/upload/chunk/<safeId>/0` → `200` vs `404`), don't version-gate. (And
  ignore api.audiobookshelf.org — its own banner says it is unmaintained.)
- Consequences for future slices: "upload to Storyteller/ABS" means *create a
  book on that server from a local file* (Storyteller: client-mint uuid → TUS
  per file → optional upload-finalize; ABS: one multipart per book → resolve
  item after scan) — the replica model (`serverId, serverBookId`) absorbs the
  identity mapping. Per-server replace semantics (decision #8) and multi-file
  packaging (decision #5) must be revisited per backend when those slices are
  planned.
- **Capability-record refinements** (evolve `TransferTransportCapabilities` when
  those slices land): model resume as a **tri-state** — `None` (ABS today) /
  `ChunkIndexed` (ABS post-#5305: resume = query received chunk indices) /
  `ByteOffset` (Storyteller TUS: `HEAD` → `Upload-Offset`); add
  `supportsClientSuppliedId` (Storyteller: remote id known *before* bytes move;
  ABS: none → post-scan resolution), `supportsReplaceInPlace` (Storyteller
  `replace-asset` ✅ / ABS ❌ → delete-then-upload with id change + progress
  loss warning), and `maxRequestBodyBytes`/`recommendedChunkBytes` hints for
  retry policy (ABS: unbounded server-side, ~100 MB practical proxy ceiling;
  Storyteller: TUS chunk-sized).

## Spike findings (protocol validation)

Source-level protocol research validating `TusUploadSession` against both
endpoints — conformance matrix, session-lifetime/auth/completion differences,
ranked risks, and the **live on-device spike runbook (T0–T8) with acceptance
criteria** — lives in `docs/tus-upload-session-spike-findings.md`.

Headline: **one TUS 1.0.0 client serves both backends** (same `@tus/server`
engine); per-backend adapters differ only in metadata vocabulary, auth/refresh,
expiry policy, and completion detection. Design consequences already folded into
the slices above:

- `Upload-Checksum` unsupported on both → client-side hashing is the only
  verification (hash rides the `Upload-Metadata`/`metadata` round-trip).
- Supabase upload URLs expire (`Upload-Expires`, then 404/410) → `tus_expires_at`
  column + recreate-session path; Storyteller sessions never expire.
- 409 offset conflicts → adopt server offset; `HEAD` is auth-free on Supabase
  (re-sync offsets before refreshing the JWT), auth-required on Storyteller.
- Storyteller: flat filenames + `totalFiles` (not `relativePath`/
  `totalAudioFiles`), late `405` metadata validation (client self-validates),
  advisory chunk size, single-node upload lock.

Still to prove live (half-day on devices): hosted Supabase expiry window
(1 h vs 24 h), iOS SIGKILL resume, gateway chunk-size ceiling (>6 MB through
hosted gateway), Storyteller chunk-size units. ≥100 MB tests need a Supabase
**Pro** project (Free caps files at 50 MB).

## Verification (all slices, PC-plan §15)

```bash
./gradlew :lib:server-parrot-cloud:allTests :feature:books:data:allTests :feature:sync:data:allTests :feature:reader:data:allTests
./gradlew :composeApp:assembleDebug
scripts/supabase/reset.sh && scripts/supabase/test.sh   # schema + RPC + RLS + storage
```

iOS via app/framework build (not `assembleXCFramework`); complete the two-device
matrix per release slice.

---

## Decision log

All 11 decisions are resolved (2026-09-23). The *Options* and *Recommendation*
columns record the pre-decision state for context; the *Status* column is
authoritative.

| # | Decision | Options | Recommendation | Status |
|---|---|---|---|---|
| 1 | Upload transport | (a) supabase-kt `storage.resumable` (TUS, Supabase-only) (b) in-repo minimal TUS client shared across backends (c) chunked re-try (restart-per-attempt) | (b) — Storyteller speaks TUS natively (`/api/v2/books/upload`), so one TUS client serves both backends; supabase-kt resumable is Supabase-only and would still need a second stack. (c) is the non-resumable transport mode required later for Audiobookshelf's multipart `POST /api/upload`, not the primary strategy | **decided: (b)** — in-repo shared `TusUploadClient` + non-resumable transport mode. Protocol research confirms one client serves both (same `@tus/server` engine, TUS 1.0.0 only — `docs/tus-upload-session-spike-findings.md`) |
| 2 | Download grant minting for `create_book_download` | (a) RPC authorizes + audits → client mints via Storage API `createSignedUrl` (RLS-checked) (b) Edge Function returns the signed URL (c) authenticated streaming download, no signed URL | (a); simplest correct, two-layer checks | **decided: (a)** — RPC = checks + audit + metadata; client mints via `createSignedUrl`; storage SELECT policy repeats ownership/available/not-blocklisted rules so the RPC cannot be skipped to bypass policy |
| 3 | Object staging semantics | (a) logical temp: single path, gated by `status='available'` (deviation from PC-plan §7.4 step 5) (b) physical temp object + server-side move at finalize | (a); no SQL-callable object move exists | **decided: (a)** — TUS completion = physical staging; `status='available'` gate = logical staging; `finalize_book_upload` is the one atomic transaction. Deviation from PC-plan §7.4 step 5 accepted (intended safety property preserved) |
| 4 | Where cloud-downloaded files live | (a) import store: `filesDir/ebooks` / `NSDocumentDirectory/ebooks` (b) reader cache: `NSCachesDirectory/ebooks` etc. (c) Application Support / `noBackupFilesDir` (persistent, backup-excluded) | (a); cache eviction would strand `imported_books.filePath` | **decided: (a)** — import store; provenance (`origin='cloud_download'`) distinguishes replicas logically; optional `isExcludedFromBackup` flag for large files is a later tweak, not a placement change |
| 5 | Audiobook / multi-file media packaging | (a) one object per `media_type` (container) (b) per-file rows (schema change to `cloud_book_files`; note Storyteller's TUS upload is one session per file with flat names, which favors this shape) (c) container (zip) objects | (b) | **decided: (b)** — per-file rows (`unique (cloud_book_id, media_type, relative_path)`); S3/S4 keep single-file semantics until multi-file imports exist. Deviation from PC-plan §6 accepted |
| 6 | Quota default, per-type size caps, signed-URL TTL | product values | quota ~5 GB/account, epub cap ~200 MB, media cap ~2 GB/object, URL TTL ~10–15 min + re-mint | **decided** — quota 5 GB/account (`quota_bytes` column, overridable), **no per-file size cap** (quota is the enforced bound; platform limit 500 GB/file on Pro stands behind it), signed-URL TTL 15 min. Conscious deviation from PC-plan §12 "server-enforced file-size limits" — revisit if abuse shows up |
| 7 | Auto-backup default | (a) manual Back up only (b) auto-upload on import when signed in + attested (c) auto with toggle, off by default | (c) | **decided: (c)** — opt-in toggle in Sync & Backup (off by default), attestation captured at enablement, bulk "Back up all existing"; "Wi-Fi only" refinement deferred |
| 8 | Re-upload / replace semantics for existing `(cloud_book_id, media_type, relative_path)` | (a) `file_exists`, delete first (b) reject + chained Replace UX (c) atomic replace-in-place | (a) initially — simpler, no dual-object window | **decided: (b)** — `file_exists` contract branch + "Replace backup" button chaining delete → upload behind one confirm; atomic replace-in-place deferred to the multi-file work where same-slot-different-hash becomes reachable |
| 9 | Takedown invalidation scope | (a) delete app-provisioned copies (`origin='cloud_download'`) only (b) also delete user-imported originals (c) remote access only, keep all bytes | (a); imports are user's own device files outside service custody | **decided: (a)** — delete `origin='cloud_download'` replicas (keyed by `cloud_book_file_id`) + reader cache via the "Remove download" machinery; user imports kept; metadata + progress kept in all cases |
| 10 | Transfer status UX integration | (a) separate transfer status surface (b) aggregate into `SyncStatus` phases `uploading_files`/`downloading_files` (PC-plan §5.3) | (a) now, (b) later | **decided: (b)** — full PC-plan §5.3 integration now; new Slice 2 builds the status model + `FileTransferStatusSource` port; Slices 3–4 report phases/byte progress through it |
| 11 | Supabase migration strategy | (a) squash into `20260922000000_parrot_cloud.sql` + dev reset (b) additive migrations | (a) while pre-launch (PC-plan "squash dev migrations") | **decided: (a)** — squash now into three canonical files (schema / RPCs / storage+policies; Slice 5 folds into them too), dev DB + storage reset per schema-changing slice, `supabase migration repair` or project reset for pre-squash shared-dev history. Client SQLDelight stays strictly additive |
