insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('book-files', 'book-files', false, null, null)
on conflict (id) do update
set name = excluded.name,
    public = false,
    file_size_limit = null,
    allowed_mime_types = null;

create policy book_files_reserved_upload_insert
on storage.objects for insert to authenticated
with check (
    bucket_id = 'book-files'
    and exists (
        select 1
        from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
);

create policy book_files_reserved_upload_update
on storage.objects for update to authenticated
using (
    bucket_id = 'book-files'
    and exists (
        select 1
        from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
)
with check (
    bucket_id = 'book-files'
    and exists (
        select 1
        from public.cloud_book_uploads u
        where u.cloud_user_id = auth.uid()
          and u.storage_path = name
          and u.status = 'reserved'
          and u.expires_at > now()
    )
);

create policy book_files_available_owner_select
on storage.objects for select to authenticated
using (
    bucket_id = 'book-files'
    and exists (
        select 1
        from public.cloud_book_files f
        where f.storage_path = name
          and f.cloud_user_id = auth.uid()
          and f.status = 'available'
          and not exists (
              select 1
              from public.cloud_content_blocklist b
              where b.content_hash_algorithm = f.content_hash_algorithm
                and b.content_hash = f.content_hash
          )
    )
);

create policy book_files_deleting_owner_delete
on storage.objects for delete to authenticated
using (
    bucket_id = 'book-files'
    and exists (
        select 1
        from public.cloud_book_files f
        where f.storage_path = name
          and f.cloud_user_id = auth.uid()
          and f.status = 'deleting'
    )
);

-- Service-role cleanup is reserved for expired reservations and audited
-- takedowns; account owners can delete only files first marked as deleting.
grant select, insert, update, delete on storage.objects to service_role;
