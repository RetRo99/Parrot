begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(10);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('14000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'storage-a@example.invalid', '', now()),
    ('14000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'storage-b@example.invalid', '', now());
-- Uploads are allowlist-only (20261003000000).
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('14000000-0000-0000-0000-000000000001', 'uploads'),
    ('14000000-0000-0000-0000-000000000002', 'uploads');

insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values
    ('24000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001',
     repeat('a', 64), 'sha-256-v1', 'Owner A', 'epub'),
    ('24000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000000002',
     repeat('b', 64), 'sha-256-v1', 'Owner B', 'epub');

insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values
    ('34000000-0000-0000-0000-000000000001',
     '24000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001',
     'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub',
     '', 'available.epub', 25, repeat('c', 64), 'sha-256-v1', 'application/epub+zip', 'available'),
    ('34000000-0000-0000-0000-000000000002',
     '24000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001',
     'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/pending.epub',
     'pending.epub', 'pending.epub', 30, repeat('d', 64), 'sha-256-v1', 'application/epub+zip', 'upload_pending');

insert into storage.objects (bucket_id, name, metadata)
values
    ('book-files',
     'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub',
     '{"size":25}'::jsonb),
    ('book-files',
     'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/pending.epub',
     '{"size":30}'::jsonb);

create temporary table owner_a_upload (upload_id uuid, storage_path text);
create temporary table owner_b_upload (upload_id uuid, storage_path text);
grant insert, select on owner_a_upload, owner_b_upload to authenticated;
set local role authenticated;
set local request.jwt.claim.sub = '14000000-0000-0000-0000-000000000001';
insert into owner_a_upload
select (result->>'upload_id')::uuid, result->>'storage_path'
from (
    select public.reserve_book_upload(
        '24000000-0000-0000-0000-000000000001',
        'application/epub+zip', 'private', 'owner-a-private.epub', 40,
        'sha-256-v1', repeat('f', 64),
        '{"attested_at":"2026-09-24T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    ) as result
) reservation;

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '14000000-0000-0000-0000-000000000002';
insert into owner_b_upload
select (result->>'upload_id')::uuid, result->>'storage_path'
from (
    select public.reserve_book_upload(
        '24000000-0000-0000-0000-000000000002',
        'application/epub+zip', '', 'owner-b.epub', 40,
        'sha-256-v1', repeat('e', 64),
        '{"attested_at":"2026-09-24T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    ) as result
) reservation;

select is(
    (select count(*)::integer from owner_b_upload),
    1,
    'the second account has its own live upload reservation for the insert control'
);
select lives_ok(
    $$insert into storage.objects (bucket_id, name, metadata)
      select 'book-files', storage_path, '{"size":40}'::jsonb from owner_b_upload$$,
    'a user may insert the object at their own live reserved path'
);

select throws_ok(
    $$insert into storage.objects (bucket_id, name, metadata)
      select 'book-files', storage_path, '{"size":40}'::jsonb from owner_a_upload$$,
    '42501',
    'new row violates row-level security policy for table "objects"',
    'a live reservation for another account cannot authorize object insertion'
);
select is(
    (select count(*)::integer from storage.objects
     where name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'),
    0,
    'cross-account reads cannot see an available object'
);
set local storage.allow_delete_query = 'true';
with removed as (
    delete from storage.objects
    where bucket_id = 'book-files'
      and name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'
    returning 1
)
select is((select count(*)::integer from removed), 0, 'cross-account deletes cannot remove an owner object');

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '14000000-0000-0000-0000-000000000001';
select is(
    (select count(*)::integer from storage.objects
     where name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'),
    1,
    'the owner can read their available object'
);
select is(
    (select count(*)::integer from storage.objects
     where name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/pending.epub'),
    0,
    'the owner cannot read an object before its file row is available'
);

set local storage.allow_delete_query = 'true';
with removed as (
    delete from storage.objects
    where bucket_id = 'book-files'
      and name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'
    returning 1
)
select is((select count(*)::integer from removed), 0, 'an available owner file is not directly deletable');

reset role;
update public.cloud_book_files
set status = 'deleting', revision = revision + 1
where id = '34000000-0000-0000-0000-000000000001';
set local role authenticated;
set local request.jwt.claim.sub = '14000000-0000-0000-0000-000000000001';
set local storage.allow_delete_query = 'true';
with removed as (
    delete from storage.objects
    where bucket_id = 'book-files'
      and name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'
    returning 1
)
select is((select count(*)::integer from removed), 1, 'owner deletion is allowed only after the row is deleting');
reset role;
select is(
    (select count(*)::integer from storage.objects
     where name = 'users/14000000-0000-0000-0000-000000000001/books/24000000-0000-0000-0000-000000000001/available.epub'),
    0,
    'the deleting owner object is removed through the Storage delete context'
);

select * from finish();
rollback;
