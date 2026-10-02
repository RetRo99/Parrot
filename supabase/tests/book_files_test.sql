begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(15);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'book-files-test@example.invalid', '', now()
);
-- Uploads are allowlist-only (20261003000000).
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('10000000-0000-0000-0000-000000000001', 'uploads');
insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    repeat('a', 64), 'sha-256-v1', 'Test book', 'epub'
);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'users/10000000-0000-0000-0000-000000000001/books/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001',
    'existing.epub', 'existing.epub', 25, repeat('f', 64), 'sha-256-v1',
    'application/epub+zip', 'available'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('10000000-0000-0000-0000-000000000001', 1000);
insert into storage.objects (bucket_id, name, metadata)
values (
    'book-files',
    'users/10000000-0000-0000-0000-000000000001/books/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001',
    '{"size":25}'::jsonb
);
insert into public.cloud_content_blocklist (
    content_hash_algorithm, content_hash, reason, created_by
) values ('sha-256-v1', repeat('b', 64), 'test block', 'test');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', '', 'book.epub',
        100, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'reservation creates a pending file and reserves quota'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', '', 'book.epub',
        100, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'upload_id'),
    (select upload_id::text from public.cloud_book_uploads
     where cloud_book_id = '20000000-0000-0000-0000-000000000001' and status = 'reserved'),
    'repeating the same reservation returns the active upload session'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', '', 'blocked.epub',
        10, 'sha-256-v1', repeat('b', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'content_blocked', 'blocked hashes cannot be reserved'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', 'large.epub', 'large.epub',
        1001, 'sha-256-v1', repeat('d', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'quota_exceeded', 'reservations above the account quota are rejected'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', 'large.epub', 'large.epub',
        1001, 'sha-256-v1', repeat('d', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'retry_after_ms'),
    '60000', 'quota rejection provides a server retry delay'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', '', 'book.epub',
        100, 'sha-256-v1', repeat('e', 64), '{}'::jsonb
    )->>'reason'),
    'attestation_required', 'a rights attestation is required'
);

select is(
    public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', 'existing.epub', 'existing.epub',
        25, 'sha-256-v1', repeat('f', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status',
    'already_available', 'matching finalized content is deduplicated'
);

select is(
    public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', 'existing.epub', 'replacement.epub',
        25, 'sha-256-v1', repeat('e', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason',
    'file_exists', 'a different hash requires explicit replacement'
);

select is(
    public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000001', 'application/epub+zip', 'existing.epub', 'replacement.epub',
        25, 'sha-256-v1', repeat('e', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->'existing'->>'cloud_book_file_id',
    '30000000-0000-0000-0000-000000000001',
    'file_exists rejections carry the existing file record for the Replace chain'
);

create temporary table test_upload as
select upload_id
from public.cloud_book_uploads
where cloud_book_id = '20000000-0000-0000-0000-000000000001' and status = 'reserved';

select is(
    (public.cancel_book_upload((select upload_id from test_upload))->>'status'),
    'cancelled', 'cancellation releases the reservation'
);

select is(
    (public.cancel_book_upload((select upload_id from test_upload))->>'status'),
    'cancelled', 'cancellation is idempotent'
);

select is(
    (select count(*)::integer from public.cloud_book_files
     where cloud_book_id = '20000000-0000-0000-0000-000000000001'
       and relative_path = ''),
    1, 'cancel retains the non-available file row for orphan cleanup'
);

select is(
    (public.delete_book_file('30000000-0000-0000-0000-000000000001')->>'status'),
    'deleting', 'the owner can mark an available cloud file for deletion'
);

set local storage.allow_delete_query = 'false';
select is(
    (select count(*)::integer from storage.objects
     where bucket_id = 'book-files'
       and name = 'users/10000000-0000-0000-0000-000000000001/books/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001'),
    0, 'deleting files are not readable outside a Storage delete request'
);

set local storage.allow_delete_query = 'true';
create temporary table storage_delete_result (removed_count integer);
with removed as (
    delete from storage.objects
    where bucket_id = 'book-files'
      and name = 'users/10000000-0000-0000-0000-000000000001/books/20000000-0000-0000-0000-000000000001/30000000-0000-0000-0000-000000000001'
    returning 1
)
insert into storage_delete_result
select count(*)::integer from removed;
select is(
    (select removed_count from storage_delete_result),
    1, 'the Storage API delete context can remove the deleting owner file'
);

select * from finish();
rollback;
