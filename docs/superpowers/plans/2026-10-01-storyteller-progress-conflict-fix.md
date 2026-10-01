# Storyteller progress push stuck on HTTP 409 — bug note and fix plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Status:** suspected bug, found by reading code on 2026-10-01. It hasn't been reproduced yet.
Task 0 confirms it.

**Goal:** when Storyteller already holds a newer position than the one we push, the app accepts
Storyteller's position (or keeps both as a visible conflict) instead of retrying forever.

## What's wrong

**How Storyteller behaves:** `POST /api/v2/books/{uuid}/positions` only accepts a position whose
`timestamp` is newer than the one stored on the server. Otherwise it answers **HTTP 409**
("Timestamp older than server state").

Evidence: BookBridge (`cporcellijr/bookbridge`), `src/api/storyteller_api.py`, `update_position`.
It handles `status_code == 409` as "Timestamp older than server state (Ignored)" and returns
success "to prevent retry loops".

**This app:** `lib/server-storyteller/.../StorytellerProgressTransport.kt`, `pushProgress`, maps
**every** failure, 409 included, to `ProgressPushResult.Rejected`.
`feature/sync/data/.../ProgressSyncEngine.kt` reacts to `Rejected` with `scheduleRetry`, an
exponential backoff capped at 2^6 seconds, with no limit on the number of retries.

**What users probably see:**
1. Read offline on device A, while device B (or Storyteller's own app) moves further on and syncs.
2. A comes online and pushes its older position. Storyteller answers 409.
3. A's outbox entry retries about every minute, forever. Its sync status keeps showing a pending
   or failed item.
4. A never takes Storyteller's newer position for that book, because `applyRemote` keeps the local
   position while a local change is pending.
5. Every retry also logs a `NetworkRequestFailed` analytics event (`KtorNetworkClient`), so one
   stuck book sends roughly one event a minute, indefinitely.

## Fix

The engine already has the right result type. `ProgressPushResult.Conflict(remote)` makes
`ProgressSyncEngine.preserveConflict` apply the remote snapshot (as the stored remote, or as local
when nothing is pending) and mark the outbox entry as a conflict, which stops the retries. Parrot
Cloud's transport already uses it (`ParrotCloudProgressTransport.kt`, around line 127).

In `StorytellerProgressTransport.pushProgress`:
- **`AppError.ApiError` with code 409:** fetch the server's current position for that book (the
  same request as `fetchProgress`) and return `ProgressPushResult.Conflict(mutationId, remote)`.
  If that fetch fails, return `Rejected` with a long retry delay (`retryAfterMillis`, for example
  15 minutes). Never retry a 409 on the short backoff.
- **Other errors:** unchanged.

**Already confirmed:** `KtorNetworkClient.handleHttpError` / `handleClientError` turn any 4xx
other than 401, 403 and 404 into `AppError.ApiError(code = errorCode)`. So a 409 arrives as
`ApiError(code = 409)`, and the network client needs no change.

**Then check:** the existing position-conflict prompt (`PositionConflictDialog` and
`ResolvePositionConflictUseCase`) is shown for books with a conflicted outbox entry, so the person
chooses. If it isn't, the conflict stays silent: report that, and don't change the dialog in this
fix.

## Tasks

### Task 0: Reproduce (before changing code)

Use a test book on the user's Storyteller server.
1. On emulator A, read to 30%, turn off the network, and read to 35%.
2. On emulator B, or in Storyteller's web or mobile app, read to 60% and sync.
3. Turn A's network back on and trigger a sync. Record the HTTP response and whether A's outbox
   entry keeps retrying (logs and the sync status screen).

### Task 1: Map 409 to a conflict

**Files:**
- Modify: `lib/server-storyteller/src/commonMain/.../StorytellerProgressTransport.kt`. The network
  client already keeps status codes; see "Already confirmed" above.
- **Tests** (in `lib/server-storyteller/src/commonTest/`, using a fake network client):
  - 204 gives `Accepted`.
  - 409 followed by a successful fetch gives `Conflict` carrying the fetched remote snapshot.
  - 409 followed by a failed fetch gives `Rejected` with a long `retryAfterMillis`.
  - 500 gives `Rejected` with no `retryAfterMillis`, as today.
- **Engine test** (`feature/sync/data`): after a `Conflict`, the outbox entry is marked conflict
  and isn't retried, and the remote position is stored.

### Task 2: Check again

Repeat Task 0. Expect one 409, then no further retries, and A showing Storyteller's 60% (or the
conflict prompt, if a local change was pending). Report what you saw.

## Related

- **Project 3** (`2026-10-01-progress-across-linked-copies.md`) depends on this fix.
  - Automatic propagation sends the source's original reading time as Storyteller's `timestamp`,
    so a 409 is the *expected* answer when Storyteller holds newer reading.
  - The positions panel sends the current time, so its writes win, which is deliberate: the person
    chose them.
- The timestamp we send is also the echo marker (project 3, §1.4, guard 2), so the write log must
  record exactly the value sent.
