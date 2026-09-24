-- Count uploaded objects as used storage as soon as their reservation becomes
-- terminal. Physical object deletion is performed through the Storage API by
-- the service-role GC worker; deleting storage.objects from SQL is insufficient.
alter table public.cloud_book_files
    add column orphan_object_counted boolean not null default false,
    add column orphan_object_bytes bigint not null default 0
        check (orphan_object_bytes >= 0);

create table public.cloud_book_file_gc_claims (
    cloud_book_file_id uuid primary key
        references public.cloud_book_files(id) on delete cascade,
    claim_id uuid not null,
    claimed_at timestamptz not null default timezone('utc', now())
);

alter table public.cloud_book_file_gc_claims enable row level security;
revoke all on public.cloud_book_file_gc_claims from public, anon, authenticated, service_role;

create or replace function public.account_orphan_book_file_object(file_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    file_row public.cloud_book_files%rowtype;
    object_bytes bigint;
begin
    select * into file_row
    from public.cloud_book_files f
    where f.id = account_orphan_book_file_object.file_id
    for update;
    if not found then
        return;
    end if;
    if file_row.status in ('available', 'deleting') or file_row.orphan_object_counted then
        return;
    end if;

    select case
        when o.metadata->>'size' ~ '^[0-9]+$' then (o.metadata->>'size')::bigint
        else 0
    end into object_bytes
    from storage.objects o
    where o.bucket_id = 'book-files' and o.name = file_row.storage_path;
    if not found then
        return;
    end if;

    update public.cloud_user_storage
    set used_bytes = used_bytes + object_bytes,
        updated_at = timezone('utc', now())
    where cloud_user_id = file_row.cloud_user_id;
    update public.cloud_book_files
    set orphan_object_counted = true,
        orphan_object_bytes = object_bytes,
        updated_at = timezone('utc', now())
    where id = file_row.id;
end;
$$;

create or replace function public.account_terminal_book_upload_object()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if old.status = 'reserved' and new.status in ('expired', 'cancelled', 'failed') then
        perform public.account_orphan_book_file_object(new.cloud_book_file_id);
    end if;
    return new;
end;
$$;

create trigger account_terminal_book_upload_object
after update of status on public.cloud_book_uploads
for each row execute function public.account_terminal_book_upload_object();

create or replace function public.guard_orphan_gc_book_file_update()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.status = 'upload_pending' and old.status is distinct from new.status then
        if auth.role() is distinct from 'service_role' and exists (
            select 1 from public.cloud_book_file_gc_claims c
            where c.cloud_book_file_id = old.id
        ) then
            raise exception using errcode = 'P0001', message = 'book_file_gc_in_progress';
        end if;

        -- A retry reuses the same path. Its new reservation covers the object
        -- already there until it is replaced or the retry itself becomes terminal.
        if old.orphan_object_counted then
            update public.cloud_user_storage
            set used_bytes = greatest(0, used_bytes - old.orphan_object_bytes),
                updated_at = timezone('utc', now())
            where cloud_user_id = old.cloud_user_id;
            new.orphan_object_counted := false;
            new.orphan_object_bytes := 0;
        end if;
    end if;
    return new;
end;
$$;

create trigger guard_orphan_gc_book_file_update
before update on public.cloud_book_files
for each row execute function public.guard_orphan_gc_book_file_update();

revoke execute on function public.account_orphan_book_file_object(uuid)
    from public, anon, authenticated, service_role;
revoke execute on function public.account_terminal_book_upload_object()
    from public, anon, authenticated, service_role;
revoke execute on function public.guard_orphan_gc_book_file_update()
    from public, anon, authenticated, service_role;

-- Expiry is handled on access as well as by the scheduled GC. Moving the
-- reservation to a terminal state invokes account_terminal_book_upload_object,
-- so completed bytes count against quota before the Storage API cleanup runs.
alter function public.reserve_book_upload(uuid, text, text, text, bigint, text, text, jsonb)
    rename to reserve_book_upload_before_orphan_gc;
revoke execute on function public.reserve_book_upload_before_orphan_gc(
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
    upload_row public.cloud_book_uploads%rowtype;
    file_row public.cloud_book_files%rowtype;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    -- Preserve the original lazy-expiry behavior while ensuring quota is
    -- re-read by the reservation RPC after expired objects have been counted.
    for upload_row in
        select * from public.cloud_book_uploads u
        where u.cloud_user_id = actor and u.status = 'reserved' and u.expires_at <= now()
        order by u.expires_at
        for update
    loop
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads
        set status = 'expired', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id
          and status in ('upload_pending', 'uploading')
        returning * into file_row;
        if found then
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            ) values (
                actor, 'book_file', file_row.id::text, 'upsert',
                jsonb_build_object(
                    'cloud_book_id', file_row.cloud_book_id,
                    'cloud_book_file_id', file_row.id,
                    'media_type', file_row.media_type,
                    'relative_path', file_row.relative_path,
                    'file_name', file_row.file_name,
                    'status', file_row.status,
                    'size_bytes', file_row.size_bytes,
                    'content_hash', file_row.content_hash,
                    'content_hash_algorithm', file_row.content_hash_algorithm,
                    'remote_revision', file_row.revision
                ), file_row.revision
            );
        end if;
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'expire', 'reservation_expired', actor::text
        );
    end loop;

    if exists (
        select 1
        from public.cloud_book_files f
        join public.cloud_book_file_gc_claims c on c.cloud_book_file_id = f.id
        where f.cloud_user_id = actor
          and f.cloud_book_id = reserve_book_upload.cloud_book_id
          and f.media_type = reserve_book_upload.media_type
          and f.relative_path = coalesce(reserve_book_upload.relative_path, '')
    ) then
        return jsonb_build_object('status', 'rejected', 'reason', 'orphan_cleanup_pending');
    end if;

    return public.reserve_book_upload_before_orphan_gc(
        cloud_book_id, media_type, relative_path, file_name, size_bytes,
        content_hash_algorithm, content_hash, rights_attestation
    );
end;
$$;

revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;

create or replace function public.cancel_book_upload(upload_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    upload_row public.cloud_book_uploads%rowtype;
    file_row public.cloud_book_files%rowtype;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    select * into upload_row from public.cloud_book_uploads u
    where u.upload_id = cancel_book_upload.upload_id and u.cloud_user_id = actor
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_found');
    end if;
    if upload_row.status = 'cancelled' then
        return jsonb_build_object('status', 'cancelled', 'upload_id', upload_row.upload_id);
    end if;
    if upload_row.status = 'finalized' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_already_finalized');
    end if;

    if upload_row.status = 'reserved' then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
    end if;
    update public.cloud_book_uploads
    set status = 'cancelled', updated_at = timezone('utc', now())
    where public.cloud_book_uploads.upload_id = upload_row.upload_id;

    select * into file_row from public.cloud_book_files f
    where f.id = upload_row.cloud_book_file_id for update;
    if found and file_row.status <> 'available' then
        perform public.account_orphan_book_file_object(file_row.id);
        -- Keep the non-available file row even when the object is not visible
        -- yet: an in-flight TUS completion may commit concurrently with cancel.
        -- The GC can safely remove the object/row after the session is terminal.
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = file_row.id;
    end if;

    insert into public.sync_changes(
        cloud_user_id, entity_type, entity_id, operation, payload, revision
    ) values (
        actor, 'book_file', upload_row.cloud_book_file_id::text, 'delete',
        jsonb_build_object(
            'cloud_book_id', upload_row.cloud_book_id,
            'cloud_book_file_id', upload_row.cloud_book_file_id,
            'media_type', upload_row.media_type,
            'relative_path', upload_row.relative_path,
            'status', 'none'
        ), 0
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
        upload_row.upload_id, upload_row.content_hash_algorithm,
        upload_row.content_hash, 'cancel', actor::text
    );
    return jsonb_build_object('status', 'cancelled', 'upload_id', upload_row.upload_id);
end;
$$;

revoke execute on function public.cancel_book_upload(uuid) from public, anon;
grant execute on function public.cancel_book_upload(uuid) to authenticated;

create or replace function public.gc_orphan_book_files(batch_size integer default 100)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor_id uuid;
    upload_row public.cloud_book_uploads%rowtype;
    file_row public.cloud_book_files%rowtype;
    claim_value uuid := gen_random_uuid();
    files_value jsonb := '[]'::jsonb;
    object_present boolean;
    candidate_id uuid;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 500 then
        raise exception 'batch_size must be between 1 and 500';
    end if;

    -- Match upload RPC lock ordering, so expiry and claim cannot race a reserve
    -- or finalize for the same account.
    for actor_id in
        select distinct candidate.cloud_user_id
        from (
            select u.cloud_user_id
            from public.cloud_book_uploads u
            where u.status = 'reserved' and u.expires_at <= now()
            union
            select f.cloud_user_id
            from public.cloud_book_files f
            where f.status not in ('available', 'deleting')
              and exists (
                  select 1 from public.cloud_book_uploads u
                  where u.cloud_book_file_id = f.id
                    and u.status in ('expired', 'cancelled', 'failed')
              )
        ) candidate
        order by candidate.cloud_user_id
    loop
        perform pg_advisory_xact_lock(hashtextextended(actor_id::text, 0));
    end loop;

    for upload_row in
        select * from public.cloud_book_uploads u
        where u.status = 'reserved' and u.expires_at <= now()
        order by u.expires_at
        for update
    loop
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = upload_row.cloud_user_id;
        update public.cloud_book_uploads
        set status = 'expired', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id
          and status in ('upload_pending', 'uploading')
        returning * into file_row;
        if found then
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            ) values (
                upload_row.cloud_user_id, 'book_file', file_row.id::text, 'upsert',
                jsonb_build_object(
                    'cloud_book_id', file_row.cloud_book_id,
                    'cloud_book_file_id', file_row.id,
                    'media_type', file_row.media_type,
                    'relative_path', file_row.relative_path,
                    'file_name', file_row.file_name,
                    'status', file_row.status,
                    'size_bytes', file_row.size_bytes,
                    'content_hash', file_row.content_hash,
                    'content_hash_algorithm', file_row.content_hash_algorithm,
                    'remote_revision', file_row.revision
                ), file_row.revision
            );
        end if;
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            upload_row.cloud_user_id, upload_row.cloud_book_id,
            upload_row.cloud_book_file_id, upload_row.upload_id,
            upload_row.content_hash_algorithm, upload_row.content_hash,
            'expire', 'reservation_expired', 'service_role'
        );
    end loop;

    -- A failed worker can leave a claim behind. Reclaim it after the lease so
    -- the next scheduled invocation can retry the idempotent Storage API delete.
    delete from public.cloud_book_file_gc_claims
    where claimed_at < timezone('utc', now()) - interval '15 minutes';

    for file_row in
        select f.*
        from public.cloud_book_files f
        where f.status not in ('available', 'deleting')
          and exists (
              select 1 from public.cloud_book_uploads u
              where u.cloud_book_file_id = f.id
                and u.status in ('expired', 'cancelled', 'failed')
          )
          and not exists (
              select 1 from public.cloud_book_uploads u
              where u.cloud_book_file_id = f.id and u.status = 'reserved'
          )
          and not exists (
              select 1 from public.cloud_book_file_gc_claims c
              where c.cloud_book_file_id = f.id
          )
        order by f.updated_at, f.id
        limit batch_size
        for update of f skip locked
    loop
        candidate_id := file_row.id;
        insert into public.cloud_book_file_gc_claims(cloud_book_file_id, claim_id)
        values (candidate_id, claim_value);
        perform public.account_orphan_book_file_object(candidate_id);
        select exists (
            select 1 from storage.objects o
            where o.bucket_id = 'book-files' and o.name = file_row.storage_path
        ) into object_present;
        files_value := files_value || jsonb_build_array(jsonb_build_object(
            'cloud_book_file_id', file_row.id,
            'cloud_user_id', file_row.cloud_user_id,
            'storage_path', file_row.storage_path,
            'object_exists', object_present
        ));
    end loop;

    return jsonb_build_object(
        'status', 'claimed', 'claim_id', claim_value, 'files', files_value
    );
end;
$$;

create or replace function public.complete_orphan_book_file_gc(
    cloud_book_file_id uuid,
    claim_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    claim_row public.cloud_book_file_gc_claims%rowtype;
    file_row public.cloud_book_files%rowtype;
    upload_row public.cloud_book_uploads%rowtype;
    removed_revision bigint;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    select * into claim_row
    from public.cloud_book_file_gc_claims c
    where c.cloud_book_file_id = complete_orphan_book_file_gc.cloud_book_file_id
      and c.claim_id = complete_orphan_book_file_gc.claim_id
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'gc_claim_not_found');
    end if;

    select * into file_row from public.cloud_book_files f
    where f.id = claim_row.cloud_book_file_id for update;
    if not found then
        delete from public.cloud_book_file_gc_claims c
        where c.cloud_book_file_id = claim_row.cloud_book_file_id;
        return jsonb_build_object('status', 'removed', 'cloud_book_file_id', cloud_book_file_id);
    end if;
    if file_row.status in ('available', 'deleting') then
        delete from public.cloud_book_file_gc_claims c
        where c.cloud_book_file_id = file_row.id;
        return jsonb_build_object('status', 'skipped', 'reason', 'file_not_orphaned');
    end if;
    if exists (
        select 1 from public.cloud_book_uploads u
        where u.cloud_book_file_id = file_row.id and u.status = 'reserved'
    ) then
        return jsonb_build_object('status', 'pending', 'reason', 'upload_session_active');
    end if;
    if exists (
        select 1 from storage.objects o
        where o.bucket_id = 'book-files' and o.name = file_row.storage_path
    ) then
        return jsonb_build_object('status', 'pending', 'reason', 'storage_object_still_exists');
    end if;

    select * into upload_row from public.cloud_book_uploads u
    where u.cloud_book_file_id = file_row.id
      and u.status in ('expired', 'cancelled', 'failed')
    order by u.updated_at desc limit 1;
    removed_revision := file_row.revision + 1;
    if file_row.orphan_object_counted then
        update public.cloud_user_storage
        set used_bytes = greatest(0, used_bytes - file_row.orphan_object_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = file_row.cloud_user_id;
    end if;
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
            'status', 'none',
            'size_bytes', file_row.size_bytes,
            'content_hash', file_row.content_hash,
            'content_hash_algorithm', file_row.content_hash_algorithm,
            'remote_revision', removed_revision
        ), removed_revision
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, reason, actor
    ) values (
        file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
        upload_row.upload_id, file_row.content_hash_algorithm,
        file_row.content_hash, 'orphan_gc', upload_row.status, 'service_role'
    );
    return jsonb_build_object('status', 'removed', 'cloud_book_file_id', file_row.id);
end;
$$;

revoke execute on function public.gc_orphan_book_files(integer)
    from public, anon, authenticated;
grant execute on function public.gc_orphan_book_files(integer) to service_role;
revoke execute on function public.complete_orphan_book_file_gc(uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.complete_orphan_book_file_gc(uuid, uuid) to service_role;
