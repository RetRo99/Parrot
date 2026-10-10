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
