# Parrot Cloud upload enablement — implementation plan

## Current product decision — 2026-09-24

Book uploads are available when the current profile has a linked Parrot Cloud
account and that account is signed in. This supersedes the client-side
hard-off/server-rollout-gate assumptions in the historical plan below. The
upload transport is enabled; Book Details, Back up all, and auto-backup upload
paths require the signed-in account to match the current profile's link.
Automatic backups still honor the user's auto-backup preference, and upload
rights attestation remains part of each backup flow.

## Status

Draft implementation plan for the remaining work between today's state —
book-file transfer fully implemented but the upload transport hard-disabled
(`ParrotCloudBookFileTransferTransport.kt:31`, `supportsUpload = false`) — and
**production file backup enabled**.

It closes the open items of the release-gate checklist
(`docs/parrot-cloud-abuse-runbook.md` §9) and the enablement requirements of
PC-plan §12 ("keep file-upload capability behind a server-controlled feature
flag until this gate is complete"). Read `docs/parrot-cloud-book-file-transfer-implementation-plan.md`
("transfer-plan" below) first; this plan completes its Slice 5 leftovers and
adds the rollout mechanics it deferred.

Scope: code gaps A–D, validation E, ops enablement F, and the flip procedure G.
Legal review, store-policy review, and staffing the abuse channel are **human
gates** — tracked here (G) but not executable by this plan. Everything else in
the §9 checklist becomes either a merged feature (A–D), a test run (E), or a
cron/ops action (F) by the time this plan lands.

### Verified current state (in repo)

| Area | State | Evidence |
|---|---|---|
| Server RPCs, tables, block-list, audit, quota, GC, takedown ops | **Done** | `supabase/migrations/2026092200000{0,1,2}…`, `20260923000000…`, `2026092400000{0,1}…`; `scripts/supabase/ops/{takedown,gc-orphans,purge-audit}.sh`; pgTAP in `supabase/tests/` |
| Upload pipeline (reserve → TUS → finalize, persisted queue, retry/resume/replace) | **Done** | `BookFileTransferEngine.kt`, `CloudFileTransferClient.kt`, `TusUploadClient.kt`, `ParrotCloudBookFileTransferTransport.kt` |
| Restore pipeline + takedown invalidation (evicts `origin='cloud_download'` replicas, keeps imports) | **Done** | `ParrotCloudBookFileDownloadTransport.kt`, `DownloadFinalizer.kt`, `ParrotCloudBookFileChangeApplier.kt` + `BookFileTransferEngine.invalidateCloudFile` (:206) |
| Upload transport gate | **Hard-off** | `ParrotCloudBookFileTransferTransport.kt:31` `supportsUpload = false` ("server-controlled rollout gate", `TransferTransportCapabilities` docstring) |
| Rights attestation capture | **Inline only** | built per call site from constants in `feature/books/ui/.../CloudBackupPolicy.kt` (`CLOUD_BACKUP_TOS_VERSION = "draft-2026-09"`, `CLOUD_BACKUP_ATTESTATION_VERSION = "1"`); `BookDetailViewModel.kt:409-413`, `BooksListViewModel.kt:316-320`. No persisted record, no re-attestation logic, versions not yet real |
| Persistent `UploadRightsAttestationRepository` (transfer-plan S5 file table) | **Missing** | no such type in repo |
| ToS acceptance at registration (touchpoint B4) | **Missing** | `CloudAccountScreen.kt` `AccountFormContent` (~:300) has email/password/submit/Google, no checkbox; `CloudAccountViewState.isSubmitEnabled` gates on form validity only |
| Auto-backup toggle (B3) + import-time prompt (B2) | **Missing** | `CloudAccountIntent` has no backup intents; `BooksListViewModel.importBook()` (:290) never touches `BookFileTransferManager` |
| Server-controlled feature flag | **Missing** | `ServerCapabilities.supportsBookUpload/BookDownload/BookDeletion` (`lib/server/api/.../ServerCapabilities.kt:16-18`) exist but are consumed nowhere (only `supportsSeries` is, `AuthenticatedRepositoryProviderImpl.kt:59,66`); no flags table/RPC in `supabase/migrations/` |
| Ops scheduling of GC/purge, abuse channel | **Missing** | runbook §7/§9 checkboxes open |
| pgTAP suite actually run | **Unverified** | runbook §6/§8 note "database tests have not run here" |

Constraints verified in code that shape this plan:

- `enqueueUpload` requires synced metadata: `BookFileTransferEngine.kt:248-251`
  errors with *"Book metadata must sync before its file can be backed up"* when
  the `library_book` row or its `cloudBookId` is absent. Import-time auto-backup
  therefore cannot be a single synchronous enqueue.
- Attestation JSON shape is enforced server-side at `reserve_book_upload`
  (`20260922000001_parrot_cloud_rpcs.sql:485-490`: requires `attested_at`,
  `tos_version`, `attestation_version`) and recorded per stored object in
  `cloud_book_uploads.rights_attestation` — so a per-upload immutable attestation
  already exists; what is missing is the per-account record that decides when to
  *demand* one (transfer-plan S5 touchpoint 5).
- Preference storage is tolerant JSON (`ignoreUnknownKeys` +
  `coerceInputValues`, `lib/preferences/api/.../Preferences.kt`), so link-record
  schema can grow without a migration.
- DI uses Koin annotations (`@Single(binds = [...])`, `@Factory`, `@Provided`);
  UI state is `ViewState` + `IntentDispatcher`; strings live in
  `translations/src/commonMain/composeResources/values/strings.xml`
  (`cloud_account_*` block ~:465) consumed via generated `StringRes` +
  `resources.translations.*` imports.
- Tests: hand-rolled fakes in `commonTest` per module
  (`CloudAuthenticationUseCasesTest.kt`, `CloudProfileLinkDataRepositoryTest.kt`,
  `BookFileTransferEngineTest.kt`); pgTAP via `supabase test db`.

---

## Slice A — Persistent upload-rights attestation record

### Scope

The versioned per-account attestation record required by transfer-plan S5
touchpoint 5 and legal-draft §E: `{attested_at, tos_version,
attestation_version}` per `(localProfileId, cloudUserId)`, with re-attestation
demanded **only** when a version advances. All backup flows stop building
`UploadRightsAttestation` inline and route through this record.

### Files & modules

| Path | Action |
|---|---|
| new `feature/cloud-account/domain/.../UploadRightsAttestationRepository.kt` | interface (below) |
| new `feature/cloud-account/domain/.../model/UploadAttestationRecord.kt` | `data class UploadAttestationRecord(attestedAt, tosVersion, attestationVersion)` (account-scoped shape; the wire shape `UploadRightsAttestation` stays in `feature/books/domain`) |
| new `feature/cloud-account/domain/.../UploadBackupPolicy.kt` | `const val CLOUD_BACKUP_TOS_VERSION`, `CLOUD_BACKUP_ATTESTATION_VERSION` — **moved** from `feature/books/ui/.../CloudBackupPolicy.kt` (delete that file); values stay `"draft-2026-09"` / `"1"` until the G flip |
| `feature/cloud-account/data/.../CloudProfileLinkDataRepository.kt` (+ record) | `CloudProfileLinkRecord` gains `attestation: UploadAttestationRecord? = null`; persist via existing `PreferencesKey.CloudProfileLinks`; `unlink()` already erases the whole record (right to erasure falls out) |
| new `feature/cloud-account/data/.../UploadRightsAttestationDataRepository.kt` | impl beside `SupabaseCloudAccountDataRepository.kt` per S5 file table; reads/writes the link record's `attestation` field |
| `feature/cloud-account/domain/.../CloudProfileLinkRepository.kt`, `CloudProfileLink.kt` | `setAttestation(...)` + `attestation` on the domain model |
| `feature/books/ui/.../detail/BookDetailViewModel.kt`, `feature/books/ui/.../list/BooksListViewModel.kt` | replace inline construction (see touchpoints) |
| `feature/books/domain/.../usecase/StartBookFileUploadUseCase.kt`, `BackupAllBooksUseCase.kt` | accept `UploadAttestationRecord`, map to `UploadRightsAttestation` at the boundary (keeps `feature/cloud-account` independent of `feature/books`) |

### Interface

```kotlin
interface UploadRightsAttestationRepository {
    /** Last accepted attestation for the linked account, if any. */
    suspend fun current(localProfileId: String): UploadAttestationRecord?

    /** Stamps and persists acceptance with the current policy versions. */
    suspend fun record(localProfileId: String): UploadAttestationRecord

    /** True when no record exists or its versions lag UploadBackupPolicy. */
    suspend fun requiresReattestation(localProfileId: String): Boolean
}
```

### Semantics

- `record()` stamps `attestedAt = Clock.System.now().toString()` and the
  **current** `UploadBackupPolicy` versions; the record is overwritten only on
  fresh acceptance (each acceptance is also captured per upload in
  `cloud_book_uploads.rights_attestation`, so the audit trail keeps every
  version actually used).
- Queued transfers keep the attestation captured at enqueue time — the JSON is
  already frozen in `CloudFileTransferEntity.rightsAttestation`; a later
  version bump must not rewrite in-flight rows.
- Unlink/`deactivate` erasure: record lives in the link record → erased with it.
  Uninstall/reinstall loses it → `requiresReattestation()` returns true →
  dialogs re-ask. Acceptable (fail-safe direction).

### Exact flow touchpoints

1. **Manual backup (B1)** — `BookDetailViewModel.startBookBackup()` (:400):
   dialog keeps its checkbox on **every** use (decision #4 — the words stay at
   the point of upload). Confirm → `attestationRepository.record(...)` →
   `startBookFileUploadUseCase(..., attestation = record.toUploadRightsAttestation())`.
   Delete `CLOUD_BACKUP_*` imports (:12-13).
2. **Backup all** — `BooksListViewModel.backUpAllBooks()` (:310): same
   re wiring (:316-320).
3. **Import (B2)** and **auto-backup enablement (B3)** — Slice C.
4. **Registration (B4)** — Slice B.

---

## Slice B — ToS acceptance at registration (touchpoint B4)

### Scope

Registration requires explicit ToS + Book Backup Terms + Privacy Policy
acceptance (legal-draft §B4 copy) and records it as the account's first
attestation. Submit is disabled until checked.

### Files & modules

| Path | Action |
|---|---|
| `feature/cloud-account/ui/.../CloudAccountScreen.kt` | checkbox in `AccountFormContent` (~:300), shown when `mode == CreateAccount`, above the submit Button (:351); label per §B4 with linked ToS/Privacy spans (Compose `LinkAnnotation`/`withLink`; open via `LocalUriHandler` — no URL pattern exists in this screen yet) |
| `feature/cloud-account/ui/.../CloudAccountViewState.kt` | `tosAccepted: Boolean = false`; `isSubmitEnabled` also requires `mode == SignIn \|\| tosAccepted` |
| `feature/cloud-account/ui/.../CloudAccountIntent.kt` | `OnTosAcceptedChanged(val accepted: Boolean)` |
| `feature/cloud-account/ui/.../CloudAccountViewModel.kt` | handle intent; `submit()` (:173) and `signInWithGoogle()` (:223) require `tosAccepted` in CreateAccount mode (Google button enabled state covers the click; `submit()` keeps an assert as belt) |
| `feature/cloud-account/domain/.../usecase/RegisterCloudAccountUseCase.kt` | `invoke(email, password, tosAccepted: Boolean)`; `require(tosAccepted)` then `attestationRepository.record(...)` — enforcement lives below the UI |
| `feature/cloud-account/domain/.../model/PendingCloudAuthentication.kt` (+ repo) | ordering fix below |

### Ordering: acceptance before the link record exists

The attestation is stored in the link record, but at registration time the link
is created later (`CloudAccountViewModel.prepareAuthenticatedAccount`/`completeLinking`
:246/:309), and with email verification (`CloudRegistrationResult.AwaitingEmailVerification`)
potentially at a later session. Rule: **acceptance is recorded before any upload
can occur, and survives until the link exists.**

Mechanism — pending slot, mirroring `PendingCloudAuthentication`:

1. `RegisterCloudAccountUseCase` writes the stamped record to the existing
   `PendingCloudAuthenticationRepository` entry (new optional field
   `attestation: UploadAttestationRecord?`) before calling `register(...)`.
2. `completeLinking()` promotes it into the link record and clears it.
3. If the pending entry is consumed without promotion (verification never
   completed), the record is dropped — no account, no storage, no obligation.
4. Google OAuth in CreateAccount mode: same path via
   `PendingCloudAuthentication` (`signInWithGoogle` already saves it).

Sign-in to an existing account does not ask for ToS (acceptance binds at
account creation; re-attestation for backup is Slice A's job).

---

## Slice C — Auto-backup (touchpoints B2, B3) + import hook

### Scope

Opt-in auto-backup per transfer-plan decision #7 (default **off**):
a Sync & Backup toggle with first-enablement attestation confirmation, a
"Back up all existing books" entry, and the import-time hook (enqueue silently
when armed and attested; prompt when armed and re-attestation is due; never
block the import).

### Files & modules

| Path | Action |
|---|---|
| `feature/cloud-account/domain/.../model/CloudProfileLink.kt` + `CloudProfileLinkDataRepository.kt` record | `autoBackupEnabled: Boolean = false` (old JSON decodes via `coerceInputValues`; new writes round-trip) |
| `feature/cloud-account/domain/.../CloudProfileLinkRepository.kt` | `setAutoBackupEnabled(localProfileId, enabled)` (mirror `setSyncEnabled`) |
| new `feature/cloud-account/domain/.../usecase/EnableAutoBackupUseCase.kt` / `DisableAutoBackupUseCase.kt` | mirror `EnableCloudSyncUseCase` (active profile via `UserRegistry`); enable records an attestation (passed in or existing-valid, else `error`) and only then flips the flag |
| `feature/cloud-account/ui/.../CloudAccountScreen.kt` | Sync & Backup section (status rows ~:499): toggle `cloud_backup_autobackup_toggle`; first-enablement dialog (body `cloud_backup_autobackup_confirm_body`) with shared checkbox `cloud_backup_attestation_checkbox`; "Back up all existing books" (`cloud_backup_backup_all`) → `BackupAllBooksUseCase` with the BooksList result pattern (`BooksListViewState.kt:27-31`: queued count / error / isRunning) |
| `feature/cloud-account/ui/.../CloudAccountViewModel.kt`, `CloudAccountIntent.kt`, `CloudAccountViewState.kt` | toggle + dialog + bulk intents/states (`OnAutoBackupToggled`, `OnAutoBackupAttestationChanged`, `OnAutoBackupConfirmed/Dismissed`, `OnBackupAllExistingClicked/...`) |
| `feature/books/ui/.../list/BooksListViewModel.kt` | import hook (below) |
| new `feature/books/data/.../transfer/AutoBackupCoordinator.kt` | deferred enqueue worker (below) |

### Import hook (B2) — `BooksListViewModel.importBook()` (:290)

After `importEpubUseCase(file)` succeeds (import remains local-first and is
never blocked):

1. No active link with `autoBackupEnabled`, or
   `!bookFileTransferManager.supportsUpload(PARROT_CLOUD_SERVER_ID)` → done
   (current behavior).
2. `!attestationRepository.requiresReattestation(...)` → enqueue silently:
   `StartBookFileUploadUseCase(PARROT_CLOUD_SERVER_ID, book.uuid, record.toUploadRightsAttestation())`.
3. Re-attestation due → set `showImportBackupAttestation` with the pending
   `importedBookUuid` (dialog reuses B1 checkbox copy). Confirm → `record()` →
   enqueue. Dismiss → book stays local, can Back up manually later.

Enqueue failures never surface as import errors: they go to
`AutoBackupCoordinator`'s pending set (below) or are dropped with a
`BookImported`-adjacent analytics breadcrumb (ids/enums only, Slice E rules).

### Deferred enqueue — `AutoBackupCoordinator`

`enqueueUpload` demands a synced `cloudBookId`
(`BookFileTransferEngine.kt:248-251`), but a fresh import is queued to the sync
outbox first (`ImportedBooksRoomDataSource` :40-43) and may not have a cloud
identity yet. Auto-backup must not race it.

- Persisted pending set: `PreferencesKey.UserScoped(userId, "PendingAutoBackups")`
  — list of `localBookUuid` (+ captured `UploadAttestationRecord`, so a later
  version bump does not silently re-attest).
- Enqueue attempts at: import time, after each successful sync push
  (`SyncStatus.Completed` from `ObserveSyncStatusUseCase`), and at startup.
- Entry removal on successful enqueue, or when the imported book no longer
  exists. `enqueueUpload` is idempotent for active/completed same-hash transfers
  (`BookFileTransferEngine.kt:256-263`), so double-promotion is harmless.
- Unlink / auto-backup disabled: pending set cleared (no surprise uploads after
  the user opted out — disabling takes effect for everything not yet enqueued).

### Strings (mapping to legal-draft §B)

| Key (draft ref) | Use |
|---|---|
| `cloud_backup_attestation_checkbox` (B1, shared) | B1/B2/B3 dialogs |
| `cloud_backup_autobackup_toggle` (B3) | toggle label |
| `cloud_backup_autobackup_confirm_body` (B3) | first-enablement dialog body |
| `cloud_backup_backup_all` (B3) | bulk action |
| `cloud_account_tos_checkbox` (B4) | registration checkbox |
| `cloud_account_tos_link` / `cloud_account_privacy_link` (B4) | link spans |

All in `translations/src/commonMain/composeResources/values/strings.xml` with
`tools:ignore="MissingTranslation"` per house style. Copy comes from legal text
§B verbatim (now complete) — **release requires counsel sign-off on that
wording** (G).

---

## Slice D — Server-controlled upload flag (kill switch / rollout gate)

### Scope

PC-plan §12: upload capability behind a **server-controlled** feature flag.
After this slice, "the flip" is an ops command, not an app release — and a
remote kill switch exists if upload abuse appears in production.

### Two-level capability model

- **Transport capability** (`TransferTransportCapabilities.supportsUpload`) =
  *"the transport implements upload"*. Flips to `true` in
  `ParrotCloudBookFileTransferTransport.kt:31` as part of this slice.
- **Service flag** (`book_upload_enabled`) = *"the service has enabled upload"*.
  Defaults `false`. The engine requires **both** (`supportsUpload(serverId)` =
  capability ∧ flag), so dark-until-ops-flip is preserved and enforceable below
  the UI (`BookFileTransferEngine.kt:75-76, 238` keep their `check(...)`).

### Server

| Path | Action |
|---|---|
| new `supabase/migrations/<ts>_parrot_cloud_service_flags.sql` | `public.cloud_service_flags (key text primary key, enabled boolean not null default false, updated_at timestamptz not null default now(), updated_by text)`; seed `('book_upload_enabled', false)`, `('book_download_enabled', true)`, `('book_deletion_enabled', true)` — download/deletion defaults preserve today's behavior; RLS: `select` for `authenticated` (global flags, no user data), `revoke all ... from anon, authenticated` + `grant select` (writes are service-role only) |
| new `supabase/tests/service_flags_test.sql` | pgTAP: authenticated select ✓; anon select ✗; authenticated insert/update/delete ✗; seeds present |
| new `scripts/supabase/ops/set-feature-flag.sh <key> <on\|off>` | service-role update + prints current state (twin of `takedown.sh` style) |
| `docs/parrot-cloud-abuse-runbook.md` §7/§9 | flag runbook: who may flip, verification query, emergency kill procedure |

### Client

| Path | Action |
|---|---|
| new `lib/cloud/implementation/.../ServiceFlagsRepository.kt` | fetch via `postgrest.from("cloud_service_flags").select()` (pattern: `SupabaseCloudStorageUsageRepository.kt` :18 area); expose `current(): CloudServiceFlags` (sync read of cache), `observe(): Flow<CloudServiceFlags>`, `suspend refresh()` |
| `lib/preferences/api/.../Preferences.kt` | `data object CloudServiceFlags : PreferencesKey("CloudServiceFlags")` — cached snapshot + `fetchedAt` |
| `feature/books/data/.../transfer/BookFileTransferEngine.kt` | `supportsUpload` = capability ∧ `flags.bookUploadEnabled`; `supportsDownload`/`supportsDeletion` ∧ their flags (:75-80) |
| `lib/server/api/.../ServerCapabilities.kt` + new resolver in `lib/server/implementation/` | `ServerCapabilitiesResolver.resolve(type)` overlays flags onto `getCapabilities()` for `ParrotCloud` (`supportsBookUpload/Download/BookDeletion`); `getCapabilities()` stays the static baseline (today's only consumer is `supportsSeries`, `AuthenticatedRepositoryProviderImpl.kt:59,66`) |
| refresh triggers | cloud session restore (`CloudSessionManager`), sync execution start (`ParrotCloudSyncExecutionContextProvider`), app foreground (via `SyncTriggerBridge` → sync start covers it) |

### Semantics

- **Fail-closed for upload, last-known-good cache**: never fetched → upload
  dark; fetch fails → previous cache used; no TTL (every trigger refetches —
  cache exists for offline reads and fast `current()`).
- Flag flip affects **new enqueues only**; in-flight transfers finish.
- Old app builds (pre-slice) keep the hardcoded `supportsUpload = false` and
  stay dark regardless of the flag — acceptable: they cannot upload today
  either. Only builds containing this work light up on flip.
- UI observes the flag (`observe()` merged into the view-state flows backing
  `BooksListViewModel.kt:67` / `BookDetailViewModel.kt:360`) so the Back up
  entry appears without an app restart after the flip.

---

## Slice E — Validation

| Check | How | Closes §9 box |
|---|---|---|
| Full pgTAP suite green | `supabase start && supabase test db` — `abuse_operations_test.sql`, `account_deletion_test.sql`, `book_files_test.sql`, `orphan_gc_test.sql`, `rls_isolation_test.sql`, + new `service_flags_test.sql` | "Account deletion… validated" (§8), "Reinstatement mechanics validated" (§6) |
| Attestation contract | extend `book_files_test.sql`: `reserve_book_upload` rejects missing/malformed `rights_attestation` (already enforced `20260922000001…:485-490`; assert it) | (regression) |
| Client unit tests | extend `BookFileTransferEngineTest.kt` (flag off → `enqueueUpload`/`backupAll` reject; pending-promotion idempotence), `CloudProfileLinkDataRepositoryTest.kt` (attestation round-trip, old-record decode, unlink erasure), `CloudAuthenticationUseCasesTest.kt` (register requires `tosAccepted`); new `UploadRightsAttestationDataRepositoryTest`, `AutoBackupCoordinatorTest`, `ServiceFlagsRepositoryTest` | (regression) |
| Manual two-device matrix | upload → restore on 2nd device (transfer-plan S4/S5 acceptance); B1–B4 dialogs; takedown drill: `takedown.sh` → next sync pull → replica bytes + rows gone, imported original kept, availability `None` (transfer-plan S5) | "runbook approved" support evidence |
| Analytics audit | verified current transfer events carry only `bookUuid`/`bookType`/`errorType` (`BookAnalyticsEvent.BookDownloadStarted/Completed`, `BookCacheDeleted`, `BookImported/BookImportFailed`). Rule for new events (`BackupQueued`, `AutoBackupEnabled`, `AttestationAccepted`, `ServiceFlagObserved`): **ids/enums only — never titles, paths, content hashes (audit data → `cloud_file_audit_events` only), tokens, or URLs** (PC-plan §12) | (audit item) |

---

## Slice F — Ops enablement

1. **Cron** (runbook §7), service-role creds from the operator secret store:

   ```cron
   */15 * * * * /path/to/StoryTellerKMP/scripts/supabase/ops/gc-orphans.sh
   0 * * * *   /path/to/StoryTellerKMP/scripts/supabase/ops/purge-audit.sh
   ```

   → closes "hourly audit-purge scheduled" and "orphan GC scheduled".
2. **Abuse intake live**: publish the legal-draft §C takedown-notice form at
   `[ABUSE CONTACT]`, staff the channel, approve the runbook → closes "runbook
   approved; abuse channel live and staffed".
3. **Flag runbook** in place (Slice D) before the flip.

---

## Slice G — The legal gate & the flip

Human gate first (long pole — send `docs/parrot-cloud-legal-text-draft.md` to
counsel at plan adoption, not at code freeze):

1. Counsel **verifies the resolved positions** recorded in legal text §F
   (regime fit: private locker → not a DSM Art. 17 content-sharing service,
   DMCA-shaped notice flow; perjury wording; "personal use" framing; retention
   7/30/180-day schedule with GDPR/CCPA mapping; repeat-infringer policy A11;
   restoration SLA A8) and signs off on ToS §A, attestation copy §B, Privacy
   Policy addendum §G, and store disclosures §H. Publish-time constants
   (operator entity, Abuse Contact, DMCA agent registration) are filled per
   legal text §I.
2. **Store-policy review** (Play/App Store) for user-uploaded copyrighted
   content — the compliance mapping and ready disclosure text are in legal
   text §H; this step is reviewer acceptance of it.
3. Code-side version pinning: set the real `CLOUD_BACKUP_TOS_VERSION` (scheme
   §E: `parrot-cloud-backup-tos-YYYY-MM-DD.N`); bump `CLOUD_BACKUP_ATTESTATION_VERSION`
   if B1 checkbox substance changed. Ship the release containing A–F with E
   green. The build is dark: flag default off.
4. **Flip**: `scripts/supabase/ops/set-feature-flag.sh book_upload_enabled on`.
   No app release required (Slice D). Verify with the §9 query set.
5. First 48h monitoring: `reserve_book_upload` rejection reasons
   (quota/block-list), GC + purge worker logs, one takedown drill, support
   inbox. Emergency kill: `set-feature-flag.sh book_upload_enabled off` — new
   enqueues stop, restores/deletions keep working.

---

## Decisions

| # | Decision | Options | Recommendation | Status |
|---|---|---|---|---|
| 1 | Upload rollout gate mechanism | (a) hardcoded constant flipped in a release (b) server-controlled flag table + cached client resolution | (b) | **proposed: (b)** — PC-plan §12 mandates server-controlled; gives the flip and a remote kill switch |
| 2 | Attestation record storage | (a) embedded in `CloudProfileLinkRecord` (b) separate Preferences key (c) server-side table | (a) | **proposed: (a)** — per `(localProfileId, cloudUserId)`, erased on unlink; per-object audit already server-side via `cloud_book_uploads.rights_attestation` |
| 3 | `UploadRightsAttestationRepository` placement | (a) `feature/cloud-account/{domain,data}` (b) `feature/books/{domain,data}` | (a) | **proposed: (a)** — follows transfer-plan S5 file table; registration lives in cloud-account; record type maps to `UploadRightsAttestation` at the use-case boundary so cloud-account never depends on books |
| 4 | B1 checkbox frequency | (a) every manual Back up (b) only when re-attestation due | (a) | **proposed: (a)** — legal draft B1 puts the words at the point of upload; the stored record short-circuits B2/B3 only |
| 5 | Import-time unsynced metadata | (a) surface error, user retries (b) persisted pending set retried after sync (c) block import until metadata synced | (b) | **proposed: (b)** — `enqueueUpload` needs `cloudBookId` (`BookFileTransferEngine.kt:248-251`); imports stay local-first |
| 6 | Flag fetch failure | (a) fail-open (b) fail-closed for upload, last-known-good cache | (b) | **proposed: (b)** — dark is the safe direction for upload; download/deletion defaults keep restores working |
| 7 | Auto-backup default | (a) on (b) off | (b) | **decided: (b)** — carries over transfer-plan decision #7 |
| 8 | Google sign-in ToS gating | (a) gate CreateAccount-mode Google button behind `tosAccepted` (b) ask after OAuth | (a) | **proposed: (a)** — acceptance precedes account creation on every path |
| 9 | Flag transport | (a) PostgREST table + select (b) RPC `get_service_flags()` | (a) | **proposed: (a)** — RLS-tested like every other table; no extra RPC surface |

## Failure & edge semantics

- Version bump mid-flight: queued transfers keep their frozen
  `rightsAttestation` JSON; new enqueues require fresh acceptance. Both remain
  tied to exact wording via `cloud_book_uploads` (legal-draft §E).
- Unlink/deactivate during active transfers: existing `unlink()` erases the
  link + record; add cancellation of non-terminal upload transfers (reuses
  `BookFileTransferEngine.cancelTransfer`) — no orphan TUS sessions (GC covers
  stragglers, runbook §7).
- Flag flip mid-upload: only new enqueues gated; downloads/deletes unaffected
  (separate flags).
- Pending-set entries whose book was deleted: dropped on promotion attempt.
- Repeated import of the same file: `enqueueUpload` dedupes by hash
  (`BookFileTransferEngine.kt:256-263`) / `AlreadyAvailable`
  (:280-290) — auto-backup cannot double-count quota.

## Rollout & risks

- **Legal timeline is the long pole** — all of A–F merges and ships dark; the
  flag flip is the only post-sign-off action. Start counsel at plan adoption.
- **Draft copy in the build**: A–C can land with legal-draft text, but release
  checklist must assert approved wording per §B keys (one diff, easy to audit).
- **Flag RLS mistakes** would let writes leak to clients — pgTAP
  (`service_flags_test.sql`) pins select-only; service-role scripts are the
  only writers.
- **Deferred enqueue surprise** (uploads starting later on Wi-Fi-less
  connections): mitigated by opt-in default-off toggle, visible transfer status
  via the S2 `FileTransferStatusSource` machinery, and pending-set clearing on
  disable. "Wi-Fi only" refinement remains deferred (transfer-plan decision #7).
- **Old builds stay dark** after the flip: expected; store listing/release notes
  should require the backup-capable version for the feature announcement.
