# Prepared chapters in Parrot Cloud: run of 2026-10-11

One line per check: what was run, what the server answered, what the screen showed.
Worktree `.claude/worktrees/tts-investigation`, branch `tts/prepared-cloud`.
No key, password, token, project reference or account email appears in this file.

## Part A: bring the branch up to date

- A1 merge `origin/main` (9a546207) into `tts/prepared-cloud` (631d48fb): merged clean,
  no conflicts, merge commit 6a391fc6. Brings the screen-locked read-aloud fix, the
  recorded-narration fix and the iPhone read-aloud spike.
- A2 owner's nine decisions of 2026-10-11 written into the brief's "Decided by the
  owner" section as commit 991d88c6, before any other work.
- A3 full verify suite plus `:feature:reader:domain` and `:lib:epub:implementation`:
  BUILD SUCCESSFUL in 1m 55s, every module green, no failures and no skips.
  Counts read from `build/test-results/*/`:
  - feature/reader/ui androidHostTest 667/667 (baseline on main was 560/560; this
    branch adds 107 prepared-cloud tests)
  - feature/reader/ui iosSimulatorArm64Test 401/401
  - feature/reader/domain androidHostTest 196/196
  - lib/epub/implementation androidHostTest 45/45
  - feature/books/data androidHostTest 151/151
  - feature/cloud-account/ui androidHostTest 28/28
  - feature/settings/ui androidHostTest 24/24
  - lib/server-parrot-cloud androidHostTest 62/62
  - lib/analytics/implementation androidHostTest 80/80
  - composeApp androidHostTest 65/65 (baseline on main was 64/64)

## Part B: the server, on the linked development project

### Environment

- B0 OrbStack (decision 4): `docker version` server 29.4.0 over
  `unix://$HOME/.orbstack/run/docker.sock`. Already running; `orbctl start` not needed.
  Supabase CLI 2.119.0.
- B0 linked this worktree to the same project as the owner's main checkout, using the
  reference stored in that checkout. No login prompt and no database password prompt;
  the CLI provisions a temporary login role per connection.
- B0 `git check-ignore -v supabase/.temp/project-ref` -> `.gitignore:38:supabase/.temp/`.
  Still ignored. Nothing from `supabase/.temp` was committed or quoted.

### B1 pending migrations

- `supabase migration list`: 33 local migrations, 31 applied remotely. Exactly two
  pending, and they are the two decision 2 allows:
  `20261009000000_parrot_cloud_saved_words.sql` and
  `20261010000000_parrot_cloud_prepared_audio.sql`. No remote migration is missing from
  the repository. Part B continues.

### B2 how the suite was made runnable against the linked project

This needed solving before any baseline could be recorded, and it is the reason the
previous run reported the suite as unrunnable.

- `supabase test db --linked supabase/tests` fails every one of the 21 files identically:
  `ERROR: function plan(integer) does not exist`, 0 tests run, `Result: FAIL`.
- Cause, established by probe: pgTAP 1.3.3 *is* installed, in schema `extensions`, and
  the test files already do `set search_path = extensions, public`. But the CLI connects
  as a temporary role `cli_login_postgres` for which
  `has_schema_privilege(current_user,'extensions','usage')` is **false**, and a function
  in a schema without USAGE reports as "does not exist" rather than "permission denied".
- That role is a member of `postgres`, `anon`, `authenticated`, `service_role` and others,
  but `rolinherit` is **false**, so it holds none of those privileges until it
  `set role`s. `set role postgres; select plan(1); select ok(true); select finish();`
  against the linked project returns `Result: PASS`.
- Second cause: the suite contains 95 `reset role;` statements, used after a
  `set local role authenticated` to verify persistence with privileged access. On the
  local stack `reset role` lands on `postgres`, which is superuser there, so the tests
  were written for a privileged session role. On the linked project it lands on
  `cli_login_postgres`, giving `permission denied for table cloud_book_files`,
  `... for schema storage`, and so on in 10 further files.
- Resolution, chosen so that **neither the repository nor the server is modified**: the
  21 `.sql` files are copied to a scratch directory outside the repository with one line
  prepended (`set role postgres;`) and each `reset role;` rewritten to
  `set role postgres;`. That reproduces exactly the privileged session role the files
  were written for. The repository's test files are unedited; no role attribute, grant
  or row on the server was changed. The alternative considered and rejected was
  `alter role cli_login_postgres inherit`, which would have mutated the project.

### B2 baseline, before applying anything

`supabase test db --linked <scratch copy>`, 62s, Files=21, Tests=424.

- 20 of 21 files report `ok` with every assertion passing: abuse_operations (25),
  account_deletion (14), book_files (15), book_links (31), client_book_ids (20),
  durable_recaps (16), finalize_book_upload (19), non_available_book_delete (9),
  orphan_gc (19), reader_settings, reading_position_source_device (12),
  reading_sessions (14), recap_error_reasons, recap_uploads (22), recap_usage (17),
  rls_isolation (10), saved_items (27), security_hardening (56),
  stale_upload_cancel (11), storage_policy (10). Total 421 assertions passing.
- 1 file fails, expected: `prepared_audio_test.sql`, `plan(38)`, ran 3 and died at
  line 111 with `ERROR: permission denied for table cloud_user_storage` — the migration
  that creates this work's objects and grants is not applied yet. This is the file whose
  38 assertions have never run anywhere.
- No file fails for any other reason, so nothing is recorded as "already failing".
- The repository has **no** saved-words pgTAP test file: `grep -rl saved_word
  supabase/tests/` returns nothing. `saved_items_test.sql` is a different, already
  applied feature. So migration 20261009000000 brings no tests of its own.

### B3 pre-apply queries from supabase/PREPARED_AUDIO_ROLLOUT.md

All four answered as the document requires, before applying:

- Q1 `cloud_book_files` rows with media type `tts_prepared_audio` breaking the new path
  or 64 MiB size check: **0**.
- Q2 same for `cloud_book_uploads`: **0**.
- Q3 `reserve_book_upload` definitions in `public`: **1** — the rename target exists and
  has not been renamed by a later migration.
- Q4 (extra) any `tts_prepared_audio` row at all: **0**. Nothing has ever written this
  media type to the project, so both new constraints can validate.

### B4 apply

- `supabase db push --linked`: applied both, in timestamp order —
  `20261009000000_parrot_cloud_saved_words.sql` then
  `20261010000000_parrot_cloud_prepared_audio.sql`. Output
  `{"upToDate":false,"dryRun":false,"migrations":[<the two above>],"seeds":[],"roles":[],
  "message":"Finished supabase db push."}`. No error, no warning about a constraint
  staying NOT VALID.

### B5 the suite after applying

First run after applying: all 20 existing files still `ok`, unedited — the migration
does not change existing book behaviour, which was the thing the rollout document said
to stop for. `prepared_audio_test.sql` still failed, and the three failures were all in
the test file, not in the migration. The file had never been executed, so none of them
had ever been caught:

- B5a line 107/269/384: `select ... from public.cloud_user_storage` while still in
  `set local role authenticated`. That table has no select grant for `authenticated`
  (`has_table_privilege('authenticated','public.cloud_user_storage','select')` = **f**;
  the app reads it through the security-definer `get_storage_usage`). The other test
  files drop to the session role to read it. Fixed the same way: `reset role` around the
  three assertions. Server answer before: `ERROR: permission denied for table
  cloud_user_storage`, file died after 3 of 38.
- B5b the three quota assertions asked to reserve 209715200 bytes and expected
  `quota_exceeded`. The migration checks the 64 MiB per-chapter cap **before** the
  allowance, so the server answered `file_too_large` with no `quota_bytes` key —
  pgTAP: `have: file_too_large / want: quota_exceeded`. The assertion as written could
  never pass against a correct migration. The 64 MiB cap already has its own assertion
  at line 150, so the quota case was made reachable instead: lower this account's
  `quota_bytes` to 20 MiB, ask for 32 MiB (under the cap, over the allowance), assert
  `quota_exceeded` and `quota_bytes` = 20971520, then restore the allowance to
  209715200 so the later byte accounting is unchanged. Assertion count stays 38.
- B5c line 378 `delete from storage.objects` was refused:
  `ERROR: Direct deletion from storage tables is not allowed. Use the Storage API
  instead.`, raised by `storage.protect_delete()`, which requires
  `current_setting('storage.allow_delete_query') = 'true'`. Five other test files
  already set that flag before standing in for a Storage API remove();
  `prepared_audio_test.sql` did not. Added the same
  `set local storage.allow_delete_query = 'true';`.
- B5 final: `Files=21, Tests=459, Result: PASS`. 421 existing assertions (all 20 files,
  unedited) plus the **38 new prepared-audio assertions, 38/38**. First time this SQL
  has ever been executed anywhere.

### B6 corrective migrations

**None were needed.** The migration `20261010000000` was correct as written; all three
failures were bugs in this work's own pgTAP file, which the prompt allows changing. No
migration that has been applied was edited, and no existing test file was touched.

### B-verify post-apply queries from the rollout document, step 4

- V1 the four constraints exist and are **validated**:
  `cloud_book_files_prepared_audio_path_check` t,
  `cloud_book_files_prepared_audio_size_check` t,
  `cloud_book_uploads_prepared_audio_path_check` t,
  `cloud_book_uploads_prepared_audio_size_check` t.
- V2 both `reserve_book_upload` and `reserve_book_upload_before_prepared_audio` exist in
  `public` — the rename happened and the wrapper is in place.
- V3 `cascade_prepared_audio_on_file_deleting` on `cloud_book_files` and
  `cascade_prepared_audio_on_block` on `cloud_content_blocklist` — both attached.
- V5 `has_function_privilege('authenticated', ...reserve_book_upload_before_prepared_audio...)`
  = **f**; the inner function is reachable by nobody directly.
- V6 `tts_prepared_audio` rows on the project: 0. Nothing uploaded yet.

### B7 open uploads to every signed-in account (decision 1)

How it was decided before: `20261003000000_parrot_cloud_security_hardening.sql:24`
`cloud_feature_enabled(feature)` is a `security definer` sql function returning
`exists (select 1 from cloud_feature_allowlist where cloud_user_id = auth.uid() and
feature = <feature>)`; line 43 `get_cloud_feature_access()` just reports that for
`'uploads'` and `'recap'`. Execute on both is revoked from `public, anon, service_role`
and granted to `authenticated`. `reserve_book_upload`, `finalize_book_upload` and three
Storage RLS policies all gate on `cloud_feature_enabled('uploads')`.

- B7a test written first: `supabase/tests/open_uploads_test.sql`, `plan(17)`. Run
  **before** the migration: `Result: FAIL`, 9 of 17 failed — tests 1-6 and 13-15.
  Server answers at that point: `cloud_feature_enabled('uploads')` = false,
  `get_cloud_feature_access()` = `{"recap": false, "uploads": false}`, reserve status
  `rejected`, and the quota and size cases answered `uploads_not_enabled` before ever
  reaching their own check. The 8 that already passed are the ones asserting what must
  **not** change: the three anon refusals, the no-subject case, and recap still being
  allow-list only.
- B7b migration: `supabase/migrations/20261011000000_parrot_cloud_open_uploads.sql`.
  `cloud_feature_enabled` keeps its shape, grants and `security definer`; its body
  becomes a `case`: false when `auth.uid() is null`, true when the feature is
  `'uploads'`, otherwise the same allow-list `exists` as before. So recap and any
  feature added later still mean "is there a row". `get_cloud_feature_access` is
  re-created unchanged so both halves sit in one migration. Existing `'uploads'`
  allow-list rows are deliberately left in place: historical record, and the rollback
  needs them.
- B7c applied with `supabase db push --linked`: `20261011000000` applied, no error.
- B7d suite after: **22 files, 476 assertions, `Result: PASS`**.
  `open_uploads_test.sql` 17/17. Two corrections were needed to the new test along the
  way, both wrong guesses about server answers rather than behaviour faults: the
  cross-account refusal is `cloud_book_not_owned`, not `book_not_found`, and
  `cloud_content_blocklist.created_by` is not null.
- B7e **quota, size limits and block-list confirmed still in force for an account with
  no allow-list row**, by assertion against the server: a 32 MiB chapter against a
  20 MiB allowance answers `quota_exceeded`; a 67108865-byte chapter answers
  `file_too_large`; a block-listed content hash answers `content_blocked`; another
  account's book answers `cloud_book_not_owned`. Anon answers `42501` to all three of
  `cloud_feature_enabled`, `get_cloud_feature_access` and `reserve_book_upload`.
- B7f **recap untouched**: an account with no row still gets `recap` false, an account
  with a row still gets true, and an account with only a recap row now reports
  `{"uploads": true, "recap": true}`.

#### Existing assertions changed, with the old and the new rule

Four in `supabase/tests/security_hardening_test.sql`, and only these. The count stays
`plan(56)`. Two had to be restated rather than inverted, because asserting the new rule
the obvious way changed state that later assertions in the same file count — recorded
here because that is the kind of thing a reader will otherwise undo:

1. `'an account off the allowlist cannot reserve uploads'` ->
   `'an account with no allowlist row is no longer stopped by the gate (20261011000000)'`.
   Old rule: reserve answers `uploads_not_enabled`. New rule: it gets past the gate.
   Asserted with a zero-byte file so the answer is `invalid_upload_metadata`. A
   *successful* reserve here was tried first and broke test 56 at the end of the file
   (`'the newest change per entity survives'`): account C's reservation writes a
   `sync_changes` row for account C, which that assertion counts. The real
   reserve-and-finalize for an account with no allow-list row is asserted in
   `open_uploads_test.sql` instead.
2. `'feature access reports nothing for an account off the allowlist'` ->
   `'feature access reports uploads open and recap still allowlisted (20261011000000)'`.
   Old rule: `{"uploads": false, "recap": false}`. New: `{"uploads": true,
   "recap": false}`. A plain inversion, no side effects.
3. `'Storage refuses writes once the account is off the allowlist'` ->
   `'Storage still accepts writes with no uploads allowlist row (20261011000000)'`.
   Old rule: `throws_ok ... 42501`. New: `lives_ok`. The Storage RLS policy gates on the
   same function, so the write is now permitted.
4. `'finalize refuses a reservation once uploads are revoked'` ->
   `'uploads stay enabled with the allowlist row gone (20261011000000)'`. Old rule:
   finalize answers `uploads_not_enabled`. New rule asserted through the gate the
   function consults, not by calling finalize: **any** finalize call, successful or
   refused, settles the reservation, and the pending-reservation cap 60 lines below
   needs that reservation still live. Calling finalize here was tried twice — once
   successfully, once with a deliberate `size_mismatch` — and each time broke tests
   30-32 (`'a 201st live reservation is refused'`, `'the cap rejection carries a retry
   hint'`, `'repeating a live reservation is not blocked by the cap'`), which depend on
   29 + 170 reservations plus this one reaching exactly 200. finalize succeeding without
   an allow-list row is asserted in `open_uploads_test.sql` instead.

The comment above that block was also reworded, since "losing the upload allowlist stops
writes" is no longer what it demonstrates.

### B-left is the project still working for book backups

Yes, and by observation rather than by construction this time: every one of the 20
pre-existing test files passes against the project after all three migrations, including
`book_files_test.sql`, `finalize_book_upload_test.sql`, `non_available_book_delete_test.sql`,
`orphan_gc_test.sql`, `storage_policy_test.sql`, `stale_upload_cancel_test.sql`,
`account_deletion_test.sql` and `rls_isolation_test.sql` — the whole reserve, upload,
finalize, restore, delete, takedown and quota path for ordinary book files. 476
assertions, `Result: PASS`.

### B8 supabase/PREPARED_AUDIO_ROLLOUT.md

Rewritten: what is now applied to the development project, the suite result, the fact
that this migration needed no correction, the recipe for running the suite against a
linked project at all (and why not to fix it with `alter role ... inherit`), a section
for `20261011000000` with its own rollback, and a six-point list of what a production
rollout still needs — including that opening uploads is a product decision with a storage
cost, not just a migration.

## Part C: the estimate on the button (decision 7)

- C1 where it is computed: `feature/reader/ui/src/commonMain/kotlin/com/retro99/reader/ui/
  reader/PreparedChapterEstimate.kt`, pure, no Android types. `preparedChapterEstimate(
  sentenceCount, voiceKind, measured)` returns `PreparedChapterEstimate(minutes, bytes)`
  or null. The row renders it in `PreparedChapterRowUi.kt` under the `NotPrepared` state
  only; nothing else about the row moves.
- C2 figures, taken from the six-sentence table in `docs/tts-prepared-chapters.md`
  ("Step 1: Samsung measurements and format gate"). Bytes are the mean of its **M4A**
  column, which is the format the store actually keeps (`PREPARED_AUDIO_EXTENSION` =
  `m4a`, `TtsPreparedStore.kt:12`):
  - System: 2500 ms and 16000 bytes per sentence (table mean 2531 ms, 16281 B)
  - Kokoro: 2200 ms and 14000 bytes per sentence (table mean 2218 ms, 14310 B)
  - Supertonic: the same as Kokoro. **It was never in that table**; it takes the other
    neural engine's figures, and this is written in the code comment too.
  The 64 kB per sentence in the same document is the *free-space headroom* figure, four
  times the measured size, and would overstate a user-facing estimate; it is not used.
- C3 **the time figures are a stand-in, and this is the one soft spot in part C.** The
  document holds no measurement of synthesis *speed* anywhere — only spoken duration per
  sentence. So spoken length stands in for preparation time. Part D check 2 compares it
  with reality for the first time; see the D section for the measured outcome.
- C4 the device's own measurements override the fixed ones: `PreparedChapterMeasured(
  msPerSentence, bytesPerSentence)`, either half independently, and a zero or negative
  figure is ignored rather than trusted. Wired on Android in
  `AndroidTtsController.preparedChapterMeasured`, which averages the manifests of
  completed chapters whose `voiceId` matches the voice selected now:
  `bytes / sentences` over all of them. `PreparedChapterSummary` gained a `sentences`
  count for the divisor. **Only the size half is measured**; synthesis time is recorded
  nowhere in the app, so `msPerSentence` is always null today and the fixed figure is
  used. Recording it would mean changing the manifest format, which the archive checks
  and the iPhone format both depend on, so it is left for the owner to decide.
- C5 no estimate when the sentence count is not known: `sentenceCount` is
  `viewState.ttsSentenceCount`, which the TTS controller only fills once read-aloud has
  loaded the chapter. Null, zero and negative all return null, and the row then shows the
  unchanged `reader_tts_prepared_chapter_hint`.
- C6 rounding, as a person would say it: under 45 s is "under a minute"; 1-9 minutes
  exactly; 10-59 to the nearest 5; an hour and more to the nearest 10 and said as
  "1 h 30 min". Size is coarser than the exact label on purpose — whole MB above a
  megabyte, tens of kB below — because "about 9.1 MB" claims a precision an estimate
  does not have.
- C7 tests: **25 added**, all written before the code. 19 in
  `PreparedChapterEstimateTest.kt` (no-count, each voice kind, measured override, one
  half measured, nonsense measured, every rounding boundary including the 44/45 s edge,
  the size label, and which kind each voice is) and 6 in `PreparedChapterRowUiTest.kt`
  (the three shapes, a whole number of hours, the plain hint when there is no estimate,
  and that the estimate never leaks into the Ready or Partly rows). One existing
  assertion in `PreparedChapterRowUiTest` gained an `assertEquals(emptyList(),
  ui.statusArgs)` line; no existing assertion was changed or removed.
- C8 new string keys in `translations/src/commonMain/composeResources/values/strings.xml`,
  beside the existing `reader_tts_prepared_` entries:
  - `reader_tts_prepared_chapter_estimate` — "About %1$d minutes · about %2$s"
  - `reader_tts_prepared_chapter_estimate_hours` — "About %1$d h %2$d min · about %3$s"
  - `reader_tts_prepared_chapter_estimate_short` — "Under a minute · about %1$s"
- C9 counts after part C, all green, no skips: reader ui android 667 -> **692**, reader ui
  ios 401 -> **426**; every other module unchanged (reader domain 196, epub 45, books data
  151, cloud-account ui 28, settings ui 24, server-parrot-cloud 62, analytics 80,
  composeApp 65).

## Part D: end to end on two devices

Devices: Samsung SM-S921B serial `RFCWC0SSVDM` and the Pixel 10 Pro XL emulator serial
`emulator-5554`. A Xiaomi (`192.168.1.248:5555`, model 2602BPC18G) was also attached for
the whole run and **no command was sent to it**; every adb call carried `-s` with one of
the two allowed serials.

- D0 build and install: `:androidApp:assembleDebug` BUILD SUCCESSFUL, installed with
  `adb install -r` on both devices, `Success` each. Launcher activity is
  `com.retro99.parrot/.android.MainActivity` (not `.MainActivity`).
- D0 **the app really does point at the project part B migrated**: the APK's
  `com.retro99.parrot.SUPABASE_URL` metadata is the linked project's host. Checked with
  `aapt2 dump xmltree`; the reference is not reproduced here.
- D0 signed in on the Samsung: **yes**, already. Signed in on the emulator: **yes**,
  already, to the same account. So decision 6 was never exercised — no Google chooser
  appeared, and **no password and no verification code was typed on either device**.
- D0 auto-backup was **off** on the Samsung and was turned on. Turning it on raises a
  rights attestation dialog ("I have the right to store these books in Parrot Cloud")
  whose checkbox must be ticked before "Turn on" does anything; the checkbox is a
  separate 125 px node to the left of the text, not the text itself.

### D1 back up a short Project Gutenberg book

- Imported `iosApp/iosApp/sample-books/PrideAndPrejudice.epub` (Project Gutenberg, public
  domain, 24,846,289 bytes — an illustrated edition, 187 entries, mostly JPEGs). Library
  21 -> 22 books.
- **With auto-backup on, the newly imported book was still "Only on this phone"** and had
  to be backed up with the book's own "Add to Parrot Cloud" action. Recorded, not fixed:
  this is book backup, which the brief puts outside this work.
- Backed it up at 00:26:55. The book screen showed "✓ On all your devices" by 00:27:03.
- Server: `cloud_book_files` media `ebook` status `available` went 2 -> 3 rows,
  `sum(size_bytes)` 90,305 -> **24,936,594**; `cloud_book_uploads` 3 finalized;
  `cloud_user_storage.used_bytes` 24,936,594. **PASS.**

### D1a a short chapter had to be manufactured

Every chapter of that Gutenberg edition is one large spine item: the chapter on screen
reported **391 sentences**, and measured preparation was ~8.5 s per sentence on this
Samsung (32 sentences in 4.5 min, started 00:34:13, cancelled at 39/391), so one chapter
is ~55 minutes. That is too slow to repeat for checks 2 and 4 to 10.

So three distinct short books were built from the repository's own 2 KB fixture
`feature/reader/ui/src/androidMain/assets/reader-preview/sample.epub` (one chapter,
9 sentences), each given a different title and one different sentence so the three have
different content hashes (verified: three different md5s). They were pushed to
`/sdcard/Download/` as `parrot-qa-short-{A,B,C}.epub`. Book **A** carries checks 2 to 5.
The Gutenberg book carries check 1, which is what check 1 is about.

The cancelled 391-sentence partial was deleted from the row first. Its confirmation read
"Delete prepared audio? This chapter will have to be prepared again before it plays
instantly." and **correctly did not mention the cloud**, because that chapter had never
been backed up.

### D2 prepare a short chapter with Kokoro

- Book A imported (22 -> 23 books) and backed up: server `ebook` rows 3 -> 4,
  `sum(size_bytes)` 24,936,594 -> **24,938,916** (+2,322, the epub exactly).
- Voice set to Kokoro · Heart, rate 1×, on both devices.
- **The estimate is shown before starting, as decision 7 asks.** For 9 sentences it read
  "**Under a minute · about 130 kB**". Prepared at 00:46:52; "Ready, plays instantly ·
  219 kB" by **t+17 s**.
  - estimate vs reality: time **under a minute vs 17 s — right**. Size **130 kB vs
    219 kB — under by 40%**; the real figure on this device is ~24 kB per sentence, not
    the 14 kB the measurement table gave. Not tuned, per the owner's instruction that
    this is being done on another branch.
- Row lines, in order: "1 of 9 sentences" -> "5 of 9 sentences" -> "Ready, plays
  instantly · 219 kB" with a second line "**Waiting to back up**".
- Server, ~5 minutes later: `cloud_book_files` media **`tts_prepared_audio`** status
  **`available`**, 1 row, **222,046 bytes**; `cloud_book_uploads` media
  `tts_prepared_audio` status `finalized`, 1; `used_bytes` **25,160,962**.
  **The upload worked end to end — the first time the app has ever talked to a server
  that knows prepared audio.**
- **But the row kept saying "Waiting to back up"** for over 5 minutes and across closing
  and reopening the Listening sheet, while the server already had the file available and
  finalized. After `am force-stop` and reopening, the same row read "**Backed up to
  Parrot Cloud**". So the state is computed correctly and only its liveness is wrong.
  Recorded as wrong behaviour **W2**. **PARTIAL PASS.**

### D3 the usage breakdown

Cloud account screen: "Parrot Cloud storage — 25.2 MB of 5.4 GB", and beneath it
"**Books 24.9 MB**" and "**Prepared audio 222.0 KB**". Server: 24,938,916 +
222,046 = 25,160,962. The two add up to the total. **PASS.**

(This account's allowance is 5.4 GB, not the 200 MiB default; it was not changed by this
run except in check 7, and was put back.)

### D4 download on the emulator

- The emulator synced and both books appeared from the cloud (library 0 -> 4 books).
  Book A's ebook downloaded on request: "Not on this phone" -> "On this phone · 2 KB".
- **Before** the Kokoro pack was installed, with the System voice selected, the chapter's
  row already read "**In Parrot Cloud, made for other settings · 222 kB**" with one
  action, **Download**. So the device learns from the cloud what is there, with its size,
  and says what it was made for. 
- Kokoro pack downloaded on the emulator (149 MB, ~2 min) and Heart selected at 1×. The
  row then read "**In Parrot Cloud, plays instantly · 222 kB**" with **Download**.
- Download tapped at 01:02:08. The store afterwards holds
  `files/tts-prepared/<64 hex>/<64 hex>/` with **9 `.m4a` files and `manifest.json`**, and
  `files/tts-prepared-inbox` is **empty** — the archive was verified and unpacked into
  place and left nothing behind.
- The row said "Downloading from Parrot Cloud…" and stayed there (same staleness, **W2**).
  After a restart it read "**Ready, plays instantly · 219 kB**" and "**Backed up to
  Parrot Cloud**".
- **Play with no synthesis: PASS.** Playback reached "Sentence 7 of 9" within 25 s, and in
  the whole logcat for that playback `grep -ci 'synthesize start'` = **0**,
  `grep -ci 'kokoro'` = **0**, `grep -c 'FATAL EXCEPTION'` = **0**. A chapter prepared on
  the Samsung played on the emulator without generating a single sentence.

### D5 a different setting selected

Covered by the voice case rather than the speed case: with the System voice selected
instead of Kokoro, the same row read "In Parrot Cloud, made for other settings · 222 kB"
and still offered Download, exactly as it does for local audio. The speed variant
(same voice, rate other than 1×) was **not** tried — see "not done" below. **PASS for
the voice case.**
