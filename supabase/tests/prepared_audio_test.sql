-- Prepared chapter audio as one more cloud_book_files row (20261010000000).
--
-- NOTE: this file has never been executed. The machine it was written on has no
-- container runtime at all, so the local Supabase stack could not be started
-- and scripts/supabase/test.sh could not run. The owner must run it before the
-- migration is applied anywhere. See docs/tts-prepared-cloud.md.
begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(38);

-- Two accounts: a1 owns everything, a2 only ever tries to reach it.
insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'prepared-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000000',
     'authenticated', 'authenticated', 'prepared-b@example.invalid', '', now());

-- Uploads are allowlist-only (20261003000000); prepared audio uses the same gate.
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('10000000-0000-0000-0000-0000000000a1', 'uploads'),
    ('10000000-0000-0000-0000-0000000000a2', 'uploads');

insert into public.cloud_books (
    id, cloud_user_id, source_content_hash, source_content_hash_algorithm, title, format
) values
    -- Book one: backed up, so its chapters may be prepared.
    ('20000000-0000-0000-0000-0000000000a1', '10000000-0000-0000-0000-0000000000a1',
     repeat('1', 64), 'sha-256-v1', 'Backed up book', 'epub'),
    -- Book two: no file at all, so it may not hold prepared audio.
    ('20000000-0000-0000-0000-0000000000a2', '10000000-0000-0000-0000-0000000000a1',
     repeat('2', 64), 'sha-256-v1', 'Unbacked book', 'epub'),
    -- Book three: backed up, kept aside for the takedown.
    ('20000000-0000-0000-0000-0000000000a3', '10000000-0000-0000-0000-0000000000a1',
     repeat('7', 64), 'sha-256-v1', 'Taken down book', 'epub'),
    -- Book four: backed up, kept aside for the block-list hit.
    ('20000000-0000-0000-0000-0000000000a4', '10000000-0000-0000-0000-0000000000a1',
     repeat('6', 64), 'sha-256-v1', 'Blocked book', 'epub');

insert into public.cloud_book_files (
    id, cloud_book_id, cloud_user_id, storage_path, relative_path, file_name,
    size_bytes, content_hash, content_hash_algorithm, media_type, status
) values
    ('30000000-0000-0000-0000-0000000000a1', '20000000-0000-0000-0000-0000000000a1',
     '10000000-0000-0000-0000-0000000000a1',
     'users/10000000-0000-0000-0000-0000000000a1/books/20000000-0000-0000-0000-0000000000a1/30000000-0000-0000-0000-0000000000a1',
     '', 'book.epub', 1000, repeat('3', 64), 'sha-256-v1', 'ebook', 'available'),
    -- Book three's backup and one prepared chapter of it, both available.
    ('30000000-0000-0000-0000-0000000000b1', '20000000-0000-0000-0000-0000000000a3',
     '10000000-0000-0000-0000-0000000000a1',
     'users/10000000-0000-0000-0000-0000000000a1/books/20000000-0000-0000-0000-0000000000a3/30000000-0000-0000-0000-0000000000b1',
     '', 'third.epub', 10, repeat('8', 64), 'sha-256-v1', 'ebook', 'available'),
    ('30000000-0000-0000-0000-0000000000b2', '20000000-0000-0000-0000-0000000000a3',
     '10000000-0000-0000-0000-0000000000a1',
     'users/10000000-0000-0000-0000-0000000000a1/books/20000000-0000-0000-0000-0000000000a3/30000000-0000-0000-0000-0000000000b2',
     'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
     'chapter.zip', 20, repeat('9', 64), 'sha-256-v1', 'tts_prepared_audio', 'available'),
    -- Book four's backup and one prepared chapter of it, for the block-list hit.
    ('30000000-0000-0000-0000-0000000000d1', '20000000-0000-0000-0000-0000000000a4',
     '10000000-0000-0000-0000-0000000000a1',
     'users/10000000-0000-0000-0000-0000000000a1/books/20000000-0000-0000-0000-0000000000a4/30000000-0000-0000-0000-0000000000d1',
     '', 'fourth.epub', 40, repeat('ab', 32), 'sha-256-v1', 'ebook', 'available'),
    ('30000000-0000-0000-0000-0000000000c1', '20000000-0000-0000-0000-0000000000a4',
     '10000000-0000-0000-0000-0000000000a1',
     'users/10000000-0000-0000-0000-0000000000a1/books/20000000-0000-0000-0000-0000000000a4/30000000-0000-0000-0000-0000000000c1',
     'tts-prepared/' || repeat('9', 64) || '/' || repeat('b', 64) || '.zip',
     'chapter.zip', 30, repeat('e', 64), 'sha-256-v1', 'tts_prepared_audio', 'available');

insert into public.cloud_user_storage (cloud_user_id, quota_bytes, used_bytes)
values ('10000000-0000-0000-0000-0000000000a1', 209715200, 1100);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-0000000000a1';
set local request.jwt.claim.role = 'authenticated';

-- ---------------------------------------------------------------------------
-- Reserve: the new media type on the normal path
-- ---------------------------------------------------------------------------

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('4', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'a prepared chapter can be reserved for a book that is backed up'
);

select is(
    (select media_type from public.cloud_book_files
     where cloud_book_id = '20000000-0000-0000-0000-0000000000a1'
       and relative_path = 'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip'),
    'tts_prepared_audio', 'the reservation creates one more file row of the book'
);

select is(
    (select status from public.cloud_book_files
     where id = '30000000-0000-0000-0000-0000000000a1'),
    'available', 'the book ebook row is untouched beside it'
);

select is(
    (select reserved_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-0000000000a1'),
    4096::bigint, 'prepared audio reserves quota exactly like a book file'
);

-- A second chapter of the same book is a second slot, not a conflict.
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('c', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 2048, 'sha-256-v1', repeat('5', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'a second chapter of the same book gets its own slot'
);

-- The same chapter prepared with other settings is a different file.
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('a', 64) || '/' || repeat('d', 64) || '.zip',
        'chapter.zip', 2048, 'sha-256-v1', repeat('6', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'the same chapter with other settings is a different file'
);

-- ---------------------------------------------------------------------------
-- Reserve: the new rules
-- ---------------------------------------------------------------------------

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('e', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 67108865, 'sha-256-v1', repeat('a', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'file_too_large', 'a prepared chapter over 64 MiB is rejected'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('f', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 67108864, 'sha-256-v1', repeat('b', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'exactly 64 MiB is still accepted'
);

-- The never-empty relative path is the whole protection for older apps.
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio', '',
        'chapter.zip', 4096, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'invalid_upload_metadata',
    'prepared audio may never take the empty relative path an older app reads'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/chapter-one/voice.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'invalid_upload_metadata',
    'a relative path that is not two hashes is rejected, so no title can leak into it'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a2', 'tts_prepared_audio',
        'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'book_backup_unavailable',
    'prepared audio is refused for a book that has no backup in the cloud'
);

-- An ordinary book file is unaffected by any of the new rules.
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a2', 'ebook', '', 'other.epub',
        100, 'sha-256-v1', repeat('d', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'status'),
    'reserved', 'an ordinary book file is still reserved for a book with no files yet'
);

-- An upload_pending ebook is not a backup yet, so the audio still waits.
select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a2', 'tts_prepared_audio',
        'tts-prepared/' || repeat('a', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('c', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'book_backup_unavailable',
    'a book backup that is only reserved does not yet admit prepared audio'
);

-- ---------------------------------------------------------------------------
-- Quota
-- ---------------------------------------------------------------------------

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('1', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 209715200, 'sha-256-v1', repeat('f', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'quota_exceeded', 'a prepared chapter that does not fit the allowance is refused'
);

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('1', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 209715200, 'sha-256-v1', repeat('f', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'quota_bytes'),
    '209715200', 'the quota rejection carries the total bytes'
);

select ok(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a1', 'tts_prepared_audio',
        'tts-prepared/' || repeat('1', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 209715200, 'sha-256-v1', repeat('f', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    ) ? 'used_bytes'),
    'the quota rejection carries the used bytes'
);

-- ---------------------------------------------------------------------------
-- Finalize commits the bytes, as for a book file
-- ---------------------------------------------------------------------------

create temporary table prepared_upload as
select upload_id, cloud_book_file_id, storage_path
from public.cloud_book_uploads
where media_type = 'tts_prepared_audio' and size_bytes = 4096 and status = 'reserved';

reset role;
insert into storage.objects (bucket_id, name, metadata)
select 'book-files', storage_path, '{"size":4096}'::jsonb from prepared_upload;
set local role authenticated;

select is(
    (public.finalize_book_upload(
        (select upload_id from prepared_upload), 4096, repeat('4', 64)
    )->>'status'),
    'available', 'a prepared chapter finalizes through the existing path'
);

select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-0000000000a1'),
    5196::bigint, 'finalizing commits the prepared chapter bytes into used bytes'
);

-- ---------------------------------------------------------------------------
-- The storage usage breakdown
-- ---------------------------------------------------------------------------

select is(
    (public.get_storage_usage()->>'prepared_audio_bytes'),
    '4146', 'storage usage reports the bytes used by prepared audio'
);

select is(
    (public.get_storage_usage()->>'books_bytes'),
    '1050', 'storage usage reports the bytes used by books'
);

select ok(
    (public.get_storage_usage() ?& array[
        'used_bytes', 'reserved_bytes', 'quota_bytes', 'available_bytes', 'updated_at'
    ]),
    'the existing storage usage response shape is unchanged'
);

select is(
    ((public.get_storage_usage()->>'books_bytes')::bigint
        + (public.get_storage_usage()->>'prepared_audio_bytes')::bigint),
    (public.get_storage_usage()->>'used_bytes')::bigint,
    'the breakdown always adds up to the used bytes the account is charged'
);

-- ---------------------------------------------------------------------------
-- Row level security
-- ---------------------------------------------------------------------------

set local request.jwt.claim.sub = '10000000-0000-0000-0000-0000000000a2';

select is(
    (select count(*)::integer from public.cloud_book_files
     where media_type = 'tts_prepared_audio'),
    0, 'another account cannot see prepared audio rows'
);

select is(
    (public.create_book_download(
        (select cloud_book_file_id from prepared_upload)
    )->>'reason'),
    'cloud_book_file_not_owned', 'another account cannot download prepared audio'
);

select is(
    (public.delete_book_file(
        (select cloud_book_file_id from prepared_upload)
    )->>'reason'),
    'cloud_book_file_not_owned', 'another account cannot delete prepared audio'
);

set local request.jwt.claim.sub = '10000000-0000-0000-0000-0000000000a1';

select is(
    (public.create_book_download(
        (select cloud_book_file_id from prepared_upload)
    )->>'status'),
    'available', 'the owner can download their own prepared audio'
);

-- ---------------------------------------------------------------------------
-- Cascade: audio never outlives its book
-- ---------------------------------------------------------------------------

select is(
    (public.delete_book_file('30000000-0000-0000-0000-0000000000a1')->>'status'),
    'deleting', 'deleting the book backup starts the usual deletion handshake'
);

select is(
    (select count(*)::integer from public.cloud_book_files
     where cloud_book_id = '20000000-0000-0000-0000-0000000000a1'
       and media_type = 'tts_prepared_audio'
       and status <> 'deleting'),
    0, 'deleting the book backup takes every prepared chapter of it too'
);

select is(
    (select count(*)::integer from public.cloud_book_uploads u
     join public.cloud_book_files f on f.id = u.cloud_book_file_id
     where u.media_type = 'tts_prepared_audio'
       and u.status = 'reserved'
       and f.cloud_book_id = '20000000-0000-0000-0000-0000000000a1'),
    0, 'a prepared chapter still uploading is cancelled with its book'
);

select is(
    (select status from public.cloud_book_files
     where id = '30000000-0000-0000-0000-0000000000b2'),
    'available', 'another book prepared audio is not touched'
);

-- The bytes come back through the same handshake the ebook uses.
reset role;
delete from storage.objects
where bucket_id = 'book-files'
  and name = (select storage_path from prepared_upload);
set local role authenticated;

select is(
    (public.complete_book_file_deletion(
        (select cloud_book_file_id from prepared_upload)
    )->>'status'),
    'removed', 'the prepared chapter row is removed once its object is gone'
);

select is(
    (select used_bytes from public.cloud_user_storage
     where cloud_user_id = '10000000-0000-0000-0000-0000000000a1'),
    1100::bigint, 'completing the deletion releases the prepared chapter bytes'
);

-- A takedown of the book reaches its prepared audio as well.
reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';

select is(
    (public.admin_takedown_book_file(
        '30000000-0000-0000-0000-0000000000b1', 'test takedown', 'test'
    )->>'status'),
    'deleting', 'an admin takedown starts on the book file'
);

reset role;
select is(
    (select status from public.cloud_book_files
     where id = '30000000-0000-0000-0000-0000000000b2'),
    'deleting', 'an admin takedown of the book takes its prepared audio with it'
);

-- A block-list hit reaches prepared audio even though the block deletes nothing.
reset role;
set local role service_role;
set local request.jwt.claim.role = 'service_role';

select is(
    (public.admin_block_content_hash(
        'sha-256-v1', repeat('ab', 32), 'test block', 'test'
    )->>'status'),
    'blocked', 'blocking the book content hash succeeds'
);

reset role;
select is(
    (select status from public.cloud_book_files
     where id = '30000000-0000-0000-0000-0000000000c1'),
    'deleting',
    'blocking the book content hash takes its prepared audio, which has another hash'
);

-- Reserving it again after the block is refused, so the audio cannot come back.
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-0000000000a1';
set local request.jwt.claim.role = 'authenticated';

select is(
    (public.reserve_book_upload(
        '20000000-0000-0000-0000-0000000000a4', 'tts_prepared_audio',
        'tts-prepared/' || repeat('2', 64) || '/' || repeat('b', 64) || '.zip',
        'chapter.zip', 4096, 'sha-256-v1', repeat('1', 64),
        '{"attested_at":"2026-10-10T00:00:00Z","tos_version":"test","attestation_version":"test"}'::jsonb
    )->>'reason'),
    'content_blocked',
    'prepared audio cannot be re-uploaded for a book whose content is blocked'
);

-- Account deletion removes prepared audio rows with everything else.
reset role;
delete from auth.users where id = '10000000-0000-0000-0000-0000000000a1';

select is(
    (select count(*)::integer from public.cloud_book_files
     where media_type = 'tts_prepared_audio'),
    0, 'deleting the account removes every prepared audio row'
);

select * from finish();
rollback;
