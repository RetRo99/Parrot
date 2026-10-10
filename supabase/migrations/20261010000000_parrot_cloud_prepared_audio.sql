-- Prepared chapter audio in Parrot Cloud.
--
-- Rollout: see supabase/PREPARED_AUDIO_ROLLOUT.md.
--
-- A prepared chapter is one more cloud_book_files row of its book, with the
-- media type 'tts_prepared_audio', carried through the existing reserve,
-- resumable upload, finalize, download and delete path. The table is already
-- unique on (cloud_book_id, media_type, relative_path) and no RPC in that path
-- branches on a media type, so this migration adds rules rather than machinery:
--
--   1. A prepared chapter's relative_path is never empty and is two hashes.
--      This is the whole protection for apps released before this feature: they
--      build the library's media resources from cloud_book_files rows filtered
--      on an empty relative path (lib/server-local/.../LocalBooksRepository.kt),
--      so a non-empty path keeps prepared audio invisible to them. It is
--      enforced here, on the server, and must never be relaxed to a convention
--      the client is trusted to keep.
--   2. 64 MiB per chapter file, instead of the ebook's 2 GiB.
--   3. Prepared audio only for a book the caller owns that has an available
--      backup whose content is not blocked.
--   4. Audio never outlives its book: the last backup going, a takedown and a
--      block-list hit each take the book's prepared audio with them. Account
--      deletion and orphan GC already did, through the owner foreign key and
--      the media-type-agnostic GC.
--   5. get_storage_usage gains a books/prepared-audio breakdown, with every
--      existing key kept.
--
-- The bucket needs no change: its allowed MIME types already include
-- application/octet-stream, which is what the client's
-- TusUploadMetadata.contentType() produces for this media type.

-- ---------------------------------------------------------------------------
-- 1. Table constraints
-- ---------------------------------------------------------------------------
-- Added NOT VALID and validated only when existing rows comply, so applying
-- never fails on old data; NOT VALID still checks every new or updated row.
-- This mirrors 20261003000000.
do $$
declare
    c record;
    violated boolean;
begin
    for c in
        select *
        from (values
            ('cloud_book_files', 'cloud_book_files_prepared_audio_path_check',
             $c$media_type <> 'tts_prepared_audio'
                or relative_path ~ '^tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\.zip$'$c$),
            ('cloud_book_files', 'cloud_book_files_prepared_audio_size_check',
             $c$media_type <> 'tts_prepared_audio' or size_bytes <= 67108864$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_prepared_audio_path_check',
             $c$media_type <> 'tts_prepared_audio'
                or relative_path ~ '^tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\.zip$'$c$),
            ('cloud_book_uploads', 'cloud_book_uploads_prepared_audio_size_check',
             $c$media_type <> 'tts_prepared_audio' or size_bytes <= 67108864$c$)
        ) as t(table_name, constraint_name, expression)
    loop
        execute format(
            'alter table public.%I add constraint %I check (%s) not valid',
            c.table_name, c.constraint_name, c.expression
        );
        execute format(
            'select exists (select 1 from public.%I where not (%s))',
            c.table_name, c.expression
        ) into violated;
        if violated then
            raise warning '% left NOT VALID: existing rows violate it', c.constraint_name;
        else
            execute format(
                'alter table public.%I validate constraint %I',
                c.table_name, c.constraint_name
            );
        end if;
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- 2. Does a book still have a usable backup?
-- ---------------------------------------------------------------------------
-- "Backed up" means an available file that is not itself prepared audio and
-- whose content is not on the block-list. Prepared audio may exist only while
-- that holds, which is both the precondition for uploading it and the test the
-- cascade below uses to decide when it has to go.
create or replace function public.book_has_available_backup(p_cloud_book_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.cloud_book_files f
        where f.cloud_book_id = p_cloud_book_id
          and f.media_type <> 'tts_prepared_audio'
          and f.status = 'available'
          and not exists (
              select 1 from public.cloud_content_blocklist b
              where b.content_hash_algorithm = f.content_hash_algorithm
                and b.content_hash = f.content_hash
          )
    );
$$;

revoke execute on function public.book_has_available_backup(uuid)
    from public, anon, authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 3. The cascade
-- ---------------------------------------------------------------------------
-- Puts every prepared chapter of a book through exactly the lifecycle
-- delete_book_file gives an ordinary file, so bytes are released by the same
-- complete_book_file_deletion handshake and devices learn through the same
-- book_file sync feed:
--
--   * an available row is marked 'deleting'; the Storage object is removed
--     through the Storage API and complete_book_file_deletion credits the bytes;
--   * a row that never became available has its live reservation released, and
--     is then either orphan-accounted and marked 'deleting' (its object did
--     materialise) or deleted outright (it did not) -- its bytes were only ever
--     reserved, never charged, so they must not be credited twice;
--   * a row already 'deleting' is left alone, which makes this idempotent and
--     safe to reach twice (a takedown both blocks a hash and marks a file).
create or replace function public.cascade_prepared_audio_deletion(
    p_cloud_book_id uuid,
    p_reason text,
    p_actor text
)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    file_row public.cloud_book_files%rowtype;
    upload_row public.cloud_book_uploads%rowtype;
    removed_revision bigint;
    object_exists boolean;
    affected integer := 0;
begin
    for file_row in
        select * from public.cloud_book_files f
        where f.cloud_book_id = p_cloud_book_id
          and f.media_type = 'tts_prepared_audio'
          and f.status <> 'deleting'
        order by f.id
        for update
    loop
        -- Release a reservation still in flight, so its bytes are not held and
        -- a late finalize cannot resurrect the file after its book has gone.
        for upload_row in
            select * from public.cloud_book_uploads u
            where u.cloud_book_file_id = file_row.id
              and u.status = 'reserved'
            for update
        loop
            update public.cloud_user_storage
            set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
                updated_at = timezone('utc', now())
            where cloud_user_id = file_row.cloud_user_id;
            update public.cloud_book_uploads
            set status = 'cancelled', updated_at = timezone('utc', now())
            where public.cloud_book_uploads.upload_id = upload_row.upload_id;
            insert into public.cloud_file_audit_events(
                cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
                content_hash_algorithm, content_hash, action, reason, actor
            ) values (
                file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
                upload_row.upload_id, upload_row.content_hash_algorithm,
                upload_row.content_hash, 'cancel', p_reason, p_actor
            );
        end loop;

        select exists (
            select 1 from storage.objects o
            where o.bucket_id = 'book-files' and o.name = file_row.storage_path
        ) into object_exists;

        if file_row.status <> 'available' and not object_exists then
            -- Never charged, so nothing to credit beyond counted orphan bytes.
            if file_row.orphan_object_counted then
                update public.cloud_user_storage
                set used_bytes = greatest(0, used_bytes - file_row.orphan_object_bytes),
                    updated_at = timezone('utc', now())
                where cloud_user_id = file_row.cloud_user_id;
            end if;
            removed_revision := file_row.revision + 1;
            delete from public.cloud_book_files f where f.id = file_row.id;
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            ) values (
                file_row.cloud_user_id, 'book_file', file_row.id::text, 'delete',
                jsonb_build_object(
                    'cloud_book_id', file_row.cloud_book_id,
                    'cloud_book_file_id', file_row.id,
                    'media_type', file_row.media_type,
                    'relative_path', file_row.relative_path,
                    'file_name', file_row.file_name,
                    'status', 'removed',
                    'size_bytes', file_row.size_bytes,
                    'content_hash', file_row.content_hash,
                    'content_hash_algorithm', file_row.content_hash_algorithm,
                    'remote_revision', removed_revision
                ), removed_revision
            );
            insert into public.cloud_file_audit_events(
                cloud_user_id, cloud_book_id, cloud_book_file_id,
                content_hash_algorithm, content_hash, action, reason, actor
            ) values (
                file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
                file_row.content_hash_algorithm, file_row.content_hash,
                'delete', p_reason, p_actor
            );
            affected := affected + 1;
            continue;
        end if;

        if file_row.status <> 'available' then
            -- A failed upload that still materialised an object: count it and
            -- use the normal deletion handshake rather than orphaning it.
            perform public.account_orphan_book_file_object(file_row.id);
        end if;

        update public.cloud_book_files
        set status = 'deleting', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = file_row.id
        returning * into file_row;

        insert into public.sync_changes(
            cloud_user_id, entity_type, entity_id, operation, payload, revision
        ) values (
            file_row.cloud_user_id, 'book_file', file_row.id::text, 'upsert',
            jsonb_build_object(
                'cloud_book_id', file_row.cloud_book_id,
                'cloud_book_file_id', file_row.id,
                'media_type', file_row.media_type,
                'relative_path', file_row.relative_path,
                'file_name', file_row.file_name,
                'status', 'deleting',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
            file_row.content_hash_algorithm, file_row.content_hash,
            'delete_requested', p_reason, p_actor
        );
        affected := affected + 1;
    end loop;

    return affected;
end;
$$;

revoke execute on function public.cascade_prepared_audio_deletion(uuid, text, text)
    from public, anon, authenticated, service_role;

-- A book file of any other media type entering 'deleting' -- through
-- delete_book_file, admin_takedown_book_file or anything added later -- takes
-- the book's prepared audio with it, but only once no usable backup is left.
-- Deleting one of two representations keeps the audio, which is why this asks
-- book_has_available_backup rather than assuming the deleted row was the last.
create or replace function public.cascade_prepared_audio_on_file_deleting()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.book_has_available_backup(new.cloud_book_id) then
        perform public.cascade_prepared_audio_deletion(
            new.cloud_book_id,
            'book_backup_removed',
            coalesce(auth.uid()::text, auth.role(), 'server')
        );
    end if;
    return null;
end;
$$;

revoke execute on function public.cascade_prepared_audio_on_file_deleting()
    from public, anon, authenticated, service_role;

-- The media type condition is what stops this recursing: the cascade only ever
-- moves prepared-audio rows, which this trigger does not fire for.
create trigger cascade_prepared_audio_on_file_deleting
after update on public.cloud_book_files
for each row
when (
    new.status = 'deleting'
    and old.status is distinct from new.status
    and new.media_type <> 'tts_prepared_audio'
)
execute function public.cascade_prepared_audio_on_file_deleting();

-- A block-list hit needs its own path. admin_block_content_hash deletes
-- nothing, and every block check matches on the blocked hash, which a book's
-- prepared audio does not share -- so without this, blocking a book would leave
-- its audio downloadable. The cascade is unconditional here: the book's file
-- rows stay 'available', so there is no "last backup gone" to detect.
create or replace function public.cascade_prepared_audio_on_block()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    book_id uuid;
begin
    for book_id in
        select distinct f.cloud_book_id
        from public.cloud_book_files f
        where f.content_hash_algorithm = new.content_hash_algorithm
          and f.content_hash = new.content_hash
          and f.media_type <> 'tts_prepared_audio'
    loop
        perform public.cascade_prepared_audio_deletion(
            book_id, 'book_content_blocked', coalesce(new.created_by, 'server')
        );
    end loop;
    return null;
end;
$$;

revoke execute on function public.cascade_prepared_audio_on_block()
    from public, anon, authenticated, service_role;

create trigger cascade_prepared_audio_on_block
after insert on public.cloud_content_blocklist
for each row execute function public.cascade_prepared_audio_on_block();

-- ---------------------------------------------------------------------------
-- 4. reserve_book_upload: the prepared-audio rules
-- ---------------------------------------------------------------------------
-- The house pattern for extending this RPC: rename the current definition and
-- wrap it, so none of the existing behaviour is restated here. The current body
-- qualifies its own parameters with the function name, so the renamed copy has
-- to be re-executed with that qualification rewritten -- exactly as
-- 20260924000004 did for the previous rename.
alter function public.reserve_book_upload(uuid, text, text, text, bigint, text, text, jsonb)
    rename to reserve_book_upload_before_prepared_audio;

do $$
declare
    definition text;
begin
    definition := pg_get_functiondef(
        'public.reserve_book_upload_before_prepared_audio(uuid,text,text,text,bigint,text,text,jsonb)'::regprocedure
    );
    if position('reserve_book_upload.' in definition) = 0 then
        raise exception 'reserve_book_upload self-qualified parameters not found';
    end if;
    definition := replace(
        definition,
        'reserve_book_upload.',
        'reserve_book_upload_before_prepared_audio.'
    );
    execute definition;
end;
$$;

revoke execute on function public.reserve_book_upload_before_prepared_audio(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, authenticated, service_role;

create or replace function public.reserve_book_upload(
    cloud_book_id uuid,
    media_type text,
    relative_path text,
    file_name text,
    size_bytes bigint,
    content_hash_algorithm text,
    content_hash text,
    rights_attestation jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    book_owner uuid;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    -- The same per-account lock the inner RPCs take (re-entrant).
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    -- Checked first so the answer for an account that may not upload does not
    -- depend on which media type it asked for.
    if not public.cloud_feature_enabled('uploads') then
        return jsonb_build_object('status', 'rejected', 'reason', 'uploads_not_enabled');
    end if;

    if media_type = 'tts_prepared_audio' then
        -- Never the empty relative path an older app would read as the book's
        -- own file, and two hashes only, so no title or href can leak into it.
        if relative_path is null
            or not coalesce(
                relative_path ~ '^tts-prepared/[0-9a-f]{64}/[0-9a-f]{64}\.zip$',
                false
            )
        then
            return jsonb_build_object('status', 'rejected', 'reason', 'invalid_upload_metadata');
        end if;

        if coalesce(size_bytes, 0) > 67108864 then
            return jsonb_build_object('status', 'rejected', 'reason', 'file_too_large');
        end if;

        -- Ownership is re-verified by the inner RPC; this only needs to know
        -- the book is the caller's before reporting on its backup.
        select f.cloud_user_id into book_owner
        from public.cloud_books f
        where f.id = reserve_book_upload.cloud_book_id and f.deleted_at is null;
        if book_owner is distinct from actor then
            return jsonb_build_object('status', 'rejected', 'reason', 'cloud_book_not_owned');
        end if;

        if exists (
            select 1
            from public.cloud_book_files f
            join public.cloud_content_blocklist b
              on b.content_hash_algorithm = f.content_hash_algorithm
             and b.content_hash = f.content_hash
            where f.cloud_book_id = reserve_book_upload.cloud_book_id
              and f.media_type <> 'tts_prepared_audio'
        ) then
            return jsonb_build_object('status', 'rejected', 'reason', 'content_blocked');
        end if;

        if not public.book_has_available_backup(reserve_book_upload.cloud_book_id) then
            return jsonb_build_object('status', 'rejected', 'reason', 'book_backup_unavailable');
        end if;
    end if;

    return public.reserve_book_upload_before_prepared_audio(
        cloud_book_id, media_type, relative_path, file_name, size_bytes,
        content_hash_algorithm, content_hash, rights_attestation
    );
end;
$$;

revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, service_role;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;

-- ---------------------------------------------------------------------------
-- 5. get_storage_usage: the breakdown the app shows
-- ---------------------------------------------------------------------------
-- Every existing key keeps its name and meaning. books_bytes is derived as the
-- remainder rather than summed, so the two parts always add up to the
-- used_bytes the account is actually charged even if a counted orphan or a
-- rounding of the accounting ever drifts from the row totals.
create or replace function public.get_storage_usage()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    usage_row public.cloud_user_storage%rowtype;
    prepared_bytes bigint;
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

    select coalesce(sum(
        case when f.orphan_object_counted then f.orphan_object_bytes else f.size_bytes end
    ), 0)
    into prepared_bytes
    from public.cloud_book_files f
    where f.cloud_user_id = actor
      and f.media_type = 'tts_prepared_audio'
      and (f.status in ('available', 'deleting') or f.orphan_object_counted);

    prepared_bytes := least(greatest(prepared_bytes, 0), usage_row.used_bytes);

    return jsonb_build_object(
        'used_bytes', usage_row.used_bytes,
        'reserved_bytes', usage_row.reserved_bytes,
        'quota_bytes', usage_row.quota_bytes,
        'available_bytes', greatest(0, usage_row.quota_bytes - usage_row.used_bytes - usage_row.reserved_bytes),
        'updated_at', usage_row.updated_at,
        'prepared_audio_bytes', prepared_bytes,
        'books_bytes', greatest(0, usage_row.used_bytes - prepared_bytes)
    );
end;
$$;

revoke execute on function public.get_storage_usage() from public, anon;
grant execute on function public.get_storage_usage() to authenticated;
