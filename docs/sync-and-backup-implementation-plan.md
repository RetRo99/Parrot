# Sync and Backup implementation plan

## Goal and release scope

Offer an optional paid Parrot cloud account so readers can continue their reading
state across Android and iOS devices.

The first release synchronizes small data only:

- Reader settings, including TTS language, voice preference, rate, and pitch.
- Reading position and progress.
- Bookmarks, including edits, ordering, and deletion.
- Collections and collection membership.
- Local book metadata.

EPUB/PDF files, covers, custom fonts, and downloaded voices remain local. Notes
and highlights are outside the initial scope unless explicitly added.

Initially, synchronize locally imported books. Storyteller and Audiobookshelf
retain their existing server-managed position synchronization. Combining those
systems requires a separate ownership and conflict design.

Use customer-facing language such as “Sync your reading data.” Explain that
book files must be imported separately on each device.

## 1. Existing architecture and identity model

The app already has a local profile system, server authentication, a local
SQLDelight database, and preferences.

| Existing component | Responsibility |
| --- | --- |
| `lib/user/api/.../UserProfile.kt` | Device-local profile identity and display information |
| `lib/user/implementation/.../UserRegistryImpl.kt` | Creates, persists, and selects profiles |
| `lib/user/implementation/.../DefaultUserInitializer.kt` | Creates and activates the default profile |
| `feature/login/data/.../LoginDataRepository.kt` | Authenticates against a book server and registers it |
| `feature/auth/domain/.../CheckAuthStateUseCase.kt` | Checks server authentication or skipped login |
| `composeApp/.../RootNavigationViewModel.kt` | Routes to Home or existing login/onboarding |
| `feature/auth/domain/.../LogoutUseCase.kt` | Removes server credentials; `logoutAll()` also clears database data |
| `feature/reader/data/.../ReaderLocalDataSource.kt` | Reads and writes reader data and preferences |
| `lib/server-local/.../LocalReaderRepository.kt` | Persists local-book reading positions |

Add a cloud-account association to the local profile:

```text
Local UserProfile
├── Registered Storyteller/Audiobookshelf servers
├── Server credentials
├── Local database and imported books
├── Reader settings
└── CloudAccountLink
    ├── Supabase user ID
    ├── Sync enabled
    └── Initial merge completed
```

Keep three identities distinct:

```text
localProfileId = local storage/profile identity
cloudUserId    = Supabase auth.users.id
serverId       = one configured book server
```

Do not replace `UserProfile.id` with the Supabase ID. Existing database selection,
preferences, and server configuration depend on the local identity.

### Initial profile/account rules

- One local profile can link to one cloud account.
- One cloud account represents one synced library.
- On one device, prevent multiple local profiles from linking to the same cloud
  account; select the existing linked profile instead.
- Shared family accounts with multiple cloud profiles are a later feature.
- Never silently reassign a profile's data from account A to account B.

## 2. Proposed modules

Follow the existing feature-module structure:

```text
feature/cloud-account/domain
feature/cloud-account/data
feature/cloud-account/ui

feature/sync/domain
feature/sync/data
feature/sync/ui

feature/subscription/domain
feature/subscription/data
feature/subscription/ui

lib/cloud/implementation
```

Keep `feature/login` responsible for book-server connections. Cloud login does
not create an entry in `ServerRegistry`.

### Cloud-account domain

Proposed interfaces:

```text
CloudAccountRepository
├── Observe authentication state
├── Register with email/password
├── Sign in with email/password
├── Request magic link
├── Request password reset
├── Update password after recovery
└── Sign out

CloudProfileLinkRepository
├── Read profile/account binding
├── Link an unlinked local profile
└── Mark initial merge complete
```

Expose domain authentication states such as:

```text
RestoringSession
SignedOut
AwaitingEmailVerification
SignedIn(account)
ReauthenticationRequired(account)
```

Authentication, subscription access, and sync status are separate state models.
An authenticated user can have no subscription or have synchronization disabled.

### Cloud-account data

Implement `CloudAccountDataRepository`, a Supabase Auth adapter, secure session
storage, authentication callback processing, and local profile/account binding.
Keep Supabase types inside data/infrastructure modules.

### Shared Supabase infrastructure

Provide a Supabase client through `lib/cloud/implementation`, with Auth and
PostgREST configured. Account and sync repositories share the client for the
active cloud-session scope rather than running competing session refreshers.

Register the modules through the existing Koin application composition. Keep
domain modules independent of data and UI modules.

### Sync modules

`sync/domain` defines `SyncRepository`, status/conflict models, and use cases such
as `SyncNowUseCase`, `ObserveSyncStatusUseCase`, and
`ObserveLastSyncTimeUseCase`.

`sync/data` implements synchronization coordination, remote calls, retry policy,
conflict handling, and local sync-state access.

`sync/ui` provides the Sync screen and its ViewModel/ViewState, reachable from
`feature/settings/ui`. Put user-facing strings in `translations`.

## 3. Login and navigation UX

### Existing reader enabling sync

```text
Home
  → Settings
    → Sync & Backup
      → Sign in / Create account
        → Verify email if required
          → Review local-library merge
            → Subscription or beta entitlement
              → Enable sync
```

Account creation does not immediately upload library data. Upload requires a
confirmed profile link, confirmed merge, enabled sync, and access entitlement.

The initial screen explains:

> Keep your progress, bookmarks, collections, and reader settings available
> across devices. Book files remain on this device.

Provide “Create account” and “Sign in” actions.

### Returning reader

Show account email, access/subscription status, sync status, last successful sync,
and actions for manual sync, subscription management, and cloud sign-out.

### New installation

Keep local reading available immediately. Optionally expose “Sign in to Parrot”
alongside server connection and local-reading choices during onboarding. Both
onboarding and Settings open the same cloud-account flow.

Do not invoke the existing `LoginRepository.login()` for cloud authentication:
that implementation registers a book server.

## 4. Email/password registration and sign-in

### Registration sequence

```text
CloudAccountScreen
  → CloudAccountViewModel
    → RegisterCloudAccountUseCase
      → CloudAccountRepository
        → Supabase Auth
```

1. Submit email and password.
2. Show “Check your email” when verification is required.
3. Complete verification through the configured callback flow.
4. Wait for an authenticated session; successful registration alone is not proof
   that a session exists.
5. Resolve the authenticated `cloudUserId` against local profile links.
6. Confirm linking and merging the selected local profile.
7. Check entitlement.
8. Start initial sync after enablement.

### Sign-in sequence

1. Authenticate through Supabase.
2. Persist the session through the configured secure session manager.
3. Read the authoritative user ID from the session.
4. If already linked on this device, select that account's local profile.
5. Otherwise, offer to link the current unlinked profile or create a separate
   local profile.
6. Never silently relink a profile owned by a different account.

```text
Current local profile → account A
Reader signs in to account B
                     ↓
Open/create B's local profile
                     ↓
Do not upload A's database into B
```

Persist a pending-authentication context so a delayed callback cannot bind an
account to whichever profile happens to be active when the link arrives.

## 5. Magic links, verification, and recovery callbacks

### Android integration

Existing entry point:

`androidApp/src/main/kotlin/com/retro99/parrot/android/MainActivity.kt`

Both `onCreate()` and `onNewIntent()` call `handleDeepLinkIntent()`. That currently
tries the Storyteller OAuth callback before ordinary navigation.

Extend routing with distinct, exactly matched callback destinations:

```text
Incoming URL
  ├── Parrot cloud authentication callback
  ├── Storyteller OAuth callback
  └── Existing app navigation link
```

Configure the required platform link registration and Supabase redirect allowlist.

### iOS integration

Existing entry point:

`iosApp/iosApp/ContentView.swift`

The current `.onOpenURL` forwards to `StorytellerOAuthCallbackRegistry`. Route
cloud callbacks to a shared Kotlin handler too. If using universal links, also
connect the relevant universal-link lifecycle entry point.

Expose dependencies through the project's shared dependency bridge rather than
exposing Koin to Swift.

### Callback processing

1. Validate the destination.
2. Delegate token/code validation and exchange to Supabase Auth.
3. Restore the pending authentication context.
4. Distinguish ordinary authentication from password recovery.
5. Publish domain authentication state and navigate accordingly.

```text
Recovery email
  → App callback
    → NewPasswordScreen
      → Update password
        → Account screen
```

Persist required authentication-flow state securely across process termination.
For PKCE flows, define the same-device limitation and provide a retry path when
the verifier is unavailable. Do not log callback credentials or tokens.

## 6. Session restoration and offline behavior

```text
Initialize local profile
        ↓
Open local database / render existing app
        ↓
Restore cloud session asynchronously
        ↓
Resolve linked profile and entitlement
        ↓
Start sync when eligible
```

Do not change `CheckAuthStateUseCase` into a mandatory Supabase-session check.
Cloud outages or expired refresh tokens must not block local reading or redirect
the reader out of their library.

Existing platform storage includes Android's `EncryptedPreferenceFactory` and
iOS's Keychain-backed `IosSettingsFactory`. Use an explicit secure cloud-session
store, verifying Android encryption and backup behavior before reuse. Namespace
stored sessions by account/profile binding rather than relying implicitly on the
SDK's default storage.

Differentiate:

- Offline refresh attempt: retain account association; report offline sync.
- Invalid/revoked refresh token: pause sync and request reauthentication.
- Local reading: available in both cases.

## 7. Safe sign-out and account switching

Add `SignOutCloudAccountUseCase`. Do not call existing
`LogoutUseCase.logoutAll()`: it invokes `databaseCleaner.clearAllData()`.

Cloud sign-out sequence:

1. Disable synchronization for the account.
2. Cancel and await account-scoped sync work.
3. Invalidate the local session generation.
4. Clear local cloud credentials and attempt remote sign-out as appropriate.
5. Preserve local book files and reading data.
6. Preserve the account binding to prevent accidental reassignment.
7. Publish signed-out cloud state.

Pending changes remain associated with their original account. Signing back into
that account can resume them. Local credential clearing must succeed even when
the network is unavailable; remote session revocation may require connectivity.

| Action | Meaning |
| --- | --- |
| Disconnect Storyteller/Audiobookshelf | Remove that server's authentication |
| Sign out of Parrot cloud | Stop cloud sync and clear its session |
| Remove local profile | Remove that profile's local data |
| Delete cloud account | Run the server-side account deletion workflow |

## 8. Local schema and migration work

### Profile/account binding

Persist a device-level mapping, with credentials stored separately:

```text
cloud_account_links
  local_profile_id
  cloud_user_id
  sync_enabled
  initial_merge_completed
```

### Stable book identity

- Stream SHA-256 over original file bytes during import.
- Persist the content hash and algorithm version.
- Backfill existing imported files incrementally and resumably.
- Match identical files across devices by hash.
- Treat different editions or modified files as distinct books initially.
- Never automatically identify a book by filename, title, or author.

### Separate metadata from file availability

`ImportedBook.sq` currently requires `file_path TEXT NOT NULL`. A remotely synced
book must not be inserted with a fabricated or empty path.

Introduce a logical library record and local file mapping:

```text
library_books
  library_book_id
  content_hash
  title
  author
  format
  remote_revision
  deleted_at

local_book_files
  library_book_id
  imported_book_uuid
```

The mapping can point to the existing imported-book row rather than duplicating
its file path. Local library queries combine metadata with optional device-local
file availability.

```text
Metadata exists, file absent
  → Show “Import file”

Matching file imported
  → Link by content hash
  → Resolve bookmarks and progress
  → Enable reading
```

### Bookmarks and collections

`Bookmark.sq` currently hard-deletes records. Replace synchronized deletion with
a tombstone plus durable delete mutation in the same transaction. Normal reader
queries exclude tombstones.

Preserve existing globally unique IDs. Map local book references to stable cloud
book IDs at the sync boundary.

Give collection memberships individual stable identities/versioning so one
device's membership change does not replace the entire collection. Distinguish
locally owned collections from server-provided collections before enabling upload.

### Reader settings

`ReaderLocalDataSource` currently reads/writes `PreferencesKey.ReaderSettings`
directly, while `CurrentlyReading` uses user-scoped preference use cases. Make
reader-setting ownership explicitly profile-scoped before cloud synchronization.

Recommended durable storage:

```text
reader_settings
  setting_key
  value
  remote_revision
  deleted_at
```

Move syncable settings into profile-scoped SQLDelight storage and migrate existing
preference values once. Define which profile receives legacy globally stored
settings; do not silently copy one profile's preferences into every cloud account.
Keep the existing `ReaderSettingsRepository` interface to minimize reader UI
changes.

Keep local paths and device-only settings out of sync payloads. For TTS, persist
language and portable preference information alongside platform-specific voice
IDs. Fall back locally when a voice is unavailable without overwriting the user's
original preference.

## 9. Durable local change capture

Do not upload from ViewModels. Attach change capture to local persistence.

Current bookmark path:

```text
Reader UI
  → ReaderSettingsRepository
    → ReaderDataRepository
      → ReaderLocalDataSource
        → BookmarksDatabase
```

Proposed database operation:

```text
SQLDelight transaction
  ├── Write bookmark
  └── Write outbox mutation
```

Apply the same pattern to local reading positions, settings, collections, and
metadata. Expose transactional operations through `lib/database/api`; keep their
implementation in `lib/database/implementation`. Feature repositories should not
depend on `sync/data` merely to record local changes.

Outbox entries include:

```text
cloud_user_id
mutation_id
entity_type
entity_id
operation
payload
base_revision
local_mutation_sequence
retry_metadata
```

Only bind previously local-only data to an account after merge confirmation.
Once linked, retain durable pending edits even while offline or signed out.

Remote application uses a separate persistence path that updates local rows
without generating fresh outbox mutations.

## 10. Supabase backend and security

Use separate development and production projects. Keep schema, functions, and
RLS policies in version-controlled migrations.

Configure email/password, verification, magic links, password recovery, redirect
allowlists, and production transactional email delivery. Google/Apple login can
follow after the core flow is stable.

Use the project URL and publishable key, or legacy anonymous key, in
environment-specific client configuration. These are public client credentials;
RLS and authenticated ownership checks protect data. Never ship a service-role
or secret key in the app.

### Proposed server tables

| Table | Purpose |
| --- | --- |
| `library_books` | Account-owned book identities and metadata |
| `reader_settings` | Independently versioned preferences |
| `reading_positions` | Locator and progress per book |
| `bookmarks` | Bookmark locator, label, order, and deletion |
| `collections` | Collection metadata |
| `collection_books` | Individually versioned memberships |
| `sync_changes` | Account-scoped incremental change feed |
| `sync_mutations` | Idempotency records |
| `entitlements` | Server-managed subscription access |

Synced records carry account ownership, stable identity, schema version,
server-controlled revision, server update time, deletion marker, and any logical
modification metadata required for conflicts.

### Access rules

- Enable RLS on every exposed table.
- Derive ownership from `auth.uid()`.
- Check ownership on reads and inserted/updated rows.
- Prevent ownership changes.
- Use ownership-aware foreign keys between related records.
- Keep entitlement writes server-only.
- Prevent direct table writes from bypassing sync validation, quotas, or payment
  gates.
- Audit privileged RPC functions and grants; do not trust client-supplied user IDs.

## 11. Sync coordinator and protocol

Implement an account/profile-scoped `SyncCoordinator` in `feature/sync/data`.

Inputs:

```text
Active local profile
Cloud session
Profile/account binding
Sync enabled
Entitlement
Connectivity
Lifecycle and manual triggers
```

Eligibility:

```text
Active profile matches binding
AND authenticated account matches binding
AND sync enabled
AND server-authorized entitlement available
```

### Profile-switch safety

Capture immutable run context:

```text
localProfileId
cloudUserId
sessionGeneration
databaseHandle
```

All reads and writes use that context. Sign-out/profile switching invalidates
the generation and cancels the run. A late network response must never resolve
the newly active database and write into another profile.

### Proposed API contract

Authenticated PostgREST RPCs:

```text
push_sync_changes(mutations)
pull_sync_changes(cursor, limit)
```

Each mutation includes an idempotency ID, entity identity/type, expected revision,
payload or deletion marker, and logical modification metadata. Responses identify
accepted changes, conflicts, and authoritative server revisions.

### Sync cycle

1. Validate session/access and acquire an account-scoped lock.
2. Pull changes while preserving pending local edits.
3. Push bounded batches of durable mutations.
4. Resolve revision conflicts deterministically.
5. Pull authoritative results.
6. Persist successful reconciliation state and timestamp.

### Required guarantees

- Duplicate mutation submissions are idempotent.
- An acknowledgement clears only its mutation, never a newer local edit.
- Remote application does not enqueue another upload.
- Pulled changes and cursor advancement commit atomically locally.
- The change feed cannot skip late-committing transactions; a plain timestamp or
  sequence alone is not proof of commit ordering.
- Pagination has a stable boundary.
- Expired cursors require safe full reconciliation before stale uploads proceed.
- Failed or interrupted batches remain retryable.

Trigger sync on sign-in, launch/resume, connectivity return, manual refresh, and
debounced foreground edits. Add platform background execution as best-effort
support; correctness must not depend on it.

Use bounded batches, exponential backoff with jitter, and coalescing of frequent
position updates.

## 12. First sync and second-device restore

### Existing device enabling sync

1. Confirm linking and merging the local profile.
2. Snapshot eligible local data into durable pending changes.
3. Hash existing imported files incrementally.
4. Pull remote data into a merge that preserves pending edits.
5. Resolve book identity by hash.
6. Upload dependency-ordered changes: books, settings/collections, then positions,
   bookmarks, and memberships.
7. Pull authoritative results.
8. Mark initial merge complete.

Persist progress so process termination resumes the same merge rather than
starting an unrelated migration.

### Second device

1. Sign into the same cloud account.
2. Link/create its local profile.
3. Download metadata, settings, bookmarks, and positions.
4. Show books as requiring local import.
5. Import the identical file.
6. Attach it to the existing cloud identity.
7. Open at the synced position.

## 13. Conflict and deletion rules

| Data | Resolution |
| --- | --- |
| Reading position | Latest logical edit wins, not furthest page |
| Settings | Latest edit wins per setting |
| Independent bookmarks | Merge by stable ID |
| Same bookmark edited concurrently | Deterministic latest-edit rule |
| Collections | Merge metadata and memberships independently |
| Metadata | Merge supported fields independently where practical |
| Deletion versus stale edit | Tombstone wins; restore is explicit |

Specify logical ordering before implementation. Server arrival time allows an
old offline edit to overwrite a newer position; raw device time is vulnerable to
clock skew. Use expected revisions, a documented logical-time strategy,
deterministic tie-breaking, and explicit skew handling. Retain sufficient previous
position information to recover from unwanted overwrites.

Retain tombstones for the supported offline window. Devices older than the
retention boundary must rebootstrap before submitting stale state.

Distinguish removing a local file from removing a book from the synced library.

## 14. Sync UI state

Expose `synced`, `syncing`, `offline`, `error`, and `conflict`, alongside separate
authentication and access states.

Show:

- Account identity.
- Subscription/access status.
- Current sync state.
- Last successful sync time.
- Pending changes where useful.
- Manual sync and actionable retry controls.
- A clear explanation that files remain local.

Only report “synced” after a successful reconciliation with no pending local
changes. An authenticated session alone is not successful synchronization.

## 15. Subscription flow

Require cloud sign-in before a new purchase so ownership can be bound to a stable
account.

```text
Signed-in account
  → Google Play Billing / StoreKit purchase
    → Purchase evidence sent to backend
      → Store verification
        → Entitlement assigned to cloudUserId
          → Sync enabled
```

Use a Supabase Edge Function or separate backend for verification and store
notifications. The implementation must:

- Prevent a purchase being claimed by multiple cloud accounts.
- Process notifications idempotently.
- Handle renewal, expiry, refund, revocation, and grace periods.
- Support purchase restoration and periodic store reconciliation.
- Expose one canonical entitlement to clients.

Enforce access on backend sync endpoints and exposed table paths independently
of client UI state. Local reading continues after expiration. Define cloud-data
retention explicitly; deletion and export remain available independently of paid
sync access.

The same cloud account can access its entitlement on another platform subject
to product and store policy.

## 16. Testing and operational readiness

### Required verification

- Registration, verification, password login, magic links, and recovery.
- Cold/warm callback handling and process termination during authentication.
- Session restoration, offline refresh, and revoked sessions.
- Sign-out and account switching while requests are in flight.
- Legacy profile/settings/database migration without data loss.
- Two devices editing positions, settings, bookmarks, and memberships offline.
- Clock skew, out-of-order requests, and duplicate mutations.
- Server acceptance followed by a lost response.
- Process termination at local transaction and upload boundaries.
- Deleted records remaining deleted after old devices reconnect.
- Expired cursors and full reconciliation.
- Identical-file import and missing-file library states.
- Unavailable cross-platform TTS voices.
- Cross-account RLS, ownership changes, and quota enforcement.
- Purchase restore, expiration, refunds, and duplicate ownership claims.

Run appropriate domain/data tests and Android compilation as implementation
slices land. Verify iOS through an appropriate framework/app build without
requiring `assembleXCFramework`.

### Operations

Track sync success, duration, pending-queue age, retry counts, conflict outcomes,
and authentication failure categories. Exclude book content, titles, credentials,
and callback tokens from telemetry.

Configure usage alerts, supported spend controls, server-enforced record/payload/
request quotas, a sync kill switch, and backup/restore procedures. Use Free for
development and budget for Pro in production. Verify current billing coverage;
spend controls are not a universal hard budget cap.

Publish privacy, retention, account-deletion, data-export, and support flows before
paid release.

## 17. Implementation slices and release gates

| Slice | Deliverable |
| --- | --- |
| 1. Account foundation | Modules, profile binding, email/password auth, secure restoration |
| 2. Auth UX | Settings entry, verification, magic links, recovery, safe cloud sign-out |
| 3. Local data preparation | Profile-scoped settings, hashes, metadata/file mapping, outbox, tombstones |
| 4. Vertical sync slice | Local reading position round-trips between two devices |
| 5. Remaining synced data | Settings, bookmarks, collections, metadata, missing-file UI |
| 6. Reliability | Offline conflicts, retries, process death, account/profile switching |
| 7. Paid access | Native billing, verification backend, entitlement enforcement |

Start with account foundation and the reading-position vertical slice. This proves
the full path before expanding to every entity:

```text
Local profile
  → Cloud account
    → Local database
      → Authenticated backend
        → Second device
```

Roll out through internal two-device testing, closed beta with server-issued test
entitlements, migration/reliability review, subscription sandbox testing, and then
a gradual paid release.

## 18. Later milestone: full book backup

Treat file backup as a separate implementation and capacity/pricing milestone:

- Private Supabase Storage bucket and user-owned paths.
- Storage RLS and server-enforced quota reservations.
- Explicit consent before upload.
- Resumable upload/download.
- Per-user duplicate detection and hash verification.
- Corruption handling and interrupted-upload cleanup.
- Distinct local removal and cloud deletion actions.
- Restore, retention, export, and account-deletion behavior.

Do not describe the initial metadata-only release as backing up book files.
