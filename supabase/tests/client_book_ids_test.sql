begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(20);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'client-ids-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'client-ids-b@example.invalid', '', now());
-- Uploads are allowlist-only (20261003000000).
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('10000000-0000-0000-0000-000000000051', 'uploads'),
    ('10000000-0000-0000-0000-000000000052', 'uploads');

create temporary table push_results (case_name text primary key, result jsonb);
grant select, insert on push_results to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';

-- 1. A client-chosen UUID becomes the Parrot Cloud book id.
insert into push_results
select 'new', public.push_sync_changes('[{
    "mutation_id": "50000000-0000-0000-0000-000000000001",
    "entity_type": "library_book",
    "entity_id": "6f1c0000-0000-4000-8000-000000000001",
    "operation": "upsert",
    "payload": {
        "library_book_id": "6f1c0000-0000-4000-8000-000000000001",
        "title": "Client Book",
        "author": "Author",
        "format": "epub",
        "source_content_hash": "aaaa",
        "source_content_hash_algorithm": "sha-256-v1"
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'new'),
    'accepted', 'an upsert with a new UUID and a new source hash is accepted'
);
select is(
    (select result->>'library_book_id' from push_results where case_name = 'new'),
    '6f1c0000-0000-4000-8000-000000000001', 'the accepted result echoes the client book id'
);
select is(
    (select count(*)::integer from public.cloud_books
     where id = '6f1c0000-0000-4000-8000-000000000001'),
    1, 'cloud_books.id is the client book id'
);
select is(
    (select payload->>'library_book_id' from jsonb_to_recordset(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row(entity_type text, payload jsonb)
     where entity_type = 'library_book'),
    '6f1c0000-0000-4000-8000-000000000001', 'pulled payloads carry library_book_id = cloud_books.id'
);
select is(
    (select payload->>'source_content_hash' from jsonb_to_recordset(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row(entity_type text, payload jsonb)
     where entity_type = 'library_book'),
    'aaaa', 'pulled payloads carry the source content hash'
);

-- 2. Hash-derived ids are rejected.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000002",
        "entity_type": "library_book",
        "entity_id": "sha-256-v1:abc",
        "operation": "upsert",
        "payload": {
            "library_book_id": "sha-256-v1:abc",
            "title": "Hash Book",
            "format": "epub",
            "source_content_hash": "abc",
            "source_content_hash_algorithm": "sha-256-v1"
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_library_book_id', 'a hash-derived library book id is rejected'
);

-- 3. The same source hash under a different id is a duplicate.
insert into push_results
select 'duplicate', public.push_sync_changes('[{
    "mutation_id": "50000000-0000-0000-0000-000000000003",
    "entity_type": "library_book",
    "entity_id": "6f1c0000-0000-4000-8000-000000000002",
    "operation": "upsert",
    "payload": {
        "library_book_id": "6f1c0000-0000-4000-8000-000000000002",
        "title": "Client Book Again",
        "format": "epub",
        "source_content_hash": "aaaa",
        "source_content_hash_algorithm": "sha-256-v1"
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'duplicate'),
    'duplicate', 'a second id with the same source hash is reported as a duplicate'
);
select is(
    (select result->>'existing_book_id' from push_results where case_name = 'duplicate'),
    '6f1c0000-0000-4000-8000-000000000001', 'the duplicate names the existing book'
);
select is(
    (select result->'payload'->>'title' from push_results where case_name = 'duplicate'),
    'Client Book', 'the duplicate carries the existing book payload'
);
select is(
    (select count(*)::integer from public.cloud_books
     where id = '6f1c0000-0000-4000-8000-000000000002'),
    0, 'a duplicate creates no book'
);

-- 5. A stale base revision for an existing id still conflicts.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000005",
        "entity_type": "library_book",
        "entity_id": "6f1c0000-0000-4000-8000-000000000001",
        "operation": "upsert",
        "base_revision": 0,
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000001",
            "title": "Renamed",
            "format": "epub"
        }
    }]'::jsonb, 0)->0->>'status'),
    'conflict', 'a stale base revision returns the conflict payload'
);

-- 7. Positions reference books by library_book_id and must be owned.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000007",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-0000000000ff",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-0000000000ff",
            "position": {"progression": 0.5}
        }
    }]'::jsonb, 0)->0->>'reason'),
    'library_book_not_found', 'a position for an unknown book is rejected'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000008",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000001",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000001",
            "position": {"progression": 0.5}
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a position for an owned book is accepted'
);

reset role;
select is(
    (select payload->>'library_book_id' from public.reading_positions
     where cloud_book_id = '6f1c0000-0000-4000-8000-000000000001'),
    '6f1c0000-0000-4000-8000-000000000001', 'the stored position carries the book id'
);

-- 4. Duplicates are only detected within one account.
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000004",
        "entity_type": "library_book",
        "entity_id": "6f1c0000-0000-4000-8000-000000000003",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000003",
            "title": "Other Account Book",
            "format": "epub",
            "source_content_hash": "aaaa",
            "source_content_hash_algorithm": "sha-256-v1"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'the same source hash in another account is a new book'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000009",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000001",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000001",
            "position": {"progression": 0.9}
        }
    }]'::jsonb, 0)->0->>'reason'),
    'library_book_not_found', 'a position for another account book is rejected'
);

-- 6. A live Parrot file with the same hash identifies the existing book.
reset role;
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '30000000-0000-0000-0000-000000000051',
    '6f1c0000-0000-4000-8000-000000000001',
    '10000000-0000-0000-0000-000000000051',
    'users/10000000-0000-0000-0000-000000000051/books/6f1c0000-0000-4000-8000-000000000001/30000000-0000-0000-0000-000000000051',
    '', 'file.epub', 100, repeat('b', 64), 'sha-256-v1', 'ebook', 'available'
);
insert into public.cloud_user_storage (cloud_user_id, quota_bytes, used_bytes)
values ('10000000-0000-0000-0000-000000000051', 10000, 100);
insert into storage.objects (bucket_id, name, metadata)
values (
    'book-files',
    'users/10000000-0000-0000-0000-000000000051/books/6f1c0000-0000-4000-8000-000000000001/30000000-0000-0000-0000-000000000051',
    '{"size":100}'::jsonb
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000006",
        "entity_type": "library_book",
        "entity_id": "6f1c0000-0000-4000-8000-000000000004",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000004",
            "title": "Same File",
            "format": "epub",
            "source_content_hash": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "source_content_hash_algorithm": "sha-256-v1"
        }
    }]'::jsonb, 0)->0->>'existing_book_id'),
    '6f1c0000-0000-4000-8000-000000000001', 'a live file hash match is reported as a duplicate'
);

-- 8. Replacing the file with different content, through the delete-then-upload
--    replace flow, keeps the book id.
select is(
    (public.delete_book_file('30000000-0000-0000-0000-000000000051')->>'status'),
    'deleting', 'the existing file is marked for deletion before replacement'
);
reset role;
-- Simulate Storage API removal; SQL must not perform this in production.
set local storage.allow_delete_query = 'true';
delete from storage.objects
where bucket_id = 'book-files'
  and name = 'users/10000000-0000-0000-0000-000000000051/books/6f1c0000-0000-4000-8000-000000000001/30000000-0000-0000-0000-000000000051';
set local storage.allow_delete_query = 'false';
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';
select public.complete_book_file_deletion('30000000-0000-0000-0000-000000000051');

create temporary table replace_upload as
select (public.reserve_book_upload(
    '6f1c0000-0000-4000-8000-000000000001', 'ebook', '', 'new.epub',
    120, 'sha-256-v1', repeat('c', 64),
    '{"attested_at":"2026-09-30T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
)) as result;
reset role;
insert into storage.objects (bucket_id, name, metadata)
select 'book-files', result->>'storage_path', '{"size":120}'::jsonb from replace_upload;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.finalize_book_upload(
        (select (result->>'upload_id')::uuid from replace_upload), 120, repeat('c', 64)
    )->>'status'),
    'available', 'the replacement file with a different hash is finalized'
);
reset role;
select is(
    (select cloud_book_id::text || ':' || content_hash from public.cloud_book_files
     where cloud_user_id = '10000000-0000-0000-0000-000000000051'
       and status = 'available'),
    '6f1c0000-0000-4000-8000-000000000001:' || repeat('c', 64), 'the replaced file belongs to the same book id'
);

select * from finish();
rollback;
