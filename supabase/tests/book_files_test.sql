begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(10);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'book-files-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
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

select * from finish();
rollback;
