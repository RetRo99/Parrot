begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(4);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'delete-one@example.invalid', '', now()),
    ('11000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'delete-two@example.invalid', '', now());

insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '21000000-0000-0000-0000-000000000001',
    '11000000-0000-0000-0000-000000000001',
    repeat('a', 64), 'sha-256-v1', 'Deletion test', 'epub'
);

set local role authenticated;
set local request.jwt.claim.sub = '11000000-0000-0000-0000-000000000001';

select is(
    public.request_cloud_account_deletion()->>'status',
    'deleting',
    'authenticated account can request deletion'
);

select is(
    public.cloud_account_deletion_in_progress(),
    true,
    'deletion request blocks the owning account'
);

select throws_ok(
    $$select public.reserve_book_upload(
        '21000000-0000-0000-0000-000000000001', 'application/epub+zip', '', 'book.epub',
        100, 'sha-256-v1', repeat('b', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )$$,
    'P0001',
    'cloud_account_deletion_in_progress',
    'account cannot start a new upload after deletion is requested'
);

set local request.jwt.claim.sub = '11000000-0000-0000-0000-000000000002';
select is(
    public.cloud_account_deletion_in_progress(),
    false,
    'deletion request does not block another account'
);

select * from finish();
rollback;
