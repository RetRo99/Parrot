# Server-backed recaps and cross-device sync — implementation prompt

Implement server-backed recap generation and cross-device recap sync together in one phase.

Inspect the existing recap pipeline, Supabase security patterns, cloud book identity mapping, progress sync, and repository instructions before making changes. Reuse existing architecture rather than introducing parallel identity or sync systems.

## Goals

1. Generated recaps survive lost HTTP responses and app termination.
2. Retrying the same session does not generate another recap or consume quota again.
3. Signed-in devices can fetch recaps for the same cloud-linked book.
4. Recaps remain cached locally and available offline.

## Server

- Add durable recap jobs/results owned by the authenticated user.
- Use the originating session ID as an idempotency key, with a unique constraint on `(user_id, session_id)`.
- Associate linked books with the existing canonical cloud book identity. Never assume local book UUIDs match across devices or match books by title.
- Support unlinked books for originating-device recovery, without claiming cross-device book matching.
- Persist session metadata needed to display a recap without a local reading-session row: language, session end time, and relevant reading position.
- Make submission return an existing job/result when repeated. Reject conflicting payloads for an existing idempotency key rather than overwriting the original job.
- Generate through durable background processing, not processing whose survival depends on the original HTTP request.
- Implement atomic job claiming, bounded retries, backoff, stale-job recovery, and scheduled processing using mechanisms supported by this project's Supabase deployment.
- Preserve existing provider configuration, prompt safeguards, allowlist, kill switch, input validation, and quotas.
- Consume quota once when a job is admitted for generation, not on duplicate submissions, fetching, or internal retries. Document how quota deferrals and provider failures behave. Keep quota accounting and job admission atomic.
- Persist the completed result before reporting success.
- Provide authenticated result lookup by session ID and paginated/incremental retrieval by cloud book identity. Include deletion propagation so cached results do not reappear or remain indefinitely after deletion.
- Clients must not directly modify job status, summaries, ownership, or quota accounting.
- Enforce RLS and verify access to referenced cloud books. Derive user ownership from authentication, never request fields.
- Delete excerpts and last-sentence hints after terminal processing; enforce bounded retention for pending jobs and results.
- Never log excerpts, prompts, summaries, authentication tokens, or secrets. Safe operational metadata and token counts may follow existing diagnostics conventions.
- Integrate account/book deletion and prevent deleted jobs/results from being resurrected by an in-flight worker.
- Document the delivery guarantee honestly: database idempotency prevents duplicate submissions, but an external provider call can be repeated after a crash unless the provider supports idempotency. Minimize this window and bound retries; do not claim exactly-once provider execution.

## Client

- Adapt `CloudRecapEngine`, `RecapJobRunner`, repositories, and local schema to submit jobs and fetch results.
- Keep network failures distinct from server job failures: a lost submission response must be recoverable using the same session ID.
- Represent queued/running/not-enough/completed/failed states consistently.
- Fetch pending results on startup, foreground, connectivity restoration, and sign-in, with bounded polling/backoff.
- Fetch recaps for cloud-linked books across devices and cache them locally.
- Reuse existing cloud-to-local book identity resolution.
- Allow imported recaps without a corresponding local reading-session row; inspect current foreign keys and UI assumptions.
- Preserve originating-session linkage where available.
- Viewing a cached recap must never trigger duplicate generation.
- Handle profile/account switches safely: never send or display another account's text or results.
- Preserve existing eligibility, capture consent, and offline behavior.
- Define safe handling of existing local pending and completed recaps without silently uploading historical content.
- Honor existing position/banner rules so a fetched recap is not shown at an inappropriate reading position.
- Treat the server as authoritative for cloud job/result state while retaining a local display cache and offline submission queue.

## Privacy and UX

- Update consent, settings text, and documentation: excerpts are temporarily processed/stored, and summaries are stored in the account and fetched across devices.
- Do not silently expand prior local-only consent into server storage; require consent for the changed behavior using existing project conventions.
- Define behavior for disabling recaps, withdrawing consent, deleting a recap, and deleting an account.
- Ensure withdrawal stops submission/processing and removes temporary server text. Explain that text already sent to the provider cannot be recalled.
- Specify and implement concrete retention periods and user deletion behavior, including what happens to completed server summaries and local caches after withdrawal.
- Follow existing localization conventions for new and changed strings.

## Tests

Cover:

- Duplicate and concurrent submissions, including conflicting payloads.
- Response lost after job creation or completion.
- Worker crash, stale claims, and retry exhaustion.
- Quota charged once and deferred jobs resumed correctly.
- Cross-device fetch with different local book UUIDs.
- Recaps without local reading-session rows.
- Unlinked-book recovery.
- RLS and unauthorized book/result access.
- Account switching and consent withdrawal.
- Deletion during generation and deletion propagation to local caches.
- Retention cleanup and existing-local-data migration.
- Pagination/incremental fetch correctness and offline recovery.

## Deliverables

- Implement database migrations, durable processing, API changes, client integration, UI/consent updates, and tests in this phase.
- Add deployment documentation covering required secrets, scheduler/worker configuration, retention cleanup, and rollout order.
- Document the API contracts, state transitions, identity mapping, quota semantics, retry behavior, and privacy/retention policy.
- Run relevant automated tests and report their results. Identify tests that could not run and any deployment requirements or remaining limitations.
- Keep changes consistent with project patterns and do not overwrite unrelated work.
- Before implementation, present a concise design and identify any blocking product or infrastructure decisions. Then proceed within the agreed scope.
