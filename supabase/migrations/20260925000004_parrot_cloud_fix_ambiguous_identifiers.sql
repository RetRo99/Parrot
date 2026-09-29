-- Fix plpgsql identifier ambiguities that newer Postgres rejects at runtime.
--
-- `upload_id` (finalize_book_upload parameter) and `cloud_book_file_id`
-- (confirm_orphan_book_file_gc parameter) collide with same-named columns in
-- unqualified WHERE clauses. The bodies below are identical to the current
-- definitions except those references are qualified to the intended column.

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
        where public.cloud_book_uploads.upload_id = upload_row.upload_id;
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
        update public.cloud_book_file_gc_claims as claim
        set claimed_at = timezone('utc', now())
        where claim.cloud_book_file_id = claim_row.cloud_book_file_id;
        return jsonb_build_object('status', 'confirmed', 'cloud_book_file_id', confirm_orphan_book_file_gc.cloud_book_file_id);
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
    update public.cloud_book_file_gc_claims as claim
    set claimed_at = timezone('utc', now())
    where claim.cloud_book_file_id = file_row.id;
    return jsonb_build_object('status', 'confirmed', 'cloud_book_file_id', file_row.id);
end;
$$;

revoke execute on function public.confirm_orphan_book_file_gc(uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.confirm_orphan_book_file_gc(uuid, uuid) to service_role;
