# Durable, account-backed reading recaps

## Architecture and button integration

`generate-recap` authenticates a live, non-anonymous account and performs
submission, lookup, consent and deletion. It does not call the AI provider.
`recap_jobs` is authoritative; Supabase Queues (`pgmq`) stores job IDs only.
An insert wakeup starts processing promptly; conditional minute Cron recovery
retries lost wakeups and stale workers without requiring the originating app.
No external workflow service is needed.

Automatic generation and an on-demand session button share:

```kotlin
val result = recapRepository.request(sessionId)
```

Handle `QUEUED`, `IN_PROGRESS`, `ALREADY_GENERATED`, `TEXT_UNAVAILABLE` and
`ACCOUNT_REQUIRED`. Observe `observeRecap(sessionId)` for updates. Display
reads never generate. The **Generate recap** button is available in Statistics
→ Sessions → tap a session, below its recap status/summary. It is enabled for
waiting/retryable delivery only while consent and sign-in allow requests;
running/completed, ineligible and text-less historical sessions cannot start
another generation. Duplicate taps are guarded, request failures can be retried,
and late replies cannot overwrite another selected session's state.
Statistics-only historical sessions without captured, consented excerpts
return `TEXT_UNAVAILABLE`; do not reconstruct/upload their text silently.
Existing eligibility, excerpt bounds and backoff still apply.

Cross-device history resolves `cloud_books.id` through existing library IDs,
portable `CopyKey` book links and position links. Titles and device-local UUID
equality are never identity. An unlinked job can be recovered on its originating
device by session ID but cannot be attached to an unrelated book elsewhere.
Cloud caches and pending privacy commands are account-bound. Reader banners
require a known current total progression at or just after the recap endpoint
(within 0.02); unknown/earlier/far-later positions do not show a recap banner.

## HTTP contract

All calls use POST `/functions/v1/generate-recap`, a publishable `apikey` and
the user's bearer JWT. User identity is never accepted from a body field.

* Consent: `{ "operation": "consent", "enabled": true }` after displaying
  the new storage/sync disclosure. `false` cancels jobs and scrubs results.
* Submit: `{ "consentVersion": 2, "sessionId": "stable-session-id",
  "cloudBookId": "canonical-uuid-or-null", "endedAt": 1790000000000,
  "position": { "href": "chapter.xhtml", "totalProgression": 0.5 },
  "excerpt": "...", "language": "en", "lastSentence": "..." }`.
  Use JSON null, not the literal string shown above, for an unlinked book.
* Fetch: `{ "operation": "fetch", "sessionId": "..." }`, or
  `{ "operation": "fetch", "cloudBookId": "...", "cursor": 0, "limit": 100 }`.
  Returns `items`, `nextCursor`, `hasMore`, `consentEnabled`. Apply items
  before advancing the cursor. Pages are bounded to 100 records.
* Delete: `{ "operation": "delete", "sessionId": "..." }`.

Submission returns 202 for queued/running, 200 for terminal records and 409
for conflicting immutable input. Multipart uploads retain the existing
`upload: { id, index, total }, text` contract with the new submission metadata.
Lookup first after a lost response; joining the final parts and persisting the
job is transactional. Returned states are `queued`, `running`, `completed`,
`not_enough`, `failed`, `deleted`. Results never include excerpts/fingerprints.
401 requires sign-in, 403 denotes denied access/consent, 400/422 invalid input,
429 bounded staging/pending storage, 503 disabled/misconfigured generation.

## Guarantees and limits

* A unique `(user_id, session_id)` and account transaction lock serialize
  repeated admission. Different canonicalized payloads return conflict.
  Existing/deleted records are not regenerated under the same ID.
* Quota is charged atomically once when a worker first admits a job, not
  on upload/fetch or subsequent worker attempts. Quota-deferred jobs wait
  until UTC midnight, but never outlive their 24-hour text window.
* Workers claim a fenced three-minute lease, process one job with a 120-second
  provider budget, and persist completion before queue acknowledgement.
  Five worker attempts maximum; exponential backoff respects provider
  Retry-After (capped to one day). A crash leaves durable work to reclaim.
* This is **not exactly-once provider execution**: a crash after provider
  dispatch but before result commit can cause another billable provider call.
* Offline app submissions need connectivity before the server owns the job.
  Once admitted, app termination does not stop processing. Display caching is
  offline; deletion/withdrawal commands persist and flush on reconnect.
* Client wake paths: cold start (Koin app initializers **and** the first
  foreground resume), later foregrounds, connectivity restore, session end,
  user action and Android background work. The runner self-starts on any
  trigger, one broken app initializer cannot disable the others, stray
  cancellations cannot kill its loops, and startup-recovery failures never
  block delivery of due rows.
* Server cleanup is scheduled every 15 minutes. Pending excerpts/hints have a
  24-hour deadline (physical removal at the next cleanup, normally within
  15 additional minutes); terminal processing removes them immediately.
  Multipart staging expires after one hour. Summaries expire 180 days after
  terminal completion and are hidden by lookup at expiry, then scrubbed.
  Local cleanup runs at startup and hourly while the runner is alive.
* Withdrawal, session deletion, cloud book deletion and account deletion
  scrub content and fence late completions. Already dispatched provider text
  cannot be recalled. Provider-side retention is governed by its own policy;
  the Parrot TTL does not promise provider deletion or backup erasure.
* Content-free identity/fingerprint tombstones are retained until account
  deletion to prevent resurrection and preserve incremental deletion sync.
  They and sync events still consume database space; monitor growth.
  Unknown-session deletion fences require recap access and prior V2 consent
  (consent may now be disabled), and are limited to 100 per account per UTC
  day, 1,000 per account overall and 10,000 deployment-wide. Limit responses
  are HTTP 429; existing jobs can always be deleted without those budgets.
  Replayed deletions create no further events, and fences do not wake workers.
* Local migration 34 purges historical excerpts and does **not** upload them.
  Legacy summaries remain physically local but unknown-account legacy rows
  are hidden by account-isolated reads; no ownership is guessed.
  The old preference does not authorize new capture. Fresh V2 consent is
  required separately on each profile/device before capture and sync.

## Deployment checklist (not executed automatically)

1. Back up and inspect the hosted migration history **before** `supabase db
   push`. An older cloud-identity migration in this repository truncates cloud
   data; do not blindly apply pending historical migrations to production.
   Check pgmq availability, `pgcrypto` in `extensions`, current cloud tables,
   allowlist, quota settings and existing sync RPCs on a disposable staging
   project. Run all `supabase/tests/*.sql` there.
2. Apply `20261005000000_durable_recaps.sql`,
   `20261005000100_recap_deletion_fence.sql` and
   `20261005000200_recap_deletion_limits.sql` after the existing quota/security/
   multipart migrations. Jobs/consent are RLS-protected with no client table
   access; worker RPCs are service-only. This migration revokes client pgmq
   access globally: review this if adding other queues to the project.
3. Configure Edge secrets privately: existing provider key/model/enable flag
   described in `supabase/functions/generate-recap/README.md`, plus a random
   `RECAP_WORKER_SECRET` of at least 32 characters. Never put it in app config
   or git. Ensure the Edge service-role secret is available. With CLI linked
   to the reviewed staging project:

   ```sh
   supabase functions deploy generate-recap --no-verify-jwt
   supabase functions deploy recap-worker --no-verify-jwt
   ```

   Gateway JWT verification is disabled in `supabase/config.toml`; the user
   function verifies live user authentication itself. The worker accepts only
   its dedicated `X-Recap-Worker` secret, not user JWTs/publishable keys.
4. Enable Vault, Cron and pg_net. Create Vault secrets privately named
   `recap_project_url` (project URL without trailing slash),
   `recap_publishable_key`, `recap_worker_secret` (same worker secret).
   Execute `supabase/recap-scheduler.sql`. Confirm the three Cron jobs,
   wakeup trigger, queue visibility and cleanup. Do not expose pgmq/net/vault
   through the Data API. Review privileges and rotate secrets together.
5. Ship the client migration/new consent UI. Legacy clients now get a consent
   error rather than silently opting into server persistence. Existing
   synchronous consumers must update to the queued/fetch contract.
6. Stage real two-device tests: submit then kill app; reconnect/recover result;
   concurrent same-session submissions; conflicting input; worker crash/lease
   expiry; quota refusal; deletion during provider work; account/profile switch
   during every network/database suspension; offline withdrawal; physical book
   deletion; retention after simulated expiry. Inspect content-free logs only.

## Staying on Supabase Free

The design uses Supabase-native queues/Cron/Vault/pg_net. Free limits checked
during implementation: 500k Edge invocations/month, 500 MB database, 1 GB file
storage, 5 GB egress and 150-second Edge wall-clock; pricing can change.
Pending recap text is capped to 32 MiB globally/10 jobs per account; staging
is capped to 16 MiB. These are payload caps, **not** total database size caps
(indexes, WAL, bloat, summaries, events and book data need headroom).
Idle minute recovery performs no Edge call when the queue has no due message;
15-minute cleanup adds approximately 2,880 invocations/month. Each minute
recovery processes one job, with insertion wakeups providing extra throughput.
Paused projects and provider outages delay processing; 24-hour text expiry
can make a job permanently unavailable. Monitor DB size, invocation count,
due queue age, Cron errors and quota refusals. Set conservative global quotas.

## Validation

Local validation on 2026-10-03: **494 Kotlin host tests passed**, SQLDelight
migration verification passed, **84 Edge helper/provider tests passed**,
both Edge entrypoints type-checked, and **51 PostgreSQL/WASM SQL assertions
passed**. iOS compilation/device tests were not run.

Hosted deployment on 2026-10-03: linked `wtvwvhehsqxpexshicsr` (Parrot
project), applied the durable migration, deployed both recap Edge functions,
configured a fresh worker secret in Edge/Vault, and installed all three active
Cron jobs. All **16 hosted SQL suites (343 assertions)** passed. The two older
audit/blocklist tests needed owner-role assertions for private tables; no
production permissions were relaxed. Live unauthenticated API/worker calls
returned 401; authenticated idle worker and cleanup calls returned 200.
Deployment did not reset existing data or change existing provider credentials.
The hosted end-to-end test passed, including four genuinely concurrent HTTP
submissions, a real provider completion via automatic pg_net wakeup, exactly
one database job/quota charge, terminal excerpt scrubbing and deletion/
withdrawal. Its synthetic Auth account and cloud data were deleted afterward.
Remote `db lint --level error` returned no errors; Cron recovery runs succeeded
and pg_net recorded a 200 response. Physical worker-process crash injection,
two real app devices and iOS validation remain untested.

Client checks: reader data/domain/UI and statistics UI host tests, database
host tests, SQLDelight migration verification and home/settings compilation.
Server checks: `deno check` for both Edge entrypoints; `deno test
supabase/functions`; and the portable SQL test:

```sh
deno test --allow-read --allow-net --allow-env --allow-sys supabase/tests/durable_recaps_smoke_test.ts
```

The portable test uses PostgreSQL/WASM with real upstream pgmq SQL and
pgcrypto, but minimal Auth/cloud fixtures and assertion helpers. It is **not**
hosted Supabase/PostgREST, multi-connection concurrency or Cron integration.
Docker/Supabase CLI and a linked test project are required for those checks.

An explicit hosted end-to-end test creates and deletes a synthetic confirmed
Auth user/book. It tests live sign-in, consent, four concurrent submissions,
payload conflict, automatic wakeup, provider completion, quota accounting,
text scrubbing, deletion and withdrawal. It makes a real provider call and
may incur cost; run only on a disposable project:

```sh
python3 supabase/tests/hosted_recaps_smoke_test.py PROJECT_REF
```
