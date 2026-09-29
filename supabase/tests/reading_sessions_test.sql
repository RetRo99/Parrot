begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(14);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'sessions-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'sessions-b@example.invalid', '', now());

select ok(
    not has_function_privilege(
        'anon',
        'public.push_sync_changes(jsonb, bigint)',
        'execute'
    ),
    'anonymous role cannot push sync mutations'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000001",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-a|ebook|1000|2000|1000",
        "operation": "upsert",
        "created_at": "2026-09-25T10:00:00Z",
        "payload": {
            "session_id": "rs1|book-a|ebook|1000|2000|1000",
            "book_uuid": "book-a",
            "book_title": "First Book",
            "book_type": "ebook",
            "start_time": 1000,
            "end_time": 2000,
            "duration_ms": 1000,
            "reading_speed_wpm": 250
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'reading session mutation is accepted'
);
select is(
    (select count(*)::integer from public.cloud_reading_sessions
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    1, 'the session ledger stores the row'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000001",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-a|ebook|1000|2000|1000",
        "operation": "upsert",
        "created_at": "2026-09-25T10:00:00Z",
        "payload": {
            "session_id": "rs1|book-a|ebook|1000|2000|1000",
            "book_uuid": "book-a",
            "book_title": "First Book",
            "book_type": "ebook",
            "start_time": 1000,
            "end_time": 2000,
            "duration_ms": 1000
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a replayed mutation id returns its stored response'
);

-- sync_mutations is write-only for clients by design; inspect it as the
-- database owner.
reset role;
select is(
    (select count(*)::integer from public.sync_mutations
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    1, 'a replayed mutation id is stored once'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000002",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-a|ebook|1000|2000|1000",
        "operation": "upsert",
        "created_at": "2026-09-25T10:05:00Z",
        "payload": {
            "session_id": "rs1|book-a|ebook|1000|2000|1000",
            "book_uuid": "book-a",
            "book_title": "First Book",
            "book_type": "ebook",
            "start_time": 1000,
            "end_time": 2000,
            "duration_ms": 1000
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a re-uploaded session under a new mutation id is accepted idempotently'
);
select is(
    (select count(*)::integer from public.cloud_reading_sessions
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'),
    1, 'a re-uploaded session does not duplicate the ledger row'
);
select is(
    (select count(*)::integer from public.sync_changes
     where cloud_user_id = '10000000-0000-0000-0000-000000000031'
       and entity_type = 'reading_session'),
    1, 'a re-uploaded session does not emit a second change event'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000003",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-b|readaloud|5000|9000|4000",
        "operation": "upsert",
        "created_at": "2026-09-25T11:00:00Z",
        "payload": {
            "session_id": "rs1|book-b|readaloud|5000|9000|4000",
            "book_uuid": "book-b",
            "book_title": "Second Book",
            "book_type": "readaloud",
            "start_time": 5000,
            "end_time": 9000,
            "duration_ms": 4000
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a distinct session is accepted'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000004",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-a|ebook|1000|2000|1000",
        "operation": "delete",
        "created_at": "2026-09-25T12:00:00Z",
        "payload": {
            "session_id": "rs1|book-a|ebook|1000|2000|1000"
        }
    }]'::jsonb, 0)->0->>'reason'),
    'unsupported_mutation', 'session deletes are rejected: the ledger is append-only'
);

select is(
    (public.push_sync_changes('[{
        "mutation_id": "40000000-0000-0000-0000-000000000005",
        "entity_type": "reading_session",
        "entity_id": "rs1|book-c|ebook|1|2|1",
        "operation": "upsert",
        "created_at": "2026-09-25T12:30:00Z",
        "payload": {
            "session_id": "rs1|book-c|ebook|1|2|1",
            "book_uuid": "book-c",
            "book_title": "Broken Book",
            "book_type": "ebook",
            "start_time": 1,
            "end_time": 2
        }
    }]'::jsonb, 0)->0->>'reason'),
    'reading_session_fields_required', 'sessions without a duration are rejected'
);

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000032';
set local request.jwt.claim.role = 'authenticated';

select is(
    (select count(*)::integer from public.cloud_reading_sessions),
    0, 'RLS hides another account session rows'
);
select is(
    (select count(*)::integer from jsonb_array_elements(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row
     where change_row ->> 'entity_type' = 'reading_session'),
    0, 'another account cannot pull foreign session changes'
);

reset role;
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000031';
set local request.jwt.claim.role = 'authenticated';

select is(
    (select count(*)::integer from jsonb_array_elements(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row
     where change_row ->> 'entity_type' = 'reading_session'),
    2, 'the owner pulls one change per accepted session'
);

select * from finish();
rollback;
