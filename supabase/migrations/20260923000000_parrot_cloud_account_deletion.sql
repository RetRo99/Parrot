-- Account deletion is a resumable Storage-API operation followed by Auth user
-- deletion. The request row freezes new tenant writes while cleanup is running.
create table public.cloud_account_deletion_requests (
    cloud_user_id uuid primary key references auth.users(id) on delete cascade,
    requested_at timestamptz not null default timezone('utc', now())
);

create index cloud_file_audit_events_created_at
on public.cloud_file_audit_events(created_at);

alter table public.cloud_account_deletion_requests enable row level security;
revoke all on public.cloud_account_deletion_requests from anon, authenticated;

create or replace function public.cloud_account_deletion_in_progress()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.cloud_account_deletion_requests d
        where d.cloud_user_id = auth.uid()
    );
$$;

create or replace function public.request_cloud_account_deletion()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    actor uuid := auth.uid();
    requested_at_value timestamptz;
begin
    if actor is null then
        raise exception 'Authentication required';
    end if;

    -- Serialize with sync/upload operations and the Storage-object write guard.
    perform pg_advisory_xact_lock(hashtextextended(actor::text, 0));
    insert into public.cloud_account_deletion_requests(cloud_user_id)
    values (actor)
    on conflict (cloud_user_id) do nothing;
    select d.requested_at into requested_at_value
    from public.cloud_account_deletion_requests d
    where d.cloud_user_id = actor;

    return jsonb_build_object(
        'status', 'deleting',
        'requested_at', requested_at_value
    );
end;
$$;

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

revoke execute on function public.cloud_account_deletion_in_progress() from public, anon;
revoke execute on function public.request_cloud_account_deletion() from public, anon;
revoke execute on function public.guard_cloud_account_deletion_write() from public, anon;
grant execute on function public.cloud_account_deletion_in_progress() to authenticated, service_role;
grant execute on function public.request_cloud_account_deletion() to authenticated;
grant execute on function public.guard_cloud_account_deletion_write() to authenticated, service_role;

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'cloud_books',
        'cloud_book_files',
        'cloud_book_uploads',
        'cloud_user_storage',
        'cloud_file_audit_events',
        'reading_positions',
        'sync_changes',
        'sync_mutations'
    ] loop
        execute format(
            'create trigger guard_cloud_account_deletion_write '
            'before insert or update on public.%I '
            'for each row execute function public.guard_cloud_account_deletion_write()',
            table_name
        );
    end loop;
end;
$$;

create trigger guard_book_files_storage_deletion_write
before insert or update on storage.objects
for each row execute function public.guard_cloud_account_deletion_write();

drop policy if exists book_files_reserved_upload_insert on storage.objects;
create policy book_files_reserved_upload_insert
on storage.objects for insert to authenticated
with check (
    bucket_id = 'book-files'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1 from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
);

drop policy if exists book_files_reserved_upload_update on storage.objects;
create policy book_files_reserved_upload_update
on storage.objects for update to authenticated
using (
    bucket_id = 'book-files'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1 from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
)
with check (
    bucket_id = 'book-files'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1 from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
);

drop policy if exists book_files_available_owner_select on storage.objects;
create policy book_files_available_owner_select
on storage.objects for select to authenticated
using (
    bucket_id = 'book-files'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1
        from public.cloud_book_files f
        where f.storage_path = name
          and f.cloud_user_id = auth.uid()
          and f.status = 'available'
          and not exists (
              select 1 from public.cloud_content_blocklist b
              where b.content_hash_algorithm = f.content_hash_algorithm
                and b.content_hash = f.content_hash
          )
    )
);

create or replace function public.purge_expired_cloud_file_audit_events()
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    deleted_count integer;
begin
    if auth.role() is distinct from 'service_role' then
        raise exception 'Service role required';
    end if;
    delete from public.cloud_file_audit_events
    where created_at < timezone('utc', now()) - interval '180 days';
    get diagnostics deleted_count = row_count;
    return jsonb_build_object('status', 'purged', 'deleted_count', deleted_count);
end;
$$;

revoke execute on function public.purge_expired_cloud_file_audit_events() from public, anon, authenticated;
grant execute on function public.purge_expired_cloud_file_audit_events() to service_role;
