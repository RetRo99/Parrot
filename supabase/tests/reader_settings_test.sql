begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(12);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000041', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'reader-settings-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000042', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'reader-settings-b@example.invalid', '', now());

select ok(
    not has_function_privilege('anon', 'public.push_reader_settings(jsonb, bigint)', 'execute'),
    'anonymous users cannot push reader settings'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000041';
set local request.jwt.claim.role = 'authenticated';

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000041",
        "entity_type":"reader_settings",
        "entity_id":"theme",
        "operation":"upsert",
        "base_revision":null,
        "created_at":"2026-10-07T12:00:00Z",
        "payload":"DARK"
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a first setting value is accepted'
);

select is(
    (select setting_value from public.cloud_reader_settings
     where cloud_user_id = '10000000-0000-0000-0000-000000000041'
       and setting_key = 'theme'),
    '"DARK"'::jsonb, 'the setting value is stored as JSON'
);

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000042",
        "entity_type":"reader_settings",
        "entity_id":"theme",
        "operation":"upsert",
        "base_revision":null,
        "created_at":"2026-10-07T12:01:00Z",
        "payload":"LIGHT"
    }]'::jsonb, 0)->0->>'status'),
    'conflict', 'a stale setting mutation returns the current cloud value'
);

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000043",
        "entity_type":"reader_settings",
        "entity_id":"theme",
        "operation":"upsert",
        "base_revision":1,
        "created_at":"2026-10-07T12:02:00Z",
        "payload":"LIGHT"
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a setting rebased on the current revision is accepted'
);

select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '10000000-0000-0000-0000-000000000041'
       and entity_type = 'reader_settings' and entity_id = 'theme'),
    2, 'accepted setting writes emit changes; conflicts do not'
);

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000041",
        "entity_type":"reader_settings",
        "entity_id":"theme",
        "operation":"upsert",
        "base_revision":null,
        "created_at":"2026-10-07T12:00:00Z",
        "payload":"DARK"
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'replaying a mutation id returns its original result'
);

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000044",
        "entity_type":"reader_settings",
        "entity_id":"tts_voice_id",
        "operation":"upsert",
        "base_revision":null,
        "created_at":"2026-10-07T12:03:00Z",
        "payload":"android:voice:7"
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_reader_setting', 'platform TTS voice IDs are rejected'
);

select is(
    (public.push_reader_settings('[{
        "mutation_id":"40000000-0000-0000-0000-000000000045",
        "entity_type":"reader_settings",
        "entity_id":"font_family",
        "operation":"upsert",
        "base_revision":null,
        "created_at":"2026-10-07T12:04:00Z",
        "payload":"My Local Font"
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_reader_setting', 'local custom-font selections are rejected'
);

select is(
    (select count(*)::integer from jsonb_array_elements(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row
     where change_row ->> 'entity_type' = 'reader_settings'),
    2, 'the owner pulls the accepted setting changes'
);

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000042';
set local request.jwt.claim.role = 'authenticated';

select is(
    (select count(*)::integer from public.cloud_reader_settings),
    0, 'RLS hides another account reader settings'
);
select is(
    (select count(*)::integer from jsonb_array_elements(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row
     where change_row ->> 'entity_type' = 'reader_settings'),
    0, 'another account cannot pull reader settings changes'
);

select * from finish();
rollback;
