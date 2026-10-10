# Prepared chapters in Parrot Cloud

The running record of this work. The standing specification is
`docs/tts-prepared-cloud-brief.md`. The phone-side feature this builds on is
`docs/tts-prepared-chapters.md`.

Branch `tts/prepared-cloud`, based on `ae1c40f8`.

## Run status

Step 0 is complete and the gate is **passed**: a prepared chapter can be one more
`cloud_book_files` row, and the compatibility risk in answer 3 below is preventable
from the server side.

Step 1 is written but **its SQL has NOT been run** — see below; nothing could be
executed on this machine. Steps 2 to 7 are not built.

### Can the local Supabase stack run here?

**No.** `scripts/supabase/test.sh` was run once before anything was changed. It fails
at the first step:

```
Connecting to local database...
{"_tag":"Error","error":{"code":"DbConnectError","message":"failed to connect to
postgres: ... dial error (connect ECONNREFUSED 127.0.0.1:54322)",
"suggestion":"Make sure Docker is running, then run: supabase start"}}
```

The reason is not a stopped daemon: there is **no container runtime installed on this
machine at all**. `docker` is not on `PATH`, `/Applications/Docker.app` does not exist,
`~/.docker/bin` does not exist, and `colima`, `podman` and `orbstack` are all absent.
The `supabase` CLI itself is present at `/opt/homebrew/bin/supabase`. So the stack
cannot be started from this run, and **no SQL written by this work has been run**
anywhere. Every migration this work produces must be run locally by the owner before
it is applied to anything.

### Baseline test counts, before any change

Read from the `build/test-results` XML, not from console summaries. Zero failures,
zero errors, zero skips in every module.

| Module | Task | Passed/Total |
| --- | --- | ---: |
| `feature/reader/ui` | `testAndroidHostTest` | 545/545 |
| `feature/reader/ui` | `iosSimulatorArm64Test` | 357/357 |
| `feature/books/data` | `testAndroidHostTest` | 121/121 |
| `feature/cloud-account/ui` | `testAndroidHostTest` | 23/23 |
| `feature/settings/ui` | `testAndroidHostTest` | 24/24 |
| `lib/server-parrot-cloud` | `testAndroidHostTest` | 62/62 |
| `lib/analytics/implementation` | `testAndroidHostTest` | 77/77 |
| `composeApp` | `testAndroidHostTest` | 64/64 |

Server suite: not run, see above.

## Step 0: the design answers

Line numbers are at `ae1c40f8`. Migration files are named by their timestamp prefix.

### 1. Can a prepared chapter be one more `cloud_book_files` row?

**Yes.** Nothing in the reserve, upload, finalize, download or delete path is written
for one file per book, and nothing is written for a closed set of media types.

- The table is keyed exactly as the brief hoped:
  `unique (cloud_book_id, media_type, relative_path)`
  (`20260922000000_parrot_cloud.sql:45`). Many rows per book are already the design.
- `cloud_book_uploads` has a matching partial unique index for live reservations,
  `cloud_book_uploads_one_active_slot` on `(cloud_book_id, media_type, relative_path)
  where status = 'reserved'` (`20260922000000_parrot_cloud.sql:72-74`). One chapter per
  slot, many chapters in flight, no contention with the ebook's own slot.
- **There is no media-type allow-list anywhere today.** The latest
  `reserve_book_upload` validates only that `media_type` is non-blank
  (`20260922000001_parrot_cloud_rpcs.sql:471`) and at most 64 characters
  (`20261003000000_parrot_cloud_security_hardening.sql:306`), mirrored by the table
  constraint `cloud_book_files_text_length_check`
  (`20261003000000...:102-105`). `finalize_book_upload`, `create_book_download`,
  `delete_book_file`, `complete_book_file_deletion`, `admin_takedown_book_file` and the
  orphan GC all address a file by its `id` and never branch on its media type.
- The storage path is media-type-free:
  `'users/' || actor || '/books/' || cloud_book_id || '/' || file_id`
  (`20260922000001_parrot_cloud_rpcs.sql:626`). All four `storage.objects` policies match
  on the path and the owner, never on a media type
  (`20260922000002_parrot_cloud_storage.sql:9-78`, replaced for the allow-list in
  `20261003000000...:211-263`).

What has to change for the new media type:

- **Allow-lists.** None has to be *relaxed*. One should be *added*: today any 64-character
  string is an acceptable media type, so the new type needs a positive, named check so it
  gets its own rules rather than inheriting the ebook's. Additive, in the new migration.
- **The bucket's MIME types.** **No change needed.** The bucket allows
  `application/octet-stream` (`20261003000000...:80`), and the client's
  `TusUploadMetadata.contentType()` already falls through to exactly that for a media type
  it has no mapping for, with a file name extension it has no mapping for
  (`lib/cloud/implementation/.../transfer/TusUploadPolicies.kt:18` and `:30-41`; `zip` is
  not in that list). A stored zip uploads as `application/octet-stream` with no bucket edit.
- **Size limits.** **Change needed.** The bucket limit is 2 GiB
  (`20261003000000...:70`) and `reserve_book_upload` rejects above the same 2 GiB
  (`20261003000000...:313-315`). The brief wants 64 MiB per chapter file, so the new
  migration adds a per-media-type cap. Additive; the ebook path keeps 2 GiB.
- **The rights attestation.** **No change needed, and it still applies.** Reserve requires
  a JSON object carrying `attested_at`, `tos_version` and `attestation_version` or answers
  `attestation_required` (`20260922000001_parrot_cloud_rpcs.sql:485-493`). Prepared audio
  is derived from a book the user already attested to, so the client supplies the same
  three fields; the server is unchanged.

### 2. What happens today to a book's other files, and what must be added?

Three of the four paths leave a book's other files alone. Only account deletion already
does the right thing.

| Event | Today | Needed |
| --- | --- | --- |
| Its ebook file is deleted | `delete_book_file(cloud_book_file_id)` marks **that one row** `deleting` and nothing else (`20260924000002_parrot_cloud_delete_incomplete_backup.sql:1-162`). Prepared audio survives. | Cascade |
| Admin takedown | `admin_takedown_book_file(cloud_book_file_id, ...)` blocks that row's hash and marks **that one row** `deleting` (`20260925000006_parrot_cloud_drop_unified_library_residue.sql:92-207`). Prepared audio survives. | Cascade |
| Blocked by content hash | `admin_block_content_hash` only inserts the block row and audit events; **it deletes nothing** (`20260922000001_parrot_cloud_rpcs.sql:1393-1443`). The block then stops reserve (`...:503-509`), finalize (`...:803-843`), `create_book_download` (`...:1066-1072`) and the storage read policy (`20260922000002...:58-63`) — but all four match **on the blocked hash**. Prepared audio has its own, different hash, so it stays downloadable after its book is blocked. | Cascade |
| Account deletion | Already correct. `cloud_book_files.cloud_user_id references auth.users(id) on delete cascade` (`20260922000000_parrot_cloud.sql:29`), and the file's storage object lives under the same `users/<uid>/` prefix the service-role worker clears. | Nothing |

So the new migration must add, for the first three, a step that finds the book's
prepared-audio rows and puts them through the same `deleting` handshake, releasing their
bytes through `complete_book_file_deletion`, which already charges and credits any file
row generically (`20260925000006...:11-85`). The orphan GC needs nothing: it selects
candidates from `cloud_book_files` without reference to a media type
(`20260924000000_parrot_cloud_orphan_gc.sql:309-549`).

### 3. How a device learns what files a book has, and what today's apps do with an unknown media type

**The feed.** The server writes one `sync_changes` row per file transition, entity type
`book_file`, with a payload that already carries `media_type` and `relative_path`
(for example `20260922000001_parrot_cloud_rpcs.sql:675-691` on reserve,
`...:942-958` on finalize, `20260924000002...:130-146` on delete). `pull_sync_changes`
returns **every** such row for the account with no filter at all
(`20260922000001_parrot_cloud_rpcs.sql:390-409`). So an older app on the same account
*will* receive a prepared-audio file change. There is no opting an old client out of the
existing feed.

**What the current app does with it.** The same code runs on Android and on iPhone: all of
it is `commonMain`.

1. `ParrotCloudBookFileChangeApplier.apply`
   (`lib/server-parrot-cloud/.../ParrotCloudBookFileChangeApplier.kt:25-61`) is
   **media-type agnostic and safe**. It parses with `Json { ignoreUnknownKeys = true }`
   (`:23`), and writes the row into the local mirror keyed by
   `(libraryBookId, mediaType, relativePath)` (`:46-60`). An unknown media type does not
   throw, and does not overwrite the ebook's row. No crash.
2. The mirror is then read in exactly two places that matter, and the dangerous one is
   `LocalBooksRepository.toLibraryServerBook`
   (`lib/server-local/.../LocalBooksRepository.kt:77-137`), which turns mirror rows into
   the `MediaResource` list the whole library and book-detail UI is built from. **It
   filters on an empty relative path before anything else:**
   `parrotFiles.filter { file -> file.relativePath.isEmpty() }` (`:83`).

That one line is the whole answer. **A row whose `relative_path` is not empty never
becomes a `MediaResource`, so it is invisible to every consumer of the mirror.** Were the
relative path empty, three real misbehaviours follow, and they are worth naming because
they are what the gate is protecting:

- `LocalBooksRepository.kt:104-107`: `visible` becomes true on an `Available` remote
  availability alone, so a book with **no** ebook backup would start appearing in the
  library as a cloud book, offering nothing to open.
- `CloudBackupSelection.kt:26`: a book is dropped from the "back up" banner and sheet if
  **any** resource is `Available`/`UploadPending`/`Uploading`. Prepared audio in the cloud
  would silently hide a book whose ebook is *not* backed up from the list of books
  offered for backup.
- `BookDetailViewModel.kt:641-643`: "Remove from Parrot Cloud" collects
  **every** media type with a `cloudBookFileId`, so the sheet would pass an unknown type
  into the removal use case.

Two further things are already safe regardless, and are worth recording because they
bound the blast radius:

- Downloading prepared audio **as a book is impossible even on purpose**:
  `BookFileTransferEngine.enqueueDownloadLocked` opens with
  `require(mediaType == BookType.EBOOK.value || mediaType == BookType.READALOUD.value)
  { "Only EPUB cloud files can be restored" }`
  (`feature/books/data/.../transfer/BookFileTransferEngine.kt:110-112`). Download is
  always driven by a caller-named media type, never by "whatever the cloud holds".
- `BookDetailViewModel.kt:621-623` maps a resource to a `BookType` with
  `BookType.entries.firstOrNull { it.value == resource.mediaType } ?: return@mapNotNull null`,
  so an unrecognised media type is dropped from the per-media action map even if it did
  reach the UI.

**How the risk is handled.** The prepared-audio `relative_path` is **never empty**, and
that is enforced on the **server**, by a `CHECK` constraint on `cloud_book_files` and a
rejection in reserve for the new media type — not by client convention. A client of any
version, including a future buggy one, cannot put a prepared-audio row where an older app
would read it. Because that holds, the existing payload is safe to keep carrying prepared
audio, and the brief's fallback of "leave it out of the existing payload and serve it by a
new call" is **not needed** — which is the better outcome, since the existing feed is also
what drives `BookFileTransferManager.invalidateCloudFile`
(`ParrotCloudBookFileChangeApplier.kt:32-36`) and so keeps deletion and takedown
propagating to devices for free.

**iPhone.** Nothing changes for it and nothing can break. It runs the same `commonMain`
applier and the same `LocalBooksRepository` gate, so prepared-audio rows are inert there
too; the only iOS-specific file-transfer code is
`feature/books/data/src/iosMain/.../IosBookFileTransferFileStore.kt`, a file store that is
never reached for a media type no one enqueues. iOS has no prepared store and no TTS
preparation at all.

### 4. How a book is identified across devices, and the local prepared folder

- `cloud_books.id` **is the client's library book id**: a random UUID the client chooses,
  the server's `default` removed, documented at the head of
  `20261001000000_parrot_cloud_client_book_ids.sql:1-3` and applied at `:32-33`. The
  source content hash is only a duplicate hint (`:26-31`).
- The change applier states the same mapping in the other direction — "The server's
  `cloud_book_id` is the library book id" — and looks the book up by it
  (`ParrotCloudBookFileChangeApplier.kt:37-39`).
- So after a second device syncs, **both devices hold the same `libraryBookId` for the
  same book**, because both took it from `cloud_books.id`.
- The local prepared store hashes that id with the server id:
  `File(root, hash("${serverId?.length ?: -1}:$serverId$bookId"))` then
  `hash(chapterHref)` (`feature/reader/ui/.../tts/TtsPreparedStore.kt:61-63`).
- In production those three values come from the open publication:
  `PreparedChapterId(bookId = epubPublication.bookUuid, serverId = epubPublication.serverId,
  chapterHref = chapterHref)` (`.../navigator/AndroidTtsController.kt:823-827`). For a
  library book `bookUuid` is the `libraryBookId` and `serverId` is the constant
  `LOCAL_SERVER_ID = "local"` (`base/.../server/ServerType.kt:7`).

**Therefore the chapter folder path is identical on both devices** — `sha256("5:local" +
libraryBookId)/sha256(chapterHref)` — which is what makes a download from the cloud
installable into the second device's store without any remapping. The cloud relative path
must still be built from the chapter href hash and a settings hash only, never from the
folder name, so that no id leaks into the server.

### 5. The upload allow-list

Prepared audio follows the same gate, with no new mechanism. `cloud_feature_enabled('uploads')`
(`20261003000000_parrot_cloud_security_hardening.sql:24-40`) is checked in
`reserve_book_upload` (`:297-299`), patched into `finalize_book_upload` (`:194-198`), and
required by both `storage.objects` write policies (`:217`, `:238`, `:250`). All three are
media-type agnostic, so an account that is not allow-listed cannot reserve, write or
finalize prepared audio either. The client reads
`get_cloud_feature_access()` (`:43-58`) to hide what the account cannot do.

### The gate

Answer 1 is yes. Answer 3 shows the one real compatibility risk — an older app reading a
prepared-audio row as one of the book's own media resources — and shows it is prevented
from the server side by a never-empty `relative_path`, enforced by a constraint rather
than by client agreement. **Step 0 does not block. Step 1 may start.**

## Step 1: the server

One new migration, `supabase/migrations/20261010000000_parrot_cloud_prepared_audio.sql`,
and one new pgTAP file, `supabase/tests/prepared_audio_test.sql` (`plan(38)`). The
owner's runbook is `supabase/PREPARED_AUDIO_ROLLOUT.md`.

### THE SQL HAS NOT BEEN RUN

Not once, not in part. There is no container runtime and no Postgres binary of any
kind on this machine (`docker`, `colima`, `podman`, `orbstack`, `psql`, `postgres`,
`initdb` and `pg_ctl` are all absent; only the `supabase` CLI is installed), so the
local stack could not be started and `scripts/supabase/test.sh` could not execute.
What was verified instead is only structural: balanced dollar-quote tags, balanced
`begin`/`end`, balanced parentheses, and every line cited from an existing migration
re-read at its stated line. **That is not a substitute for running it.** The brief is
explicit that the owner does not apply SQL that has not been run, and this SQL has
not been run. `PREPARED_AUDIO_ROLLOUT.md` step 1 is therefore mandatory, not
optional, and the 38 new assertions have never been seen to pass or fail.

A consequence worth stating plainly: the usual discipline of writing a test, watching
it fail, then making it pass was **impossible** here. The test file was written before
the migration body, in that order, but no red run and no green run exists.

### The contract

Media type `tts_prepared_audio`. Relative path
`tts-prepared/<sha256(chapter href)>/<sha256(voice id, model version, rate, pitch)>.zip`
— two 64-character lowercase hex hashes and nothing else, so no title, href or text
can appear in it. Storage path, bucket and MIME type are unchanged from any other
book file.

### What the migration changes

1. **Four table constraints**, `NOT VALID` then validated, in the style of
   `20261003000000`: on both `cloud_book_files` and `cloud_book_uploads`, a
   prepared-audio row's `relative_path` must match the two-hash pattern, and its
   `size_bytes` must be at most 64 MiB (67108864).
2. **`book_has_available_backup(uuid)`**, a new private helper: true when the book
   has an `available` file that is not prepared audio and whose content hash is not
   on the block-list. This is both the precondition for uploading audio and the test
   the cascade uses to decide when audio has to go.
3. **`reserve_book_upload` is wrapped.** The current definition is renamed to
   `reserve_book_upload_before_prepared_audio` and re-executed with its
   self-qualified parameters rewritten — the same `pg_get_functiondef` and `replace`
   dance `20260924000004:8-22` used for the previous rename, and necessary for the
   same reason. The new wrapper adds, for `tts_prepared_audio` only: the upload
   allow-list first (so the answer does not depend on media type), then
   `invalid_upload_metadata` for a path that is not two hashes (**including the empty
   path**), `file_too_large` above 64 MiB, `cloud_book_not_owned`, `content_blocked`
   when the book's own backup hash is blocked, and `book_backup_unavailable` when the
   book has no usable backup. Every other media type falls straight through
   untouched.
4. **Three cascades, so audio never outlives its book.** `cascade_prepared_audio_deletion`
   puts every prepared chapter of a book through exactly the lifecycle
   `delete_book_file` gives an ordinary file — an `available` row is marked
   `deleting` and its bytes are credited by the existing `complete_book_file_deletion`;
   a row that never became available has its live reservation released and is then
   either orphan-accounted and marked `deleting` (its object did materialise) or
   deleted outright (it did not), because its bytes were only ever *reserved* and must
   not be credited twice; a row already `deleting` is skipped, which makes the whole
   thing idempotent. It is reached two ways:
   - an **`after update` trigger on `cloud_book_files`** when any non-prepared-audio
     row enters `deleting` and no usable backup is left. This covers
     `delete_book_file` **and** `admin_takedown_book_file` with one trigger and no
     further renames, and will cover anything added later that marks a file deleting.
     The media-type condition in the `when` clause is what stops it recursing.
   - an **`after insert` trigger on `cloud_content_blocklist`**, unconditional for
     every book holding a non-prepared file of the blocked hash. This one needs its
     own path because `admin_block_content_hash` deletes nothing and every block check
     matches on the blocked hash, which a book's prepared audio does not share — so
     without it, blocking a book leaves its audio downloadable.
   - **account deletion and orphan GC need nothing**, as Step 0 answer 2 established.
5. **`get_storage_usage` gains `prepared_audio_bytes` and `books_bytes`.** All five
   existing keys keep their name and meaning. `books_bytes` is derived as the
   remainder rather than summed, so the two parts always add up to the `used_bytes`
   the account is actually charged.

### What it deliberately does not change

The bucket (its MIME types already allow `application/octet-stream`, which is what
`TusUploadMetadata.contentType()` produces for this media type, and its 2 GiB limit
stays above the new 64 MiB reserve cap); the rights attestation; the upload
allow-list mechanism; and every RPC not named above. No existing migration was
edited. No existing pgTAP test was edited.

### Existing tests that must still pass unedited

The migration replaces `reserve_book_upload` and `get_storage_usage`, so
`book_files_test.sql`, `finalize_book_upload_test.sql`, `rls_isolation_test.sql`,
`abuse_operations_test.sql`, `security_hardening_test.sql`,
`non_available_book_delete_test.sql`, `orphan_gc_test.sql` and
`storage_policy_test.sql` all exercise replaced functions. None was edited. If any
fails when the owner runs the suite, the wrapper has changed existing behaviour and
must be fixed rather than the test.

### Judgements made here, so they can be overruled

- **Deleting one of two representations keeps the audio.** The cascade asks
  `book_has_available_backup`, so deleting a book's EPUB while its audiobook copy
  stays backed up does *not* remove prepared audio. "Deleting the book's backup"
  reads to me as the last usable copy going, not any one file. If the owner means any
  file, the trigger drops its condition and becomes unconditional.
- **A takedown reaches the audio twice, harmlessly.** `admin_takedown_book_file`
  inserts the block row *and* marks the file deleting, so both triggers fire; the
  second finds nothing left to do.
- **The device hears about the audio slightly before the book.** The cascade's
  `sync_changes` rows get lower `change_id`s than the ebook's own, because the
  `after update` trigger runs inside the same statement. Each change applies
  independently, so this is cosmetic.

## Steps 2 to 7

Not built.
