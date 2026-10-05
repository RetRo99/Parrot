# Reading position restoration and conflicts

## Contract

- A conflict presents immutable local and remote candidates. Choosing one applies that
  candidate, not a second network fetch. The local candidate means **saved on this device**,
  not necessarily **read on this device**. Device attribution and observation time describe
  where and when the reading happened.
- Both book details and the reader use `ResolvePositionConflictUseCase`. Resolution is a
  database transaction: check the local generation, replace the position, remove superseded
  progress mutations and the remote baseline, and optionally enqueue a replacement.
- **Keep local** queues one replacement with the displayed remote revision and a fresh
  delivery timestamp. An automatic linked-copy position becomes a manual choice. This is
  queued delivery, not confirmation that the server accepted the write.
- **Use server** stores the displayed server candidate without posting it back. Its revision,
  reading time, device metadata, audio book time, and opaque ebook location are retained.
- A stale choice fails rather than overwriting newer local reading. Both dialogs refresh their
  candidates after a failed attempt; repeat taps cannot settle the same generation twice.
- Restoring the locator, repeated locator callbacks at that place, and closing without moving
  do not create a new reading checkpoint. Page turns or changes in narration time resume saves.
- Automatic linked-copy conflicts only yield to the server if the mutation still owns the
  current local generation. Late results for superseded mutations are ignored. Clean remote
  replacements advance the local generation, and concurrent queued saves allocate distinct
  generations inside their transactions.
- Pulls protect matching unassigned progress too: a choice made after a running pass bound its
  account is delivered on the next pass instead of overwritten by the current pull.

## Presentation

Conflict dialogs name the configured server (Parrot Cloud for a library book) and show device
and observation time when known. The Positions panel keeps differing local and server
candidates separately selectable, but previews each destination only once. Restored and
unattributed positions are explicitly labelled. Applying positions reports a local save waiting
for sync, not successful remote delivery.

## Backend boundaries

Storyteller uses its timestamp/409 protocol; Parrot Cloud uses revision checks and idempotent
mutation IDs. Audiobookshelf does not provide conditional writes or idempotency. A client
cannot retract a request already received by a server, or guarantee ordering against another
client on a backend without conditional writes. The local transaction and late-response guards
protect the device's chosen state; subsequent server changes still go through normal pulls.

## Regression coverage and manual checks

Automated tests cover both choices, revisions and routing, all old outbox states, offline cloud
baselines, failure rollback, stale generations, repeat choices, concurrent saves, late push
responses, local/remote panel candidates, restore-only checkpoints, and concurrent sync-status
updates. Transport suites cover the existing server wire formats.

For live-device/server QA (not performed by the unit tests):

1. Read offline on device A and advance the same copy on device B. Reconnect and verify both
   candidates, their named source and reading times.
2. Choose local on Parrot Cloud. Verify exactly one new mutation based on the displayed remote
   revision and eventual acceptance. Repeat with Storyteller and Audiobookshelf.
3. Choose server while offline, close immediately and reopen. Verify the chosen locator and
   that no new progress mutation was queued just by restoration or closing.
4. Move to another page or advance narration; verify ordinary progress saves resume.
5. Delay a push response, settle a conflict, then release the response. Verify it cannot recreate
   a local conflict or replace newer local reading.
6. Open Positions for a linked library/server book with differing candidates. Verify each is
   selectable and each apply target appears once. Verify the result says waiting to sync.
