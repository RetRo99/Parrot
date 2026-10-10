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
executed on this machine, and that is still true: there is still no container runtime
here. Steps 2 and 3 are complete. Steps 4 to 7 are not built.

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

## Step 2: one file per chapter

`TtsPreparedChapterArchive` (`feature/reader/ui/src/androidMain/.../tts/TtsPreparedChapterArchive.kt`),
pure logic over `java.io.File` and `java.util.zip`, in the pattern of
`TtsPreparedStore`: no Android `Context`, no Koin binding yet, nothing injected.
`TtsPreparedChapterArchiveTest` (androidHostTest, beside `TtsPreparedStoreTest`) is 22
cases on real temporary files. **21 of the 22 failed against the stub** before the
implementation; the twenty-second — "a file that is not a zip at all leaves nothing
behind" — passed fail-closed, because the stub rejected everything.

### The format

A **stored** zip. The audio is already AAC-LC in `.m4a`, so nothing is recompressed and
the payload is byte-identical through the round trip; the test asserts the entry sizes
come back at exactly 1,200 and 1,800 bytes.

Entry order is part of the format: **`manifest.json` is always first**, so an unpack
knows what it is allowed to accept before it writes a single byte. Then one `.m4a` per
*distinct* sentence key, in manifest order. Duplicate keys share one audio file while
the manifest keeps their repeated ordered positions — tested.

The name carries hashes only:

```
tts-prepared/<sha256(chapter href)>/<sha256(voice id, model version, rate, pitch)>.zip
```

matching the shape the server constrains `relative_path` to. The settings hash
length-prefixes each part (`"${voice.length}:$voice${model.length}:$model…"`) so no
voice id can impersonate a delimiter, and rate and pitch are taken at the store's own
hundredth precision so "same settings" means the same thing here as in
`TtsPreparedStore.sameSettings`. A test asserts a title-bearing href
(`"Chapter One: The Beginning.xhtml"`) leaves no trace in the path, and that the same
chapter at another rate is a different file but the same chapter folder.

**An incomplete chapter is never packed.** A manifest that is not `complete`, or has
any sentence with a null duration, is refused with `CHAPTER_INCOMPLETE` and no file is
written at all — tested, including that the destination does not exist afterwards.
Staging files, `.part` files and stray files in the chapter folder are never packed;
only `manifest.json` and the files the manifest lists.

### The checks on unpack, each with a test written first

Unpacking trusts nothing — not the entry names, not the declared sizes, not the central
directory (it reads with `ZipInputStream`, so only local headers are ever consulted).

| Rejection | What triggers it |
| --- | --- |
| `MANIFEST_NOT_FIRST` | anything before `manifest.json` |
| `UNSUPPORTED_VERSION` | a `formatVersion` that is not 2 |
| `IDENTITY_MISMATCH` | a manifest naming another book id, server id or chapter href |
| `CHAPTER_INCOMPLETE` | not `complete`, no sentences, or a null duration |
| `UNSAFE_ENTRY` | any name that is not a plain `<64 hex>.m4a` — covers `../escaped.m4a`, `/etc/passwd`, `nested/<key>.m4a` and `notes.txt` |
| `UNLISTED_ENTRY` | a well-formed key the manifest does not list |
| `DUPLICATE_ENTRY` | the same entry name twice |
| `MISSING_ENTRY` | a sentence the manifest lists that the archive does not carry |
| `SIZE_MISMATCH` | bytes actually read ≠ the manifest's `bytes` |
| `TOO_LARGE` | the archive file, or the running total while reading, over the bound (64 MiB by default) |
| `UNREADABLE` | not a zip, truncated, unparseable manifest, or a manifest with an invalid key, a non-positive size or a non-positive duration |

Two of those deserve a note. **Escaping the target folder is impossible by
construction**, not by sanitising: the only accepted names are a 64-character lowercase
hex key plus `.m4a`, which cannot contain a separator or a `..` segment at all. And
**the size bound is enforced while reading**, not only from the header, so a local
header that lies about its length cannot fill the disk — the read stops at the declared
sentence size and the mismatch is then reported.

### What a rejected archive leaves behind

**Nothing.** Everything is written into a staging folder `unpack-<uuid>` beside the
target, and the target is touched only after every check above has passed — then the
old folder is removed and the staging folder is moved into place. Three tests pin this:
a truncated archive, a file that is not a zip at all, and a size-mismatched archive
aimed at a folder that already holds a good chapter. The third asserts the installed
audio is still byte-for-byte what it was. Every rejection path also asserts no
`unpack-…` or `.part` folder survives anywhere under the test root.

A round-trip test installs a packed chapter into a *second* `TtsPreparedStore` and
reads it back through the store's own API: `state()` returns `Ready(3000)` and
`lookup()` returns the right duration and file length. That is the real contract — the
chapter has to be playable by the store on the other device, not merely present.

### Known limits of this step

- Replacing the destination is delete-then-move, so a crash in the gap between them
  leaves the chapter absent rather than half-written. That matches
  `TtsPreparedStore`'s own publish and is recoverable by downloading again.
- No checksum of the archive as a whole is verified here; the cloud layer already
  verifies a SHA-256 of the uploaded bytes, and Step 3 passes that through.
- Nothing calls this yet. It has no Koin binding, because nothing injects it until
  Step 3, so there is no new `@Single` to resolve from the real graph at this step.

## Step 3: upload

**Complete.** The upload path, its states and its analytics were built first; the two
device-side triggers, the unmetered-network signal and the thing that actually calls the
engine are there now too, so a prepared chapter is backed up without anyone asking.

### No second uploader

The brief forbids a second uploader, and there is not one. `BookFileTransferEngine`
gained exactly two things, both additive, so that a prepared chapter can go through its
existing reserve, resumable upload, finalize, retry and persisted queue:

- **`cloud_file_transfers.relative_path`**, defaulting to `''`. The engine previously
  hard-coded `relativePath = ""` in `createRequest`, `failPermanently` and
  `cancelTransfer`; those now read the transfer's own path, and `''` is exactly what an
  existing row means.
- **`cloud_file_transfers.source_path`**, nullable. A book file's bytes are found
  through the device-files row for `(book, media type)`; a prepared chapter archive is
  not a device file and has no row there, so it names its own path. `NULL` means "look
  it up the old way".

Local schema migration `42.sqm` (version 42 → 43), with
`PreparedAudioTransferMigrationTest`: a new database round-trips both columns, a book
file still means the empty path and no source, an existing version-42 row survives the
upgrade reading as the book's own file, and the upgraded table matches a fresh one.

`pruneSupersededTransfers` now also keys on the relative path, so one chapter's
finished transfer is no longer mistaken for another's.

#### The schema mismatch the first run left, and what it actually was

`42.sqm` adds the two columns with `ALTER TABLE ... ADD COLUMN`, which puts them at the
**end** of the table on an upgraded device, but `CloudFileTransfer.sq` first listed them
in the **middle** of `CREATE TABLE`, after `rights_attestation`. So a freshly created
database and an upgraded one disagreed on column order and
`:lib:database:implementation:verifySqlDelightMigration` **failed**, naming the three
index `ordinalPosition` values that moved. The two columns are now at the end of the
`CREATE TABLE`, with a comment saying why, and the task passes. `42.sqm` was not
touched, and no earlier migration was touched. These are the only two files on this
branch that changed a `.sq` or a `.sqm`.

What this was **not** is data corruption, and the record should say so plainly rather
than repeat the expected diagnosis. The worry was that the six `SELECT *` queries in
that file would read every column of an upgraded row from the wrong slot. They do not.
SQLDelight **expands `SELECT *` at generation time into an explicit, named column
list** — the generated `GetCloudFileTransferQuery` issues
`SELECT cloud_file_transfers.transfer_id, cloud_file_transfers.server_id, ... FROM
cloud_file_transfers WHERE transfer_id = ?` — so the positional
`cursor.getString(n)` calls in the generated reader are positional over a projection
the generated SQL itself names, in the `.sq` file's declared order, not over the
physical table. No literal `SELECT *` survives anywhere in the generated code
(`grep -c` over the whole generated tree: 0). Every insert statement in this file names
its columns too. So on an upgraded install reads were already correct, and the failure
was a schema-equivalence failure only.

That is pinned by a test, written before the fix:
`PreparedAudioTransferMigrationTest."an upgraded transfer reads back field for field
through the query the DAO uses"` builds the database at version 42, inserts a transfer
with a distinct value in all 22 of the then-existing columns and a real `state` of
`transferring`, migrates to 43, and reads the row back through
`cloudFileTransferQueries.getCloudFileTransfer` — the generated query the DAO's
`getTransfer` calls. All 24 fields are asserted: `state`, `attempt_count`, all three
timestamps and every other value are what was written, `relative_path` is `""` and
`source_path` is null. **It passed before the fix as well as after**, for the reason
above. It is kept because it is the assertion that makes the reason true rather than
believed: if a future change makes a reader positional over the physical table, this
test fails and `verifySqlDelightMigration` alone would not say what broke.

New domain members, each **defaulted on the interface** so no other implementation --
including two existing test fakes -- had to change: `enqueueAuxiliaryUpload`,
`cancelUpload`, `deleteRemoteFile`. `deleteRemoteBackup` keeps its exact signature and
simply delegates to `deleteRemoteFile` with the empty path.

`BookFileTransferEngine` also gained an optional `queueContext`, defaulting to
`Dispatchers.Default`. Production is unchanged; a host test passes its own dispatcher so
the queue it enqueued onto is one `runTest` can drive. Without it the engine's work
runs on real threads while `runTest`'s clock is virtual, and nothing can be awaited.

### The states

`preparedChapterBackupState` (`feature/reader/ui/.../reader/PreparedChapterBackupState.kt`),
pure and in commonMain, with 20 commonTest cases that run on Android and iOS. Twelve
states, including all six the brief names:

| State | Meaning |
| --- | --- |
| `BackedUp` | **uploaded** |
| `WaitingForWifi` | **waiting for Wi-Fi** |
| `WaitingForBookBackup` | **waiting for the book's backup** |
| `StorageFull` | **storage full** — permanent, no retry loop, one message |
| `FailedWillRetry` | **failed and will retry** |
| `NotAllowed` | **not allowed** to upload |
| `WaitingForBooks` | books are uploaded before audio |
| `BackupOff` | the auto-backup switch is off |
| `Queued` | every condition met; the only state that hands work to the engine |
| `Uploading`, `Failed`, `NotApplicable` | |

The order is the order of what the user needs to know first. **What has already
happened beats what might**: a chapter that is up reads as backed up whatever the
switches now say, and a full allowance is reported even after the device leaves Wi-Fi,
because the user has to act on it. Only then do the gates speak, outermost first — no
account, not allowed, switched off — and only then the things that resolve on their
own. A table-driven test builds one input set per state and asserts that `Queued` is
the *only* one that returns true from `shouldQueuePreparedChapterUpload`.

A server refusal of `book_backup_unavailable` is deliberately reported as
`WaitingForBookBackup`, not `Failed`: it is the same fact the gate reports, and the
gate's wording is the one a user can act on.

### Tested with a fake transport

`PreparedAudioUploadTest` (`feature/books/data`, 17 cases) drives the **real engine**
through a fake transport, not a mock of the engine:

- the chapter is reserved at its own relative path with media type
  `tts_prepared_audio`, uploaded, finalized, and becomes one more `available` file of
  its book, with the book's own row untouched beside it;
- the bytes that go up are the archive's, from the source path, not the book's;
- preparing the same chapter with the same settings does not upload twice (same
  transfer id, one reservation); with other settings it is a different file (two
  reservations, two rows);
- `quota_exceeded`, `uploads_not_enabled` and `book_backup_unavailable` each end
  `failed` with **no retry scheduled**; `too_many_pending_uploads` with a
  `retry_after_ms` ends `pending` with an attempt counted and a next attempt set;
- `already_available` completes without uploading anything;
- deleting the chapter cancels a pending upload and leaves no transfer, and
  `deleteRemoteFile` removes only its own cloud row while the book's backup stays;
- an empty relative path, a size that does not match the archive, and a book whose
  metadata has not synced are each refused before any transfer row is written.

### Analytics

`ReaderAnalyticsEvent.TtsPreparedAudioBackupEnded(outcome, sizeBytes)` — one event per
outcome, never per attempt and never per sentence. Parameters are
`operation=tts_prepared_audio_backup`, `stage=ended`, `outcome`, `size_bytes`: no book,
chapter, voice, path or server reason, because the size is the only thing about the
archive that is neither an identifier nor content.

`AnalyticsParameterSanitizer` gained `size_bytes` as a bounded byte count with its own
limit (4 GiB) rather than borrowing the one-year millisecond bound the durations use.
Three tests: the event keeps exactly its four parameters; a book uuid, chapter href,
title, voice id, relative path and error message under the same event all disappear;
and a negative size, an over-bound size, a size sent as text and a size sent as an `Int`
are each dropped. Two of the three failed before the allow-list entry existed (checked
by removing it again); the third passes fail-closed, which is the point of it.

### The two triggers, and the thing that calls the engine

`PreparedChapterBackupQueue`
(`feature/reader/ui/src/androidMain/.../tts/PreparedChapterBackupQueue.kt`) is the one
place in the app that hands a prepared chapter to the transfer engine. It uploads
nothing itself: it gathers the facts, asks the pure `preparedChapterBackupState`
decision, and **only** when that answers `Queued` does it pack the chapter (Step 2) and
call `enqueueAuxiliaryUpload`. Every other state is returned to the caller and nothing
happens, which is what makes "storage full" one message and not a retry loop.

`TtsPreparedChapterBackup` is its app-wide `@Single` home, and the two triggers are:

- **after a chapter finishes preparing.** `TtsChapterPreparationCore` now calls one more
  seam, `TtsPreparationChapterStore.prepared(id, settings)`, right after `markComplete`
  and `enforceLimit`. The member is **defaulted to doing nothing**, so every existing
  host test of preparation is unaffected and preparation behaves exactly as before when
  backup does not exist. `TtsChapterPreparationJob` forwards it to an internal
  `onPrepared` callback which `TtsPreparedChapterBackup` sets on itself in its `init`.
  The dependency runs backup to preparation and not the other way, so there is no cycle
  and the preparation job still knows nothing about the cloud.
- **at app start, for chapters prepared earlier.** `TtsPreparedChapterBackup` is bound
  as an `AppInitializer`, and `initialize()` runs `backUpEverythingPrepared()`. A
  chapter prepared on mobile data, or before the account was linked, is picked up here
  and nowhere else.

#### Where the Wi-Fi signal came from

There was no unmetered-network signal in the codebase; the only connectivity code was
`rememberIsOnMobileData` in `feature/books/ui`, which is a composable and cannot be
injected. `PreparedBackupNetwork` (same folder as the queue) is the new one, and
`AndroidPreparedBackupNetwork` reads
`NET_CAPABILITY_NOT_METERED && NET_CAPABILITY_INTERNET` off the active network.

It reads the system's **metered** flag rather than looking for a Wi-Fi transport,
because what the user means by "Wi-Fi only" is "not out of my data allowance": an
unmetered Ethernet or tether counts, and a Wi-Fi network the user has marked metered in
Android's own settings does not. `ACCESS_NETWORK_STATE` is already held
(`androidApp/src/main/AndroidManifest.xml:5`), so no permission was added.

#### How the chapter learns the facts

The decision needs cloud facts the reader module has no business reading from the
database. `BookFileTransferManager` gained three **defaulted** members, implemented by
the engine from the databases it already holds, so no other implementation and no
existing fake had to change:

| Member | Answers |
| --- | --- |
| `cloudFilesFor(libraryBookId)` | is the book backed up; is this chapter's archive already up |
| `transfersFor(serverId, libraryBookId)` | what this chapter's own transfer is doing |
| `pendingBookUploadCount(serverId)` | books still waiting, because books go first |

`BookFileTransfer` gained `relativePath` and `willRetry`, both defaulted. Without the
relative path two chapters of one book are indistinguishable in the transfer list.

`enqueueAuxiliaryUpload`'s `contentHash` and `contentHashAlgorithm` are now **nullable
and defaulted**: the engine hashes the file itself with `fileStore.contentHash` and its
own `CONTENT_HASH_ALGORITHM` when they are absent. The hash scheme is
`feature/books/data`'s to define, and the reader side now never has to know it.

Account facts come through one small port, `PreparedBackupAccount`, implemented by
`CloudPreparedBackupAccount` over `UserRegistry`, `CloudProfileLinkRepository`,
`CloudAccountRepository` and `UploadRightsAttestationRepository`. It returns null when
there is no usable cloud account, and swallows every failure to null: reading aloud is a
local feature, so an unreachable cloud identity means "no backup for now" and never an
error the reader reports. `feature/reader/ui` gained `feature/cloudAccount/domain` and
`lib/user/api` as androidMain dependencies for this.

#### Two judgements inside the gate

- **Re-attestation is not asked for.** If `requiresReattestation` is true the snapshot
  carries no attestation, `uploadsAllowed` is false and the chapter reads `NotAllowed`.
  Prepared audio will not put a rights dialog in front of someone who only asked for a
  chapter to be read aloud; the next book backup will ask, and the chapter goes up after.
- **Only a book of this device's own library is backed up.** A prepared chapter whose
  `PreparedChapterId.serverId` is not `local` belongs to a catalogue book whose id means
  nothing to Parrot Cloud, so it is `NotApplicable` and never packed.

#### The outbox, and deleting

A packed archive waits in `filesDir/tts-prepared-outbox`, not the cache, so the system
cannot evict it from under a running upload. It is deleted when the upload completes,
when the engine refuses the enqueue, when the chapter is deleted, and by the app-start
sweep for any archive whose chapter no longer exists.

`remove(id, settings)` cancels a pending upload and deletes the cloud file, and asks the
server for nothing at all when there is no cloud account. This is what the Step 5
confirmation calls.

#### Analytics, at the end rather than at the intention

The queue does not log when it decides; it logs when the engine is **finished** with the
transfer. `watchOutcome` collects `observeForBook` until the chapter's own transfer
reaches `completed`, `failed` or `cancelled` and logs one
`TtsPreparedAudioBackupEnded(outcome, sizeBytes)`, carrying the server's reason when
there is one. Two further outcomes are logged without waiting, because there is nothing
to wait for: `refused` when the engine rejects the enqueue outright, and `pack_rejected`
when the archive could not be built. States that queue nothing -- waiting for Wi-Fi,
auto-backup off, books first -- log nothing at all; they are not upload outcomes and
would be noise.

#### Tested

`PreparedChapterBackupQueueTest` (`feature/reader/ui` androidHostTest, 20 cases) uses a
real `TtsPreparedStore` and a real `TtsPreparedChapterArchive` on temporary files, with a
recording transfer manager in place of the engine, which `PreparedAudioUploadTest`
already proves. It covers: the queued path end to end, including that the archive handed
over **round-trips into a second device's store and plays** (`state()` returns
`Ready(1200)`); that the same settings are one cloud file and other settings another;
each of `NotApplicable` (no account, catalogue book, unfinished chapter), `NotAllowed`
(re-attestation due), `BackupOff`, `WaitingForWifi`, `WaitingForBookBackup`,
`WaitingForBooks`, `BackedUp` and `StorageFull`, each asserting that **nothing** was
handed to the engine; the app-start sweep offering every finished chapter and skipping
the unfinished one, queueing nothing on a metered network, and discarding an archive
whose chapter is gone; delete cancelling and removing the cloud file, and asking for
nothing when there is no account; and the three analytics outcomes.

Writing that test found two real faults in the first implementation: `remove` asked the
server to cancel and delete for a device with no cloud account at all, and the outcome
event carried the size of a file that had already been deleted.

`PreparedAudioUploadTest` gained 4 cases for the new engine reads and the self-hashing
enqueue; all four were seen to fail first.

There is a real-graph test now: `PreparedChapterJobWiringTest`'s second case resolves
`TtsPreparedChapterBackup` from the whole app's Koin graph as the single
`AppInitializer` of that name, with every repository it needs built for real.

## Steps 4 to 7

Not built.
