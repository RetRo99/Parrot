-- Orphan-GC hardening (review follow-ups):
--   1. Retry resets no longer release counted orphan bytes. The replacement
--      reservation only covers its declared size and uploaded size is not
--      enforced against it, so releasing the actual object bytes here re-opened
--      the quota bypass the accounting exists to close. Bytes now stay counted
--      until the object is actually deleted or absorbed by a successful
--      finalize (which verifies the real object size).
--   2. GC workers re-validate their claim (with keepalive) immediately before
--      the Storage API delete, so a stalled worker can never delete an object a
--      user has since re-reserved and finalized.
--   3. Storage writes re-check reservation liveness in the BEFORE trigger after
--      taking the per-account advisory lock, closing the snapshot TOCTOU that
--      could strand an untracked object after row removal.
--   4. file_exists rejections carry the existing file record so the client
--      Replace chain can always resolve the slot.
--   5. complete_orphan_book_file_gc takes the per-account advisory lock; the
--      dead cloud_book_uploads.tus_upload_id column is dropped.

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
        -- Counted orphan bytes deliberately stay in used_bytes while the retry
        -- is in flight: the new reservation only covers its declared size.
        -- Released by complete_orphan_book_file_gc / complete_book_file_deletion
        -- (object deleted) or netted off at finalize (object absorbed).
    end if;
    return new;
end;
$$;

create or replace function public.finalize_book_upload(
    upload_id uuid,
    size_bytes bigint,
    content_hash text
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
    object_size bigint;
    orphan_bytes bigint := 0;
    net_used_delta bigint;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));

    select * into upload_row
    from public.cloud_book_uploads u
    where u.upload_id = finalize_book_upload.upload_id
      and u.cloud_user_id = actor
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_found');
    end if;

    if upload_row.status = 'finalized' then
        select * into file_row from public.cloud_book_files f
        where f.id = upload_row.cloud_book_file_id;
        if not found then
            return jsonb_build_object(
                'status', 'not_available',
                'upload_id', upload_row.upload_id,
                'reason', 'file_removed'
            );
        end if;
        if file_row.status <> 'available' then
            return jsonb_build_object(
                'status', 'not_available',
                'upload_id', upload_row.upload_id,
                'cloud_book_file_id', file_row.id,
                'reason', 'file_' || file_row.status
            );
        end if;
        return jsonb_build_object(
            'status', 'available',
            'cloud_book_file_id', file_row.id,
            'storage_path', file_row.storage_path,
            'size_bytes', file_row.size_bytes,
            'content_hash', file_row.content_hash,
            'content_hash_algorithm', file_row.content_hash_algorithm,
            'revision', file_row.revision
        );
    end if;
    if upload_row.status <> 'reserved' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_reserved');
    end if;

    if upload_row.expires_at <= now() then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads
        set status = 'expired', updated_at = timezone('utc', now())
        where upload_id = upload_row.upload_id;
        update public.cloud_book_files
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id
        returning * into file_row;
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
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'expire', 'reservation_expired', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_expired');
    end if;

    if exists (
        select 1 from public.cloud_content_blocklist b
        where b.content_hash_algorithm = upload_row.content_hash_algorithm
          and b.content_hash = upload_row.content_hash
    ) then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
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
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'content_blocked', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'content_blocked');
    end if;

    if content_hash is distinct from upload_row.content_hash then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
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
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'content_hash_mismatch', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'content_hash_mismatch');
    end if;

    select (o.metadata->>'size')::bigint into object_size
    from storage.objects o
    where o.bucket_id = 'book-files' and o.name = upload_row.storage_path;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_incomplete');
    end if;

    if size_bytes is distinct from upload_row.size_bytes
        or object_size is distinct from upload_row.size_bytes
    then
        update public.cloud_user_storage
        set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
            updated_at = timezone('utc', now())
        where cloud_user_id = actor;
        update public.cloud_book_uploads set status = 'failed', updated_at = timezone('utc', now())
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
        update public.cloud_book_files set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where id = upload_row.cloud_book_file_id returning * into file_row;
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
                'status', 'upload_failed',
                'size_bytes', file_row.size_bytes,
                'content_hash', file_row.content_hash,
                'content_hash_algorithm', file_row.content_hash_algorithm,
                'remote_revision', file_row.revision
            ), file_row.revision
        );
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            actor, upload_row.cloud_book_id, upload_row.cloud_book_file_id,
            upload_row.upload_id, upload_row.content_hash_algorithm,
            upload_row.content_hash, 'finalize', 'size_mismatch', actor::text
        );
        return jsonb_build_object('status', 'rejected', 'reason', 'size_mismatch');
    end if;

    select * into file_row from public.cloud_book_files f
    where f.id = upload_row.cloud_book_file_id
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'file_removed');
    end if;
    if file_row.orphan_object_counted then
        orphan_bytes := file_row.orphan_object_bytes;
    end if;

    update public.cloud_book_files
    set status = 'available', revision = revision + 1,
        orphan_object_counted = false, orphan_object_bytes = 0,
        updated_at = timezone('utc', now())
    where id = upload_row.cloud_book_file_id
    returning * into file_row;
    update public.cloud_book_uploads
    set status = 'finalized', updated_at = timezone('utc', now())
    where public.cloud_book_uploads.upload_id = upload_row.upload_id;
    -- The verified object may already carry counted orphan bytes from an
    -- earlier attempt; only charge the difference so the same bytes are never
    -- counted twice.
    net_used_delta := upload_row.size_bytes - orphan_bytes;
    update public.cloud_user_storage
    set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
        used_bytes = greatest(0, used_bytes + net_used_delta),
        updated_at = timezone('utc', now())
    where cloud_user_id = actor;

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
            'status', 'available',
            'size_bytes', file_row.size_bytes,
            'content_hash', file_row.content_hash,
            'content_hash_algorithm', file_row.content_hash_algorithm,
            'remote_revision', file_row.revision
        ), file_row.revision
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id, upload_id,
        content_hash_algorithm, content_hash, action, actor
    ) values (
        actor, file_row.cloud_book_id, file_row.id, upload_row.upload_id,
        file_row.content_hash_algorithm, file_row.content_hash, 'finalize', actor::text
    );

    return jsonb_build_object(
        'status', 'available',
        'cloud_book_file_id', file_row.id,
        'storage_path', file_row.storage_path,
        'size_bytes', file_row.size_bytes,
        'content_hash', file_row.content_hash,
        'content_hash_algorithm', file_row.content_hash_algorithm,
        'revision', file_row.revision
    );
end;
$$;

revoke execute on function public.finalize_book_upload(uuid, bigint, text) from public, anon;
grant execute on function public.finalize_book_upload(uuid, bigint, text) to authenticated;

-- Re-validate a GC claim (with keepalive) immediately before the Storage API
-- delete. A claim that has been reaped or whose slot is no longer orphaned must
-- not have its object deleted.
create or replace function public.confirm_orphan_book_file_gc(
    cloud_book_file_id uuid,
    claim_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    owner_id uuid;
    claim_row public.cloud_book_file_gc_claims%rowtype;
    file_row public.cloud_book_files%rowtype;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;

    select f.cloud_user_id into owner_id
    from public.cloud_book_files f
    where f.id = confirm_orphan_book_file_gc.cloud_book_file_id;
    if owner_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(owner_id::text, 0));
    end if;

    select * into claim_row
    from public.cloud_book_file_gc_claims c
    where c.cloud_book_file_id = confirm_orphan_book_file_gc.cloud_book_file_id
      and c.claim_id = confirm_orphan_book_file_gc.claim_id
    for update;
    if not found then
        return jsonb_build_object('status', 'rejected', 'reason', 'gc_claim_not_found');
    end if;

    select * into file_row from public.cloud_book_files f
    where f.id = claim_row.cloud_book_file_id
    for update;
    if not found then
        -- The row is gone and storage paths embed the file id, so the path can
        -- never be re-reserved: deletion is safe.
        update public.cloud_book_file_gc_claims
        set claimed_at = timezone('utc', now())
        where cloud_book_file_id = claim_row.cloud_book_file_id;
        return jsonb_build_object('status', 'confirmed', 'cloud_book_file_id', cloud_book_file_id);
    end if;
    if file_row.status in ('available', 'deleting') then
        return jsonb_build_object('status', 'rejected', 'reason', 'file_not_orphaned');
    end if;
    if exists (
        select 1 from public.cloud_book_uploads u
        where u.cloud_book_file_id = file_row.id and u.status = 'reserved'
    ) then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_session_active');
    end if;

    -- Keepalive: extend the lease so it cannot be reaped between this
    -- confirmation and the immediate Storage API delete.
    update public.cloud_book_file_gc_claims
    set claimed_at = timezone('utc', now())
    where cloud_book_file_id = file_row.id;
    return jsonb_build_object('status', 'confirmed', 'cloud_book_file_id', file_row.id);
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
    owner_id uuid;
    claim_row public.cloud_book_file_gc_claims%rowtype;
    file_row public.cloud_book_files%rowtype;
    upload_row public.cloud_book_uploads%rowtype;
    removed_revision bigint;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;

    select f.cloud_user_id into owner_id
    from public.cloud_book_files f
    where f.id = complete_orphan_book_file_gc.cloud_book_file_id;
    if owner_id is not null then
        perform pg_advisory_xact_lock(hashtextextended(owner_id::text, 0));
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

revoke execute on function public.confirm_orphan_book_file_gc(uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.confirm_orphan_book_file_gc(uuid, uuid) to service_role;
revoke execute on function public.complete_orphan_book_file_gc(uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.complete_orphan_book_file_gc(uuid, uuid) to service_role;

-- Storage writes keep the original account-deletion guard semantics (RLS stays
-- the insert/update authority; its snapshot TOCTOU window is instead closed by
-- the untracked-object GC sweep below, which reaps any object that outlives its
-- file row). The only change here is a safe path parse: a non-UUID path segment
-- skips the guard instead of failing the write.
create or replace function public.guard_cloud_account_deletion_write()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    account_id uuid;
    path_user_id text;
begin
    if auth.role() = 'service_role' then
        return new;
    end if;

    if tg_table_schema = 'storage' and tg_table_name = 'objects' then
        if new.bucket_id <> 'book-files' or split_part(new.name, '/', 1) <> 'users' then
            return new;
        end if;
        path_user_id := split_part(new.name, '/', 2);
        if path_user_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
            return new;
        end if;
        account_id := path_user_id::uuid;
    else
        account_id := (to_jsonb(new)->>'cloud_user_id')::uuid;
    end if;

    if account_id is null then
        return new;
    end if;

    -- An in-flight write completes before the deletion request is inserted;
    -- later writes wait, observe the request, and fail.
    perform pg_advisory_xact_lock(hashtextextended(account_id::text, 0));
    if exists (
        select 1 from public.cloud_account_deletion_requests d
        where d.cloud_user_id = account_id
    ) then
        raise exception using
            errcode = 'P0001',
            message = 'cloud_account_deletion_in_progress';
    end if;
    return new;
end;
$$;

-- Objects can outlive their cloud_book_files row through a narrow TOCTOU (a
-- long TUS write committing right after its row was removed). With no row left,
-- no session can ever reference that storage path again (paths embed the file
-- id), so the worker reaps such objects after a grace period.
create or replace function public.list_untracked_book_file_objects(
    batch_size integer default 100
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    objects_value jsonb := '[]'::jsonb;
    object_row record;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    if batch_size is null or batch_size < 1 or batch_size > 500 then
        raise exception 'batch_size must be between 1 and 500';
    end if;

    for object_row in
        select o.name as storage_path
        from storage.objects o
        where o.bucket_id = 'book-files'
          and split_part(o.name, '/', 1) = 'users'
          and o.created_at < timezone('utc', now()) - interval '1 hour'
          and not exists (
              select 1 from public.cloud_book_files f
              where f.storage_path = o.name
          )
          and not exists (
              select 1 from public.cloud_book_uploads u
              where u.storage_path = o.name and u.status = 'reserved'
          )
        order by o.created_at
        limit batch_size
    loop
        objects_value := objects_value || jsonb_build_array(jsonb_build_object(
            'storage_path', object_row.storage_path
        ));
    end loop;

    return jsonb_build_object('status', 'listed', 'objects', objects_value);
end;
$$;

revoke execute on function public.list_untracked_book_file_objects(integer)
    from public, anon, authenticated;
grant execute on function public.list_untracked_book_file_objects(integer) to service_role;

alter table public.cloud_book_uploads drop column if exists tus_upload_id;

-- file_exists rejections now carry the existing file record so the client can
-- always resolve the slot for the Replace chain.
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
    response jsonb;
    existing_record jsonb;
begin
    response := public.reserve_book_upload_before_retry_after(
        cloud_book_id,
        media_type,
        relative_path,
        file_name,
        size_bytes,
        content_hash_algorithm,
        content_hash,
        rights_attestation
    );

    if response ->> 'status' = 'rejected'
        and response ->> 'reason' = 'quota_exceeded'
    then
        return response || jsonb_build_object('retry_after_ms', 60000);
    end if;

    if response ->> 'status' = 'rejected'
        and response ->> 'reason' = 'file_exists'
    then
        -- Slot lookup by book + media + path (ownership re-verified through the
        -- book row; the inner RPC already proved the slot is occupied).
        select jsonb_build_object(
            'cloud_book_file_id', f.id,
            'status', f.status,
            'media_type', f.media_type,
            'relative_path', f.relative_path,
            'file_name', f.file_name,
            'size_bytes', f.size_bytes,
            'content_hash', f.content_hash,
            'content_hash_algorithm', f.content_hash_algorithm,
            'revision', f.revision
        )
        into existing_record
        from public.cloud_book_files f
        where f.cloud_book_id = reserve_book_upload.cloud_book_id
          and f.media_type = reserve_book_upload.media_type
          and f.relative_path = coalesce(reserve_book_upload.relative_path, '');
        if existing_record is not null then
            response := response || jsonb_build_object('existing', existing_record);
        end if;
    end if;

    return response;
end;
$$;

revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, service_role;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;
