# Shared reading-progress synchronization implementation plan

Status: proposed implementation plan; application changes have not been made.

## 1. Decision and scope

Implement one local-first synchronization engine with hybrid scheduling for
Storyteller, Audiobookshelf, Parrot Cloud, and local-only books.

Every accepted reading-position change is persisted locally. When a remote
destination is configured, the same transaction records its pending delivery.
The shared engine uploads coalesced progress automatically, refreshes remote
state, resolves conflicts, and recovers interrupted work.

Parrot Cloud is a transport adapter for Supabase RPCs. It does not retain a
separate synchronization architecture or user experience.

This plan supersedes progress-specific recommendations in the older sync plans
that leave Storyteller/Audiobookshelf on direct writes or make Parrot delivery
manual-only. Existing cloud library, entitlement, file-storage, and account
features remain governed by their respective plans.

### Release scope

- Ebook locators and reading progress.
- Audiobook, ReadAloud, and TTS progress through the same persistence boundary.
- All existing progress destinations.
- Automatic foreground, lifecycle, reconnect, and background recovery triggers.
- Account-safe routing, durable retries, conflict handling, and diagnostics.

Existing book-registration mutations must continue working because progress can
depend on canonical remote book registration. Do not broaden this project into
new bookmark, collection, settings, or file synchronization features. Those
entities need their own merge rules; latest-position coalescing does not apply
to every kind of mutation.

### Product guarantees and limits

1. A successfully committed local position survives ordinary process death.
2. Remote intent is durable once the local save reports success.
3. Pending work is discoverable after restart, even if scheduler registration
   was interrupted.
4. Progress uploads happen automatically without per-page HTTP requests.
5. Ordinary network failures do not interrupt reading.
6. No backend-specific synchronization workflow is exposed to the reader.
7. Remote delivery on lock, force-stop, termination, or without connectivity
   cannot be guaranteed. A lifecycle flush is best effort.
8. Local-only books have no cross-device delivery until a remote destination is
   configured. A no-op adapter cannot provide remote storage.
9. Atomic cross-device conflict prevention depends on backend support. The
   shared engine must not claim conditional-write guarantees for APIs that lack
   them.

The durability guarantee starts at database commit, not at a transient UI event.
Choose and document a maximum local checkpoint interval for continuous audio;
that interval is also the potential last-moment loss window on abrupt death.

## 2. Current implementation and evidence

Paths below identify the existing source files; package directories are omitted
where the filename is unique within the module.

| Current component | Finding | Planned treatment |
| --- | --- | --- |
| `feature/reader/domain/.../SaveReadingProgressUseCase.kt` | Delegates persistence to the selected server repository | Retain reader-facing entry point; delegate to shared progress repository |
| `lib/server/api/.../ServerReaderRepository.kt` | Combines local storage, remote operations, and synchronization policy | Split application persistence from transport operations |
| `lib/server-storyteller/.../StorytellerReaderRepository.kt` | Local write followed by POST; local result is not checked before HTTP | Move local write to shared repository; retain HTTP mapping in adapter |
| `lib/server-audiobookshelf/.../AudiobookshelfReaderRepository.kt` | Local write followed by PATCH; no durable remote retry intent | Use the shared outbox and transport-only adapter |
| `lib/server/implementation/.../ServerPositionLocalDataSource.kt` | Local-book saves already enqueue content-hash-based cloud mutations | Make destination selection explicit; remove implicit cloud routing |
| `lib/server-parrot-cloud/.../ParrotCloudReaderRepository.kt` | Atomically stores position plus a new mutation | Generalize transaction; remove cloud-specific envelope construction from save path |
| `lib/database/implementation/.../BooksSqlDelightDao.kt` | `upsertPositionWithMutation` uses one transaction but appends every mutation | Preserve atomicity; add generation-safe unsent coalescing |
| `lib/database/implementation/.../SyncOutboxSqlDelightDao.kt` | A coalesce method exists but is not used by transactional progress saves | Replace with state-aware coalescing inside the position transaction |
| `lib/server-parrot-cloud/.../ParrotCloudSyncAdapter.kt` | Owns mutex, retries, cursor, database application, and conflict policy | Extract shared orchestration; retain RPC serialization and transport |
| `feature/reader/domain/.../GetReadingProgressWithConflictUseCase.kt` | Conflicts are based on a 1% difference; otherwise remote wins | Delegate to baseline-aware shared reconciliation |
| `feature/reader/ui/.../ReaderViewModel.kt` | Independent ViewModel-scoped saves; close explicitly checkpoints ReadAloud audio | Ordered local writes and common checkpoint/flush boundary |
| `feature/reader/ui/.../ReaderSyncCoordinator.kt` | Coordinates book/audio navigation rather than remote sync | Keep separate from the new engine |
| `feature/cloud-account/ui/.../CloudAccountViewModel.kt` | Hosts the discovered manual synchronization entry point | Route manual action into shared engine |
| `iosApp/iosApp/ContentView.swift` | Scene-phase handling currently concerns OAuth cancellation | Add lifecycle forwarding while preserving existing behavior |
| `supabase/migrations/20260922000000_parrot_cloud.sql` | RPCs provide revisions and mutation-response deduplication | Preserve transport capabilities; audit concurrency and rejection semantics |

### Correctness failures to address before automation

#### Queued updates sharing one base revision

Positions A, B, and C can be queued with the same base revision. A succeeds and
increments the server revision. B and C then conflict. The existing client
applies the conflict payload and deletes the local mutations, so A can replace
the newer B/C positions.

#### Pull overwrites dirty local progress

The cloud pull applies incoming positions directly to the local position table,
without reconciling pending local changes first.

#### Interrupted delivery on other servers

Storyteller and Audiobookshelf persist locally but do not record a durable
delivery obligation when their immediate remote calls fail.

#### Incomplete draining and recovery

The cloud client pushes at most 50 eligible entries per pass and performs one
page per pull invocation. It does not automatically continue until caught up.
Request-level exceptions return failure without the same persisted retry
scheduling used for per-mutation rejection responses.

## 3. Target ownership and module boundaries

### Application flow

Reader or player -> shared progress repository -> database transaction ->
shared engine -> selected transport adapter.

Lifecycle, network, manual actions, and OS workers all request work from that
same engine. None implement a second delivery path.

### Proposed placement

| Module | Ownership |
| --- | --- |
| `feature/sync/domain` | Pure Kotlin contracts, sync states, policy models, conflict decisions, use cases |
| `feature/sync/data` | Engine, queue draining, reconciliation, retries, scheduling decisions, persistence coordination |
| `feature/reader/domain` | Reader use cases and reader-specific models; depends on shared contracts without data-layer imports |
| `lib/database/api` | Atomic progress store, outbox, baseline, checkpoint, and lease contracts |
| `lib/database/implementation` | SQLDelight schema, migrations, transactions, conditional updates |
| `lib/server/api` | Transport contract, canonical transport models, capability declarations |
| Existing `lib/server-*` modules | API-specific adapters and identity/locator mapping |
| Android/iOS composition roots | Platform lifecycle and background scheduler wiring |
| Existing settings/cloud-account UI | Common sync status and manual request presentation |

Verify dependency direction before finalizing contract placement. Domain modules
must not depend on platform, database implementation, Supabase, or HTTP classes.
Avoid a reader-domain <-> sync-domain cycle; place shared progress concepts in
one neutral contract owner and map reader-specific UI models at the boundary.

Use existing Koin composition and repository naming conventions. The shared
implementation of `SyncRepository` should be `SyncDataRepository`; the Supabase
adapter must no longer bind itself as the application-wide sync repository.

For new modules, follow project Gradle conventions, test-fixture setup where
compatible, and `.snyk` provisioning. Do not create a new UI module solely for
this work if existing screens can present the shared state cleanly.

### Shared engine responsibilities

- Local transaction semantics and progress generations.
- Destination ownership and account isolation.
- Coalescing, draining, prioritization, and execution budgets.
- Local/remote baseline reconciliation and conflict preservation.
- Retry eligibility, backoff, and error-state transitions.
- Acknowledgements and durable remote checkpoints.
- Single-flight coordination across foreground and background callers.
- Application-facing status and diagnostics.

### Adapter responsibilities

- Endpoint, authentication, and request/response mapping.
- Remote identity and supported locator conversion.
- Fetching requested progress or a page of changes.
- Uploading immutable mutations.
- Returning normalized success, conflict, and failure results.
- Declaring batching, version, idempotency, and change-feed capabilities.

Adapters do not write the local position table, delete outbox entries, decide
which conflicting position wins, or schedule retries. Existing bounded auth
refresh may remain in the transport layer; disable overlapping automatic write
retries unless their semantics are explicitly coordinated with the engine.

## 4. Common contracts

Define semantics before selecting final Kotlin signatures.

### Application-facing operations

| Operation | Required semantics |
| --- | --- |
| Record progress | Return after local position and delivery intent commit; no HTTP dependency |
| Observe progress | Observe the reconciled local source of truth |
| Checkpoint current reader | Capture latest available position and await local commit |
| Request synchronization | Merge trigger, scope, and urgency with existing work |
| Refresh book | Prioritize freshness/reconciliation for one book |
| Observe sync status | Expose durability, pending work, blocked state, last successful delivery |
| Resolve conflict | Record an explicit choice as new intent based on the reconciled remote version |

Include update origin: user navigation/playback, restoration, or remote apply.
Restoration and remote application must not appear as new reading activity.

### Transport operations

1. Fetch progress for requested remote book identifiers.
2. Fetch a change page with an opaque cursor, where supported.
3. Push one or more immutable progress mutations.
4. Return accepted metadata, current remote state on conflict, or classified
   errors, including retry-after information when available.

Version tokens and cursors are opaque at the shared boundary, not universally
integers. A capability declaration describes actual backend behavior; it does
not change the reader workflow.

Return per-mutation results for partial batches. An omitted result is an
unresolved outcome, never an acknowledgement. A local-only adapter completes
without remote work and is represented honestly as device-local storage.

## 5. Durable data model

### 5.1 Identity and routing

Distinguish:

- Local profile identity and its database scope.
- Local canonical book identity, including content/edition identity.
- Configured server instance and remote account binding.
- Remote book identity for that destination.
- Progress kind where ebook and audio streams are independent.

Use an explicit destination binding rather than inferring remote ownership from
the source of the book file. Initially support one authoritative progress
destination per binding; multi-destination fan-out requires separate baselines
and acknowledgement state and is not implicit in this release.

Do not equate title/author matches with identical book editions. Do not assume
server-provided book UUIDs are globally unique across server instances.

### 5.2 Local progress

Persist the complete resumable snapshot plus:

- Scoped canonical identity.
- Local generation and source-event sequence where needed for ordering.
- Original observation timestamp.
- Device and reading-session identity.
- Update origin and relevant explicit navigation intent.
- Exact locator fields, including `cssSelector` where used.
- Audio offset, chapter identity, and progress metadata.

Audit every reader -> domain -> transport -> database -> reader conversion.
Current `ServerPosition` includes `cssSelector`, but the inspected position
schema and local mappings do not retain it. Rounded percentage is not adequate
proof of successful exact-position synchronization.

### 5.3 Remote baseline

For each destination/book, persist:

- Last acknowledged/fetched remote snapshot and opaque version.
- Last acknowledged local generation.
- Remote freshness/check time, separate from delivery time.
- Conflicting candidate state when reconciliation cannot safely choose.

Do not overwrite a newer local snapshot merely to update its remote revision.
An old acknowledgement advances the baseline only for its own immutable payload.

### 5.4 Outbox

Persist:

- Stable mutation ID.
- Profile/account/destination binding.
- Entity and progress kind.
- Immutable dispatched payload, local generation, and original event time.
- Base remote version.
- State: pending, dispatched/unresolved, blocked, or conflict-preserved.
- Attempt count, next attempt time, and classified last error.
- Lease/owner information if required for worker coordination.
- Dependency on remote book registration when needed.

There is at most one coalesced unsent progress snapshot per scoped key. A
dispatched/unresolved mutation can coexist with one newer pending snapshot.
Never reuse a dispatched mutation ID for different payload content.

### 5.5 Transaction boundaries

Local save transaction:

1. Validate pinned profile/destination and event ordering.
2. Update the complete local snapshot and increment its generation.
3. Replace eligible unsent progress with the latest intent.
4. Preserve dispatched or ambiguous-delivery mutations.
5. Commit before reporting save success.

Acknowledgement transaction:

1. Match destination, mutation ID, and generation.
2. Advance the acknowledged baseline for that payload.
3. Clear only the acknowledged mutation.
4. Preserve newer local/pending generations.
5. Prepare a subsequent unsent mutation against the new baseline only when
   causal reconciliation permits it; never rewrite a dispatched request.

Pull transaction:

1. Reconcile a fetched page against local baselines and pending work.
2. Persist local results or preserved conflict candidates.
3. Commit the page cursor/checkpoint in the same durable boundary.

Move sync checkpoints out of independently written preferences where necessary
to obtain this boundary. Replayed pages must remain harmless.

## 6. Execution and reconciliation algorithm

### Trigger handling

Every trigger provides reason, scope, and urgency. Merge concurrent requests;
do not spawn one drain per event. Pin the profile/account for the duration of
each operation rather than reading the currently selected profile midway.

Serialize progress writes per destination/book. Use bounded concurrency across
independent destinations so an unavailable server does not block another.
Coordinate foreground and background execution using one engine instance and,
where overlapping instances are possible, transactional leases or equivalent
database ownership. Recover expired claims after interruption.

### One bounded pass

1. Acquire ownership and resolve the pinned destination session.
2. Recover unresolved work and inspect persisted retry eligibility.
3. Refresh stale remote state for active/dirty books where needed.
4. Reconcile against the acknowledged baseline.
5. Satisfy book-registration dependencies before their progress writes.
6. Push eligible immutable snapshots within backend batch limits.
7. Apply acknowledgements/conflicts transactionally.
8. Pull additional changes when required by the transport or reconciliation.
9. Continue while work remains and the execution budget allows.
10. Persist continuation, release ownership, and request another opportunity if
    work remains.

Pull-before-push is not a substitute for reconciliation. Avoid a full library
pull for each checkpoint; retain a recent baseline during an active session and
use conditional writes where supported. Refresh on handoff, stale baseline,
conflict, reconnect, or ambiguous delivery.

### Conflict policy

| Local state | Remote state | Decision |
| --- | --- | --- |
| No pending change | Changed from baseline | Adopt remote |
| Changed from baseline | Still baseline | Upload local |
| Same semantic position | Same semantic position | Reconcile metadata; satisfy redundant work |
| Both changed | Causally ordered with reliable evidence | Prefer the causally later intent |
| Both changed | Ambiguous/concurrent | Preserve both; expose consistent resolution |
| New local generation exists | Old acknowledgement arrives | Update baseline only; retain newer progress |

Never resolve by maximum percentage. Never replace the original reading time
with upload/retry time. Device wall clocks and server arrival order alone are
not reliable proof of causality. A device sequence orders that device's events,
not independent devices globally.

Preserve a coherent locator/audio snapshot. Do not merge chapter from one
candidate with playback offset from another. Automatic resolution must avoid
silently converting a deliberate rewind or restart into forward progress.

For true concurrent offline sessions, use a small backend-neutral choice when
necessary. Ordinary unambiguous handoff should not show a conflict dialog.

## 7. Scheduling policy

The following are initial tunable defaults, not delivery SLAs.

| Event | Policy |
| --- | --- |
| Settled ebook locator, page, seek, chapter change | Local commit immediately |
| Raw animation/scroll frames | Normalize into meaningful resumable position events |
| Continuous audio | Start with a one-second local checkpoint target; measure write cost and document tolerance |
| Routine dirty foreground progress | Three-second idle debounce, minimum 15 seconds between routine uploads, maximum 30 seconds dirty wait |
| Reader close or playback pause | Checkpoint locally and request urgent flush |
| Background or lock-related lifecycle transition | Checkpoint early and request bounded urgent flush |
| App startup/resume | Recover queue, refresh active/recent books, drain eligible work |
| Book opening | Prioritize remote freshness with a 1–2 second initial budget |
| Connectivity returns | Coalesce signals; reconcile and wake eligible retries |
| Foreground reader already open on another device | Active-book refresh around 30–60 seconds or adapter notification hint |
| Manual Sync now | Immediate shared-engine request, not a separate implementation |

Rate-limit only remote work, not local durability. The maximum-wait rule avoids
an endless trailing debounce under continuous playback or scrolling.

Skip unchanged snapshots. Merge close/background/lock signals. Stop timers when
idle and avoid network requests from no-op periodic passes. An urgent request
may bypass debounce, but not server retry-after restrictions.

### Handoff presentation

- Show local content quickly and perform a bounded remote freshness check.
- Apply a newer remote position automatically before the user starts interacting.
- Do not mark initial restoration as fresh user progress.
- If the user has started reading, do not jump unexpectedly when a late pull
  finishes; preserve state and offer a consistent continuation action if needed.
- Allow a short bounded recheck during untouched startup to catch device A's
  flush completing immediately after device B's first fetch.
- If A is offline, B cannot recover progress that never reached the server.

## 8. Retry, failure, and status behavior

### Error classes

| Result | Shared action |
| --- | --- |
| Timeout, connection loss, eligible 5xx | Persist backoff and retry |
| 429 or explicit retry-after | Honor server delay |
| Authentication expired | Use supported bounded refresh; otherwise block destination for reauthentication |
| Validation or unsupported payload | Block mutation with diagnostic reason; do not hot-loop |
| Version conflict | Reconcile and preserve intent |
| Missing registration prerequisite | Complete dependency and issue appropriate new mutation if prior rejection is terminal |
| Process cancellation or uncertain response | Preserve unresolved request identity and recover |

Start transient backoff around two seconds with jitter and a five-minute cap.
Persist retry eligibility. New position events must not reset a failing
destination's backoff continually. Connectivity restoration can wake previously
offline work, but must not override explicit server cooldowns.

At-least-once attempts are the default. Use backend idempotency when available.
Without it, audit endpoint side effects and read back after an uncertain result
where feasible. Repeated absolute state writes and exactly-once API effects are
not equivalent claims.

The shared status model distinguishes:

- Saved on this device.
- Pending remote delivery or waiting for connection.
- Synchronizing.
- Up to date for the acknowledged generation/scope.
- Action required, such as authentication or a preserved conflict.
- Local-only storage.

Ordinary offline reading should not show repeated errors. Keep local-save
failures actionable. Last pull, last delivery, and pending count are different
facts; do not display a successful pull as proof that all progress was uploaded.

## 9. Server adapter work

### Storyteller

- Extract GET and POST mapping from `StorytellerReaderRepository`.
- Remove direct remote writes from the application save path.
- Verify timestamp acceptance, conditional-write support, read consistency, and
  duplicate POST side effects against the actual supported server version.
- Preserve the original reading observation time; isolate any protocol-specific
  transmission metadata from conflict ordering.
- Return normalized remote snapshots and errors.

### Audiobookshelf

- Extract GET/PATCH mapping from `AudiobookshelfReaderRepository`.
- Use absolute progress snapshots and preserve unrelated media-progress fields.
- Verify ebook locator and audiobook-offset fidelity, timestamp semantics,
  duplicate effects, and any server-supported concurrency checks.
- Return actual capabilities rather than pretending to support revisions.

### Parrot Cloud / Supabase

- Retain RPC serialization, server identity mapping, and response decoding.
- Move outbox access, local writes, retries, cursor storage, and conflict decisions
  to shared components.
- Decode page boundaries and return continuation information to the engine.
- Preserve stable mutation IDs for identical request retries.
- Treat a stored rejection/conflict response as terminal for that mutation ID;
  after reconciliation or dependency repair, a genuinely new mutation gets a new
  ID rather than an edited payload under the old ID.
- Audit null base-revision behavior: absence of a version must not implicitly
  grant permission to overwrite an established remote position.
- Test concurrent first creation. `SELECT ... FOR UPDATE` cannot lock a missing
  row, so the existing check/insert/upsert sequence needs explicit concurrency
  validation and potentially stronger locking or conditional SQL.
- Audit concurrent duplicate mutation submissions and change-feed cursor order.
  Sequence allocation order is not necessarily transaction commit order; prove
  that a cursor cannot advance past an uncommitted lower change ID and miss it.
  If necessary, serialize the relevant account's mutations or change the feed
  protocol to provide a safe committed ordering.
- Apply any server correction through a new migration rather than rewriting a
  migration already deployed.

These are transport correctness requirements. They do not create a separate
Parrot scheduler or user workflow.

### Local-only

- Use the same record/observe/checkpoint contract.
- Store locally without accumulating an undeliverable remote queue.
- If the user explicitly enables a remote destination, bootstrap through the
  normal destination-binding and initial-reconciliation flow.
- Never report a no-op acknowledgement as cross-device delivery.

## 10. Platform execution

### Android

- Forward foreground/background and reader/player boundary events into shared
  operations. Do not depend solely on `onCleared` or termination callbacks.
- Register unique network-constrained one-time WorkManager work when pending
  remote work first appears; foreground execution can drain it sooner.
- Have the worker initialize the required pinned profile/session and call the
  same engine with a bounded execution budget.
- Recheck pending generations when a worker is finishing so an enqueue/finish
  race cannot strand a new update behind already-completing unique work.
- Recover database-commit/scheduler-enqueue gaps on startup and via a safety sweep.
- Consider an approximately 30-minute periodic recovery sweep while remote sync
  is enabled; perform no HTTP if no relevant work exists.
- WorkManager periodic work has a 15-minute minimum and inexact execution. Doze,
  force-stop, and OEM restrictions mean it is not a handoff deadline.
- Use expedited work selectively; avoid a permanent foreground service solely
  for progress delivery.
- Include the existing headless Android Auto and audiobook paths as producers.

### iOS

- Forward active/inactive/background scene events into the shared checkpoint and
  synchronization operations while preserving OAuth lifecycle handling.
- Request a short background-execution assertion for a flush that may continue
  across backgrounding. Start it before relying on extended execution, respect
  expiration, and end it promptly.
- Add `BGAppRefreshTask` registration and permitted identifiers for opportunistic
  recovery. Suggest earliest execution around 15–30 minutes when useful; actual
  execution time and execution itself are not guaranteed.
- Keep expiration/cancellation safe: unresolved mutations stay durable.
- Audit locked-device database/file protection and credential accessibility.
- Do not assume a network monitor wakes a suspended app or a KMP coroutine keeps
  running after suspension. Force-quit may prevent scheduled background work
  until the app is opened again.
- Expose needed dependencies through the existing KMP bridge convention:
  `SharedDependencies` getter functions, not direct Koin access from Swift.
- Prefer Kotlin platform implementations with minimal existing host wiring.

No platform timer is the source of truth. The outbox remains the recovery record.

## 11. Migration and rollout

### Data migration rules

1. Add new schema/state before switching writers.
2. Preserve current position rows and unresolved legacy mutations.
3. Resolve destination ownership from profile/account/server bindings; do not
   bulk-assign ambiguous legacy data to whichever account is active.
4. Do not assume every old outbox entry is unsent: previous requests may have
   reached the server without an acknowledgement. Retain their IDs and resolve
   uncertain delivery before destructive coalescing.
5. Use the local mutation sequence for same-device ordering; do not sort only
   by wall-clock timestamps.
6. Seed baselines by reconciling local and remote state. Existing local progress
   is not automatically newer than a server's progress.
7. Preserve unresolved candidates when destination or edition mapping is unclear.
8. Stop the legacy direct-write/manual-only engine paths when enabling the shared
   writer. Never run two owners of the same progress destination concurrently.
9. Keep existing non-progress outbox entities functional during the transition.
10. Make each migration restart-safe and verify database upgrade paths.

A staged engine flag may control rollout, but it applies to the common path,
not to a permanent Parrot-only workflow. Roll back scheduling if needed without
discarding durable data or restoring competing direct HTTP writers.

### Account transitions

- Pin account ownership on enqueue and execution.
- Stop or safely finish in-flight work before switching profile context.
- Paused/disabled sync retains pending work without silently rerouting it.
- Logout must not rebind old intent to a newly signed-in account.
- Review existing logout/database-clearing semantics and make destructive data
  removal an explicit user operation rather than an incidental retry action.

## 12. Implementation phases and exit criteria

### Phase 0 — Contract and capability inventory

Tasks:

- Enumerate all progress producers: ebook, ReadAloud, TTS, audiobook, headless.
- Document current position identities, mapping fidelity, and profile scoping.
- Verify supported Storyteller/Audiobookshelf endpoint guarantees.
- Finalize contract placement without introducing dependency cycles.
- Define continuous-audio checkpoint tolerance and exact-locator test fixtures.
- Add regression coverage for queued same-base-revision updates and dirty pulls.

Exit: agreed invariants, backend capability matrix, and reproducible tests for
the known progress-loss cases. Successful two-device percentage transfer remains
baseline evidence, not the acceptance criterion for exact resume.

### Phase 1 — Shared durable progress storage

Tasks:

- Add identity/binding, generation, baseline, and outbox state migrations.
- Implement atomic local save plus generation-safe pending coalescing.
- Persist all required locator fields.
- Implement conditional acknowledgements and unresolved-delivery recovery.
- Introduce the shared progress repository and preserve existing reader-facing
  use-case APIs where practical.
- Define a separate remote-apply operation that never enqueues user activity.

Exit: deterministic storage tests pass for crash boundaries, out-of-order local
events, newer updates during upload, and profile isolation.

### Phase 2 — Shared engine and all adapters

Tasks:

- Implement `SyncDataRepository` and shared reconciliation/retry/drain policies.
- Extract transport operations from every server repository.
- Move policy/database ownership out of `ParrotCloudSyncAdapter`.
- Preserve registration dependencies and existing library mutation handling.
- Implement safe pagination/checkpoint application and drain continuation.
- Address Supabase protocol concurrency findings through new migrations.
- Route `SyncNowUseCase` through the shared engine for integration testing.

Exit: all adapters pass the same engine contract suite and local recording no
longer performs HTTP. Manual invocation is a development checkpoint, not a
release architecture.

### Phase 3 — Reader integration and automatic foreground handoff

Tasks:

- Route every progress producer through the shared repository.
- Replace percentage-based reader conflict policy with shared reconciliation.
- Add debounce, maximum wait, rate limiting, resume/open refresh, and reconnect.
- Add update-origin suppression for restoration and remote positioning.
- Make reader close await local checkpoint and request application-scoped flush.
- Handle late remote results without unexpected navigation.

Exit: online device handoff works without manual sync for every remote adapter;
continuous updates do not cause per-event requests or indefinite debounce.

### Phase 4 — Platform recovery

Tasks:

- Add Android WorkManager integration and iOS lifecycle/background integration.
- Handle worker ownership, cancellation, expiry, and scheduling races.
- Verify DB/key access while locked and profile-safe background initialization.
- Add startup scans and bounded background continuation.

Exit: process death, lock, reconnect, and interrupted requests preserve committed
progress and recover when execution is allowed. No test assumes guaranteed
background deadlines or callbacks on forced termination.

### Phase 5 — UX, diagnostics, rollout, and cleanup

Tasks:

- Present common sync state and actionable errors in existing surfaces.
- Keep Sync now as immediate retry/refresh using the same engine.
- Add metrics, run device/backend matrix, and tune default intervals.
- Remove legacy policy paths and obsolete DI bindings after cutover.
- Update architectural docs to reference this shared-engine direction.

Exit: common behavior is enabled for all adapters, migration/rollback recovery is
tested, and reliability/freshness targets are supported by measured results.

## 13. Verification plan

### Automated engine/storage tests

Use a controllable clock, deterministic scheduler, fake transports, and a real
test database where transaction/migration behavior matters. Follow existing KMP
test conventions; use shared test fixtures where platform-compatible.

| Scenario | Required assertion |
| --- | --- |
| Hundreds of offline updates | Latest snapshot persists; unsent progress remains bounded |
| Save transaction interrupted | Position and remote intent commit together or not at all |
| Older save finishes late | It cannot overwrite a newer accepted local event |
| New generation during upload | Older acknowledgement cannot clear it |
| Several updates use one baseline | Latest intent survives; oldest queued state does not win |
| Remote pull while dirty | Local candidate is preserved and reconciled |
| Continuous event stream | Maximum wait causes eligible checkpoints |
| Duplicate lifecycle triggers | One coordinated drain; no duplicate concurrent write |
| Network exception or 429 | Persisted eligibility/backoff; no hot loop |
| Response lost after acceptance | Stable-ID recovery or safe readback; no silent rollback |
| Rejected mutation after dependency repair | New intent uses correct ID semantics |
| Replayed pull page | Idempotent application; cursor remains correct |
| More than one batch/page | Automatic continuation reaches caught-up state |
| Profile switch during request | Result is applied only to original owner |
| Backward seek/restart | Preserved as legitimate intent |
| Clock skew | No unconditional wall-clock winner |
| Local-only book | No growing remote queue or false delivery state |
| Legacy database migration | Data preserved; ambiguous dispatch/ownership handled safely |

### Adapter/protocol tests

- Validate serialized payloads against actual server contracts.
- Verify ebook and audio position round trips separately.
- Test auth refresh, conflict responses, partial results, and duplicate delivery.
- For Supabase, test concurrent first writes, duplicate IDs, null revisions,
  pagination under concurrent commits, and rejected-response replay.
- Validate no-op and limited-capability adapters using the same engine policies.

### Physical-device scenarios

Use emulator plus Xiaomi/another Android device, and a physical iPhone:

1. Read on A, lock A, open the book on B while online.
2. Open B just before A's flush completes.
3. Read offline on A, kill the process, reconnect, relaunch, then open B.
4. Read independently offline on A and B and reconnect in both orders.
5. Lose connectivity during a write and during its acknowledgement.
6. Lock during EPUB navigation, audio playback, seek, and playback pause.
7. Exercise Android process death separately from force-stop and Doze.
8. Exercise iOS suspension, background expiration, and force-quit separately.
9. Switch profiles/accounts while work is pending.
10. Verify exact chapter/locator/audio offset, not just rounded progress percent.

Run the appropriate Gradle module tests and Android debug assembly after code
changes. Build the applicable iOS simulator/framework compilation target without
using `assembleXCFramework`; validate lifecycle behavior on physical hardware.
This document-only change does not require an application build.

## 14. Operational acceptance and observability

Measure per destination and trigger:

- Local commit latency and failure count.
- Pending generations, unresolved requests, and oldest pending age.
- Upload attempts versus position events.
- Retry classes, conflict rate, and automatic/user-assisted resolution.
- Foreground checkpoint delay and handoff freshness.
- Time spent in lifecycle flushes and background expiration outcomes.
- Drain continuation count and checkpoint lag.

Do not log credentials or full reading content. Prefer scoped diagnostic IDs,
generation numbers, normalized error categories, and durations.

Initial targets under healthy foreground conditions:

- Routine remote checkpoint within 30 seconds of dirty progress.
- Approximately 2–4 routine progress writes per minute under continuous change,
  plus necessary lifecycle flushes; reads and dependency calls counted separately.
- No progress writes when the semantic position has not changed.
- Reader opening uses a bounded freshness budget and remains usable offline.
- No acknowledged-local progress loss in the tested interruption scenarios.
- No per-backend difference in required user actions.

Publish measured handoff latency rather than promising a universal lock-to-sync
deadline. A backend outage, suspended process, or unsent offline update remains
outside any immediate-delivery guarantee.

## 15. First implementation slice

Start with Phases 0 and 1, prioritizing:

1. The regression demonstrating same-base-revision queued progress loss.
2. Scoped identity and generation-aware transactional coalescing.
3. Conditional acknowledgement and dirty-pull preservation.
4. Complete locator persistence.

Then build the shared engine and adapt all transports before enabling automatic
triggers. This ordering prevents automation from amplifying the existing
conflict and queue bugs while preserving the parts of the implementation that
already work.
