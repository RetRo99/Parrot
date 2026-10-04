begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(25);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values (
    '10000000-0000-0000-0000-000000000021',
    '00000000-0000-0000-0000-000000000000',
    'authenticated', 'authenticated', 'abuse-ops-test@example.invalid', '', now()
);
insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values (
    '20000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000021',
    repeat('a', 64), 'sha-256-v1', 'Deletion test', 'epub'
);
insert into public.reading_positions (cloud_book_id, cloud_user_id, payload)
values (
    '20000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000021',
    '{}'::jsonb
);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '30000000-0000-0000-0000-000000000021',
    '20000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000021',
    'users/10000000-0000-0000-0000-000000000021/books/20000000-0000-0000-0000-000000000021/30000000-0000-0000-0000-000000000021',
    '', 'deletion-test.epub', 25, repeat('f', 64), 'sha-256-v1', 'application/epub+zip', 'available'
);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '30000000-0000-0000-0000-000000000022',
    '20000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000021',
    'users/10000000-0000-0000-0000-000000000021/books/20000000-0000-0000-0000-000000000021/30000000-0000-0000-0000-000000000022',
    '', 'takedown-test.epub', 10, repeat('c', 64), 'sha-256-v1', 'readaloud', 'available'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes, used_bytes)
values ('10000000-0000-0000-0000-000000000021', 1000, 35);

select ok(
    not has_function_privilege(
        'anon', 'public.admin_takedown_book_file(uuid,text,text)', 'execute'
    ),
    'anonymous role cannot run file takedowns'
);
select ok(
    not has_function_privilege(
        'authenticated', 'public.admin_block_content_hash(text,text,text,text)', 'execute'
    ),
    'authenticated users cannot change the block-list'
);
select ok(
    has_function_privilege(
        'service_role', 'public.admin_unblock_content_hash(text,text,text,text)', 'execute'
    ),
    'service role can run the audited unblock operation'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000021';
set local request.jwt.claim.role = 'authenticated';
select is(
    public.get_storage_usage()->>'quota_bytes',
    '1000',
    'storage usage reports the caller quota'
);
select is(
    (public.create_book_download('30000000-0000-0000-0000-000000000021')->>'status'),
    'available',
    'available cloud files can receive a download grant before a block'
);

reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
select is(
    (public.admin_block_content_hash(
        'sha-256-v1', repeat('f', 64), 'reported content', 'ops-test'
    )->>'status'),
    'blocked',
    'service role can add a content hash to the block-list'
);
-- Service RPC access does not grant direct private-table reads.
reset role;
select is(
    (select count(*)::integer from public.cloud_content_blocklist
     where content_hash_algorithm = 'sha-256-v1' and content_hash = repeat('f', 64)),
    1,
    'block-list entry is persisted'
);

reset role;
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.create_book_download('30000000-0000-0000-0000-000000000021')->>'reason'),
    'content_blocked',
    'blocked hashes cannot receive download grants'
);

reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
select is(
    (public.admin_unblock_content_hash(
        'sha-256-v1', repeat('f', 64), 'review cleared', 'ops-test'
    )->>'status'),
    'unblocked',
    'service role can reverse a block-list decision'
);
reset role;
select is(
    (select count(*)::integer from public.cloud_content_blocklist
     where content_hash_algorithm = 'sha-256-v1' and content_hash = repeat('f', 64)),
    0,
    'unblock removes the matching algorithm and hash pair'
);
select ok(
    exists (select 1 from public.cloud_file_audit_events
            where content_hash_algorithm = 'sha-256-v1' and content_hash = repeat('f', 64)
              and action = 'unblock' and actor = 'ops-test'),
    'unblock action is recorded in the audit log'
);

reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';
select is(
    (public.admin_takedown_book_file(
        '30000000-0000-0000-0000-000000000022', 'rights notice', 'ops-test'
    )->>'status'),
    'deleting',
    'admin takedown marks the file and returns its storage path'
);
reset role;
select is(
    (select count(*)::integer from public.cloud_content_blocklist
     where content_hash_algorithm = 'sha-256-v1' and content_hash = repeat('c', 64)),
    1,
    'admin takedown blocks future uploads of that hash'
);
select is(
    (select payload->>'status' from public.sync_changes
     where entity_type = 'book_file' and entity_id = '30000000-0000-0000-0000-000000000022'
     order by change_id desc limit 1),
    'deleting',
    'admin takedown publishes the deleting state'
);
select is(
    (select count(*)::integer from public.cloud_file_audit_events
     where cloud_book_file_id = '30000000-0000-0000-0000-000000000022'
       and action = 'takedown' and reason = 'rights notice' and actor = 'ops-test'),
    1,
    'admin takedown records the reason and operator'
);
set local role service_role;
select is(
    (public.complete_book_file_deletion('30000000-0000-0000-0000-000000000022')->>'status'),
    'removed',
    'service role finalizes deletion after the object is absent'
);
select is(
    (public.admin_takedown_book_file(
        '30000000-0000-0000-0000-000000000022', 'rights notice', 'ops-test'
    )->>'status'),
    'removed',
    'repeating a completed file-ID takedown is idempotent'
);

reset role;
set local role authenticated;
set local request.jwt.claim.role = 'authenticated';
select is(
    public.get_storage_usage()->>'used_bytes',
    '25',
    'takedown finalization releases its quota before the user deletion'
);
select is(
    (public.delete_book_file('30000000-0000-0000-0000-000000000021')->>'status'),
    'deleting',
    'owner deletion first marks the remote file as deleting'
);
select is(
    (public.complete_book_file_deletion('30000000-0000-0000-0000-000000000021')->>'status'),
    'removed',
    'deletion completes after the storage object is absent'
);
select is(
    (select count(*)::integer from public.cloud_book_files
     where id = '30000000-0000-0000-0000-000000000021'),
    0,
    'completed deletion removes the file row'
);
select is(
    (select count(*)::integer from public.cloud_books
     where id = '20000000-0000-0000-0000-000000000021'),
    1,
    'file deletion preserves canonical book metadata'
);
select is(
    (select count(*)::integer from public.reading_positions
     where cloud_book_id = '20000000-0000-0000-0000-000000000021'),
    1,
    'file deletion preserves reading position'
);
select is(
    public.get_storage_usage()->>'used_bytes',
    '0',
    'completed deletion releases used quota'
);
select is(
    (select payload->>'status' from public.sync_changes
     where entity_type = 'book_file' and entity_id = '30000000-0000-0000-0000-000000000021'
     order by change_id desc limit 1),
    'removed',
    'completed deletion publishes a removal event'
);

select * from finish();
rollback;
