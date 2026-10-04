begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(12);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000071', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'source-device-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000072', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'source-device-b@example.invalid', '', now());
insert into public.cloud_books (
    id, cloud_user_id, title, format
) values
    ('6f1c0000-0000-4000-8000-000000000071', '10000000-0000-0000-0000-000000000071', 'Source A Book', 'epub'),
    ('6f1c0000-0000-4000-8000-000000000072', '10000000-0000-0000-0000-000000000072', 'Source B Book', 'epub');

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000071';
set local request.jwt.claim.role = 'authenticated';

select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000071",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000071",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000071",
            "position": {"progression": 0.4},
            "source_device": {
                "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "name": "Pixel Tablet"
            }
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'owned reading position with valid source metadata is accepted'
);

reset role;
select is(
    (select payload->'source_device'->>'id' from public.reading_positions
     where cloud_book_id = '6f1c0000-0000-4000-8000-000000000071'),
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'reading_positions retains the installation UUID'
);
select is(
    (select payload->'source_device'->>'name' from public.reading_positions
     where cloud_book_id = '6f1c0000-0000-4000-8000-000000000071'),
    'Pixel Tablet', 'reading_positions retains the display label'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000071';
set local request.jwt.claim.role = 'authenticated';
select is(
    (public.pull_sync_changes(0, 100)->'changes'->0->'payload'->'source_device'->>'id'),
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'pull returns the original source UUID'
);
select is(
    (public.pull_sync_changes(0, 100)->'changes'->0->'payload'->'source_device'->>'name'),
    'Pixel Tablet', 'pull returns the original display label'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000072",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000071",
        "operation": "upsert",
        "base_revision": 0,
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000071",
            "position": {"progression": 0.8},
            "source_device": {
                "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "name": "Other Device"
            }
        }
    }]'::jsonb, 0)->0->>'status'),
    'conflict', 'stale revision still returns a conflict'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000073",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000071",
        "operation": "upsert",
        "base_revision": 0,
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000071",
            "position": {"progression": 0.8},
            "source_device": {
                "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "name": "Other Device"
            }
        }
    }]'::jsonb, 0)->0->'payload'->'source_device'->>'id'),
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'conflict payload retains the remote UUID'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000074",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000071",
        "operation": "upsert",
        "base_revision": 0,
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000071",
            "position": {"progression": 0.8},
            "source_device": {
                "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "name": "Other Device"
            }
        }
    }]'::jsonb, 0)->0->'payload'->'source_device'->>'name'),
    'Pixel Tablet', 'conflict payload retains the remote display label'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000075",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000071",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000071",
            "position": {"progression": 0.9},
            "source_device": {"id": "not-a-uuid", "name": "Broken"}
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_source_device', 'malformed source UUID is rejected'
);
select is(
    (public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '50000000-0000-0000-0000-000000000076',
        'entity_type', 'reading_position',
        'entity_id', '6f1c0000-0000-4000-8000-000000000071',
        'operation', 'upsert',
        'payload', jsonb_build_object(
            'library_book_id', '6f1c0000-0000-4000-8000-000000000071',
            'position', jsonb_build_object('progression', 0.9),
            'source_device', jsonb_build_object(
                'id', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
                'name', repeat('x', 81)
            )
        )
    )), 0)->0->>'reason'),
    'invalid_source_device', 'display labels longer than 80 characters are rejected'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000077",
        "entity_type": "reading_position",
        "entity_id": "6f1c0000-0000-4000-8000-000000000072",
        "operation": "upsert",
        "payload": {
            "library_book_id": "6f1c0000-0000-4000-8000-000000000072",
            "position": {"progression": 0.2},
            "source_device": {
                "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "name": "Pixel Tablet"
            }
        }
    }]'::jsonb, 0)->0->>'reason'),
    'library_book_not_found', 'source metadata does not bypass book ownership'
);

reset role;
select is(
    (select revision from public.reading_positions
     where cloud_book_id = '6f1c0000-0000-4000-8000-000000000071'),
    1::bigint, 'conflicts and invalid metadata do not advance the revision'
);

select * from finish();
rollback;
