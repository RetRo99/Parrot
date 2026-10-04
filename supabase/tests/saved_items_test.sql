begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(27);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000051', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'saved-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000052', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'saved-b@example.invalid', '', now());

select ok(
    not has_function_privilege(
        'authenticated',
        'public.apply_saved_item_mutation(uuid, uuid, text, text, jsonb)',
        'execute'
    ),
    'clients cannot call the saved item helper directly'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'authenticated';

-- 1. A new highlight is accepted and stored once.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000001",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a",
            "book_title": "The Lantern Ferry",
            "type": "highlight",
            "href": "chapter8.xhtml",
            "total_progression": 0.62,
            "chapter_title": "The Crossing",
            "text_quote": "Out on the water the first of the storm lanterns flickered, then held.",
            "color": "amber",
            "created_at": "2026-10-01T10:00:00Z",
            "updated_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a new saved item is accepted'
);
select is(
    (select count(*)::integer from public.cloud_saved_items),
    1, 'the item is stored'
);
select is(
    (select payload ->> 'color' from public.cloud_saved_items),
    'amber', 'the payload keeps the colour'
);

-- 2. A newer edit from another device wins and bumps the revision.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000002",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a",
            "type": "highlight",
            "href": "chapter8.xhtml",
            "text_quote": "Out on the water the first of the storm lanterns flickered, then held.",
            "color": "rose",
            "note": "The lanterns come back in the last chapter?",
            "created_at": "2026-10-01T10:00:00Z",
            "updated_at": "2026-10-02T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'revision'),
    '2', 'a newer edit is accepted as revision 2'
);
select is(
    (select payload ->> 'note' from public.cloud_saved_items),
    'The lanterns come back in the last chapter?', 'the newer note is stored'
);

-- 3. An older edit made offline loses and receives the stored version.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000003",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a",
            "type": "highlight",
            "href": "chapter8.xhtml",
            "color": "sky",
            "created_at": "2026-10-01T10:00:00Z",
            "updated_at": "2026-10-01T12:00:00Z"
        }
    }]'::jsonb, 0)->0->'payload'->>'color'),
    'rose', 'an older edit gets a conflict carrying the newer item'
);
select is(
    (select color from (select payload ->> 'color' as color from public.cloud_saved_items) as stored),
    'rose', 'the older edit did not overwrite the item'
);
select is(
    (select count(*)::integer from public.cloud_saved_items),
    1, 'edits never duplicate an item'
);

-- 4. Equal times: the larger mutation id wins, so every device agrees.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000000",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter8.xhtml",
            "color": "sage", "updated_at": "2026-10-02T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'conflict', 'a tie with a smaller mutation id loses'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000009",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter8.xhtml",
            "color": "sage", "note": "The lanterns come back in the last chapter?",
            "updated_at": "2026-10-02T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a tie with a larger mutation id wins'
);

-- 5. Deletes are tombstones and follow the same rule.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000010",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "delete",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter8.xhtml",
            "updated_at": "2026-10-03T10:00:00Z", "deleted_at": "2026-10-03T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a delete is accepted'
);
select ok(
    (select deleted_at is not null from public.cloud_saved_items),
    'the delete is kept as a tombstone'
);
select is(
    (select operation from public.sync_changes
     where entity_type = 'saved_item' order by change_id desc limit 1),
    'delete', 'the delete reaches other devices as a delete change'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000011",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter8.xhtml",
            "color": "rose", "updated_at": "2026-10-02T11:00:00Z"
        }
    }]'::jsonb, 0)->0->'payload'->>'deleted_at' is not null),
    true, 'an edit older than the delete does not bring the item back'
);

-- 6. A replayed mutation returns its stored response without a second change.
select is(
    (select count(*)::integer from public.sync_changes where entity_type = 'saved_item'),
    4, 'four accepted saved item changes were recorded'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000010",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "delete",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter8.xhtml",
            "updated_at": "2026-10-03T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a replayed delete returns its stored response'
);
select is(
    (select count(*)::integer from public.sync_changes where entity_type = 'saved_item'),
    4, 'a replay records no new change'
);

-- 7. Validation and limits.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000020",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000002",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000099",
            "book_key": "library:book-a", "type": "bookmark", "href": "chapter1.xhtml",
            "updated_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_saved_item', 'the entity id must match the item id'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000021",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000002",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000002",
            "book_key": "library:book-a", "type": "highlight", "href": "chapter1.xhtml",
            "color": "purple", "updated_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'reason'),
    'invalid_saved_item', 'unknown colours are rejected'
);
select is(
    (public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '50000000-0000-0000-0000-000000000022',
        'entity_type', 'saved_item',
        'entity_id', '60000000-0000-0000-0000-000000000003',
        'operation', 'upsert',
        'payload', jsonb_build_object(
            'item_id', '60000000-0000-0000-0000-000000000003',
            'book_key', 'library:book-a', 'type', 'highlight', 'href', 'chapter1.xhtml',
            'text_quote', repeat('a', 1501), 'updated_at', '2026-10-01T10:00:00Z'
        )
    )), 0)->0->>'reason'),
    'saved_item_too_long', 'quotes over 1,500 characters are rejected'
);
select is(
    (public.push_sync_changes(jsonb_build_array(jsonb_build_object(
        'mutation_id', '50000000-0000-0000-0000-000000000023',
        'entity_type', 'saved_item',
        'entity_id', '60000000-0000-0000-0000-000000000004',
        'operation', 'upsert',
        'payload', jsonb_build_object(
            'item_id', '60000000-0000-0000-0000-000000000004',
            'book_key', 'library:book-a', 'type', 'bookmark', 'href', 'chapter1.xhtml',
            'updated_at', '2099-01-01T00:00:00Z'
        )
    )), 0)->0->'payload'->>'updated_at' < '2099'),
    true, 'a clock far in the future is clamped to server time'
);

-- 8. Bookmarks from Storyteller and Audiobookshelf books sync by their copy key.
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000024",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000005",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000005",
            "book_key": "audiobookshelf:li_123", "type": "bookmark", "href": "audio",
            "audio_ms": 15128000, "updated_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'status'),
    'accepted', 'a listening bookmark for an Audiobookshelf book is accepted'
);

-- 9. Isolation: another account sees nothing and its item ids do not collide.
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000052';
select is(
    (select count(*)::integer from public.cloud_saved_items),
    0, 'another account cannot read the items'
);
select is(
    (public.push_sync_changes('[{
        "mutation_id": "50000000-0000-0000-0000-000000000030",
        "entity_type": "saved_item",
        "entity_id": "60000000-0000-0000-0000-000000000001",
        "operation": "upsert",
        "payload": {
            "item_id": "60000000-0000-0000-0000-000000000001",
            "book_key": "library:book-z", "type": "bookmark", "href": "chapter1.xhtml",
            "updated_at": "2026-10-01T10:00:00Z"
        }
    }]'::jsonb, 0)->0->>'revision'),
    '1', 'the same item id in another account is a separate item'
);
select throws_ok(
    $$update public.cloud_saved_items set deleted_at = now()$$,
    '42501', null, 'items cannot be written directly'
);

-- 10. Tombstone purge is service-role only and removes old tombstones.
reset role;
update public.cloud_saved_items
set deleted_at = now() - interval '200 days',
    updated_at = now() - interval '200 days'
where item_id = '60000000-0000-0000-0000-000000000001'
  and cloud_user_id = '10000000-0000-0000-0000-000000000051';
set local request.jwt.claim.role = 'service_role';
select is(
    (public.purge_saved_item_tombstones(180) ->> 'saved_items_deleted'),
    '1', 'tombstones older than the retention window are purged'
);

select * from finish();
rollback;
