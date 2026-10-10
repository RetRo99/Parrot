# Prepared chapter audio rollout (20261010000000)

`migrations/20261010000000_parrot_cloud_prepared_audio.sql` makes a prepared
chapter one more `cloud_book_files` row of its book, with the media type
`tts_prepared_audio`. It adds four table constraints, a 64 MiB per-chapter
reserve limit, a "the book must have a usable backup" precondition, three
cascades so audio never outlives its book, and a books/prepared-audio breakdown
on `get_storage_usage`. Run every step yourself; nothing here is automated.

## Status

**Applied to the linked development project on 2026-10-11, and the SQL has now
been run.** What happened, so no one rediscovers it:

- `supabase db push --linked` applied three migrations in timestamp order:
  `20261009000000_parrot_cloud_saved_words.sql` (the saved-words feature's, which
  the owner released for this purpose), this one, and then
  `20261011000000_parrot_cloud_open_uploads.sql` (below).
- The whole pgTAP suite passes against the project: **22 files, 476 assertions,
  `Result: PASS`**, including this migration's 38. Every pre-existing file passes
  unedited apart from the four allowlist assertions that the open-uploads change
  required (listed in its section below).
- All four post-apply checks in step 4 answered as expected: four constraints
  present and `convalidated = t`, both the renamed function and the wrapper in
  place, both cascade triggers attached, and the inner function not executable by
  `authenticated`.
- **This migration needed no correction.** The three failures on its first real
  run were all in `supabase/tests/prepared_audio_test.sql`, which had never been
  executed: it read the private `cloud_user_storage` while still in role
  `authenticated`, it expected `quota_exceeded` for a 200 MB chapter when the
  64 MiB cap is checked first and answers `file_too_large`, and it deleted from
  `storage.objects` without `set local storage.allow_delete_query = 'true'`.

### Running the suite against a linked project

`supabase test db --linked` does not work against a hosted project out of the
box, and the failure is misleading: all 21 files report
`function plan(integer) does not exist` and zero tests run. pgTAP is installed
(in schema `extensions`), but the CLI connects as a temporary role
`cli_login_postgres` which has no `usage` on that schema — and a function in a
schema without `usage` reports as "does not exist", not "permission denied".
That role is a member of `postgres` but has `rolinherit = false`, so it holds
nothing until it `set role`s. The suite also contains 95 `reset role;`
statements which, on the local stack, land on a superuser `postgres`; on a
hosted project they land back on the unprivileged login role.

To run the suite against a linked project without editing the test files or
changing anything on the server, copy them to a scratch directory outside the
repository, prepend `set role postgres;` to each and rewrite each
`reset role;` to `set role postgres;`:

```bash
mkdir -p /tmp/parrot-suite
for f in supabase/tests/*.sql; do
  { echo "set role postgres;"; sed 's/^reset role;/set role postgres;/' "$f"; } \
    > /tmp/parrot-suite/$(basename "$f")
done
supabase test db --linked /tmp/parrot-suite
```

Do **not** fix this with `alter role cli_login_postgres inherit`: that mutates
the project. On the local stack (`scripts/supabase/test.sh`) none of this is
needed.

## Also applied: 20261011000000, uploads open to everyone

The owner's decision of 2026-10-11: anyone with a Parrot Cloud account may
upload. `cloud_feature_enabled('uploads')` now answers true for any caller with
an `auth.uid()`, instead of looking for a `cloud_feature_allowlist` row.
`'recap'` is untouched and still allowlist-only. Nothing else about uploading
moves: the 200 MiB allowance, the per-file size limits including this
migration's 64 MiB per chapter, the content block-list, the pending-reservation
cap, the rights attestation and every RLS policy are all checked elsewhere and
unchanged. `anon` still cannot execute either function.

Existing `'uploads'` allowlist rows are left in place on purpose: they are now
historical, and the rollback needs them.

Its tests are `supabase/tests/open_uploads_test.sql` (17 assertions), written
before the migration and seen to fail on 9 of them. Four assertions in
`supabase/tests/security_hardening_test.sql` stated the old rule and were
changed; they are listed in
`docs/manual-qa-evidence/2026-10-11/tts-prepared-cloud/NOTES.md`.

Rollback for it is the previous definition, which is additive to restore:

```sql
create or replace function public.cloud_feature_enabled(feature text)
returns boolean language sql stable security definer set search_path = public
as $$
    select exists (
        select 1 from public.cloud_feature_allowlist a
        where a.cloud_user_id = auth.uid()
          and a.feature = cloud_feature_enabled.feature
    );
$$;
revoke execute on function public.cloud_feature_enabled(text)
from public, anon, service_role;
grant execute on function public.cloud_feature_enabled(text) to authenticated;
```

Accounts that uploaded while it was open keep their files; they simply cannot
upload more until they are allowlisted again.

## What a production rollout still needs

Nothing here has touched production. In order:

1. **Decide the saved-words migration separately.** `db push` applies the whole
   pending chain, so `20261009000000_parrot_cloud_saved_words.sql` will go out
   with these. On the development project the owner allowed that. For production
   it is a separate feature's decision.
2. **Run the suite against production's schema before pushing**, by the scratch
   copy recipe above, and read step 2's pre-apply queries there — they must all
   return 0. On a database with real uploads they may not.
3. **Open uploads is a product decision with a cost.** On the development
   project it is mock data. In production it removes the only thing limiting who
   can consume storage: every signed-in account gets a 200 MiB allowance it can
   fill. Before pushing `20261011000000`, confirm the abuse controls in
   `docs/parrot-cloud-abuse-runbook.md` are live and watched, and that the
   storage cost of the whole account base at 200 MiB each is acceptable.
4. **Ship a client that understands the new media type first, or confirm older
   clients are safe.** The never-empty relative path is what keeps prepared
   audio invisible to apps released before this feature; see the note at the end
   of this document.
5. **The end-to-end device checks** in
   `docs/manual-qa-evidence/2026-10-11/tts-prepared-cloud/NOTES.md`, repeated
   against production once applied.
6. Rollback for both migrations is in this document; neither drops a column, so
   no data is lost either way.

## 1. Run it locally first (still the right first step for production)

```bash
supabase start
scripts/supabase/reset.sh      # applies the whole migration chain from scratch
scripts/supabase/test.sh       # must report ok for all 38 prepared-audio tests
```

`supabase/tests/prepared_audio_test.sql` is the new file, `plan(38)`. The whole
suite must stay green: this migration replaces `reserve_book_upload` and
`get_storage_usage`, so `book_files_test.sql`, `finalize_book_upload_test.sql`,
`rls_isolation_test.sql`, `abuse_operations_test.sql`,
`security_hardening_test.sql`, `non_available_book_delete_test.sql`,
`orphan_gc_test.sql` and `storage_policy_test.sql` all exercise the replaced
functions and must pass **unedited**. If any of them fails, stop: the wrapper
has changed existing behaviour, which it must not.

## 2. Before applying anywhere else

Check for rows that would break the new constraints. Each query must return 0.
If one does not, that constraint stays `NOT VALID` (the migration logs a
warning) and any later update of such a row fails, so fix or delete the rows
first.

```sql
select count(*) from public.cloud_book_files
where media_type = 'tts_prepared_audio'
  and (relative_path !~ '^tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\.zip$'
       or size_bytes > 67108864);

select count(*) from public.cloud_book_uploads
where media_type = 'tts_prepared_audio'
  and (relative_path !~ '^tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\.zip$'
       or size_bytes > 67108864);
```

Both should return 0 on any database today, because nothing writes that media
type yet. If either does not, something has already written prepared audio and
you should find out what before continuing.

Confirm the rename target exists and has not been renamed by a later migration.
This must return exactly one row.

```sql
select proname from pg_proc
where proname = 'reserve_book_upload'
  and pronamespace = 'public'::regnamespace;
```

## 3. Apply

```bash
supabase db push
```

The migration is additive: one new migration file, no existing migration
edited. It does, however, `alter function ... rename to` and
`create or replace` two live RPCs, so it is not a pure add. See step 5 for the
rollback.

## 4. Verify after applying

Each of these runs in the SQL editor as the service role.

```sql
-- 1. The four constraints exist and are validated (convalidated = true).
select conname, convalidated from pg_constraint
where conname like '%prepared_audio%' order by conname;
-- Expect 4 rows, all t.

-- 2. The rename happened and the wrapper is in place.
select proname from pg_proc
where proname in ('reserve_book_upload', 'reserve_book_upload_before_prepared_audio')
  and pronamespace = 'public'::regnamespace
order by proname;
-- Expect both.

-- 3. The two triggers are attached.
select tgname, tgrelid::regclass from pg_trigger
where tgname in (
    'cascade_prepared_audio_on_file_deleting',
    'cascade_prepared_audio_on_block'
) and not tgisinternal;
-- Expect cloud_book_files and cloud_content_blocklist.

-- 4. The storage breakdown is served and still adds up.
--    Run as an allowlisted account, not the service role.
select public.get_storage_usage();
-- Expect the five existing keys plus prepared_audio_bytes and books_bytes,
-- with books_bytes + prepared_audio_bytes = used_bytes.

-- 5. The inner function is reachable by nobody directly.
select has_function_privilege(
    'authenticated',
    'public.reserve_book_upload_before_prepared_audio(uuid,text,text,text,bigint,text,text,jsonb)',
    'execute'
);
-- Expect f.
```

Then, as an allowlisted account with one backed-up book, confirm the
precondition is live. This must be rejected with `book_backup_unavailable` for
a book id that has no available file:

```sql
select public.reserve_book_upload(
    '<book-id-with-no-backup>', 'tts_prepared_audio',
    'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
    'chapter.zip', 4096, 'sha-256-v1', repeat('4', 64),
    '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"1","attestation_version":"1"}'::jsonb
);
```

## 5. Rollback

The migration does not drop or rename any column, so no data is lost and the
rollback is a function and trigger restore. Run it as one transaction.

```sql
begin;

-- 1. Detach the cascades.
drop trigger if exists cascade_prepared_audio_on_block on public.cloud_content_blocklist;
drop trigger if exists cascade_prepared_audio_on_file_deleting on public.cloud_book_files;
drop function if exists public.cascade_prepared_audio_on_block();
drop function if exists public.cascade_prepared_audio_on_file_deleting();
drop function if exists public.cascade_prepared_audio_deletion(uuid, text, text);

-- 2. Put reserve_book_upload back. Dropping the wrapper and renaming the
--    saved definition back is exact: the wrapper added no state.
drop function if exists public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
);
alter function public.reserve_book_upload_before_prepared_audio(
    uuid, text, text, text, bigint, text, text, jsonb
) rename to reserve_book_upload;
-- The body self-qualifies its parameters with the function name, so rewrite
-- that qualification back, the same way the migration rewrote it forward.
do $$
declare
    definition text;
begin
    definition := pg_get_functiondef(
        'public.reserve_book_upload(uuid,text,text,text,bigint,text,text,jsonb)'::regprocedure
    );
    definition := replace(
        definition,
        'reserve_book_upload_before_prepared_audio.',
        'reserve_book_upload.'
    );
    execute definition;
end;
$$;
revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, service_role;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;

-- 3. Drop the storage breakdown by restoring the previous body verbatim
--    (20260922000001_parrot_cloud_rpcs.sql:1096).
create or replace function public.get_storage_usage()
returns jsonb
language plpgsql
security definer
set search_path = public
as $fn$
declare
    actor uuid := auth.uid();
    usage_row public.cloud_user_storage%rowtype;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    insert into public.cloud_user_storage(cloud_user_id)
    values (actor)
    on conflict (cloud_user_id) do nothing;
    select * into usage_row
    from public.cloud_user_storage
    where cloud_user_id = actor;
    return jsonb_build_object(
        'used_bytes', usage_row.used_bytes,
        'reserved_bytes', usage_row.reserved_bytes,
        'quota_bytes', usage_row.quota_bytes,
        'available_bytes', greatest(0, usage_row.quota_bytes - usage_row.used_bytes - usage_row.reserved_bytes),
        'updated_at', usage_row.updated_at
    );
end;
$fn$;
revoke execute on function public.get_storage_usage() from public, anon;
grant execute on function public.get_storage_usage() to authenticated;

-- 4. Drop the constraints and the helper.
alter table public.cloud_book_uploads
    drop constraint if exists cloud_book_uploads_prepared_audio_size_check,
    drop constraint if exists cloud_book_uploads_prepared_audio_path_check;
alter table public.cloud_book_files
    drop constraint if exists cloud_book_files_prepared_audio_size_check,
    drop constraint if exists cloud_book_files_prepared_audio_path_check;
drop function if exists public.book_has_available_backup(uuid);

commit;
```

**What the rollback leaves behind.** Any `tts_prepared_audio` row already
written stays, and becomes an ordinary unconstrained file row. That is safe for
older and newer apps alike, because its relative path is still non-empty and so
still invisible to the library's media resources — but its 64 MiB limit and its
cascades are gone, so audio could then outlive its book. If you roll back after
anything has been uploaded, delete those rows through the normal
`delete_book_file` path first:

```sql
select public.delete_book_file(id) from public.cloud_book_files
where media_type = 'tts_prepared_audio' and cloud_user_id = '<account-id>';
```

## Notes

- **The bucket is not touched.** Its allowed MIME types already include
  `application/octet-stream`, which is what the client produces for this media
  type, and its 2 GiB file size limit stays — the 64 MiB cap is enforced in
  `reserve_book_upload`, above it.
- **Uploads are no longer allowlist-only.** Prepared audio goes through
  `cloud_feature_enabled('uploads')` like any other file, and since
  `20261011000000` that answers true for every signed-in account. This migration
  alone grants nothing; the two together open backup to everyone.
- **The never-empty relative path is load-bearing.** It is the only thing
  keeping prepared audio invisible to apps released before this feature. Do not
  relax `cloud_book_files_prepared_audio_path_check`.
