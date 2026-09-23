begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(5);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'rls-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'rls-b@example.invalid', '', now());

insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000011',
    repeat('1', 64), 'sha-256-v1', 'Private book', 'epub'
);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '30000000-0000-0000-0000-000000000011',
    '20000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000011',
    'users/10000000-0000-0000-0000-000000000011/books/20000000-0000-0000-0000-000000000011/30000000-0000-0000-0000-000000000011',
    '', 'private.epub', 12, repeat('2', 64), 'sha-256-v1', 'application/epub+zip', 'available'
);

select ok(
    not has_function_privilege(
        'anon',
        'public.reserve_book_upload(uuid,text,text,text,bigint,text,text,jsonb)',
        'execute'
    ),
    'anonymous role cannot call upload reservation'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000012';

select is(
    (select count(*)::integer from public.cloud_books
     where id = '20000000-0000-0000-0000-000000000011'),
    0, 'RLS hides another account book rows'
);
select is(
    (select count(*)::integer from public.cloud_book_files
     where id = '30000000-0000-0000-0000-000000000011'),
    0, 'RLS hides another account file rows'
);
select is(
    public.reserve_book_upload(
        '20000000-0000-0000-0000-000000000011', 'application/epub+zip', '', 'forged.epub',
        12, 'sha-256-v1', repeat('2', 64),
        '{"attested_at":"2026-09-23T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason',
    'cloud_book_not_owned', 'forged book IDs cannot reserve another account file'
);
select is(
    public.create_book_download('30000000-0000-0000-0000-000000000011')->>'reason',
    'cloud_book_file_not_owned', 'another account cannot request a download grant'
);

select * from finish();
rollback;
