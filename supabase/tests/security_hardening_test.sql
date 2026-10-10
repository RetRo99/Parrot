begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(56);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('17000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'hardening-a@example.invalid', '', now()),
    ('17000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'hardening-b@example.invalid', '', now()),
    ('17000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'hardening-c@example.invalid', '', now());

-- A may upload and use recaps, B may only use recaps, C may do neither.
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('17000000-0000-0000-0000-000000000001', 'uploads'),
    ('17000000-0000-0000-0000-000000000001', 'recap'),
    ('17000000-0000-0000-0000-000000000002', 'recap');

insert into public.cloud_books (id, cloud_user_id, title, format)
values
    ('27000000-0000-0000-0000-000000000001', '17000000-0000-0000-0000-000000000001', 'A', 'epub'),
    ('27000000-0000-0000-0000-000000000003', '17000000-0000-0000-0000-000000000003', 'C', 'epub');
insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('17000000-0000-0000-0000-000000000001', 1000000000);

create temporary table attestation as
select '{"attested_at":"2026-10-03T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    as value;
grant select on attestation to authenticated;

-- Access control on the new tables and functions.
select ok(
    (select relrowsecurity from pg_class where oid = 'public.cloud_feature_allowlist'::regclass)
    and (select relrowsecurity from pg_class where oid = 'public.recap_settings'::regclass)
    and (select relrowsecurity from pg_class where oid = 'public.recap_global_usage'::regclass),
    'the new tables have RLS enabled'
);
select is(
    (select count(*)::integer from pg_policies
     where schemaname = 'public'
       and tablename in ('cloud_feature_allowlist', 'recap_settings', 'recap_global_usage')),
    0, 'the new tables have no client policies'
);
select ok(
    not has_table_privilege('authenticated', 'public.cloud_feature_allowlist', 'select')
    and not has_table_privilege('authenticated', 'public.cloud_feature_allowlist', 'insert')
    and not has_table_privilege('anon', 'public.cloud_feature_allowlist', 'select')
    and not has_table_privilege('authenticated', 'public.recap_settings', 'update')
    and not has_table_privilege('authenticated', 'public.recap_global_usage', 'select'),
    'clients cannot read or edit the allowlist or recap settings'
);
select ok(
    not has_function_privilege('authenticated', 'public.guard_cloud_account_deletion_write()', 'execute'),
    'clients cannot execute the deletion trigger function'
);
select ok(
    has_function_privilege('authenticated', 'public.cloud_account_deletion_in_progress()', 'execute'),
    'Storage RLS can still call cloud_account_deletion_in_progress as the client'
);
select ok(
    not has_table_privilege('authenticated', 'public.cloud_books', 'truncate')
    and not has_table_privilege('anon', 'public.cloud_books', 'trigger')
    and not has_table_privilege('authenticated', 'public.cloud_feature_allowlist', 'references'),
    'clients have no truncate, trigger or references privileges'
);
select ok(
    has_function_privilege('service_role', 'public.purge_cloud_retention(integer)', 'execute')
    and not has_function_privilege('authenticated', 'public.purge_cloud_retention(integer)', 'execute')
    and not has_function_privilege('anon', 'public.purge_cloud_retention(integer)', 'execute'),
    'only the service role can run the retention purge'
);

-- Quota default and bucket limits.
select is(
    (select column_default from information_schema.columns
     where table_schema = 'public' and table_name = 'cloud_user_storage'
       and column_name = 'quota_bytes'),
    '209715200', 'new storage rows default to 200 MiB'
);
select is(
    (select file_size_limit from storage.buckets where id = 'book-files'),
    2147483648::bigint, 'the bucket caps a single object at 2 GiB'
);
select ok(
    (select allowed_mime_types @> array['application/epub+zip', 'audio/mp4', 'application/octet-stream']
     from storage.buckets where id = 'book-files'),
    'the bucket allows the MIME types the client sends'
);

-- Upload allowlist gate.
set local role authenticated;
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000003';
set local request.jwt.claim.role = 'authenticated';
-- Since 20261011000000 this account gets past the gate: the refusal it gets is
-- now about the file, not about the allowlist. Asserted with a zero-byte file so
-- the reservation is refused and leaves no sync change behind for this account,
-- which the retention-purge assertion at the end of this file counts. A real
-- reservation and finalize by an account with no allowlist row is asserted in
-- supabase/tests/open_uploads_test.sql.
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000003', 'ebook', '', 'c.epub',
        0, 'sha-256-v1', repeat('a', 64), (select value from attestation)
    )->>'reason',
    'invalid_upload_metadata',
    'an account with no allowlist row is no longer stopped by the gate (20261011000000)'
);
select is(
    public.get_cloud_feature_access(),
    '{"uploads": true, "recap": false}'::jsonb,
    'feature access reports uploads open and recap still allowlisted (20261011000000)'
);
select is(
    (public.get_storage_usage()->>'quota_bytes')::bigint,
    209715200::bigint, 'a new account starts with the 200 MiB quota'
);

set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000001';
select is(
    public.get_cloud_feature_access(),
    '{"uploads": true, "recap": true}'::jsonb,
    'feature access reports the allowlisted features'
);

-- Upload metadata validation.
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'zero', 'zero.epub',
        0, 'sha-256-v1', repeat('a', 64), (select value from attestation)
    )->>'reason',
    'invalid_upload_metadata', 'an empty file cannot be reserved'
);
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'algo', 'algo.epub',
        10, 'sha256', repeat('a', 64), (select value from attestation)
    )->>'reason',
    'invalid_upload_metadata', 'an unknown hash algorithm is rejected'
);
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'short', 'short.epub',
        10, 'sha-256-v1', 'abc', (select value from attestation)
    )->>'reason',
    'invalid_upload_metadata', 'a hash that is not 64 hex digits is rejected'
);
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'long', repeat('n', 1025),
        10, 'sha-256-v1', repeat('a', 64), (select value from attestation)
    )->>'reason',
    'invalid_upload_metadata', 'an oversized file name is rejected'
);

create temporary table upper_upload as
select public.reserve_book_upload(
    '27000000-0000-0000-0000-000000000001', 'ebook', 'upper', 'upper.epub',
    10, ' SHA-256-V1 ', repeat('AB', 32), (select value from attestation)
) as result;
select is(
    (select result->>'status' from upper_upload),
    'reserved', 'an upper-case hash is accepted'
);
reset role;
select is(
    (select content_hash_algorithm || ':' || content_hash from public.cloud_book_uploads
     where upload_id = (select (result->>'upload_id')::uuid from upper_upload)),
    'sha-256-v1:' || repeat('ab', 32), 'the stored hash and algorithm are normalized'
);
select ok(
    (select expires_at between now() + interval '719 minutes' and now() + interval '721 minutes'
     from public.cloud_book_uploads
     where upload_id = (select (result->>'upload_id')::uuid from upper_upload)),
    'reservations expire after twelve hours'
);

set local role authenticated;
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000001';
set local request.jwt.claim.role = 'authenticated';
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'huge', 'huge.m4b',
        2147483649, 'sha-256-v1', repeat('a', 64), (select value from attestation)
    )->>'reason',
    'file_too_large', 'a file over the 2 GiB bucket limit cannot be reserved'
);

-- Storage refuses an object larger than its reservation when it knows the
-- size, and the client's upper-case hash still finalizes.
select throws_ok(
    $$insert into storage.objects (bucket_id, name, metadata)
      select 'book-files', u.storage_path, '{"size":11}'::jsonb
      from public.cloud_book_uploads u
      where u.upload_id = (select (result->>'upload_id')::uuid from upper_upload)$$,
    '42501', null, 'an object larger than its reservation is refused'
);
select lives_ok(
    $$insert into storage.objects (bucket_id, name, metadata)
      select 'book-files', u.storage_path, '{"size":10}'::jsonb
      from public.cloud_book_uploads u
      where u.upload_id = (select (result->>'upload_id')::uuid from upper_upload)$$,
    'an object of the reserved size is stored'
);
select is(
    public.finalize_book_upload(
        (select (result->>'upload_id')::uuid from upper_upload), 10, repeat('AB', 32)
    )->>'status',
    'available', 'an upper-case hash reserved and finalized as sent succeeds'
);

-- Removing the upload allowlist row no longer stops anything: since
-- 20261011000000 uploads do not depend on it. Both halves of the old
-- revocation scenario are kept, asserting the new rule.
create temporary table revoked_upload as
select public.reserve_book_upload(
    '27000000-0000-0000-0000-000000000001', 'ebook', 'revoked', 'revoked.epub',
    10, 'sha-256-v1', repeat('d', 64), (select value from attestation)
) as result;
reset role;
delete from public.cloud_feature_allowlist
where cloud_user_id = '17000000-0000-0000-0000-000000000001' and feature = 'uploads';
set local role authenticated;
select lives_ok(
    $$insert into storage.objects (bucket_id, name, metadata)
      select 'book-files', u.storage_path, '{"size":10}'::jsonb
      from public.cloud_book_uploads u
      where u.upload_id = (select (result->>'upload_id')::uuid from revoked_upload)$$,
    'Storage still accepts writes with no uploads allowlist row (20261011000000)'
);
-- Finalize is no longer gated on the allowlist either. Asserted through the gate
-- the function consults rather than by calling it: any finalize call, successful
-- or refused, settles the reservation, and the pending-reservation cap below
-- needs this one still live. finalize succeeding for an account with no
-- allowlist row is asserted in supabase/tests/open_uploads_test.sql.
select is(
    public.cloud_feature_enabled('uploads'),
    true, 'uploads stay enabled with the allowlist row gone (20261011000000)'
);
reset role;
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values ('17000000-0000-0000-0000-000000000001', 'uploads');

-- Pending-reservation cap. "Back up all" reserves every file at once, so 30
-- in a row must pass; 199 more slots reach 200 and the 201st is refused.
set local role authenticated;
select is(
    (select count(*)::integer
     from generate_series(1, 29) as slot(n)
     where public.reserve_book_upload(
         '27000000-0000-0000-0000-000000000001', 'ebook', 'cap/' || slot.n, 'cap.epub',
         10, 'sha-256-v1', lpad(to_hex(slot.n), 64, '0'), (select value from attestation)
     )->>'status' = 'reserved'),
    29, 'a backup of 30 files reserves every file'
);
select is(
    (select count(*)::integer
     from generate_series(30, 199) as slot(n)
     where public.reserve_book_upload(
         '27000000-0000-0000-0000-000000000001', 'ebook', 'cap/' || slot.n, 'cap.epub',
         10, 'sha-256-v1', lpad(to_hex(slot.n), 64, '0'), (select value from attestation)
     )->>'status' = 'reserved'),
    170, 'reservations up to the cap succeed'
);
create temporary table capped_upload as
select public.reserve_book_upload(
    '27000000-0000-0000-0000-000000000001', 'ebook', 'cap/201', 'cap.epub',
    10, 'sha-256-v1', repeat('c', 64), (select value from attestation)
) as result;
select is(
    (select result->>'reason' from capped_upload),
    'too_many_pending_uploads', 'a 201st live reservation is refused'
);
select is(
    (select result->>'retry_after_ms' from capped_upload),
    '60000', 'the cap rejection carries a retry hint'
);
select is(
    public.reserve_book_upload(
        '27000000-0000-0000-0000-000000000001', 'ebook', 'revoked', 'revoked.epub',
        10, 'sha-256-v1', repeat('d', 64), (select value from attestation)
    )->>'upload_id',
    (select result->>'upload_id' from revoked_upload),
    'repeating a live reservation is not blocked by the cap'
);
reset role;

-- Table constraints catch writes that bypass the RPC.
select throws_ok(
    $$insert into public.cloud_book_uploads (
        cloud_book_id, cloud_user_id, cloud_book_file_id, media_type, file_name,
        size_bytes, content_hash, content_hash_algorithm, rights_attestation,
        storage_path, status, expires_at
    ) values (
        '27000000-0000-0000-0000-000000000001', '17000000-0000-0000-0000-000000000001',
        gen_random_uuid(), 'ebook', 'x.epub', 10, 'not-a-hash', 'sha-256-v1', '{}',
        'x', 'cancelled', now()
    )$$,
    '23514', null, 'the upload hash format is enforced by a constraint'
);
select throws_ok(
    $$insert into public.cloud_book_files (
        cloud_book_id, cloud_user_id, storage_path, file_name, size_bytes,
        content_hash, content_hash_algorithm, media_type, status
    ) values (
        '27000000-0000-0000-0000-000000000001', '17000000-0000-0000-0000-000000000001',
        'y', 'y.epub', 0, repeat('a', 64), 'sha-256-v1', 'ebook', 'upload_failed'
    )$$,
    '23514', null, 'empty files are refused by a constraint'
);
select ok(
    (select bool_and(convalidated) from pg_constraint
     where conname in (
         'cloud_book_files_hash_format_check',
         'cloud_book_uploads_hash_algorithm_check',
         'cloud_books_metadata_size_check'
     )),
    'constraints are validated when existing rows comply'
);

-- Recaps: allowlist gate and global daily cap.
-- Quota is now an owner-only internal algorithm called by durable admission.
update public.recap_settings set value = 2 where key = 'global_daily_limit';

reset role;
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000003';
set local request.jwt.claim.role = 'authenticated';
select is(public.consume_recap_quota(5), false, 'an account off the allowlist gets no recap');

set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000001';
select is(public.consume_recap_quota(5), true, 'the first global unit is granted');
select is(public.consume_recap_quota(5), true, 'the second global unit is granted');
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000002';
select is(public.consume_recap_quota(5), false, 'the global daily cap stops further recaps');
reset role;

select is(
    (select count from public.recap_global_usage where day = timezone('utc', now())::date),
    2, 'refused calls do not advance the global counter'
);
select is(
    (select count from public.recap_usage
     where user_id = '17000000-0000-0000-0000-000000000002'
       and day = timezone('utc', now())::date),
    0, 'a global refusal refunds the user unit'
);
select is(
    (select count from public.recap_usage
     where user_id = '17000000-0000-0000-0000-000000000003'),
    null, 'an account off the allowlist leaves no usage row'
);

update public.recap_settings set value = 0 where key = 'global_daily_limit';
delete from public.recap_global_usage;
reset role;
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000002';
set local request.jwt.claim.role = 'authenticated';
select is(public.consume_recap_quota(5), false, 'a zero global limit turns recaps off');
reset role;

-- A direct call can't raise its own limit past per_user_daily_limit.
update public.recap_settings set value = 500 where key = 'global_daily_limit';
update public.recap_settings set value = 1 where key = 'per_user_daily_limit';
reset role;
select is(public.consume_recap_quota(1000000), true, 'the clamped limit grants one unit');
select is(
    public.consume_recap_quota(1000000), false,
    'a caller-supplied limit cannot exceed per_user_daily_limit'
);

-- Sync payload limits.
set local role authenticated;
select throws_ok(
    $$select public.push_sync_changes(
        (select jsonb_agg(jsonb_build_object('mutation_id', gen_random_uuid()))
         from generate_series(1, 201)),
        0
    )$$,
    'P0001', 'sync_batch_too_large', 'more than 200 mutations are refused'
);
select throws_ok(
    $$select public.push_sync_changes(
        (select jsonb_agg(jsonb_build_object('pad', repeat('x', 6000)))
         from generate_series(1, 100)),
        0
    )$$,
    'P0001', 'sync_batch_too_large', 'batches over 512 KiB are refused'
);
select is(
    public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '57000000-0000-0000-0000-000000000001',
        'entity_type', 'library_book',
        'entity_id', '67000000-0000-4000-8000-000000000001',
        'operation', 'upsert',
        'payload', jsonb_build_object(
            'library_book_id', '67000000-0000-4000-8000-000000000001',
            'title', repeat('t', 9000)
        )
    )), 0)->0->>'reason',
    'payload_too_large', 'a single oversized mutation is rejected, not raised'
);
select is(
    public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '57000000-0000-0000-0000-000000000004',
        'entity_type', 'library_book',
        'entity_id', '67000000-0000-4000-8000-000000000004',
        'operation', 'upsert',
        'base_revision', repeat('9', 9000),
        'payload', jsonb_build_object('library_book_id', '67000000-0000-4000-8000-000000000004')
    )), 0)->0->>'reason',
    'payload_too_large', 'size is checked before any cast of an oversized mutation'
);
select is(
    public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '57000000-0000-0000-0000-000000000005',
        'entity_type', 'library_book',
        'entity_id', '67000000-0000-4000-8000-000000000005',
        'operation', 'upsert',
        'base_revision', 'abc',
        'payload', jsonb_build_object(
            'library_book_id', '67000000-0000-4000-8000-000000000005',
            'title', 'T'
        )
    )), 0)->0->>'reason',
    'invalid_base_revision', 'a malformed base revision is rejected, not raised'
);
select is(
    public.push_sync_changes('[{
        "mutation_id": "57000000-0000-0000-0000-000000000002",
        "entity_type": "library_book",
        "entity_id": "27000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {"library_book_id": "27000000-0000-0000-0000-000000000001", "title": "X"}
    }]'::jsonb, 0)->0->>'reason',
    'invalid_library_book_id', 'a foreign book id reads like an invalid one'
);
select is(
    public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '57000000-0000-0000-0000-000000000003',
        'entity_type', 'book_link_decision',
        'entity_id', 'k',
        'operation', 'upsert',
        'payload', jsonb_build_object(
            'pair_key', repeat('k', 1025),
            'decision', 'never',
            'decided_at', '2026-10-03T00:00:00Z'
        )
    )), 0)->0->>'reason',
    'invalid_link_decision', 'an oversized pair key is rejected'
);
reset role;

-- Retention purge keeps the newest change per entity.
insert into public.sync_changes (
    cloud_user_id, entity_type, entity_id, operation, payload, revision, created_at
) values
    ('17000000-0000-0000-0000-000000000003', 'library_book', 'old', 'upsert', '{}', 1, now() - interval '200 days'),
    ('17000000-0000-0000-0000-000000000003', 'library_book', 'old', 'upsert', '{}', 2, now() - interval '150 days'),
    ('17000000-0000-0000-0000-000000000003', 'library_book', 'new', 'upsert', '{}', 1, now() - interval '200 days'),
    ('17000000-0000-0000-0000-000000000003', 'library_book', 'new', 'upsert', '{}', 2, now());
insert into public.recap_usage (user_id, day, count)
values ('17000000-0000-0000-0000-000000000003', (now() - interval '120 days')::date, 1);

set local role authenticated;
set local request.jwt.claim.sub = '17000000-0000-0000-0000-000000000003';
set local request.jwt.claim.role = 'authenticated';
select throws_ok(
    'select public.purge_cloud_retention(90)',
    '42501', null, 'clients cannot run the retention purge'
);
reset role;

set local role service_role;
set local request.jwt.claim.role = 'service_role';
select throws_ok(
    'select public.purge_cloud_retention(7)',
    'P0001', 'retain_days must be at least 30', 'a too-short retention is refused'
);
select is(
    (public.purge_cloud_retention(90)->>'sync_changes_deleted')::integer,
    2, 'superseded changes older than the window are purged'
);
reset role;
select is(
    (select array_agg(entity_id || ':' || revision order by entity_id)
     from public.sync_changes
     where cloud_user_id = '17000000-0000-0000-0000-000000000003'),
    array['new:2', 'old:2'], 'the newest change per entity survives'
);

select * from finish();
rollback;
