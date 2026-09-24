begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(19);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000031',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'orphan-gc-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000031',
    '10000000-0000-0000-0000-000000000031',
    repeat('a', 64), 'sha-256-v1', 'Orphan GC test', 'epub'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('10000000-0000-0000-0000-000000000031', 1000);

create temporary table expired_upload (result jsonb);
grant select, insert on expired_upload to authenticated;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';
insert into expired_upload
select public.reserve_book_upload(
    '20000000-0000-0000-0000-000000000031', 'application/epub+zip', '', 'expired.epub',
    123, 'sha-256-v1', repeat('b', 64),
    '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
);
select is(
    (select result->>'status' from expired_upload),
    'reserved', 'reservation creates the upload session to be expired'
);
reset role;

insert into storage.objects (bucket_id, name, metadata)
select 'book-files', f.storage_path, '{"size":123}'::jsonb
from public.cloud_book_files f
where f.cloud_book_id = '20000000-0000-0000-0000-000000000031'
  and f.file_name = 'expired.epub';
update public.cloud_book_uploads
set expires_at = timezone('utc', now()) - interval '1 minute'
where upload_id = ((select result->>'upload_id' from expired_upload)::uuid);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000031', 'application/epub+zip', 'next.epub', 'next.epub',
        1, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'a subsequent reservation lazily expires the old session'
);
reset role;

select is(
    (select status from public.cloud_book_uploads
     where upload_id = ((select result->>'upload_id' from expired_upload)::uuid)),
    'expired', 'lazy expiry marks the session terminal'
);
select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    123::bigint, 'lazy expiry immediately counts the uploaded object against quota'
);

create temporary table gc_result (result jsonb);
grant select, insert on gc_result to service_role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
insert into gc_result select public.gc_orphan_book_files(100);
reset role;

select is(
    jsonb_array_length(result->'files'), 1,
    'service GC claims the expired file'
) from gc_result;
select is(
    result->'files'->0->>'cloud_book_file_id',
    (select result->>'cloud_book_file_id' from expired_upload),
    'GC claims the matching cloud file'
) from gc_result;
select is(
    result->'files'->0->>'object_exists', 'true',
    'GC identifies the completed storage object'
) from gc_result;
select ok(
    has_function_privilege('service_role', 'public.gc_orphan_book_files(integer)', 'execute'),
    'the service role can run orphan GC'
);
select ok(
    not has_function_privilege('authenticated', 'public.gc_orphan_book_files(integer)', 'execute'),
    'authenticated users cannot run orphan GC'
);

create temporary table gc_confirm_ok (result jsonb);
create temporary table gc_confirm_bad (result jsonb);
grant select, insert on gc_confirm_ok, gc_confirm_bad to service_role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
insert into gc_confirm_ok
select public.confirm_orphan_book_file_gc(
    (result->'files'->0->>'cloud_book_file_id')::uuid,
    (result->>'claim_id')::uuid
)
from gc_result;
insert into gc_confirm_bad
select public.confirm_orphan_book_file_gc(
    (result->'files'->0->>'cloud_book_file_id')::uuid,
    gen_random_uuid()
)
from gc_result;
reset role;

select is(
    (select result->>'status' from gc_confirm_ok),
    'confirmed', 'claim confirmation validates the orphan before deletion'
);
select is(
    (select result->>'reason' from gc_confirm_bad),
    'gc_claim_not_found', 'a mismatched claim cannot authorize deletion'
);
select ok(
    not has_function_privilege(
        'authenticated', 'public.confirm_orphan_book_file_gc(uuid,uuid)', 'execute'
    ),
    'authenticated users cannot confirm GC claims'
);

-- Stand in for the worker's Storage API remove() call. In production the GC
-- script removes the backing object before invoking the completion RPC.
set local storage.allow_delete_query = 'true';
delete from storage.objects
where bucket_id = 'book-files'
  and name = (
      select f.storage_path from public.cloud_book_files f
      where f.id = ((select result->>'cloud_book_file_id' from expired_upload)::uuid)
  );
select is(
    (select count(*)::integer from storage.objects
     where bucket_id = 'book-files'
       and name like 'users/10000000-0000-0000-0000-000000000031/%'),
    0, 'the Storage API removal leaves no storage object'
);

create temporary table gc_complete_result (result jsonb);
grant select, insert on gc_complete_result to service_role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
insert into gc_complete_result
select public.complete_orphan_book_file_gc(
    (result->'files'->0->>'cloud_book_file_id')::uuid,
    (result->>'claim_id')::uuid
)
from gc_result;
reset role;

select is(
    (select result->>'status' from gc_complete_result), 'removed',
    'GC drops the orphan file row after object deletion'
);
select is(
    (select count(*)::integer from public.cloud_book_files
     where id = ((select result->>'cloud_book_file_id' from expired_upload)::uuid)),
    0, 'the orphan metadata row is removed'
);
select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    0::bigint, 'orphan cleanup releases the charged quota'
);

-- Retry-reset accounting: a replacement reservation must keep the previous
-- object's real bytes charged until they are deleted or absorbed by finalize.
create temporary table retry_upload (result jsonb);
grant select, insert on retry_upload to authenticated;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';
insert into retry_upload
select public.reserve_book_upload(
    '20000000-0000-0000-0000-000000000031', 'application/epub+zip', 'retry.epub', 'retry.epub',
    5, 'sha-256-v1', repeat('d', 64),
    '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
);
reset role;

insert into storage.objects (bucket_id, name, metadata)
select 'book-files', f.storage_path, '{"size":123}'::jsonb
from public.cloud_book_files f
where f.cloud_book_id = '20000000-0000-0000-0000-000000000031'
  and f.file_name = 'retry.epub';
update public.cloud_book_uploads
set status = 'failed', updated_at = timezone('utc', now())
where upload_id = ((select result->>'upload_id' from retry_upload)::uuid);

select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    123::bigint, 'a terminal failed upload charges the real object bytes'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000031', 'application/epub+zip', 'retry.epub', 'retry.epub',
        5, 'sha-256-v1', repeat('d', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'the slot accepts a replacement reservation'
);
reset role;

select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    123::bigint, 'a retry keeps the replaced object charged until deletion or finalize'
);

select * from finish();
rollback;
