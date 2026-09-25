begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(55);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    (
        '12000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000000',
        'authenticated', 'authenticated', 'library-book-removal-test@example.invalid', '', now()
    ),
    (
        '12000000-0000-0000-0000-000000000102',
        '00000000-0000-0000-0000-000000000000',
        'authenticated', 'authenticated', 'library-book-removal-other@example.invalid', '', now()
    );
insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '22000000-0000-0000-0000-000000000101',
    '12000000-0000-0000-0000-000000000101',
    repeat('a', 64), 'sha-256-v1', 'Original title', 'epub'
);
insert into public.cloud_books (
    id, cloud_user_id, content_hash, content_hash_algorithm, title, format
) values (
    '22000000-0000-0000-0000-000000000102',
    '12000000-0000-0000-0000-000000000101',
    repeat('b', 64), 'sha-256-v1', 'Retryable title', 'epub'
);
insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values (
    '23000000-0000-0000-0000-000000000102',
    '22000000-0000-0000-0000-000000000102',
    '12000000-0000-0000-0000-000000000101',
    'test/retryable-cloud-file',
    'retry.epub', 'retry.epub', 25, repeat('b', 64), 'sha-256-v1',
    'application/epub+zip', 'available'
);
insert into public.reading_positions (
    cloud_book_id, cloud_user_id, payload, revision
) values (
    '22000000-0000-0000-0000-000000000101',
    '12000000-0000-0000-0000-000000000101',
    jsonb_build_object(
        'cloud_book_id', '22000000-0000-0000-0000-000000000101',
        'library_book_id', 'sha-256-v1:' || repeat('a', 64),
        'position', jsonb_build_object('book_uuid', 'test-book')
    ),
    5
);

create temporary table removal_result (result jsonb);
grant select, insert on removal_result to authenticated;
create temporary table stale_upsert_result (result jsonb);
grant select, insert on stale_upsert_result to authenticated;
create temporary table current_revision_upsert_result (result jsonb);
create temporary table stale_position_result (result jsonb);
create temporary table intentional_reimport_result (result jsonb);
create temporary table stale_intentional_reimport_result (result jsonb);
create temporary table post_reimport_position_result (result jsonb);
grant select, insert on current_revision_upsert_result to authenticated;
grant select, insert on stale_position_result to authenticated;
grant select, insert on intentional_reimport_result to authenticated;
grant select, insert on stale_intentional_reimport_result to authenticated;
grant select, insert on post_reimport_position_result to authenticated;
create temporary table duplicate_removal_result (result jsonb);
grant select, insert on duplicate_removal_result to authenticated;
create temporary table foreign_removal_result (result jsonb);
grant select, insert on foreign_removal_result to authenticated;
create temporary table first_pull_result (result jsonb);
grant select, insert on first_pull_result to authenticated;
create temporary table second_pull_result (result jsonb);
grant select, insert on second_pull_result to authenticated;
create temporary table stale_revision_removal_result (result jsonb);
grant select, insert on stale_revision_removal_result to authenticated;
create temporary table retryable_removal_result (result jsonb);
grant select, insert on retryable_removal_result to authenticated;
create temporary table retried_removal_result (result jsonb);
grant select, insert on retried_removal_result to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000101',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'delete',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from removal_result),
    'accepted', 'owner can remove an empty Cloud book'
);
select ok(
    nullif((select result->0->'payload'->>'deleted_at' from removal_result), '') is not null,
    'accepted removal returns the deletion timestamp'
);
select is(
    (select revision from public.cloud_books
     where id = '22000000-0000-0000-0000-000000000101'),
    2::bigint, 'removal advances the Cloud book revision'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book_deleted'
       and operation = 'delete'
       and payload->>'deleted_at' is not null),
    1, 'removal emits one sync tombstone'
);
select is(
    (select count(*)::integer from public.reading_positions
     where cloud_book_id = '22000000-0000-0000-0000-000000000101'),
    0, 'accepted removal deletes the server reading position'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into duplicate_removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000101',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'delete',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from duplicate_removal_result),
    'accepted', 'replaying a delete mutation returns its accepted response'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book_deleted'
       and operation = 'delete'),
    1, 'replaying the mutation does not emit another tombstone'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000102';
set local request.jwt.claim.role = 'authenticated';
insert into foreign_removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000109',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'delete',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from foreign_removal_result),
    'rejected', 'another account cannot remove the book'
);
select is(
    (select result->0->>'reason' from foreign_removal_result),
    'cloud_book_not_owned', 'cross-account removal reports an ownership rejection'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book_deleted'
       and operation = 'delete'),
    1, 'cross-account rejection does not add a tombstone'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into stale_upsert_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000102',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'upsert',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'content_hash', repeat('a', 64),
            'content_hash_algorithm', 'sha-256-v1',
            'title', 'Stale title',
            'format', 'epub'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from stale_upsert_result),
    'conflict', 'a stale upsert cannot overwrite a removed book'
);
select is(
    (select result->0->>'reason' from stale_upsert_result),
    'book_deleted', 'the stale upsert conflict identifies the tombstone'
);
select ok(
    nullif((select result->0->'payload'->>'deleted_at' from stale_upsert_result), '')
        is not null,
    'the conflict payload preserves the deletion timestamp'
);
select is(
    (select title || ':' || revision::text || ':' || (deleted_at is not null)::text
     from public.cloud_books
     where id = '22000000-0000-0000-0000-000000000101'),
    'Original title:2:true', 'the stale upsert leaves the server tombstone unchanged'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into stale_position_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000104',
        'entity_type', 'reading_position',
        'entity_id', 'test-book',
        'operation', 'upsert',
        'base_revision', 5,
        'payload', jsonb_build_object(
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'position', jsonb_build_object('book_uuid', 'test-book')
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from stale_position_result),
    'conflict', 'a stale position write conflicts with the book tombstone'
);
select is(
    (select result->0->>'reason' from stale_position_result),
    'book_deleted', 'a stale position write identifies the book tombstone'
);
select ok(
    (select (result->0->'payload'->>'is_deleted')::boolean from stale_position_result),
    'the stale position conflict carries a deletion snapshot'
);
select is(
    (select result->0->'payload'->>'remote_revision' from stale_position_result),
    '6', 'the stale position conflict carries the tombstone revision'
);
select is(
    (select count(*)::integer from public.reading_positions
     where cloud_book_id = '22000000-0000-0000-0000-000000000101'),
    0, 'a rejected stale position write leaves progress deleted'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into current_revision_upsert_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000103',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'upsert',
        'base_revision', 2,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'content_hash', repeat('a', 64),
            'content_hash_algorithm', 'sha-256-v1',
            'title', 'Retry title',
            'format', 'epub'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from current_revision_upsert_result),
    'conflict', 'even a retried upsert cannot resurrect a deleted book'
);
select is(
    (select result->0->>'reason' from current_revision_upsert_result),
    'book_deleted', 'a retry still receives the tombstone conflict reason'
);
select ok(
    nullif((select result->0->'payload'->>'deleted_at'
            from current_revision_upsert_result), '') is not null,
    'a retried upsert receives the full tombstone payload'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book'
       and operation = 'upsert'),
    0, 'rejected upserts do not publish resurrection changes'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into stale_intentional_reimport_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000106',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'upsert',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'content_hash', repeat('a', 64),
            'content_hash_algorithm', 'sha-256-v1',
            'title', 'Stale intentional reimport',
            'format', 'epub',
            'intentional_reimport', true
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from stale_intentional_reimport_result),
    'conflict', 'an explicit reimport with a stale revision cannot resurrect the book'
);
select is(
    (select result->0->>'reason' from stale_intentional_reimport_result),
    'book_deleted', 'a stale explicit reimport retains the tombstone conflict'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into first_pull_result
select public.pull_sync_changes(0, 100);
insert into second_pull_result
select public.pull_sync_changes(
    (select (result->>'next_cursor')::bigint from first_pull_result),
    1
);
reset role;

select is(
    (select jsonb_array_length(result->'changes')::integer from first_pull_result),
    2, 'the first pull delivers position and book tombstones'
);
select is(
    (select count(*)::integer
     from jsonb_array_elements((select result->'changes' from first_pull_result)) as changes(change)
     where change->>'entity_type' = 'library_book_deleted'),
    1, 'the pull identifies the deleted-book event'
);
select ok(
    nullif((select change->'payload'->>'deleted_at'
            from first_pull_result,
                 jsonb_array_elements(result->'changes') as changes(change)
            where change->>'entity_type' = 'library_book_deleted'), '') is not null,
    'the pulled event carries its deletion timestamp'
);
select is(
    (select (result->>'next_cursor')::bigint from first_pull_result),
    (select change_id from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book_deleted'),
    'the pull cursor advances through the tombstone'
);
select is(
    (select result->>'has_more' from first_pull_result),
    'false', 'the first pull reports no additional changes'
);
select is(
    (select jsonb_array_length(result->'changes')::integer from second_pull_result),
    0, 'pulling from the returned cursor does not redeliver the tombstone'
);
select is(
    (select (result->>'next_cursor')::bigint from second_pull_result),
    (select (result->>'next_cursor')::bigint from first_pull_result),
    'an empty follow-up pull retains the cursor'
);
select is(
    (select result->>'has_more' from second_pull_result),
    'false', 'the follow-up pull remains complete'
);
select is(
    (select count(*)::integer
     from jsonb_array_elements((select result->'changes' from first_pull_result)) as changes(change)
     where change->>'entity_type' = 'reading_position'),
    1, 'the pull includes one reading-position tombstone'
);
select is(
    (select change->>'operation'
     from first_pull_result,
          jsonb_array_elements(result->'changes') as changes(change)
     where change->>'entity_type' = 'reading_position'),
    'delete', 'the reading-position feed marks the position deleted'
);
select ok(
    nullif((select change->'payload'->>'deleted_at'
            from first_pull_result,
                 jsonb_array_elements(result->'changes') as changes(change)
            where change->>'entity_type' = 'reading_position'), '') is not null,
    'the position tombstone carries the book deletion timestamp'
);
select is(
    (select (change->>'revision')::bigint
     from first_pull_result,
          jsonb_array_elements(result->'changes') as changes(change)
     where change->>'entity_type' = 'reading_position'),
    6::bigint, 'the position tombstone advances its position revision'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into intentional_reimport_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000105',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('a', 64),
        'operation', 'upsert',
        'base_revision', 2,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'content_hash', repeat('a', 64),
            'content_hash_algorithm', 'sha-256-v1',
            'title', 'Intentionally reimported title',
            'format', 'epub',
            'intentional_reimport', true
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from intentional_reimport_result),
    'accepted', 'an explicitly requested reimport resurrects a tombstoned book'
);
select is(
    (select result->0->>'cloud_book_id' from intentional_reimport_result),
    '22000000-0000-0000-0000-000000000101',
    'reimport retains the original Cloud book ID'
);
select is(
    (select result->0->>'revision' from intentional_reimport_result),
    '3', 'reimport advances the tombstone revision'
);
select is(
    (select (deleted_at is null)::text || ':' || revision::text || ':' || title
     from public.cloud_books
     where id = '22000000-0000-0000-0000-000000000101'),
    'true:3:Intentionally reimported title',
    'reimport clears the tombstone and updates metadata'
);
select is(
    (select count(*)::integer from public.cloud_books
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and content_hash = repeat('a', 64)),
    1, 'reimport does not create a duplicate canonical identity'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and entity_type = 'library_book'
       and operation = 'upsert'
       and payload->>'title' = 'Intentionally reimported title'),
    1, 'reimport publishes the resurrected metadata'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into post_reimport_position_result
select public.push_sync_changes(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000107',
        'entity_type', 'reading_position',
        'entity_id', 'test-book',
        'operation', 'upsert',
        'base_revision', 6,
        'payload', jsonb_build_object(
            'cloud_book_id', '22000000-0000-0000-0000-000000000101',
            'library_book_id', 'sha-256-v1:' || repeat('a', 64),
            'position', jsonb_build_object('book_uuid', 'test-book')
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from post_reimport_position_result),
    'accepted', 'position sync resumes after an intentional book reimport'
);
select is(
    (select result->0->>'revision' from post_reimport_position_result),
    '7', 'position revisions continue after the deletion tombstone'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into stale_revision_removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000110',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('b', 64),
        'operation', 'delete',
        'base_revision', 0,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('b', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000102'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from stale_revision_removal_result),
    'conflict', 'a stale book-removal revision remains a conflict even while files exist'
);
select is(
    (select result->0->>'reason' from stale_revision_removal_result),
    'stale_revision', 'the stale book-removal conflict identifies its revision mismatch'
);

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into retryable_removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000111',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('b', 64),
        'operation', 'delete',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('b', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000102'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from retryable_removal_result),
    'retryable', 'book removal with Cloud files returns a retryable response'
);
select is(
    (select result->0->>'retry_after_ms' from retryable_removal_result),
    '30000', 'the retryable book-removal response includes a 30-second retry delay'
);
select is(
    (select result->0->>'reason' from retryable_removal_result),
    'book_files_must_be_removed_first', 'the retryable response identifies the remaining files'
);
select is(
    (select count(*)::integer from public.sync_mutations
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and mutation_id = '32000000-0000-0000-0000-000000000111'),
    0, 'the retryable response is not cached as a sync mutation'
);

delete from public.cloud_book_files
where id = '23000000-0000-0000-0000-000000000102';

set local role authenticated;
set local request.jwt.claim.sub = '12000000-0000-0000-0000-000000000101';
set local request.jwt.claim.role = 'authenticated';
insert into retried_removal_result
select public.delete_cloud_books(
    jsonb_build_array(jsonb_build_object(
        'mutation_id', '32000000-0000-0000-0000-000000000111',
        'entity_type', 'library_book',
        'entity_id', 'sha-256-v1:' || repeat('b', 64),
        'operation', 'delete',
        'base_revision', 1,
        'payload', jsonb_build_object(
            'library_book_id', 'sha-256-v1:' || repeat('b', 64),
            'cloud_book_id', '22000000-0000-0000-0000-000000000102'
        )
    )),
    0
);
reset role;

select is(
    (select result->0->>'status' from retried_removal_result),
    'accepted', 'the same mutation is reevaluated and accepted after the files are removed'
);
select is(
    (select result->0->>'mutation_id' from retried_removal_result),
    '32000000-0000-0000-0000-000000000111', 'the accepted retry retains the original mutation ID'
);
select is(
    (select count(*)::integer from public.sync_mutations
     where cloud_user_id = '12000000-0000-0000-0000-000000000101'
       and mutation_id = '32000000-0000-0000-0000-000000000111'
       and response->>'status' = 'accepted'),
    1, 'the accepted retry is cached after it succeeds'
);
select is(
    (select (deleted_at is not null)::text || ':' || revision::text
     from public.cloud_books
     where id = '22000000-0000-0000-0000-000000000102'),
    'true:2', 'the retried removal tombstones the Cloud book'
);

select * from finish();
rollback;
