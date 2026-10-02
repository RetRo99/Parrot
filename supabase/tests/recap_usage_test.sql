begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(17);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'recap-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000062', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'recap-b@example.invalid', '', now());
-- Recaps are allowlist-only (20261003000000).
insert into public.cloud_feature_allowlist (cloud_user_id, feature)
values
    ('10000000-0000-0000-0000-000000000061', 'recap'),
    ('10000000-0000-0000-0000-000000000062', 'recap');

-- Access control.
select ok(
    (select relrowsecurity from pg_class where oid = 'public.recap_usage'::regclass),
    'recap_usage has RLS enabled'
);
select is(
    (select count(*)::integer from pg_policies
     where schemaname = 'public' and tablename = 'recap_usage'),
    0, 'recap_usage has no client policies'
);
select ok(
    not has_table_privilege('authenticated', 'public.recap_usage', 'select')
    and not has_table_privilege('authenticated', 'public.recap_usage', 'insert')
    and not has_table_privilege('authenticated', 'public.recap_usage', 'update')
    and not has_table_privilege('anon', 'public.recap_usage', 'select'),
    'clients have no direct table privileges'
);
select ok(
    has_function_privilege('authenticated', 'public.consume_recap_quota(integer)', 'execute'),
    'authenticated users can consume quota'
);
select ok(
    not has_function_privilege('anon', 'public.consume_recap_quota(integer)', 'execute'),
    'the anon role cannot consume quota'
);
select ok(
    not has_function_privilege('service_role', 'public.consume_recap_quota(integer)', 'execute'),
    'the service role is not granted the user RPC'
);
select ok(
    (select prosecdef from pg_proc
     where oid = 'public.consume_recap_quota(integer)'::regprocedure),
    'consume_recap_quota is security definer'
);

-- User A: limit 2.
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000061","role":"authenticated","is_anonymous":false}';

select is(public.consume_recap_quota(2), true, 'first call is allowed');
select is(public.consume_recap_quota(2), true, 'second call is allowed');
select is(public.consume_recap_quota(2), false, 'third call is over the limit');
select is(public.consume_recap_quota(0), false, 'a non-positive limit never allows');
select is(public.consume_recap_quota(3), true, 'a higher limit allows one more');

-- User B has a separate counter.
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000062","role":"authenticated"}';
select is(public.consume_recap_quota(1), true, 'another user has their own quota');

-- Anonymous and missing identities.
set local request.jwt.claims = '{"sub":"10000000-0000-0000-0000-000000000062","role":"authenticated","is_anonymous":true}';
select throws_ok(
    'select public.consume_recap_quota(5)',
    'Anonymous users cannot request recaps',
    'anonymous sign-ins are rejected'
);
set local request.jwt.claims = '{"role":"authenticated"}';
select throws_ok(
    'select public.consume_recap_quota(5)',
    'Authentication required',
    'a token without a subject is rejected'
);

reset role;
select is(
    (select count from public.recap_usage
     where user_id = '10000000-0000-0000-0000-000000000061'
       and day = timezone('utc', now())::date),
    3, 'refused calls are not counted'
);
select is(
    (select count from public.recap_usage
     where user_id = '10000000-0000-0000-0000-000000000062'),
    1, 'the anonymous attempt did not count against the user'
);

select * from finish();
rollback;
