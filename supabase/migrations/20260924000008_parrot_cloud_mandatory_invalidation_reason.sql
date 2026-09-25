alter table public.cloud_book_files
    add column invalidation_reason text
        check (invalidation_reason is null or invalidation_reason = 'mandatory_invalidation');

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
    removed_payload jsonb;
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

    removed_payload := jsonb_build_object(
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
    );
    if file_row.invalidation_reason = 'mandatory_invalidation' then
        removed_payload := removed_payload || jsonb_build_object(
            'invalidation_reason', file_row.invalidation_reason
        );
    end if;
    insert into public.sync_changes(
        cloud_user_id, entity_type, entity_id, operation, payload, revision
    ) values (
        file_row.cloud_user_id, 'book_file', file_row.id::text, 'delete',
        removed_payload, removed_revision
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

create or replace function public.admin_takedown_book_file(
    cloud_book_file_id uuid,
    reason text,
    actor text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    file_row public.cloud_book_files%rowtype;
    inserted_block boolean := false;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    if nullif(btrim(reason), '') is null or nullif(btrim(actor), '') is null then
        raise exception 'Reason and actor are required';
    end if;
    select * into file_row
    from public.cloud_book_files f
    where f.id = admin_takedown_book_file.cloud_book_file_id
    for update;
    if not found then
        if exists (
            select 1 from public.cloud_file_audit_events e
            where e.cloud_book_file_id = admin_takedown_book_file.cloud_book_file_id
              and e.action = 'takedown'
        ) then
            return jsonb_build_object(
                'status', 'removed',
                'cloud_book_file_id', admin_takedown_book_file.cloud_book_file_id
            );
        end if;
        return jsonb_build_object('status', 'rejected', 'reason', 'cloud_book_file_not_found');
    end if;

    insert into public.cloud_content_blocklist(
        content_hash_algorithm, content_hash, reason, created_by
    ) values (
        file_row.content_hash_algorithm, file_row.content_hash, btrim(reason), btrim(actor)
    ) on conflict (content_hash_algorithm, content_hash) do nothing;
    inserted_block := found;
    if inserted_block then
        insert into public.cloud_file_audit_events(
            cloud_user_id, cloud_book_id, cloud_book_file_id,
            content_hash_algorithm, content_hash, action, reason, actor
        ) values (
            file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
            file_row.content_hash_algorithm, file_row.content_hash,
            'block', btrim(reason), btrim(actor)
        );
    end if;

    if file_row.status = 'deleting' then
        if not exists (
            select 1 from public.cloud_file_audit_events e
            where e.cloud_book_file_id = file_row.id
              and e.action = 'takedown'
              and e.reason = btrim(admin_takedown_book_file.reason)
              and e.actor = btrim(admin_takedown_book_file.actor)
        ) then
            insert into public.cloud_file_audit_events(
                cloud_user_id, cloud_book_id, cloud_book_file_id,
                content_hash_algorithm, content_hash, action, reason, actor
            ) values (
                file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
                file_row.content_hash_algorithm, file_row.content_hash,
                'takedown', btrim(reason), btrim(actor)
            );
        end if;

        if file_row.invalidation_reason is distinct from 'mandatory_invalidation' then
            update public.cloud_book_files
            set invalidation_reason = 'mandatory_invalidation',
                revision = revision + 1,
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
                    'remote_revision', file_row.revision,
                    'invalidation_reason', file_row.invalidation_reason
                ), file_row.revision
            );
        end if;
        return jsonb_build_object(
            'status', 'deleting', 'cloud_book_file_id', file_row.id,
            'storage_path', file_row.storage_path
        );
    end if;
    if file_row.status <> 'available' then
        return jsonb_build_object('status', 'rejected', 'reason', 'file_not_available');
    end if;

    update public.cloud_book_files
    set status = 'deleting',
        invalidation_reason = 'mandatory_invalidation',
        revision = revision + 1,
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
            'remote_revision', file_row.revision,
            'invalidation_reason', file_row.invalidation_reason
        ), file_row.revision
    );
    insert into public.cloud_file_audit_events(
        cloud_user_id, cloud_book_id, cloud_book_file_id,
        content_hash_algorithm, content_hash, action, reason, actor
    ) values (
        file_row.cloud_user_id, file_row.cloud_book_id, file_row.id,
        file_row.content_hash_algorithm, file_row.content_hash,
        'takedown', btrim(reason), btrim(actor)
    );
    return jsonb_build_object(
        'status', 'deleting', 'cloud_book_file_id', file_row.id,
        'storage_path', file_row.storage_path
    );
end;
$$;

revoke execute on function public.admin_takedown_book_file(uuid, text, text)
    from public, anon, authenticated;
grant execute on function public.admin_takedown_book_file(uuid, text, text)
    to service_role;
