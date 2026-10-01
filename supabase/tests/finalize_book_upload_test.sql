begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(19);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '13000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'finalize-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values (
    '23000000-0000-0000-0000-000000000001',
    '13000000-0000-0000-0000-000000000001',
    repeat('a', 64), 'sha-256-v1', 'Finalize test', 'epub'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('13000000-0000-0000-0000-000000000001', 10000);

create temporary table finalize_inputs (
    case_name text primary key,
    size_bytes bigint not null,
    content_hash text not null
);
insert into finalize_inputs values
    ('happy', 101, repeat('1', 64)),
    ('size-mismatch', 202, repeat('2', 64)),
    ('expired', 303, repeat('3', 64)),
    ('incomplete', 404, repeat('4', 64)),
    ('hash-mismatch', 505, repeat('5', 64));

create temporary table finalize_uploads (
    case_name text primary key,
    upload_id uuid not null,
    storage_path text not null,
    size_bytes bigint not null,
    content_hash text not null
);
grant select on finalize_inputs to authenticated;
grant insert, select on finalize_uploads to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '13000000-0000-0000-0000-000000000001';
insert into finalize_uploads
select input.case_name,
       (reserved.result->>'upload_id')::uuid,
       reserved.result->>'storage_path',
       input.size_bytes,
       input.content_hash
from finalize_inputs input
cross join lateral (
    select public.reserve_book_upload(
        '23000000-0000-0000-0000-000000000001',
        'application/epub+zip',
        input.case_name,
        input.case_name || '.epub',
        input.size_bytes,
        'sha-256-v1',
        input.content_hash,
        '{"attested_at":"2026-09-24T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    ) as result
) reserved;

select is(
    (select count(*)::integer from finalize_uploads),
    5,
    'fixtures create five reserved uploads through the public reservation RPC'
);

reset role;
update public.cloud_book_uploads
set expires_at = now() - interval '1 minute'
where upload_id = (select upload_id from finalize_uploads where case_name = 'expired');
insert into storage.objects (bucket_id, name, metadata)
select 'book-files', storage_path,
       jsonb_build_object(
           'size', case when case_name = 'size-mismatch' then size_bytes - 1 else size_bytes end
       )
from finalize_uploads
where case_name in ('happy', 'size-mismatch', 'expired', 'hash-mismatch');

select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '1515:0',
    'reservation bytes are charged before any upload is finalized'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'happy'),
        101,
        repeat('1', 64)
    )->>'status'),
    'available',
    'finalize commits a complete Storage object'
);

reset role;
select is(
    (select u.status || ':' || f.status
     from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.upload_id = (select upload_id from finalize_uploads where case_name = 'happy')),
    'finalized:available',
    'successful finalize atomically marks both rows available/finalized'
);
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '1414:101',
    'successful finalize moves only that reservation into used quota'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'happy'),
        101,
        repeat('1', 64)
    )->>'status'),
    'available',
    'a second finalize returns the already-committed file'
);
reset role;
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '1414:101',
    'idempotent finalize does not charge quota a second time'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'size-mismatch'),
        202,
        repeat('2', 64)
    )->>'reason'),
    'size_mismatch',
    'finalize rejects a Storage object whose recorded size differs from the reservation'
);
reset role;
select is(
    (select u.status || ':' || f.status
     from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.upload_id = (select upload_id from finalize_uploads where case_name = 'size-mismatch')),
    'failed:upload_failed',
    'size mismatch permanently fails the session and file row'
);
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '1212:302',
    'size mismatch releases its reservation and charges the leftover object as orphan usage'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'expired'),
        303,
        repeat('3', 64)
    )->>'reason'),
    'upload_expired',
    'finalize lazily expires a reservation past its deadline'
);
reset role;
select is(
    (select u.status || ':' || f.status
     from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.upload_id = (select upload_id from finalize_uploads where case_name = 'expired')),
    'expired:upload_failed',
    'expired finalize records the terminal session and failed file state'
);
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '909:605',
    'expired finalize releases its reserved quota and charges the leftover object'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'incomplete'),
        404,
        repeat('4', 64)
    )->>'reason'),
    'upload_incomplete',
    'finalize rejects a session with no materialized Storage object'
);
reset role;
select is(
    (select u.status || ':' || f.status
     from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.upload_id = (select upload_id from finalize_uploads where case_name = 'incomplete')),
    'reserved:upload_pending',
    'an incomplete upload remains resumable and keeps its pending file row'
);
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '909:605',
    'upload-incomplete does not release quota before a terminal transition'
);

set local role authenticated;
select is(
    (public.finalize_book_upload(
        (select upload_id from finalize_uploads where case_name = 'hash-mismatch'),
        505,
        repeat('f', 64)
    )->>'reason'),
    'content_hash_mismatch',
    'finalize rejects a client hash that differs from the reserved hash'
);
reset role;
select is(
    (select u.status || ':' || f.status
     from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.upload_id = (select upload_id from finalize_uploads where case_name = 'hash-mismatch')),
    'failed:upload_failed',
    'hash mismatch permanently fails the session and file row'
);
select is(
    (select reserved_bytes::text || ':' || used_bytes::text
     from public.cloud_user_storage
     where cloud_user_id = '13000000-0000-0000-0000-000000000001'),
    '404:1110',
    'hash mismatch releases its reservation and charges the leftover object as orphan usage'
);

select * from finish();
rollback;
