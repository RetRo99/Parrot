create or replace function public.delete_book_file(cloud_book_file_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    file_row public.cloud_book_files%rowtype;
    upload_row public.cloud_book_uploads%rowtype;
    removed_revision bigint;
    deleting_orphan_object boolean := false;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    select * into file_row
    from public.cloud_book_files f
    where f.id = delete_book_file.cloud_book_file_id
      and f.cloud_user_id = actor
    for update;
    if not found then
        if exists (
            select 1 from public.cloud_book_files f
            where f.id = delete_book_file.cloud_book_file_id
              and f.cloud_user_id <> actor
        ) then
            return jsonb_build_object('status', 'rejected', 'reason', 'cloud_book_file_not_owned');
        end if;
        return jsonb_build_object('status', 'removed', 'cloud_book_file_id', cloud_book_file_id);
    end if;
    if file_row.status = 'deleting' then
        return jsonb_build_object(
            'status', 'deleting',
            'cloud_book_file_id', file_row.id,
            'storage_path', file_row.storage_path
        );
    end if;

    if file_row.status <> 'available' then
        -- Expired sessions can remain reserved until they are touched. Release
        -- those reservations before removing their non-available file row.
        for upload_row in
            select * from public.cloud_book_uploads u
            where u.cloud_book_file_id = file_row.id
              and u.status = 'reserved'
              and u.expires_at <= now()
            for update
        loop
            update public.cloud_user_storage
            set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
                updated_at = timezone('utc', now())
            where cloud_user_id = actor;
            update public.cloud_book_uploads
            set status = 'expired', updated_at = timezone('utc', now())
            where public.cloud_book_uploads.upload_id = upload_row.upload_id;
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
            select 1 from public.cloud_book_uploads u
            where u.cloud_book_file_id = file_row.id
              and u.status = 'reserved'
              and u.expires_at > now()
        ) then
            return jsonb_build_object('status', 'rejected', 'reason', 'upload_in_progress');
        end if;

        if not exists (
            select 1 from storage.objects o
            where o.bucket_id = 'book-files' and o.name = file_row.storage_path
        ) then
            if file_row.orphan_object_counted then
                update public.cloud_user_storage
                set used_bytes = greatest(0, used_bytes - file_row.orphan_object_bytes),
                    updated_at = timezone('utc', now())
                where cloud_user_id = actor;
            end if;
            removed_revision := file_row.revision + 1;
            delete from public.cloud_book_files f where f.id = file_row.id;
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            ) values (
                actor, 'book_file', file_row.id::text, 'delete',
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
                cloud_user_id, cloud_book_id, cloud_book_file_id,
                content_hash_algorithm, content_hash, action, reason, actor
            ) values (
                actor, file_row.cloud_book_id, file_row.id,
                file_row.content_hash_algorithm, file_row.content_hash,
                'delete', 'non_available_backup_replaced', actor::text
            );
            return jsonb_build_object('status', 'removed', 'cloud_book_file_id', file_row.id);
        end if;

        -- A failed upload may still have materialized an object. Count it and
        -- use the normal Storage API deletion handshake rather than orphaning it.
        perform public.account_orphan_book_file_object(file_row.id);
        deleting_orphan_object := true;
    end if;

    update public.cloud_book_files
    set status = 'deleting',
        revision = revision + 1,
        updated_at = timezone('utc', now())
    where id = file_row.id
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
        actor, file_row.cloud_book_id, file_row.id,
        file_row.content_hash_algorithm, file_row.content_hash,
        'delete_requested',
        case when deleting_orphan_object then 'non_available_backup_has_storage_object' end,
        actor::text
    );
    return jsonb_build_object(
        'status', 'deleting',
        'cloud_book_file_id', file_row.id,
        'storage_path', file_row.storage_path
    );
end;
$$;

create or replace function public.complete_book_file_deletion(cloud_book_file_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    caller_role text := auth.role();
    file_row public.cloud_book_files%rowtype;
    removed_revision bigint;
    charged_bytes bigint;
begin
    if actor is null and caller_role is distinct from 'service_role' then
        raise exception 'Authentication required';
    end if;
    if actor is not null then
        perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    end if;
    select * into file_row
    from public.cloud_book_files f
    where f.id = complete_book_file_deletion.cloud_book_file_id
      and (caller_role = 'service_role' or f.cloud_user_id = actor)
    for update;
    if not found then
        return jsonb_build_object('status', 'removed', 'cloud_book_file_id', cloud_book_file_id);
    end if;
    if file_row.status <> 'deleting' then
        return jsonb_build_object('status', 'rejected', 'reason', 'file_not_deleting');
    end if;
    if exists (
        select 1 from storage.objects o
        where o.bucket_id = 'book-files' and o.name = file_row.storage_path
    ) then
        return jsonb_build_object('status', 'deleting', 'reason', 'storage_object_still_exists');
    end if;

    charged_bytes := case
        when file_row.orphan_object_counted then file_row.orphan_object_bytes
        else file_row.size_bytes
    end;
    removed_revision := file_row.revision + 1;
    update public.cloud_user_storage
    set used_bytes = greatest(0, used_bytes - charged_bytes),
        updated_at = timezone('utc', now())
    where cloud_user_id = file_row.cloud_user_id;
    delete from public.cloud_book_files where id = file_row.id;
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
        content_hash_algorithm, content_hash, action, actor
    ) values (
        file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
        file_row.content_hash_algorithm, file_row.content_hash,
        'delete', coalesce(actor::text, 'service_role')
    );
    return jsonb_build_object('status', 'removed', 'cloud_book_file_id', file_row.id);
end;
$$;

revoke execute on function public.delete_book_file(uuid) from public, anon;
grant execute on function public.delete_book_file(uuid) to authenticated;
revoke execute on function public.complete_book_file_deletion(uuid) from public, anon;
grant execute on function public.complete_book_file_deletion(uuid) to authenticated, service_role;
