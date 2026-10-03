begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(22);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000071', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'upload-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000072', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'upload-b@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000073', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'upload-c@example.invalid', '', now());
-- C is not allowlisted for recaps.
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('10000000-0000-0000-0000-000000000071', 'recap'),
    ('10000000-0000-0000-0000-000000000072', 'recap');

-- Access control.
select ok(
    (select relrowsecurity from pg_class where oid = 'public.recap_upload_parts'::regclass),
    'recap_upload_parts has RLS enabled'
);
select ok(
    not has_table_privilege('authenticated', 'public.recap_upload_parts', 'select')
    and not has_table_privilege('authenticated', 'public.recap_upload_parts', 'insert')
    and not has_table_privilege('anon', 'public.recap_upload_parts', 'select'),
    'clients have no direct table privileges'
);
select ok(
    has_function_privilege('authenticated', 'public.put_recap_upload_part(uuid, integer, integer, text)', 'execute')
    and has_function_privilege('authenticated', 'public.take_recap_upload(uuid, integer)', 'execute')
    and not has_function_privilege('anon', 'public.put_recap_upload_part(uuid, integer, integer, text)', 'execute')
    and not has_function_privilege('anon', 'public.take_recap_upload(uuid, integer)', 'execute'),
    'only signed-in users can call the upload RPCs'
);

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000071","role":"authenticated","is_anonymous":false}';

-- Parts may arrive in any order; take joins them by index.
select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 1, 3, 'two '), true, 'part 1 stored');
select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 0, 3, 'one '), true, 'part 0 stored');
select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 0, 3, 'ONE '), true, 'a resent part replaces the old one');
select throws_ok(
    $$select public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 2, 3, 'last')$$,
    '23514', null, 'the last part is never stored'
);
select throws_ok(
    $$select public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 0, 65, 'x')$$,
    '23514', null, 'at most 64 parts'
);
select throws_ok(
    $$select public.put_recap_upload_part('a0000000-0000-4000-8000-000000000001', 0, 3, '')$$,
    '23514', null, 'an empty part is refused'
);

-- Another user cannot read A's parts.
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000072","role":"authenticated"}';
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000001', 3), null, 'another user gets nothing');

set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000071","role":"authenticated"}';
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000001', 2), null, 'a wrong part count gets nothing');
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000001', 3), null, 'and the failed take deleted the parts');

select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000002', 1, 3, 'two '), true, 'new upload part 1');
select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000002', 0, 3, 'one '), true, 'new upload part 0');
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000002', 3), 'one two ', 'parts come back joined in order');
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000002', 3), null, 'a take deletes the parts');

select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000003', 0, 4, 'only one'), true, 'an incomplete upload');
select is(public.take_recap_upload('a0000000-0000-4000-8000-000000000003', 4), null, 'missing parts give null, and are dropped');

-- Not allowlisted, and anonymous.
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000073","role":"authenticated"}';
select is(public.put_recap_upload_part('a0000000-0000-4000-8000-000000000004', 0, 2, 'x'), false, 'no recap access, nothing stored');
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000072","role":"authenticated","is_anonymous":true}';
select throws_ok(
    $$select public.put_recap_upload_part('a0000000-0000-4000-8000-000000000004', 0, 2, 'x')$$,
    'Anonymous users cannot request recaps',
    'anonymous sign-ins are rejected'
);

-- Abandoned uploads go after an hour.
reset role;
insert into public.recap_upload_parts (user_id, upload_id, part_index, part_count, content, created_at)
values
    ('10000000-0000-0000-0000-000000000072', 'a0000000-0000-4000-8000-000000000005', 0, 2, 'old', now() - interval '2 hours'),
    ('10000000-0000-0000-0000-000000000072', 'a0000000-0000-4000-8000-000000000006', 0, 2, 'new', now());
set local role service_role;
set local request.jwt.claim.role = 'service_role';
select is(
    (public.purge_cloud_retention(90)->>'recap_upload_parts_deleted')::integer,
    1, 'the retention purge drops uploads older than an hour'
);
reset role;
select is(
    (select count(*)::integer from public.recap_upload_parts),
    1, 'a fresh incomplete upload is kept'
);

select * from finish();
rollback;
