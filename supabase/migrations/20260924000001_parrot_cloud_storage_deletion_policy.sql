drop policy if exists book_files_deleting_owner_delete on storage.objects;
create policy book_files_deleting_owner_delete
on storage.objects for delete to authenticated
using (
    bucket_id = 'book-files'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1
        from public.cloud_book_files f
        where f.storage_path = name
          and f.cloud_user_id = auth.uid()
          and f.status = 'deleting'
    )
);

-- Supabase Storage evaluates a SELECT policy while locating rows for its
-- delete-many operation. Restrict this lookup policy to the GUC that Storage
-- sets only for API-driven deletes so deleting files stay unreadable.
drop policy if exists book_files_deleting_owner_delete_lookup on storage.objects;
create policy book_files_deleting_owner_delete_lookup
on storage.objects for select to authenticated
using (
    bucket_id = 'book-files'
    and current_setting('storage.allow_delete_query', true) = 'true'
    and not public.cloud_account_deletion_in_progress()
    and exists (
        select 1
        from public.cloud_book_files f
        where f.storage_path = name
          and f.cloud_user_id = auth.uid()
          and f.status = 'deleting'
    )
);
