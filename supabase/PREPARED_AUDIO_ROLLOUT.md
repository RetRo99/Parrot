# Prepared chapter audio rollout (20261010000000)

`migrations/20261010000000_parrot_cloud_prepared_audio.sql` makes a prepared
chapter one more `cloud_book_files` row of its book, with the media type
`tts_prepared_audio`. It adds four table constraints, a 64 MiB per-chapter
reserve limit, a "the book must have a usable backup" precondition, three
cascades so audio never outlives its book, and a books/prepared-audio breakdown
on `get_storage_usage`. Run every step yourself; nothing here is automated.

> **This migration has never been run.** The agent run that wrote it had no
> container runtime available, so the local Supabase stack could not be started
> and `scripts/supabase/test.sh` could not execute. Step 1 below is therefore
> not optional: run it locally and read the pgTAP output before you apply this
> anywhere else.

## Status on the linked development project, as of 2026-10-10

Still **not applied**, and the run of 2026-10-10 could not apply it. What that
run established, so the next one need not rediscover it:

- **Linking needs no password.** `supabase link --project-ref <ref>` succeeds
  from a fresh worktree with only the stored CLI login: the CLI provisions a
  temporary login role per connection, so neither a login nor a database
  password is prompted for. `supabase migration list --linked` then works.
- **Two migrations are pending, not one.**
  `20261009000000_parrot_cloud_saved_words.sql` is unapplied as well as this
  one. It belongs to the saved-words/offline-dictionary feature, not to this
  work. `supabase db push` and `supabase migration up` both apply the entire
  pending chain in timestamp order, so neither can apply this migration without
  also deploying that one. **Decide the saved-words migration first**; there is
  no CLI route that skips it, and applying this one alone would leave the
  remote's migration history out of order.
- **The pgTAP suite cannot run on this machine.** `supabase test db --linked`
  fails with `DockerRunError`: `--linked` only redirects which database
  pg_prove is pointed at, and pg_prove itself still runs in a container. No
  docker, podman, colima, `psql` or `pg_prove` is installed. So step 1 below,
  and the step 4 verification queries, both need either a container runtime or
  a `psql` on the machine that runs them.

So the order for the owner is: resolve 20261009000000, install a container
runtime or `psql`, then step 1.

## 1. Run it locally first

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
- **Uploads stay allowlist-only.** Prepared audio goes through
  `cloud_feature_enabled('uploads')` like any other file; no account gains
  anything from this migration alone.
- **The never-empty relative path is load-bearing.** It is the only thing
  keeping prepared audio invisible to apps released before this feature. Do not
  relax `cloud_book_files_prepared_audio_path_check`.
