begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(11);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000051',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'stale-cancel-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000051',
    '10000000-0000-0000-0000-000000000051',
    repeat('a', 64), 'sha-256-v1', 'Stale cancel test', 'epub'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('10000000-0000-0000-0000-000000000051', 1000);

create temporary table test_sessions (
    old_result jsonb,
    new_result jsonb,
    cancel_result jsonb
);
grant select, insert, update on test_sessions to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';

insert into test_sessions (old_result)
values (public.reserve_book_upload(
    '20000000-0000-0000-0000-000000000051', 'application/epub+zip', '', 'book.epub',
    100, 'sha-256-v1', repeat('b', 64),
    '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
));

select is(
    (select old_result->>'status' from test_sessions),
    'reserved', 'the first attempt reserves an upload session'
);

reset role;
update public.cloud_book_uploads
set expires_at = now() - interval '1 minute'
where upload_id = (select (old_result->>'upload_id')::uuid from test_sessions);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
update test_sessions
set new_result = public.reserve_book_upload(
    '20000000-0000-0000-0000-000000000051', 'application/epub+zip', '', 'book.epub',
    100, 'sha-256-v1', repeat('b', 64),
    '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
);

select is(
    (select new_result->>'status' from test_sessions),
    'reserved', 'retry creates a newer reservation on the reused file row'
);
select isnt(
    (select new_result->>'upload_id' from test_sessions),
    (select old_result->>'upload_id' from test_sessions),
    'the retry has a distinct upload session id'
);
select is(
    (select status from public.cloud_book_uploads
     where upload_id = (select (old_result->>'upload_id')::uuid from test_sessions)),
    'expired', 'lazy expiry marks the old session terminal'
);

update test_sessions
set cancel_result = public.cancel_book_upload(
    (select (old_result->>'upload_id')::uuid from test_sessions)
);

reset role;
select is(
    (select cancel_result->>'reason' from test_sessions),
    'upload_not_reserved', 'cancelling the old session is a no-op'
);
select is(
    (select status from public.cloud_book_uploads
     where upload_id = (select (old_result->>'upload_id')::uuid from test_sessions)),
    'expired', 'the stale session remains expired'
);
select is(
    (select status from public.cloud_book_uploads
     where upload_id = (select (new_result->>'upload_id')::uuid from test_sessions)),
    'reserved', 'the newer upload session remains reserved'
);
select is(
    (select status from public.cloud_book_files
     where cloud_book_id = '20000000-0000-0000-0000-000000000051'
       and relative_path = ''),
    'upload_pending', 'the shared file row remains available to the active upload'
);
select is(
    (select count(*)::integer from public.sync_changes
     where entity_type = 'book_file'
       and entity_id = (select new_result->>'cloud_book_file_id' from test_sessions)
       and operation = 'delete'),
    0, 'stale cancellation emits no delete change'
);
select is(
    (select count(*)::integer from public.cloud_file_audit_events
     where upload_id = (select (old_result->>'upload_id')::uuid from test_sessions)
       and action = 'cancel'),
    0, 'stale cancellation emits no cancel audit event'
);
select is(
    (select reserved_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000051'),
    100::bigint, 'only the newer session remains charged as reserved'
);

select * from finish();
rollback;
