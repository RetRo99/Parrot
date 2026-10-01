# Project 1: One Book, Many Copies — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan slice by slice. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A book in the user's own library (imported on this device and/or stored in Parrot Cloud)
is one book, with one stable ID and one card. Its badge says where it lives, and its actions
act on the right copy.

**Architecture:** The device and Parrot Cloud stop being two separate book sources. One repository
(the existing `local` server) lists "your library": `library_books` rows, each with per-media-type
copies. A copy is either a device file (`device_files`) or a Parrot file (`cloud_book_file_state`).
A book's ID is a random UUID. The same ID is used by the device, Parrot Cloud (`cloud_books.id`),
positions, favorites, bookmarks, reading sessions and navigation. File hashes are file properties
and a hint for spotting duplicates; they never identify a book. Storyteller and Audiobookshelf are
not changed by this project.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight, Koin, Supabase (Postgres
RPCs and pgTAP tests).

**Spec:** Part 1 of this document. For history only, see
`docs/parrot-cloud-server-implementation-plan.md` §3.3 and §5.2. Where that plan disagrees with
this document, this document wins.

This is project 1 of 3:
1. **This project:** one book with copies.
2. Linking the same book across servers, such as Storyteller plus Parrot.
3. Progress sync between linked copies.

Don't build anything for projects 2 or 3 here.

## Global Constraints

- **The abandoned branch:** don't reuse code, design or notes from `feature/unified-library` or
  `backup/unified-library-*`. That attempt was abandoned as broken.
- **UI:** keep changes functional and minimal. The user will do the visual design later. Use
  existing components (buttons, dialogs, badges, text styles) for the text, badges, conditions and
  actions listed here. Don't invent layouts, new visual components or restyles. The layout may
  change where a behaviour needs it, but keep that change as small as possible.
- **Slices:** one slice is one PR. The app must build and work after every slice. Stop after each
  slice, report to the user, and wait.
- **Git:** never commit, push, merge or rebase without asking the user first. Ask the user for the
  branch name.
- **Supabase:** never run anything against the linked (remote) project. Use the local stack only,
  via `scripts/supabase/reset.sh` and `scripts/supabase/test.sh`. The user applies migrations to
  the remote project.
- **Server ID:** keep the value of `LOCAL_SERVER_ID` (`"local"`) and keep `ServerType.Local`. The
  value is stored in "currently reading" and in Android Auto media IDs (`book:local:<id>`). From
  now on it means "your library".
- **Kotlin style:** lines up to 100 characters, 4-space indentation, no wildcard imports,
  trailing commas, named lambda parameters (no `it`), no semicolons.
- **Strings:** all user-facing text goes in
  `translations/src/commonMain/composeResources/values/strings.xml`. Add new keys at the bottom
  with `tools:ignore="MissingTranslation"`. English is the only locale, so reword existing values
  in place.
- **Legal text:** don't change any `cloud_backup_attestation*` string or the "Book Backup Terms"
  name. That text needs legal review first.
- **Tests:** follow this repo's style: `kotlin.test`, hand-written fakes (see
  `feature/books/data/src/commonTest/.../transfer/DownloadFinalizerTest.kt`), backticked names and
  `// Given`, `// When`, `// Then` comments. This repo has no `BaseKoinTest`. Write mapper tests as
  a table of cases in one test, because commonTest has no JUnit Parameterized.
- **Reporting:** "verified" means you ran it and saw it. Report the commands you ran, their
  results, and what you observed on the device. Never write an acceptance claim you didn't observe.

---

# Part 1 — Design

## 1.1 What's wrong today

| What the user did | What they see |
|---|---|
| Imported a file | A "Local" badge. This one is fine. |
| Uploaded it to Parrot | Still "Local". Filtering by Parrot Cloud hides it. |
| Downloaded it from Parrot on a second device | A new "Local" book |
| "Delete Book" on an uploaded book | It comes back as a Parrot Cloud book that isn't downloaded |
| "Remove download" on a downloaded Parrot book | Goes to the reader-cache delete. On Android the file is deleted but its row stays; on iOS nothing is deleted. |

Root causes:
1. **A book's identity is its file hash.** Locally `library_book_id = "sha-256-v1:<hash>"`
   (`LibraryBookLocalModel.kt:31`, `BookFileTransferEngine.kt:278`), and `DownloadFinalizer.kt:40`
   rejects anything else. In the backend, `cloud_books` is unique on the user plus the hash, and has
   a single `format`. So replacing a file creates a new book, and a book can't have a second file.
2. **The device and Parrot are two servers listing the same book.**
   `aggregateBookReplicas()` (`feature/books/domain/.../model/ServerBookExt.kt`) keeps the device
   entry and throws the Parrot entry away. Everything after that point treats the book as local.
3. **Five IDs for one book:** the imported file UUID, the hash ID, `cloud_book_id`,
   `localSourceUuid` and `cloud_book_file_id`. Most of the special cases in
   `BookDetailViewModel` exist to translate between them.
4. **Parrot stores only a title and author.** Books that aren't downloaded have no cover. Slice 5
   fixes this.

## 1.2 Scope

**In scope:**
- a UUID book identity everywhere, on the client and the backend,
- one library repository with copies,
- the home badge,
- correct actions on the detail screen,
- Parrot as a place in the wording, not a backup,
- a clean slate for Parrot data and for the device library.

**Out of scope:**
- Storyteller and Audiobookshelf behaviour (project 2),
- position translation (project 3),
- visual design (the user does it later),
- keeping existing users' imported books or their reading data. The user decided on 2026-09-30
  not to keep them,
- renaming `local` or `ServerType.Local`,
- changing *what* syncs. Imported books of a signed-in user still create a Parrot book record
  with metadata and progress, as they do today,
- changing the auto-upload behaviour,
- an upsell for signed-out users,
- squashing the Supabase migration history. Do that before launch as a separate chore.

## 1.3 Decisions

| # | Decision | Why |
|---|---|---|
| D1 | A book ID is a random UUID (`Uuid.random()`). It's never derived from content and never changes. | Files get replaced, and books gain more files (audio, read-aloud) and files Parrot generates. An ID tied to one file breaks every time that happens. |
| D2 | No data carries over. The device library is reset: imported books plus their positions, favorites, bookmarks and reading sessions. Storyteller and Audiobookshelf data isn't touched. | The user decided not to keep user data, and very few users exist. A reset means no backfill code and no mixed old and new IDs. |
| D3 | Parrot uses the same ID: `cloud_books.id` is the client's book ID. | One ID, and no attach or translate step. |
| D4 | A content hash is a property of a file, plus a *duplicate hint* on the book (`source_content_hash`). | It answers "is this file already in my library?" and nothing more. |
| D5 | Device files are keyed by (book ID, media type) in a new `device_files` table. `imported_books` and `local_book_files` are removed. | Removes the file UUID, the mapping table and `localSourceUuid`. |
| D6 | One repository, the `local` server, lists your library. `ParrotCloudBooksRepository` lists nothing. Routing stays `(serverId, uuid)`, which is `("local", bookId)` for your library. | Each book appears once. The reader, navigation, Android Auto and Storyteller/Audiobookshelf code paths don't change. |
| D7 | The domain type `LibraryBook` replaces `LocalBook`. It has one `MediaResource` per media type: `localPath` is the device copy and `remoteAvailability` is the Parrot copy. | A book that isn't downloaded and a book on the device are the same type. |
| D8 | Duplicates are resolved in two ways. At import, attach the file to an existing book when the hash matches. Otherwise the server answers `duplicate`, and the client merges its book into the existing one. When merging, the most recent reading position wins. | Covers the realistic case: someone with the same books on two devices signs in to Parrot. |
| D9 | Clean slate. The backend, every Parrot/sync table on the device, and the device library are reset (D2). | Parrot was never released: 0.4.5 shipped database version 9, and every Parrot table arrived in migrations 17–26. |
| D10 | Parrot Cloud is a place, not a backup. Wording: "Add to Parrot Cloud", "In Parrot Cloud", "Remove from Parrot Cloud". | Parrot Cloud is the product being sold. |

## 1.4 IDs and invariants

| ID | What it is | Where it lives |
|---|---|---|
| `bookId` | The book | `library_books.library_book_id`, `cloud_books.id`, `device_files.library_book_id`, `cloud_book_file_state.library_book_id`, `position.book_uuid` (and `position.library_book_id` for your library), `favorites.book_uuid`, `bookmarks.book_uuid`, `reading_session.book_uuid`, navigation `uuid` for server `local` |
| `parrotFileId` | One file in Parrot | `cloud_book_files.id`, `cloud_book_file_state.cloud_book_file_id` |
| `transferId` | One upload or download | `cloud_file_transfers.transfer_id` |

No other book IDs exist. Delete `localSourceUuid`, `cloudBookId` as a separate value, the imported
file UUID, and every `"$algorithm:$hash"` ID.

Invariants. Test for these:
- **I1** Every string built as `"$algorithm:$hash"` or `algorithm || ':' || hash` has been
  deleted, from the client and from SQL.
- **I2** `ServerBook.uuid == ServerBook.libraryBookId` for every book from the `local` server.
- **I3** A book appears in the library list at most once.
- **I4** For your library, `position.library_book_id == position.book_uuid`. For Storyteller and
  Audiobookshelf positions it's `NULL`. The Parrot progress transport pushes only positions where
  it isn't `NULL`.
- **I5** Code must never assume a device file path contains the book ID. Always read
  `device_files.file_path`.
- **I6** Library-book code never calls the reader-cache API: `isEbookCached`, `deleteEbookCache` or
  `DeleteMediaCacheUseCase`.
- **I7** The outbox sends `library_book` mutations before any mutation that refers to that book:
  positions, sessions and bookmarks.

## 1.5 Rules

In these rules:
- **Parrot active** means a Parrot account is linked and signed in for the current profile. Reuse
  the logic in `BookDetailViewModel.observeActiveCloudAccount()`, which combines
  `cloudAccountRepository.observeAuthState()` with
  `cloudProfileLinkRepository.observeForLocalProfile(...)` and `isActiveFor`.
- **Parrot copy states:** `Available`, `UploadPending`, `Uploading`, `UploadFailed`, `Deleting` or
  `None`, from `RemoteFileAvailability`.

**Repository (Parrot inactive):** set every resource's `remoteAvailability` to `None` before
applying the rules below.

**Visibility:** a library book is listed if it has a device copy, or if it has a Parrot copy that
is `Available`, `UploadPending` or `Uploading`. A book record in Parrot with no file (progress only)
is not listed on a device that doesn't have the file.

**Home:**

| Book | Home |
|---|---|
| Library book with a Parrot copy that is `Available`, `UploadPending` or `Uploading` | Parrot Cloud |
| Any other library book | This device |
| Storyteller server book | Storyteller |
| Audiobookshelf server book | Audiobookshelf |

**Card (list and search):**
- **Badge:** shows the home, and only when more than one home exists in the list. That replaces
  today's `showServerBadge` rule, which counts distinct server types.
- **Downloaded icon** (`Icons.Outlined.DownloadDone`):
  - For library books, show it when the book has a device copy **and** its home is Parrot Cloud.
  - For server books, keep today's rule (`progressInfo.hasAnyCached`).

**Source filter:**
- The options are the homes present in the list.
- The label for This device is "Only on this device".
- The "Downloaded" quick filter includes every library book with a device copy, plus server books
  cached as today.

**Detail screen, per media type.** "Device" is `localPath != null`. "Parrot" is
`remoteAvailability`.

| Device | Parrot | Open | Download | Remove download | Add to Parrot | Retry upload |
|---|---|---|---|---|---|---|
| yes | None | ✓ | | | if Parrot active | |
| yes | UploadPending / Uploading | ✓ | | | | |
| yes | Available | ✓ | | ✓ | | |
| yes | UploadFailed | ✓ | | | | if Parrot active |
| yes | Deleting | ✓ | | | | |
| no | Available | | ✓ | | | |
| no | anything else | | | | | |

The existing cancel action stays available while an upload or download is running.

**Detail screen, whole book:**
- **Remove from Parrot Cloud:** shown if any media type is Parrot `Available`.
- **Delete from this device:** shown if the book has a device copy and no media type is Parrot
  `Available`, `UploadPending` or `Uploading`.

**Detail status line:** one line of text, placed where the server badge is today, in the same text
style.
- "Only on this device": home is This device.
- "In Parrot Cloud · Downloaded": home is Parrot Cloud and the book has a device copy.
- "In Parrot Cloud · Not downloaded": home is Parrot Cloud with no device copy.
- While a transfer runs, show the existing progress text instead.

**Effects of each action:**
- **Remove download:** deletes this media type's device file and its `device_files` row. The book
  stays, and so does its position.
- **Delete from this device:** deletes all of the book's device files, its `device_files` rows and
  its `library_books` row. The Parrot book record isn't deleted. If the user re-imports the file
  later while signed in, the server reports it as a duplicate (D8), and its progress comes back from
  Parrot. Positions, favorites and sessions follow today's delete behaviour.
- **Remove from Parrot Cloud:**
  1. This device changes the `origin` of all the book's device files to `'import'`, so they
     survive the incoming deletion.
  2. It calls the existing remote delete for each Parrot media type, via
     `BookFileTransferManager.deleteRemoteBackup`.
  3. On other devices, the existing change applier removes device files with
     `origin = 'cloud_download'` and keeps `'import'` files.

  The book's home becomes This device wherever a device copy remains.
- **Add to Parrot Cloud:** the existing upload flow, including the rights confirmation, called with
  `bookId`.
- **Sign-out:** the visibility and home rules with Parrot inactive. Books that exist only in Parrot
  disappear from the list, and come back after signing in again.

## 1.6 Wording

Change these existing values. Keys not listed here keep their values.

| Key | New value |
|---|---|
| `cloud_backup_button` | Add to Parrot Cloud |
| `cloud_backup_title` | Add this book to Parrot Cloud? |
| `cloud_backup_confirm` | Add |
| `cloud_backup_queued` | Waiting to upload |
| `cloud_backup_progress` | Uploading to Parrot Cloud · %1$d%% |
| `cloud_backup_finishing` | Finishing upload… |
| `cloud_backup_complete` | In Parrot Cloud |
| `cloud_backup_failed` | Upload failed: %1$s |
| `cloud_backup_reason_file_exists` | This file is already in your Parrot Cloud library. |
| `cloud_backup_reason_content_blocked` | This file can't be added to Parrot Cloud. |
| `cloud_backup_reason_attestation_required` | Confirm you have the rights to this file, then try again. |
| `cloud_backup_cancel` | Cancel upload |
| `cloud_backup_cancelled` | Upload cancelled |
| `cloud_backup_retry` | Retry upload |
| `cloud_backup_replace_button` | Replace file in Parrot Cloud |
| `cloud_backup_replace_title` | Replace the file in Parrot Cloud? |
| `cloud_backup_replace_message` | Replace the Parrot Cloud file for “%1$s” with the copy on this device? |
| `cloud_backup_replacing` | Replacing file… |
| `cloud_backup_autobackup_prompt_title` | Add imported books to Parrot Cloud automatically? |
| `cloud_backup_autobackup_prompt_body` | New books you import will be uploaded to your Parrot Cloud library. You can change this anytime in Sync &amp; Backup settings. |
| `cloud_backup_autobackup_confirm_body` | Books you import will be uploaded to your Parrot Cloud library. They count against your 5 GB storage and download bandwidth. By turning this on, you confirm the checkbox below. |
| `cloud_backup_autobackup_toggle` | Add new books to Parrot Cloud automatically |
| `cloud_backup_autobackup_enable` | Turn on |
| `cloud_backup_all_button` | Add all |
| `cloud_backup_backup_all` | Add all books on this device to Parrot Cloud |
| `cloud_backup_all_title` | Add all books to Parrot Cloud? |
| `cloud_backup_all_message` | Books on this device will be queued for upload to Parrot Cloud. |
| `cloud_backup_all_queued` | Queued %1$d books for upload. |
| `cloud_backup_all_summary` | Queued %1$d books for upload; %2$d could not be queued. |
| `cloud_backup_all_result_title` | Add to Parrot Cloud |
| `books_delete_local_title` | Delete from this device? |
| `books_delete_local_message` | “%1$s” is only on this device. Deleting it removes the book and its file. This can’t be undone. |
| `books_delete_local_button` | Delete from this device |
| `books_filter_on_this_device` | Only on this device |

Add these new keys at the bottom:

| Key | Value |
|---|---|
| `library_home_this_device` | This device |
| `library_status_only_this_device` | Only on this device |
| `library_status_parrot_downloaded` | In Parrot Cloud · Downloaded |
| `library_status_parrot_not_downloaded` | In Parrot Cloud · Not downloaded |
| `library_remove_from_parrot_button` | Remove from Parrot Cloud |
| `library_remove_from_parrot_title` | Remove from Parrot Cloud? |
| `library_remove_from_parrot_message_keep_local` | “%1$s” will be removed from Parrot Cloud and your other devices. It stays on this device. |
| `library_remove_from_parrot_message` | “%1$s” will be removed from Parrot Cloud and your other devices. |
| `library_remove_from_parrot_confirm` | Remove |

Delete `cloud_backup_delete_button`, `cloud_backup_delete_title`, `cloud_backup_delete_message`
and `cloud_backup_delete_confirm` once nothing references them. Leave the "Sync & Backup" settings
screen name and every `cloud_backup_attestation*` value unchanged.

---

# Part 2 — Slices

| Slice | PR | The app afterwards |
|---|---|---|
| 1 | Backend: the client chooses book IDs | Unchanged. The remote project isn't migrated yet. |
| 2 | Client: one ID everywhere | Behaves as it does today, but uses UUID identity. The device library is empty after the upgrade (D2). The user resets the remote project and applies slice 1's migration when this lands. |
| 3 | One library | One card per book with the right badge. Detail and reader open by `bookId`. |
| 4 | Correct actions and wording | The detail-screen actions table, filters and strings |
| 5 (optional) | Parrot keeps metadata and cover | Books that aren't downloaded look complete |

## Module test commands

- Host tests (the fastest): `./gradlew :<module>:testAndroidHostTest`. Only
  `feature/books/data`, `feature/books/ui` and `lib/database/implementation` have `withHostTest {}`
  today.
- Other modules: add `withHostTest {}` to the module's `androidLibrary {}` block, copying
  `feature/books/data/build.gradle.kts`. Do this for `feature/books/domain`, `feature/reader/domain`,
  `feature/sync/data`, `lib/server-local` and `lib/server-parrot-cloud` when you add tests there.
  Fall back to `:<module>:iosSimulatorArm64Test` if host tests can't be enabled.
- Android build: `./gradlew :androidApp:assembleDebug`
- iOS compile of each changed module: `./gradlew :<module>:compileKotlinIosSimulatorArm64`
- Backend: `scripts/supabase/reset.sh && scripts/supabase/test.sh`

---

## Slice 1 — Backend: the client chooses book IDs

### Task 1.1: Migration for client-chosen IDs and the source hash as a hint

**Files:**
- Create: `supabase/migrations/20261001000000_parrot_cloud_client_book_ids.sql`
- Modify: every pgTAP file in `supabase/tests/` that creates `cloud_books` or pushes
  `library_book` mutations. Today that's `book_files_test.sql`, `finalize_book_upload_test.sql`,
  `non_available_book_delete_test.sql`, `orphan_gc_test.sql`, `rls_isolation_test.sql`,
  `stale_upload_cancel_test.sql`, `storage_policy_test.sql`, `abuse_operations_test.sql`,
  `account_deletion_test.sql` and `reading_sessions_test.sql`.
- Create: `supabase/tests/client_book_ids_test.sql`

**Interfaces:**
- Produces:
  - `push_sync_changes` accepts `library_book` upserts identified by
    `payload.library_book_id` (a UUID string). `cloud_books.id` is set to that value.
  - A new result status, `duplicate`, with `existing_book_id` and `payload`, the existing book's
    payload.
  - Every change and pull payload has `library_book_id = cloud_books.id::text`, and includes
    `source_content_hash` and `source_content_hash_algorithm`.
  - `reading_position` mutations take the book from `payload.library_book_id::uuid`.
  - `reading_session` mutations are **unchanged**. `cloud_reading_sessions` stores an opaque
    payload whose `book_uuid` can be any book, Storyteller and Audiobookshelf included. Never
    require a Parrot book for a session.

**Rules for writing this migration:**
- For each function you change, start from its **latest** definition, meaning the last migration
  that `create or replace`s it. Several were redefined later: check
  `20260925000003_parrot_cloud_reading_sessions.sql` and
  `20260925000004_parrot_cloud_fix_ambiguous_identifiers.sql`.
- plpgsql only checks column names when a function runs. After renaming columns, grep the latest
  body of **every** function for `content_hash`. Every use that refers to `cloud_books` must change,
  and every function you touch must be exercised by a pgTAP test.

- [ ] **Step 1: Write the failing pgTAP test** `supabase/tests/client_book_ids_test.sql`. Cover
  these cases:
  1. An upsert with `library_book_id = '6f1c…'` (a valid UUID) and a new source hash creates
     `cloud_books` with `id = '6f1c…'` and returns `status = 'accepted'`.
  2. An upsert with `library_book_id = 'sha-256-v1:abc'` returns `rejected` with reason
     `invalid_library_book_id`.
  3. A second, different UUID with the **same** `source_content_hash` for the same user returns
     `duplicate` with `existing_book_id` set to the first ID, and creates no row.
  4. The same source hash for a **different** user is accepted as a new book. Duplicates are only
     detected within one account.
  5. An upsert of an existing ID with a stale `base_revision` returns the conflict payload, as
     today.
  6. A new ID whose `source_content_hash` matches a live `cloud_book_files.content_hash` of the same
     user returns `duplicate` with that file's book.
  7. A `reading_position` mutation for a `library_book_id` the user doesn't own is rejected with
     `library_book_not_found`.
  8. After a file with a *different* hash is finalized for the same book and media type, through
     the existing replace flow, the book keeps its ID.

- [ ] **Step 2: Run it and confirm it fails.** Run
  `scripts/supabase/reset.sh && scripts/supabase/test.sh`. The new file should fail and the old
  files should pass.

- [ ] **Step 3: Write the migration.** The shape:

```sql
-- Parrot Cloud was never released. This migration assumes a clean slate: it deletes
-- every user's Parrot data. Do not apply this to a database with real users.

-- 1. Clean slate: truncate every user-data table the Parrot migrations created
--    (cloud_books cascades to files, positions and uploads; also sync_changes, the
--    reading-session tables and audit events). Keep admin configuration such as the
--    content-hash blocklist.
truncate table public.cloud_books, public.sync_changes restart identity cascade;
-- (list the remaining Parrot user-data tables explicitly)

-- 2. The book ID comes from the client; the hash is only a duplicate hint.
alter table public.cloud_books
    drop constraint cloud_books_cloud_user_id_content_hash_algorithm_content_hash_key;
-- (confirm the constraint name with \d public.cloud_books on the local stack)
alter table public.cloud_books rename column content_hash to source_content_hash;
alter table public.cloud_books
    rename column content_hash_algorithm to source_content_hash_algorithm;
alter table public.cloud_books
    alter column source_content_hash drop not null,
    alter column source_content_hash_algorithm drop not null,
    alter column format drop not null;
create index cloud_books_source_hash_idx
    on public.cloud_books (cloud_user_id, source_content_hash_algorithm, source_content_hash)
    where deleted_at is null;

-- 3. create or replace push_sync_changes, pull_sync_changes and every other function that
--    read cloud_books.content_hash, copied from their latest definitions.
```

  In `push_sync_changes`, the `library_book` upsert branch works like this:

```text
id := payload.library_book_id::uuid     -- on failure: rejected 'invalid_library_book_id'
title required                          -- else: rejected 'book_identity_required'
if a row (id, actor) exists:
    same revision rules and conflict payload as today; update title, author, format,
    metadata and the source hash
elif a row with this id exists for another user:
    rejected 'library_book_id_conflict'
else:
    dup := a live book of actor with the same (source_content_hash_algorithm,
           source_content_hash), or the book of a live cloud_book_files row of actor
           with the same file hash
    if dup: return status 'duplicate', existing_book_id = dup.id, payload = dup's payload
    else:   insert with id = id; emit a sync change as today
```

  In the `reading_position` branch: `cloud_book_id := library_book_id::uuid`. If no row
  `(cloud_book_id, actor)` exists, reject with `library_book_not_found`.

  Today that branch builds `library_book_id` from the hash and looks the book up by hash. The
  latest copy is in `20260925000003_parrot_cloud_reading_sessions.sql` around lines 250–280, and
  the older copy is at `20260922000001_parrot_cloud_rpcs.sql:237`. Replace both behaviours. Every
  payload builder emits `'library_book_id', id::text`, and no `algorithm || ':' || hash`
  expression may remain (I1).

- [ ] **Step 4: Update the old pgTAP fixtures.** Insert `cloud_books` with explicit UUID IDs and
  the `source_content_hash` column names. Run
  `scripts/supabase/reset.sh && scripts/supabase/test.sh`. All files should pass.

- [ ] **Step 5: Stop and report.** Report the test output. Tell the user this migration must not
  reach the remote project until slice 2 is merged. When it's applied, the remote needs a database
  reset and the `book-files` bucket emptied. Ask before committing.

---

## Slice 2 — Client: one ID everywhere

After this slice, the screens behave as they do today, including today's wrong badges and actions,
which slices 3 and 4 fix. The device library starts empty after the upgrade (D2).
`aggregateBookReplicas()` still merges the two listings, now by equal UUIDs. Sync works against
the local backend stack.

### Task 2.1: Database migration 27 (reset) and the new tables

**Files:**
- Create: `lib/database/implementation/src/commonMain/sqldelight/com/retro99/database/implementation/27.sqm`
- Create: `.../sqldelight/.../DeviceFile.sq`
- Modify: `.../sqldelight/.../LibraryBook.sq`, `CloudBookFileState.sq`, `CloudFileTransfer.sq`
- Delete: `.../sqldelight/.../ImportedBook.sq`, `LocalBookFile.sq`
- Modify: `lib/database/implementation/build.gradle.kts`: set `version = 27`
- Create: `lib/database/implementation/src/androidHostTest/resources/v9_schema.sql` and
  `v26_schema.sql` (see step 1)
- Create: `lib/database/implementation/src/androidHostTest/kotlin/com/retro99/database/implementation/OneBookIdMigrationTest.kt`

No data carries over (D2). The migration only has to upgrade every real schema without crashing,
reset the device library, and leave Storyteller and Audiobookshelf data alone.

- [ ] **Step 1: Build the schema fixtures.** 0.4.5 shipped database version 9, and `HEAD` before
  your change is version 26. For each one, concatenate the `CREATE TABLE` and `CREATE INDEX`
  statements of every `.sq` file:

```bash
for f in $(git ls-tree -r --name-only v0.4.5 | grep 'sqldelight/.*\.sq$'); do
  git show "v0.4.5:$f"
done > /tmp/v9_all.sq
# the same with HEAD instead of v0.4.5 for v26, before you edit any .sq file
```

  Keep only the DDL statements in `v9_schema.sql` and `v26_schema.sql`.

- [ ] **Step 2: Write the failing migration test.** Follow `MediaFileSizeTest.kt`, which uses a
  `JdbcSqliteDriver(IN_MEMORY)`:

```kotlin
class OneBookIdMigrationTest {

    @Test
    fun `migrating from 9 and from 26 produces the fresh schema`() {
        listOf(9L to "v9_schema.sql", 26L to "v26_schema.sql").forEach { (version, fixture) ->
            // Given
            val migrated = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            executeScript(migrated, readResource(fixture))
            val fresh = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

            // When
            AppDatabase.Schema.migrate(migrated, oldVersion = version, newVersion = 27)
            AppDatabase.Schema.create(fresh)

            // Then: the same tables, and the same columns per table
            assertEquals(schemaOf(fresh), schemaOf(migrated), "from version $version")
        }
    }

    @Test
    fun `migration resets the device library and keeps server book data`() {
        // Given a version 26 database with an imported book, a Storyteller book and
        // a position, favorite, bookmark and reading session for each
        // (insert rows: imported_books uuid 'imp-1'; position/favorites/bookmarks/
        //  reading_session for 'imp-1', for 'sha-256-v1:x' and for 'st-1', a Storyteller uuid)

        // When
        AppDatabase.Schema.migrate(driver, oldVersion = 26, newVersion = 27)

        // Then
        // - no row anywhere for 'imp-1' or 'sha-256-v1:x'
        // - every row for 'st-1' still exists, and position.library_book_id is NULL
        // - library_books, device_files, cloud_book_file_state, cloud_file_transfers,
        //   sync_outbox, sync_checkpoints and remote_position are empty
    }
}
```

  `schemaOf` reads `sqlite_master` (tables) and `pragma_table_info(<table>)` (column names, types,
  `notnull`, `pk`) into a sorted map.

- [ ] **Step 3: Run it and confirm it fails.** Run
  `./gradlew :lib:database:implementation:testAndroidHostTest`.

- [ ] **Step 4: Write `27.sqm`.** Make the `.sq` files match the tables it creates.

```sql
-- One book ID everywhere. Parrot Cloud was never released and the device library is
-- not carried over, so the device library and all Parrot/sync state are reset.
-- Storyteller and Audiobookshelf data is left alone.

-- 1. Remove reading data that belongs to the device library or to Parrot.
DELETE FROM position
WHERE book_uuid IN (SELECT uuid FROM imported_books)
   OR book_uuid LIKE 'sha-256-v1:%'
   OR library_book_id LIKE 'sha-256-v1:%';
-- Every remaining position belongs to a Storyteller or Audiobookshelf book (I4).
UPDATE position SET library_book_id = NULL;
DELETE FROM remote_position;
DELETE FROM favorites
WHERE book_uuid IN (SELECT uuid FROM imported_books) OR book_uuid LIKE 'sha-256-v1:%';
DELETE FROM bookmarks
WHERE book_uuid IN (SELECT uuid FROM imported_books) OR book_uuid LIKE 'sha-256-v1:%';
DELETE FROM reading_session
WHERE book_uuid IN (SELECT uuid FROM imported_books) OR book_uuid LIKE 'sha-256-v1:%';
DELETE FROM sync_outbox;
DELETE FROM sync_checkpoints;

-- 2. Drop the old library tables.
DROP TABLE IF EXISTS local_book_files;
DROP TABLE IF EXISTS cloud_book_file_state;
DROP TABLE IF EXISTS cloud_file_transfers;
DROP TABLE IF EXISTS library_books;
DROP TABLE IF EXISTS imported_books;

-- 3. The new tables.
CREATE TABLE library_books (
    library_book_id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    author TEXT,
    description TEXT,
    cover_path TEXT,
    publication_date TEXT,
    source_content_hash TEXT,
    source_content_hash_algorithm TEXT,
    added_at TEXT NOT NULL,
    last_opened_at TEXT,
    remote_revision INTEGER,
    deleted_at TEXT,
    metadata_json TEXT
);
CREATE INDEX idx_library_books_source_hash
ON library_books(source_content_hash_algorithm, source_content_hash);

CREATE TABLE device_files (
    library_book_id TEXT NOT NULL,
    media_type TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    content_hash TEXT,
    content_hash_algorithm TEXT,
    origin TEXT NOT NULL DEFAULT 'import',
    added_at TEXT NOT NULL,
    PRIMARY KEY (library_book_id, media_type)
);
CREATE INDEX idx_device_files_hash ON device_files(content_hash_algorithm, content_hash);

CREATE TABLE cloud_book_file_state (
    library_book_id TEXT NOT NULL,
    cloud_book_file_id TEXT NOT NULL,
    media_type TEXT NOT NULL,
    relative_path TEXT NOT NULL DEFAULT '',
    file_name TEXT NOT NULL,
    status TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    content_hash_algorithm TEXT NOT NULL,
    remote_revision INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (library_book_id, media_type, relative_path)
);
CREATE INDEX idx_cloud_book_file_state_hash
ON cloud_book_file_state(content_hash_algorithm, content_hash);

-- cloud_file_transfers: the current columns minus cloud_book_id and local_source_uuid
CREATE TABLE cloud_file_transfers ( /* copy from CloudFileTransfer.sq minus those two */ );
```

  A 0.4.5 (version 9) database goes through migrations 10–26 first. Check that none of them fails
  on a version 9 fixture. The first test covers this.

  **Old files on disk:** imported EPUBs from before this change are no longer referenced.
  - On iOS, delete the import-only directory `Documents/ebooks` once, guarded by a preference flag.
  - On Android, leave the files. That directory also holds the Storyteller reader cache, and the
    leftover files are harmless.

  **Queries** (`DeviceFile.sq`): `upsertDeviceFile`, `getDeviceFile(library_book_id, media_type)`,
  `getDeviceFilesForBook`, `observeAllDeviceFiles`, `findDeviceFileByHash(algorithm, hash)`,
  `deleteDeviceFile(library_book_id, media_type)`, `deleteDeviceFilesForBook`,
  `setOriginForBook(origin, library_book_id)`.

  **Queries** (`LibraryBook.sq`): `upsertLibraryBook`, `getLibraryBookById`,
  `observeLibraryBooks` (live only), `findLibraryBookBySourceHash(algorithm, hash)`,
  `deleteLibraryBook`, `updateLastOpenedAt`.

  **Queries** (`CloudBookFileState.sq`): keep the existing ones, minus `cloud_book_id`, and add
  `findCloudFileByHash(algorithm, hash)`.

- [ ] **Step 5: Run the tests and confirm they pass.** Run
  `./gradlew :lib:database:implementation:testAndroidHostTest`.

### Task 2.2: Database API for books, device files and merging

**Files:**
- Modify: `lib/database/api/src/commonMain/kotlin/com/retro99/database/api/library/LibraryBooksDatabase.kt`
  and its entity types
- Create: `lib/database/api/.../library/DeviceFilesDatabase.kt` and `DeviceFileEntity.kt`
- Create: `lib/database/api/.../library/LibraryBookMergeDatabase.kt`
- Delete: `lib/database/api/.../importedbooks/*` and the matching implementation under
  `lib/database/implementation/.../dao/importedbooks/`
- Modify: the implementation DAOs under `lib/database/implementation/.../dao/library/` and
  `dao/cloudfiles/`
- Test: `lib/database/implementation/src/androidHostTest/.../LibraryBookMergeTest.kt`

**Interfaces:**

```kotlin
interface DeviceFilesDatabase {
    fun observeAllDeviceFiles(): Flow<List<DeviceFileEntity>>
    suspend fun getDeviceFiles(libraryBookId: String): List<DeviceFileEntity>
    suspend fun findByContentHash(algorithm: String, hash: String): DeviceFileEntity?
    suspend fun upsertDeviceFile(file: DeviceFileEntity)
    suspend fun deleteDeviceFile(libraryBookId: String, mediaType: String)
    suspend fun setOriginForBook(libraryBookId: String, origin: String)
}

interface DeviceFileEntity {
    val libraryBookId: String
    val mediaType: String
    val filePath: String
    val fileSize: Long
    val contentHash: String?
    val contentHashAlgorithm: String?
    val origin: String // "import" | "cloud_download"
    val addedAt: String
}

interface LibraryBookMergeDatabase {
    /**
     * Moves everything that belongs to [fromId] onto [intoId] and deletes [fromId],
     * in one transaction. Used when Parrot Cloud reports that [fromId] duplicates [intoId].
     *
     * @return paths of device files that became redundant. Delete them after this returns.
     */
    suspend fun mergeLibraryBook(fromId: String, intoId: String): List<String>
}
```

**Merge rules**, applied in one transaction:

| Table | Rule |
|---|---|
| `position` | If only `fromId` has a row, change it to `intoId` (both `book_uuid` and `library_book_id`). If both have rows, keep the one with the later `updated_at`, change it to `intoId`, and delete the other. |
| `remote_position` | Delete the `fromId` row |
| `favorites` | `INSERT OR IGNORE` the `intoId` row, then delete the `fromId` row |
| `bookmarks`, `reading_session` | `UPDATE ... SET book_uuid = intoId WHERE book_uuid = fromId` |
| `device_files` | Move `fromId` rows to `intoId`, unless `intoId` already has that media type. In that case keep `intoId`'s row and delete the `fromId` file from disk *after* the commit (return the paths). |
| `cloud_book_file_state` | Delete the `fromId` rows. The server's rows for `intoId` arrive by pull. |
| `cloud_file_transfers` | Change non-terminal rows to `intoId`, delete terminal ones |
| `sync_outbox` | Pending rows with `entity_id = fromId`, or whose JSON `library_book_id` is `fromId`: rewrite both in Kotlin inside the transaction |
| `library_books` | Delete `fromId` |

  Test the returned paths too.

- [ ] **Step 1: Write the failing tests.** Write one test per table row above, plus a test that a
  failure midway leaves every table unchanged. Example:

```kotlin
@Test
fun `merge keeps the most recently updated position`() {
    // Given
    insertPosition(bookUuid = "from", updatedAt = "2026-09-30T10:00:00Z", progression = 0.6)
    insertPosition(bookUuid = "into", updatedAt = "2026-09-29T10:00:00Z", progression = 0.2)

    // When
    runBlocking { classUnderTest.mergeLibraryBook(fromId = "from", intoId = "into") }

    // Then
    val position = database.positionQueries.getPositionByBookUuid("into").executeAsOne()
    assertEquals(0.6, position.progression)
    assertEquals("into", position.library_book_id)
    assertNull(database.positionQueries.getPositionByBookUuid("from").executeAsOneOrNull())
}
```

- [ ] **Step 2:** Run `./gradlew :lib:database:implementation:testAndroidHostTest` and confirm the
  tests fail.
- [ ] **Step 3:** Implement the interfaces.
- [ ] **Step 4:** Run the tests again and confirm they pass.

### Task 2.3: Import creates UUID books and attaches duplicates

**Files:**
- Modify: `feature/books/data/src/androidMain/kotlin/com/retro99/books/data/AndroidFileImportManager.kt`
- Modify: `feature/books/data/src/iosMain/kotlin/com/retro99/books/data/IosFileImportManager.kt`
- Replace: `feature/books/data/.../source/ImportedBooksRoomDataSource.kt` and `ImportedBooksLocalSource`
  with `LibraryLocalSource` and `LibraryLocalDataSource`
- Modify/Delete: `feature/books/data/.../model/LibraryBookLocalModel.kt` (removes
  `"$algorithm:$hash"` at line 31), `ImportedBookLocalModel.kt`, `LibraryBookJsonCodec`
- Test: `feature/books/data/src/commonTest/.../LibraryImportTest.kt`

**Interfaces:**

```kotlin
interface LibraryLocalSource {
    /** Adds an imported file. Returns the book it belongs to (new or existing). */
    suspend fun addImportedFile(file: ImportedFileCandidate): AppResult<String>
    fun observeLibrary(): Flow<List<LibraryBookRecord>>
    suspend fun getLibraryBook(libraryBookId: String): LibraryBookRecord?
    suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult
    suspend fun removeDeviceFile(libraryBookId: String, mediaType: String): CompletableResult
}

data class ImportedFileCandidate(
    val stagedPath: String,
    val mediaType: String,
    val fileSize: Long,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val metadata: EpubMetadata,
    val coverPath: String?,
)

/** A library book and its copies, straight from the database. */
data class LibraryBookRecord(
    val libraryBookId: String,
    val title: String,
    val author: String?,
    val description: String?,
    val coverPath: String?,
    val publicationDate: String?,
    val addedAt: String,
    val lastOpenedAt: String?,
    val deviceFiles: List<DeviceFileEntity>,
    val parrotFiles: List<CloudBookFileEntity>,
)
```

**`addImportedFile` algorithm:**

```text
match := device_files by hash, then cloud_book_file_state by hash,
         then library_books by source hash (live rows only)
if match exists:
    if match already has a device file for this media type: delete staged file,
        return match.id (re-import is a no-op)
    else: move staged file to <library dir>/<matchId>_<mediaType>.epub,
        insert device_files(origin 'import'), return match.id
else:
    id := Uuid.random().toString()
    move staged file to <library dir>/<id>_<mediaType>.epub
    one transaction: insert library_books (metadata, source hash = file hash),
        device_files (origin 'import'), sync_outbox library_book upsert
        (the same mutation as today, with library_book_id = id)
    return id
```

  `<library dir>` is `filesDir/library` on Android and `Documents/library` on iOS. That's a new
  directory, separate from the reader cache at `filesDir/ebooks` (I5, I6). Still always read the
  path from `device_files` and never rebuild it from the ID. That keeps moving or renaming files
  possible later.

- [ ] **Step 1: Write the failing tests** with fake databases:
  1. A new file creates a new UUID book and one outbox entry. The ID matches the regex
     `^[0-9a-f-]{36}$`.
  2. The same hash imported twice returns the same ID and creates no new rows.
  3. A hash matching `cloud_book_file_state` attaches to that book, with no new book and no
     outbox entry.
  4. A hash matching only `library_books.source_content_hash` attaches to that book.
  5. A different media type for a matched book adds a second `device_files` row.
- [ ] **Steps 2–4:** Watch the tests fail, implement, and watch them pass
  (`./gradlew :feature:books:data:testAndroidHostTest`).

### Task 2.4: Transfers and the download finalizer use the book ID

**Files:**
- Modify: `feature/books/data/.../transfer/BookFileTransferEngine.kt`. Remove `localSourceUuid`, the
  `"$algorithm:$contentHash"` at line 278, and the identity check at line 863. Read device files
  through `DeviceFilesDatabase`.
- Modify: `feature/books/data/.../transfer/DownloadFinalizer.kt`. Remove the identity check at
  line 40, and the library-book lookup "by `cloud_book_id`, then by hash". Write `device_files`
  with `origin = 'cloud_download'` at `<library dir>/<bookId>_<mediaType>.epub`. Keep the hash
  verification against the Parrot file record.
- Modify: `feature/books/domain/.../usecase/StartBookFileUploadUseCase.kt`,
  `RemoveBookFileDownloadUseCase.kt` and `BookFileTransferTransport.kt` (`BookFileTransferManager`),
  so that every signature takes `libraryBookId` and never a local file UUID.
- Tests: update `BookFileTransferEngineTest.kt` and `DownloadFinalizerTest.kt`. Add a case where a
  finalized download creates `device_files(bookId, "ebook", origin = "cloud_download")` and keeps
  an existing position keyed by `bookId`.

- [ ] Update the tests first and watch them fail. Then implement, and run
  `./gradlew :feature:books:data:testAndroidHostTest`.

### Task 2.5: Sync sends UUIDs and merges duplicates

**Files:**
- Modify: `lib/server-parrot-cloud/.../ParrotCloudSyncAdapter.kt` (remove
  `DEFAULT_CONTENT_HASH_ALGORITHM`-based IDs), `ParrotCloudModels.kt` (`ParrotCloudBookPayload`:
  `library_book_id`, `source_content_hash`, `source_content_hash_algorithm`; drop `cloud_book_id`),
  `ParrotCloudLibraryMutationSyncTransport.kt`, `ParrotCloudLibraryMutationApplier.kt`,
  `ParrotCloudBookFileChangeApplier.kt` and `ParrotCloudProgressTransport.kt`
- Modify: `feature/sync/data/src/commonMain/kotlin/com/retro99/sync/data/LibraryBookSyncApplier.kt`,
  so that remote library books upsert `library_books` by ID. Books from a pull have no device
  files.
- Create: the interface `feature/sync/domain/.../LibraryBookMerger.kt`:
  `suspend fun merge(fromId: String, intoId: String)`. Sync-data calls it when a `duplicate`
  response arrives.
- Create: its implementation, `feature/books/data/.../LibraryBookMergerImpl.kt`. Books-data already
  depends on sync-domain and owns the device files. It:
  - calls `LibraryBookMergeDatabase.mergeLibraryBook`,
  - deletes the returned paths through the existing `BookFileTransferFileStore`,
  - rewrites the `PreferencesKey.CurrentlyReading` preference when it points at
    `("local", fromId)`. Add `projects.lib.preferences.api` to books-data if needed.

  Domain modules must not depend on data modules.
- Modify: outbox draining, so that `library_book` entries go first (I7).
- Tests: `lib/server-parrot-cloud/src/commonTest/...` and `feature/sync/data/src/commonTest/...`

**Behaviour:**
- `accepted`: as today.
- `duplicate`: call `LibraryBookMerger.merge(fromId = entry.entityId, intoId = existing_book_id)`,
  apply the returned payload as a remote library book, and mark the outbox entry done.
- `library_book_not_found` on a position or session: retry later, the same as a transient failure.

- [ ] **Step 1: Write the failing tests:**
  1. The payload for book `B` has `library_book_id == B`, with no `:`.
  2. A `duplicate` response calls merge with `(B, X)` and resolves the outbox entry.
  3. Draining order puts `library_book` before `reading_position` for the same book, even when the
     position was enqueued first.
  4. A pulled library book creates a `library_books` row with no device file.
- [ ] **Steps 2–4:** Watch the tests fail, implement, and watch them pass.

### Task 2.6: Keep today's screens working on the new data

**Files:**
- Modify: `lib/server-local/.../LocalBooksRepository.kt`. List only books that have device files,
  mapped to today's `LocalBook` shape with `uuid = libraryBookId`.
- Modify: `lib/server-parrot-cloud/.../ParrotCloudBooksRepository.kt`. List books with Parrot
  state, `uuid = libraryBookId`. Remove the hash join at line 51 and the dead `saveBook` body.
- Modify: `feature/books/domain/.../model/BookDomainModel.kt` (the `libraryBookId` getter at line
  116 returns `uuid`) and `ServerBookExt.kt` (`canonicalIdentity` becomes `libraryBookId ?:
  "$serverId:$uuid"`).
- Modify: `BookDetailViewModel` and `BookDetailScreen`, only where they read removed fields
  (`localSourceUuid`, `origin`). This is a mechanical fix; the behaviour is corrected in slice 4.

- [ ] Run every module's tests, `./gradlew :androidApp:assembleDebug`, and the iOS compile of the
  changed modules.

### Slice 2 device check (run it, then report what you saw)

Use the local Supabase stack. Point a debug build at it the way the Parrot dev setup does. If you
can't, stop and ask the user.

1. **Upgrade from 0.4.5:**
   - Build 0.4.5 in a separate worktree: `git worktree add ../parrot-v045 v0.4.5`, then
     `./gradlew :androidApp:assembleDebug` in that worktree.
   - Install it on an emulator. Connect a Storyteller server, download one Storyteller book, and
     import two EPUBs.
   - Install this branch's build over it.
   - Expect: the app starts without a crash, the device library is empty (D2), and the
     Storyteller book, its download and its position are still there. Import one EPUB again; it
     appears and opens.
2. **Two devices:** import on emulator A and sign in to Parrot. Sign in on emulator B with the same
   account. Upload from A, then download on B. Expect B to open at A's position.
3. **Duplicate merge:** import the same EPUB on A and B before signing in, and read further on B.
   Sign in on both. Expect one book on each device, at B's position.

---

## Slice 3 — One library

### Task 3.1: The domain model: `LibraryBook` and `BookHome`

**Files:**
- Create: `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/model/BookHome.kt`
- Modify: `feature/books/domain/.../model/BookDomainModel.kt`. Replace `LocalBook` with
  `LibraryBook`.
- Modify: `feature/books/domain/.../model/ServerBookMapper.kt`. `isLocal` maps to `LibraryBook`.
- Delete: `feature/books/domain/.../model/ServerBookExt.kt` and `ServerBookExtTest.kt`. Remove
  `aggregateBookReplicas()` from `GetBooksUseCase` and `ObserveAllBooksWithProgressUseCase`.
- Modify: `lib/server/api/.../ServerBooksRepository.kt` (`ServerBook`): remove `localSourceUuid`.
- Update every `BookDomainModel.LocalBook` usage (about 18 files; find them with
  `grep -rn "BookDomainModel.LocalBook"`).
- Test: `feature/books/domain/src/commonTest/.../model/BookHomeTest.kt`

```kotlin
// @Serializable because BookUiModel (which carries it) is @Serializable.
@Serializable
enum class BookHome { ThisDevice, ParrotCloud, Storyteller, Audiobookshelf }

private val PARROT_HOME_STATES = setOf(
    RemoteFileAvailability.Available,
    RemoteFileAvailability.UploadPending,
    RemoteFileAvailability.Uploading,
)

fun List<MediaResource>.hasDeviceCopy(): Boolean =
    any { resource -> resource.localPath != null }

fun List<MediaResource>.isInParrotCloud(): Boolean =
    any { resource -> resource.remoteAvailability in PARROT_HOME_STATES }

val BookDomainModel.home: BookHome
    get() = when (this) {
        is BookDomainModel.LibraryBook ->
            if (mediaResources.isInParrotCloud()) BookHome.ParrotCloud else BookHome.ThisDevice
        is BookDomainModel.StorytellerBook ->
            if (serverType == ServerType.Audiobookshelf) {
                BookHome.Audiobookshelf
            } else {
                BookHome.Storyteller
            }
    }
```

```kotlin
/** Your library: files on this device and/or in Parrot Cloud. [uuid] is the book ID. */
data class LibraryBook(
    override val uuid: String,
    override val serverId: String,
    override val serverType: ServerType?,
    override val title: String,
    override val description: String?,
    override val coverUrl: String?,
    val author: String?,
    val publicationDate: String?,
    val addedAt: String,
    val lastOpenedAt: String?,
    override val mediaResources: List<MediaResource>,
) : BookDomainModel() {
    override val series: List<SeriesDomainModel> = emptyList()
    override val libraryBookId: String get() = uuid

    fun deviceFilePath(bookType: BookType): String? = mediaResources
        .firstOrNull { resource -> resource.mediaType == bookType.value }
        ?.localPath
}
```

- [ ] **Step 1:** Write a table test for `home`. It needs one case per row of the home table in
  §1.5, plus `UploadFailed`, `Deleting` and `None` with a device copy, all of which give
  ThisDevice.
- [ ] **Steps 2–4:** Watch it fail, implement, and watch it pass.

### Task 3.2: One library repository

**Files:**
- Modify (renaming the class is optional): `lib/server-local/.../LocalBooksRepository.kt`, which
  builds `ServerBook` from `LibraryLocalSource.observeLibrary()` combined with Parrot active.
- Create: `lib/server/api/.../ParrotCloudLibraryState.kt`:

```kotlin
/** Whether the current profile has an active Parrot Cloud account. */
interface ParrotCloudLibraryState {
    fun observeIsActive(): Flow<Boolean>
}
```

  Implement it in `lib/server-parrot-cloud`, moving the logic out of
  `BookDetailViewModel.observeActiveCloudAccount()`. `BookDetailViewModel` then uses it too.
- Modify: `lib/server-parrot-cloud/.../ParrotCloudBooksRepository.kt`. `getBooks()` returns
  `flowOf(Ok(emptyList()))` and `getBook()` returns `NotFoundError`. Add a KDoc explaining that
  Parrot books are listed by the `local` library repository.
- Test: `lib/server-local/src/commonTest/.../LocalBooksRepositoryTest.kt`

**Mapping:**
- For each record, build one `MediaResource` per media type present in `deviceFiles` or
  `parrotFiles`:
  - `localPath` is the device file path.
  - `remoteAvailability` is the Parrot status. It's `None` if Parrot is inactive or there's no
    Parrot file.
  - `cloudBookFileId` is the Parrot file ID.
  - `localOrigin` is the device origin.
- Then apply the visibility rule from §1.5.
- `ServerBook(uuid = id, serverId = LOCAL_SERVER_ID, serverType = ServerType.Local, isLocal = true,
  libraryBookId = id, coverUrl = coverPath, ...)`.

- [ ] **Step 1:** Write a table test covering: device only; Parrot `Available` only (visible while
  active, hidden when inactive); device plus Parrot; Parrot progress-only with no files (hidden);
  and Parrot `UploadFailed` with no device copy (hidden).
- [ ] **Steps 2–4:** Watch it fail, implement, and watch it pass.

### Task 3.3: The reader, detail and progress read library books

**Files:**
- Modify: `feature/books/domain/.../usecase/GetBookByUuidUseCase.kt`. For `LOCAL_SERVER_ID`, look
  up by book ID.
- Modify: `feature/reader/domain/.../usecase/InitializeReaderUseCase.kt` (line 61):
  `is BookDomainModel.LibraryBook -> initializeLibraryBook(book, bookType)` using
  `book.deviceFilePath(bookType)`. If it's `null`, return
  `AppError.NotFoundError("Book has no downloaded $bookType file")`.
- Modify: `feature/books/ui/.../detail/BookDetailViewModel.kt`. `navigateToReader` always calls
  `onNavigateToReader(serverId, bookUuid, ...)`. Delete the `localSourceUuid` redirect.
- Modify: `feature/reader/domain/.../usecase/ObserveAllBooksWithProgressUseCase.kt`, which works
  on `ServerBook`. For books from the local server (`ServerBook.isLocal`), the cached flags in
  `createProgressInfo` come from `mediaResources`, meaning `localPath` per type, not from
  `readerSettingsRepository.isEbookCached` (I6). Look positions up by `uuid`.
- Modify: `feature/books/ui/.../model/BookUiModel.kt` and `BookUiModelMapper.kt`:
  - Add `abstract val home: BookHome` to `BookUiModel`, mapped from `BookDomainModel.home`.
  - Replace `LocalBook` with `LibraryBook`, which also carries `mediaResources` and
    `hasDeviceCopy`.
- Tests: `InitializeReaderUseCase` opens the device path, returns an error when there's no device
  file, and never calls the reader-cache API.

### Task 3.4: The card badge shows the home

**Files:**
- Modify: `feature/books/ui/.../components/BookComponents.kt`. `ServerTypeBadge` becomes
  `HomeBadge(home: BookHome)` with the same shape and colours:
  - ThisDevice uses today's `Local` colours and the label `library_home_this_device`.
  - The others use today's colours and `ServerType.displayName`.
  - The downloaded icon follows the card rule in §1.5.
- Modify: `feature/books/ui/.../list/BooksListViewState.kt`. `showServerBadge` becomes
  `books.map { book -> book.home }.distinct().size > 1`.
- Test: `feature/books/ui/src/commonTest/...`. Cover the badge-visibility rule and the
  downloaded-icon rule as pure functions.

### Slice 3 device check

1. An uploaded book shows a single card with the badge "Parrot Cloud" and the downloaded icon.
2. The same book on emulator B, before downloading: "Parrot Cloud" with no icon. Tap it, download
   it, and it opens.
3. Sign out on B. The book that isn't downloaded disappears, and downloaded books remain with the
   badge "This device". Sign in again and it comes back.
4. Android Auto: the browse tree still opens a library book. (Media IDs are `book:local:<id>`.)
   If no head unit is available, test with the Desktop Head Unit, or ask the user.

---

## Slice 4 — Correct actions and wording

### Task 4.1: Action rules as a pure function

**Files:**
- Create: `feature/books/ui/src/commonMain/kotlin/com/retro99/books/ui/detail/LibraryBookActions.kt`
- Test: `feature/books/ui/src/commonTest/.../detail/LibraryBookActionsTest.kt`

```kotlin
data class LibraryMediaActions(
    val open: Boolean,
    val download: Boolean,
    val removeDownload: Boolean,
    val addToParrot: Boolean,
    val retryUpload: Boolean,
)

data class LibraryBookActions(
    val removeFromParrot: Boolean,
    val deleteFromDevice: Boolean,
)

fun MediaResourceUiModel.libraryActions(parrotActive: Boolean): LibraryMediaActions

fun BookUiModel.LibraryBook.bookActions(): LibraryBookActions
```

- [ ] **Step 1:** Write a table test with one case per row of the detail tables in §1.5, for both
  `parrotActive = true` and `false`.
- [ ] **Steps 2–4:** Watch it fail, implement, and watch it pass
  (`./gradlew :feature:books:ui:testAndroidHostTest`).

### Task 4.2: Wire the actions into the existing detail screen

**Files:**
- Modify: `feature/books/ui/.../detail/BookDetailViewModel.kt`:
  - Delete `canBackUp`, `backupServerId`, `canDeleteCloudBackup` and the branch in
    `deleteMediaCache` that chooses between the Parrot and cache paths.
  - For library books, derive everything from `LibraryBookActions`.
  - Each action goes through a use case in `feature/books/domain/.../usecase/`, backed by the data
    layer. The ViewModel never calls database or local-source APIs directly:
    - "Remove download" uses `RemoveBookFileDownloadUseCase(libraryBookId, mediaType)`, which
      removes the device file.
    - "Remove from Parrot Cloud" uses a new `RemoveFromParrotCloudUseCase(libraryBookId)`. It first
      sets the origin of the book's device files to `'import'`, then calls
      `BookFileTransferManager.deleteRemoteBackup(PARROT_CLOUD_SERVER_ID, libraryBookId, type)` for
      each Parrot media type.
    - "Delete from this device" uses a new `DeleteBookFromDeviceUseCase(libraryBookId)`, backed by
      `LibraryLocalSource.deleteBookFromDevice`.
- Modify: `feature/books/ui/.../detail/BookDetailScreen.kt`:
  - Show the same buttons as today, driven by the new flags.
  - The status line from §1.5 goes where the server badge was.
  - The "Remove from Parrot Cloud" confirmation reuses the existing delete-backup dialog, with the
    new strings. Use `…_keep_local` when the book has a device copy.
  - Delete the `isLocalBook` and `canDeleteCache` logic around lines 1025–1110 for library books.
    Storyteller and Audiobookshelf books keep their current cache delete.
- Modify: `BookDetailIntent.kt` and `BookDetailViewState.kt` as needed. Keep the names you rename
  consistent across VM, state and screen.
- Tests: ViewModel tests for each action. Each should call the right use case with `bookId`, and
  "Remove from Parrot Cloud" should set the origin before deleting.

### Task 4.3: Source filter by home, and the wording

**Files:**
- Modify: `feature/books/ui/.../model/BookFilterState.kt`. `serverTypeFilter: ServerType?`
  becomes `homeFilter: BookHome?`. If `BookFilterState` is persisted, make sure an old saved value
  decodes to `homeFilter = null` and doesn't crash. Check with
  `grep -rn "BookFilterState" --include='*.kt'`.
- Modify: `feature/books/ui/.../list/BooksListViewState.kt` (`applyServerTypeFilter` becomes
  `applyHomeFilter`), `BooksListViewModel.kt`, `BooksListIntent.kt`, `BooksListScreen.kt`
  (around lines 388–395) and `components/BookFilterBottomSheet.kt` (`sourceLabel`, around line 225).
- Modify: `lib/analytics/api/.../BookAnalyticsEvent.kt` (`ServerTypeFilterChanged`). Rename the
  value only if analytics allows it; otherwise send `home.name`.
- Modify: `translations/src/commonMain/composeResources/values/strings.xml`, following §1.6.
- Tests: a filter table test (each home, and null).

### Slice 4 device check

Walk through every row of both detail tables in §1.5 on an emulator, including the other-device
effect of "Remove from Parrot Cloud". Report each row as observed, not observed, or couldn't test,
with the reason.

---

## Slice 5 (optional, separate PR) — Parrot keeps metadata and the cover

Books that aren't downloaded currently show no cover or description.
- When a book is added to Parrot, send `description`, `publication_date` and `series` in the
  existing `metadata` JSON.
- Upload the cover as a small image into the `book-files` bucket at a path under the user and
  book, using the same storage policy.
- On pull, save the metadata into `library_books`, and download the cover into the app's cover
  directory (`cover_path`).
- Add pgTAP tests for the storage policy and client tests for the pull mapping.

Plan this slice in detail with the user before starting it.

---

# Part 3 — Risks and notes

- **First sync after sign-in with an existing library:** every local book is pushed. Books that
  match by hash on another device come back as `duplicate` and merge (D8). With a large library,
  test that draining doesn't block the UI.
- **Reading sessions** are append-only on the server. I7 ordering plus the outbox rewrite during a
  merge stop old IDs being pushed. Any path that pushes sessions without going through the outbox
  breaks this, so check.
- **The detail screen is large** (`BookDetailScreen.kt` is 75 KB, `BookDetailViewModel.kt` is 770
  lines). Put the logic in the ViewModel and in pure functions (Task 4.1), and keep the composable
  changes thin, because the user will redesign these screens later. Where a composable only
  branches on `isLocalBook`, replace the condition and leave the composable alone.
- **Parrot Ink restyle:** `docs/superpowers/plans/2026-09-29-parrot-ink-redesign.md` (Tasks 7 and
  8) restyles `BookComponents.kt`, `BooksListScreen.kt` and `BookDetailScreen.kt`. If it's being
  implemented at the same time, expect merge conflicts in those files. Ask the user which lands
  first.
- **iOS:** compile every changed module for `iosSimulatorArm64`. The import directory
  (`Documents/library`) and the finalizer paths are iOS-specific. Say in the report whether iOS was
  actually run.
- **Later, not now:**
  - an "Add to Parrot Cloud" prompt for signed-out users (upsell),
  - auto-upload as the default for paying users,
  - renaming the internal `local` and `ServerType.Local` to "library",
  - squashing Parrot migrations before launch,
  - projects 2 and 3, planned in `2026-10-01-linked-books-across-servers.md` and
    `2026-10-01-progress-across-linked-copies.md`.
