begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(8);

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

insert into public.cloud_file_audit_events (
    cloud_user_id, action, reason, actor
) values
    (
        '11000000-0000-0000-0000-000000000001',
        'takedown', 'rights notice', 'ops-test'
    ),
    (
        '11000000-0000-0000-0000-000000000001',
        'upload', null, '11000000-0000-0000-0000-000000000001'
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

reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
select is(
    public.redact_cloud_account_audit_events('11000000-0000-0000-0000-000000000001'::uuid),
    2,
    'account deletion redacts all audit rows for the account'
);
reset role;
select is(
    (select actor from public.cloud_file_audit_events
     where action = 'takedown' and reason = 'rights notice'),
    'ops-test',
    'account deletion preserves the operator actor on an admin takedown'
);
select is(
    (select actor from public.cloud_file_audit_events
     where action = 'upload' and reason is null),
    'deleted_account',
    'account deletion redacts a user-derived actor value'
);
select is(
    (select count(*)::integer from public.cloud_file_audit_events
     where cloud_user_id is null),
    2,
    'account deletion clears the relational owner from every audit row'
);

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '11000000-0000-0000-0000-000000000001';
set local request.jwt.claim.role = 'authenticated';
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
