-- Uploads are open to every signed-in account (owner's decision 1 of 2026-10-11).
--
-- Before this work, 'uploads' was allow-list only, like 'recap'. The owner has
-- opened it: anyone with a Parrot Cloud account may back up. Nothing else moves
-- — 'recap' stays allow-list only, and the quota, the per-file size limits and
-- the content block-list all still apply exactly as before.
--
-- Written before the migration 20261011000000 and seen to fail.

begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(17);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    -- D has no allow-list row at all: the account this decision is about.
    ('18000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'open-uploads-d@example.invalid', '', now()),
    -- E has a recap row, to show the two features are decided separately.
    ('18000000-0000-0000-0000-00000000000e', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'open-uploads-e@example.invalid', '', now());

insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values ('18000000-0000-0000-0000-00000000000e', 'recap');

insert into public.cloud_books (id, cloud_user_id, title, format)
values
    ('28000000-0000-0000-0000-00000000000d', '18000000-0000-0000-0000-00000000000d', 'D', 'epub'),
    ('28000000-0000-0000-0000-00000000000e', '18000000-0000-0000-0000-00000000000e', 'E', 'epub');

insert into public.cloud_user_storage (cloud_user_id, quota_bytes)
values ('18000000-0000-0000-0000-00000000000d', 209715200);

create temporary table attestation as
select '{"attested_at":"2026-10-11T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    as value;
grant select on attestation to authenticated;

-- ---------------------------------------------------------------------------
-- A signed-in account with no allow-list row may upload
-- ---------------------------------------------------------------------------

set local role authenticated;
set local request.jwt.claim.sub = '18000000-0000-0000-0000-00000000000d';
set local request.jwt.claim.role = 'authenticated';

select is(
    public.cloud_feature_enabled('uploads'),
    true,
    'uploads is enabled for a signed-in account with no allow-list row'
);

select is(
    public.get_cloud_feature_access(),
    '{"uploads": true, "recap": false}'::jsonb,
    'feature access reports uploads allowed and recap not, with no allow-list row'
);

create temporary table open_upload as
select public.reserve_book_upload(
    '28000000-0000-0000-0000-00000000000d', 'ebook', '', 'd.epub',
    1024, 'sha-256-v1', repeat('a', 64), (select value from attestation)
) as result;

select is(
    (select result->>'status' from open_upload),
    'reserved',
    'an account with no allow-list row may reserve an ebook upload'
);

select isnt(
    (select result->>'upload_id' from open_upload),
    null,
    'the reservation carries an upload id'
);

-- And it can be finalized, so the whole handshake is open, not just reserve.
reset role;
insert into storage.objects (bucket_id, name, metadata)
select 'book-files', u.storage_path, '{"size":1024}'::jsonb
from public.cloud_book_uploads u
where u.upload_id = (select (result->>'upload_id')::uuid from open_upload);
set local role authenticated;

select is(
    public.finalize_book_upload(
        (select (result->>'upload_id')::uuid from open_upload), 1024, repeat('a', 64)
    )->>'status',
    'available',
    'finalize succeeds for an account with no allow-list row'
);

-- Prepared audio rides the same gate, so it is open too.
select is(
    (public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000d', 'tts_prepared_audio',
        'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('c', 64), (select value from attestation)
    )->>'status'),
    'reserved',
    'prepared audio may be reserved with no allow-list row, once the book is backed up'
);

-- ---------------------------------------------------------------------------
-- An anonymous caller may not
-- ---------------------------------------------------------------------------

reset role;
set local role anon;
set local request.jwt.claim.sub = '';
set local request.jwt.claim.role = 'anon';

select throws_ok(
    $$select public.cloud_feature_enabled('uploads')$$,
    '42501',
    null,
    'anon cannot even ask whether uploads are enabled'
);

select throws_ok(
    $$select public.get_cloud_feature_access()$$,
    '42501',
    null,
    'anon cannot read feature access'
);

select throws_ok(
    $$select public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000d', 'ebook', '', 'anon.epub',
        10, 'sha-256-v1', repeat('e', 64), '{}'::jsonb
    )$$,
    '42501',
    null,
    'anon cannot reserve an upload'
);

-- Signed in but with no subject is not signed in either.
reset role;
set local role authenticated;
set local request.jwt.claim.sub = '';
set local request.jwt.claim.role = 'authenticated';

select is(
    public.cloud_feature_enabled('uploads'),
    false,
    'uploads is not enabled when there is no account behind the call'
);

-- ---------------------------------------------------------------------------
-- recap is still allow-list only
-- ---------------------------------------------------------------------------

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '18000000-0000-0000-0000-00000000000d';
set local request.jwt.claim.role = 'authenticated';

select is(
    public.cloud_feature_enabled('recap'),
    false,
    'recap is still refused to an account with no allow-list row'
);

set local request.jwt.claim.sub = '18000000-0000-0000-0000-00000000000e';

select is(
    public.cloud_feature_enabled('recap'),
    true,
    'recap is still granted by an allow-list row'
);

select is(
    public.get_cloud_feature_access(),
    '{"uploads": true, "recap": true}'::jsonb,
    'an account with only a recap row now gets uploads as well'
);

-- ---------------------------------------------------------------------------
-- The quota, the size limits and the block-list still apply
-- ---------------------------------------------------------------------------

set local request.jwt.claim.sub = '18000000-0000-0000-0000-00000000000d';

reset role;
update public.cloud_user_storage
   set quota_bytes = 20971520
 where cloud_user_id = '18000000-0000-0000-0000-00000000000d';
set local role authenticated;

select is(
    (public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000d', 'tts_prepared_audio',
        'tts-prepared/' || repeat('1', 64) || '/' || repeat('2', 64) || '.zip',
        'chapter.zip', 33554432, 'sha-256-v1', repeat('f', 64), (select value from attestation)
    )->>'reason'),
    'quota_exceeded',
    'the allowance still applies to an account with no allow-list row'
);

reset role;
update public.cloud_user_storage
   set quota_bytes = 209715200
 where cloud_user_id = '18000000-0000-0000-0000-00000000000d';
set local role authenticated;

select is(
    (public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000d', 'tts_prepared_audio',
        'tts-prepared/' || repeat('3', 64) || '/' || repeat('4', 64) || '.zip',
        'chapter.zip', 67108865, 'sha-256-v1', repeat('f', 64), (select value from attestation)
    )->>'reason'),
    'file_too_large',
    'the 64 MiB per-chapter limit still applies to an account with no allow-list row'
);

-- The block-list: a hash on it is refused even though uploads are now open.
reset role;
insert into public.cloud_content_blocklist
    (content_hash_algorithm, content_hash, reason, created_by)
values ('sha-256-v1', repeat('9', 64), 'test', 'open-uploads-test');
set local role authenticated;

select is(
    (public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000d', 'ebook', 'blocked', 'blocked.epub',
        1024, 'sha-256-v1', repeat('9', 64), (select value from attestation)
    )->>'reason'),
    'content_blocked',
    'the block-list still applies to an account with no allow-list row'
);

-- And one account still cannot touch another's book.
select is(
    (public.reserve_book_upload(
        '28000000-0000-0000-0000-00000000000e', 'ebook', 'other', 'other.epub',
        1024, 'sha-256-v1', repeat('8', 64), (select value from attestation)
    )->>'reason'),
    'cloud_book_not_owned',
    'opening uploads does not let one account upload into another account book'
);

select finish();
rollback;
