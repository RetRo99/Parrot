begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(31);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000061', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'links-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000062', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'links-b@example.invalid', '', now());

create temporary table push_results (case_name text primary key, result jsonb);
grant select, insert on push_results to authenticated;

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000061';
set local request.jwt.claim.role = 'authenticated';

-- 1. A link with two members from different sources is accepted.
insert into push_results
select 'new', public.push_sync_changes('[{
    "mutation_id": "60000000-0000-0000-0000-000000000001",
    "entity_type": "book_link",
    "entity_id": "70000000-0000-4000-8000-000000000001",
    "operation": "upsert",
    "payload": {
        "link_id": "70000000-0000-4000-8000-000000000001",
        "members": ["library:book-1", "storyteller:st-1"],
        "deleted": false
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'new'),
    'accepted', 'a link with two members from different sources is accepted'
);
select is(
    (select result->>'revision' from push_results where case_name = 'new'),
    '1', 'a new link starts at revision 1'
);
select is(
    (select members from public.cloud_book_links
     where id = '70000000-0000-4000-8000-000000000001'),
    array['library:book-1', 'storyteller:st-1'], 'the link stores its members'
);
select is(
    (select payload->'members' from jsonb_to_recordset(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row(entity_type text, operation text, payload jsonb)
     where entity_type = 'book_link' and operation = 'upsert'),
    '["library:book-1", "storyteller:st-1"]'::jsonb, 'a pull emits the link with its members'
);

-- 2. Invalid links are rejected.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000002",
        "entity_type": "book_link",
        "entity_id": "70000000-0000-4000-8000-000000000002",
        "operation": "upsert",
        "payload": {
            "link_id": "70000000-0000-4000-8000-000000000002",
            "members": ["storyteller:st-2", "storyteller:st-3"],
            "deleted": false
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_link', 'a link with two members from one source is rejected'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000003",
        "entity_type": "book_link",
        "entity_id": "70000000-0000-4000-8000-000000000002",
        "operation": "upsert",
        "payload": {
            "link_id": "70000000-0000-4000-8000-000000000002",
            "members": ["storyteller:st-2"],
            "deleted": false
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_link', 'a link with fewer than two members is rejected'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000004",
        "entity_type": "book_link",
        "entity_id": "not-a-uuid",
        "operation": "upsert",
        "payload": {
            "link_id": "not-a-uuid",
            "members": ["library:book-9", "storyteller:st-9"],
            "deleted": false
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_link', 'a link whose id is not a UUID is rejected'
);
select is(
    (select count(*)::integer from public.cloud_book_links
     where id = '70000000-0000-4000-8000-000000000002'),
    0, 'a rejected link creates no row'
);

-- 3. A second link containing a member of a live link conflicts with it.
insert into push_results
select 'overlap', public.push_sync_changes('[{
    "mutation_id": "60000000-0000-0000-0000-000000000005",
    "entity_type": "book_link",
    "entity_id": "70000000-0000-4000-8000-000000000003",
    "operation": "upsert",
    "payload": {
        "link_id": "70000000-0000-4000-8000-000000000003",
        "members": ["storyteller:st-1", "audiobookshelf:abs-1"],
        "deleted": false
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'overlap'),
    'conflict', 'a link sharing a member with a live link is a conflict'
);
select is(
    (select result->'payload'->>'link_id' from push_results where case_name = 'overlap'),
    '70000000-0000-4000-8000-000000000001', 'the conflict carries the first link'
);
select is(
    (select result->'payload'->'members' from push_results where case_name = 'overlap'),
    '["library:book-1", "storyteller:st-1"]'::jsonb, 'the conflict carries the first link members'
);
select is(
    (select count(*)::integer from public.cloud_book_links
     where id = '70000000-0000-4000-8000-000000000003'),
    0, 'an overlapping link creates no row'
);
select isnt(
    (select result->'payload'->>'created_at' from push_results where case_name = 'overlap'),
    null, 'the conflict says when the first link was created'
);

-- 4. A stale revision conflicts; the current revision updates the link.
insert into push_results
select 'stale', public.push_sync_changes('[{
    "mutation_id": "60000000-0000-0000-0000-000000000006",
    "entity_type": "book_link",
    "entity_id": "70000000-0000-4000-8000-000000000001",
    "operation": "upsert",
    "base_revision": 0,
    "payload": {
        "link_id": "70000000-0000-4000-8000-000000000001",
        "members": ["library:book-1", "storyteller:st-1", "audiobookshelf:abs-1"],
        "deleted": false
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'stale'),
    'conflict', 'a stale base revision is a conflict'
);
select is(
    (select result->'payload'->>'remote_revision' from push_results where case_name = 'stale'),
    '1', 'the stale conflict carries the current link'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000007",
        "entity_type": "book_link",
        "entity_id": "70000000-0000-4000-8000-000000000001",
        "operation": "upsert",
        "base_revision": 1,
        "payload": {
            "link_id": "70000000-0000-4000-8000-000000000001",
            "members": ["library:book-1", "storyteller:st-1", "audiobookshelf:abs-1"],
            "deleted": false
        }
    }]'::jsonb, 0)->0->>'revision'),
    '2', 'the current base revision updates the link'
);
select is(
    (select cardinality(members) from public.cloud_book_links
     where id = '70000000-0000-4000-8000-000000000001'),
    3, 'the updated link stores the new member'
);

-- 5. A delete removes the link from active queries and a pull emits it.
insert into push_results
select 'delete', public.push_sync_changes('[{
    "mutation_id": "60000000-0000-0000-0000-000000000008",
    "entity_type": "book_link",
    "entity_id": "70000000-0000-4000-8000-000000000001",
    "operation": "delete",
    "base_revision": 2,
    "payload": {
        "link_id": "70000000-0000-4000-8000-000000000001",
        "members": [],
        "deleted": true
    }
}]'::jsonb, 0)->0;

select is(
    (select result->>'status' from push_results where case_name = 'delete'),
    'accepted', 'a delete is accepted'
);
select is(
    (select count(*)::integer from public.cloud_book_links
     where id = '70000000-0000-4000-8000-000000000001' and deleted_at is null),
    0, 'a deleted link disappears from active queries'
);
select is(
    (select payload->>'deleted' from jsonb_to_recordset(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row(entity_type text, operation text, payload jsonb)
     where entity_type = 'book_link' and operation = 'delete'),
    'true', 'a pull emits the deletion'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000009",
        "entity_type": "book_link",
        "entity_id": "70000000-0000-4000-8000-000000000003",
        "operation": "upsert",
        "payload": {
            "link_id": "70000000-0000-4000-8000-000000000003",
            "members": ["storyteller:st-1", "audiobookshelf:abs-1"],
            "deleted": false
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'members of a deleted link can be linked again'
);

-- Decisions: last write wins on decided_at.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000010",
        "entity_type": "book_link_decision",
        "entity_id": "library:book-1|storyteller:st-1",
        "operation": "upsert",
        "payload": {
            "pair_key": "library:book-1|storyteller:st-1",
            "decision": "skip",
            "decided_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a link decision is accepted'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000011",
        "entity_type": "book_link_decision",
        "entity_id": "library:book-1|storyteller:st-1",
        "operation": "upsert",
        "payload": {
            "pair_key": "library:book-1|storyteller:st-1",
            "decision": "never",
            "decided_at": "2026-10-01T09:00:00Z"
        }
    }]'::jsonb, 0)->0->'payload'->>'decision'),
    'skip', 'an older decision loses and the current decision is returned'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000012",
        "entity_type": "book_link_decision",
        "entity_id": "library:book-1|storyteller:st-1",
        "operation": "upsert",
        "payload": {
            "pair_key": "library:book-1|storyteller:st-1",
            "decision": "never",
            "decided_at": "2026-10-01T11:00:00Z"
        }
    }]'::jsonb, 0)->0->>'revision'),
    '2', 'a newer decision wins'
);
select is(
    (select decision from public.cloud_book_link_decisions
     where pair_key = 'library:book-1|storyteller:st-1'),
    'never', 'the newest decision is stored'
);
select is(
    (select count(*)::integer from jsonb_to_recordset(
        public.pull_sync_changes(0, 100)->'changes'
    ) as change_row(entity_type text)
     where entity_type = 'book_link_decision'),
    2, 'a pull emits each applied decision'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "60000000-0000-0000-0000-000000000013",
        "entity_type": "book_link_decision",
        "entity_id": "library:book-1|storyteller:st-2",
        "operation": "upsert",
        "payload": {
            "pair_key": "library:book-1|storyteller:st-2",
            "decision": "maybe",
            "decided_at": "2026-10-01T11:00:00Z"
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_link_decision', 'an unknown decision is rejected'
);

-- 6. RLS isolation: user B cannot read or modify user A's links.
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000062';

select is(
    (select count(*)::integer from public.cloud_book_links),
    0, 'RLS hides another account links'
);
select is(
    (select count(*)::integer from public.cloud_book_link_decisions),
    0, 'RLS hides another account link decisions'
);
select throws_ok(
    $$update public.cloud_book_links set deleted_at = now()$$,
    '42501', null, 'links cannot be written directly'
);
select public.push_sync_changes('[{
    "mutation_id": "60000000-0000-0000-0000-000000000014",
    "entity_type": "book_link",
    "entity_id": "70000000-0000-4000-8000-000000000003",
    "operation": "delete",
    "payload": {
        "link_id": "70000000-0000-4000-8000-000000000003",
        "members": [],
        "deleted": true
    }
}]'::jsonb, 0);

reset role;
select is(
    (select count(*)::integer from public.cloud_book_links
     where cloud_user_id = '10000000-0000-0000-0000-000000000061'
       and id = '70000000-0000-4000-8000-000000000003'
       and deleted_at is null),
    1, 'another account cannot delete a link through the RPC'
);

select * from finish();
rollback;
