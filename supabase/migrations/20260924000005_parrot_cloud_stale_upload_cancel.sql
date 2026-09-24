-- A terminal upload session may share its file row with a newer retry. Never
-- let cancelling that stale session mutate the newer reservation's file row.
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

    -- Terminal sessions are immutable. In particular, an expired/failed
    -- session can outlive the shared file row's newer reserved session.
    if upload_row.status = 'cancelled' then
        return jsonb_build_object('status', 'cancelled', 'upload_id', upload_row.upload_id);
    end if;
    if upload_row.status = 'finalized' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_already_finalized');
    end if;
    if upload_row.status in ('failed', 'expired') then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_reserved');
    end if;
    if upload_row.status <> 'reserved' then
        return jsonb_build_object('status', 'rejected', 'reason', 'upload_not_reserved');
    end if;

    update public.cloud_user_storage
    set reserved_bytes = greatest(0, reserved_bytes - upload_row.size_bytes),
        updated_at = timezone('utc', now())
    where cloud_user_id = actor;

    update public.cloud_book_uploads
    set status = 'cancelled', updated_at = timezone('utc', now())
    where public.cloud_book_uploads.upload_id = upload_row.upload_id;

    select * into file_row from public.cloud_book_files f
    where f.id = upload_row.cloud_book_file_id for update;
    if found and file_row.status <> 'available' and not exists (
        select 1 from public.cloud_book_uploads u2
        where u2.cloud_book_file_id = file_row.id
          and u2.status = 'reserved'
    ) then
        -- Physical object removal remains the Storage API GC worker's job.
        perform public.account_orphan_book_file_object(file_row.id);
        update public.cloud_book_files f
        set status = 'upload_failed', revision = revision + 1,
            updated_at = timezone('utc', now())
        where f.id = file_row.id
          and f.status <> 'available'
          and not exists (
              select 1 from public.cloud_book_uploads u2
              where u2.cloud_book_file_id = f.id
                and u2.status = 'reserved'
          )
        returning * into file_row;

        if found then
            insert into public.sync_changes(
                cloud_user_id, entity_type, entity_id, operation, payload, revision
            ) values (
                actor, 'book_file', file_row.id::text, 'delete',
                jsonb_build_object(
                    'cloud_book_id', upload_row.cloud_book_id,
                    'cloud_book_file_id', file_row.id,
                    'media_type', upload_row.media_type,
                    'relative_path', upload_row.relative_path,
                    'status', 'none'
                ), 0
            );
        end if;
    end if;

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
