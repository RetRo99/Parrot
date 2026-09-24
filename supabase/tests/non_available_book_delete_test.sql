begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(9);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000041',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'non-available-delete-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000041',
    '10000000-0000-0000-0000-000000000041',
    repeat('a', 64), 'sha-256-v1', 'Replacement test', 'epub'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('10000000-0000-0000-0000-000000000041', 1000);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values
    (
        '30000000-0000-0000-0000-000000000041',
        '20000000-0000-0000-0000-000000000041',
        '10000000-0000-0000-0000-000000000041',
        'users/10000000-0000-0000-0000-000000000041/books/20000000-0000-0000-0000-000000000041/30000000-0000-0000-0000-000000000041',
        '', 'failed-no-object.epub', 100, repeat('b', 64), 'sha-256-v1', 'application/epub+zip', 'upload_failed'
    ),
    (
        '30000000-0000-0000-0000-000000000042',
        '20000000-0000-0000-0000-000000000041',
        '10000000-0000-0000-0000-000000000041',
        'users/10000000-0000-0000-0000-000000000041/books/20000000-0000-0000-0000-000000000041/30000000-0000-0000-0000-000000000042',
        '', 'failed-with-object.epub', 100, repeat('c', 64), 'sha-256-v1', 'application/audiobook+zip', 'upload_failed'
    );
insert into storage.objects (bucket_id, name, metadata)
values (
    'book-files',
    'users/10000000-0000-0000-0000-000000000041/books/20000000-0000-0000-0000-000000000041/30000000-0000-0000-0000-000000000042',
    '{"size":5}'::jsonb
);

create temporary table direct_delete_result (result jsonb);
grant select, insert on direct_delete_result to authenticated;
create temporary table object_delete_result (result jsonb);
grant select, insert on object_delete_result to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000041';
set local request.jwt.claim.role = 'authenticated';
insert into direct_delete_result
select public.delete_book_file('30000000-0000-0000-0000-000000000041');
select is(
    (select result->>'status' from direct_delete_result), 'removed',
    'non-available file without an object is removed directly'
);
reset role;

select is(
    (select count(*)::integer from public.cloud_book_files
     where id = '30000000-0000-0000-0000-000000000041'),
    0, 'direct removal clears the non-available file row'
);
select is(
    (select operation || ':' || (payload->>'status') from public.sync_changes
     where entity_type = 'book_file' and entity_id = '30000000-0000-0000-0000-000000000041'
     order by change_id desc limit 1),
    'delete:none', 'direct removal publishes a delete change'
);
select is(
    (select action || ':' || reason from public.cloud_file_audit_events
     where cloud_book_file_id = '30000000-0000-0000-0000-000000000041'
     order by id desc limit 1),
    'delete:non_available_backup_replaced', 'direct removal records an audit event'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000041';
set local request.jwt.claim.role = 'authenticated';
insert into object_delete_result
select public.delete_book_file('30000000-0000-0000-0000-000000000042');
select is(
    (select result->>'status' from object_delete_result), 'deleting',
    'non-available file with an object uses the Storage API deletion handshake'
);
reset role;

select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000041'),
    5::bigint, 'a remaining failed-upload object is charged using its actual size'
);
-- Simulate Storage API removal; SQL must not perform this in production.
delete from storage.objects
where bucket_id = 'book-files'
  and name = 'users/10000000-0000-0000-0000-000000000041/books/20000000-0000-0000-0000-000000000041/30000000-0000-0000-0000-000000000042';

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000041';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.complete_book_file_deletion('30000000-0000-0000-0000-000000000042')->>'status'),
    'removed', 'the deletion handshake completes after Storage API removal'
);
reset role;

select is(
    (select count(*)::integer from public.cloud_book_files
     where id = '30000000-0000-0000-0000-000000000042'),
    0, 'completion removes the failed file row'
);
select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000041'),
    0::bigint, 'completion releases exactly the counted object bytes'
);

select * from finish();
rollback;
