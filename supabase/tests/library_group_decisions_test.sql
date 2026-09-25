begin;

create extension if not exists pgtap with schema extensions;
set search_path = extensions, public;
select plan(49);

insert into auth.users (id, instance_id, aud, role, email, encrypted_password, email_confirmed_at)
values
    ('10000000-0000-0000-0000-000000000081', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'group-a@example.invalid', '', now()),
    ('10000000-0000-0000-0000-000000000082', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'group-b@example.invalid', '', now());

create temporary table group_decision_payload (payload jsonb);
grant select on group_decision_payload to authenticated;
insert into group_decision_payload values (
    jsonb_build_object(
        'version', 1,
        'decision_id', '40000000-0000-0000-0000-000000000081',
        'decision_type', 'merge',
        'target_group_id', '50000000-0000-0000-0000-000000000081',
        'retained_group_id', null,
        'members', jsonb_build_array(
            jsonb_build_object(
                'adapter_id', 'parrot-cloud',
                'backend_id', 'parrot-cloud',
                'account_id', '10000000-0000-0000-0000-000000000081',
                'native_book_id', 'cloud-book-a'
            ),
            jsonb_build_object(
                'adapter_id', 'storyteller',
                'backend_id', 'storyteller-instance',
                'account_id', 'story-account-a',
                'native_book_id', 'story-book-a'
            )
        ),
        'retained_members', '[]'::jsonb,
        'preferred_metadata_member', null,
        'preferred_media_members', jsonb_build_object(
            'ebook', jsonb_build_object(
                'adapter_id', 'parrot-cloud',
                'backend_id', 'parrot-cloud',
                'account_id', '10000000-0000-0000-0000-000000000081',
                'native_book_id', 'cloud-book-a'
            )
        ),
        'created_at', '2026-09-24T10:00:00Z'
    )
);

create temporary table group_push_results (
    result_name text primary key,
    result jsonb not null
);
grant select, insert on group_push_results to authenticated;

select ok(
    not has_function_privilege(
        'anon',
        'public.push_library_group_decisions(jsonb,bigint)',
        'execute'
    ),
    'anonymous clients cannot push grouping decisions'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000081';
set local request.jwt.claim.role = 'authenticated';
insert into group_push_results
select 'first', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000081',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000081',
        'operation', 'upsert',
        'base_revision', null,
        'payload', payload
    )
)) -> 0
from group_decision_payload;

select is(
    (select result ->> 'status' from group_push_results where result_name = 'first'),
    'accepted',
    'the authenticated account can push a portable merge'
);
select is(
    (select (result ->> 'revision')::bigint from group_push_results where result_name = 'first'),
    1::bigint,
    'the first decision receives account revision one'
);
select is(
    jsonb_array_length(public.pull_sync_changes(0, 20) -> 'changes'),
    1,
    'the accepted decision appears in the existing ordered sync feed'
);
select is(
    public.pull_sync_changes(0, 20) -> 'changes' -> 0 ->> 'entity_type',
    'library_group_decision',
    'the existing feed labels the decision with its versioned entity type'
);
select is(
    public.pull_sync_changes(0, 20) -> 'changes' -> 0 -> 'payload' ->
        'preferred_media_members' -> 'ebook' ->> 'native_book_id',
    'cloud-book-a',
    'the account feed preserves a validated preferred media member'
);

insert into group_push_results
select 'duplicate', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000081',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000081',
        'operation', 'upsert',
        'base_revision', null,
        'payload', payload
    )
)) -> 0
from group_decision_payload;
select is(
    (select (result ->> 'revision')::bigint from group_push_results where result_name = 'duplicate'),
    1::bigint,
    'replaying one mutation ID returns its original accepted revision'
);
reset role;
select is(
    (select count(*)::integer from public.library_group_decisions
     where cloud_user_id = '10000000-0000-0000-0000-000000000081'),
    1,
    'duplicate delivery stores one account-scoped decision'
);

set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000082';
set local request.jwt.claim.role = 'authenticated';
select is(
    jsonb_array_length(public.pull_sync_changes(0, 20) -> 'changes'),
    0,
    'another account cannot observe the first account decision feed'
);
insert into group_push_results
select 'same-id-other-account', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000081',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000081',
        'operation', 'upsert',
        'base_revision', null,
        'payload', jsonb_set(
            jsonb_set(
                payload,
                '{members,0,account_id}',
                to_jsonb('10000000-0000-0000-0000-000000000082'::text)
            ),
            '{preferred_media_members,ebook,account_id}',
            to_jsonb('10000000-0000-0000-0000-000000000082'::text)
        )
    )
)) -> 0
from group_decision_payload;
select is(
    (select result ->> 'status' from group_push_results
     where result_name = 'same-id-other-account'),
    'accepted',
    'mutation IDs are deduplicated within, not across, authenticated accounts'
);
select is(
    (select (result ->> 'revision')::bigint from group_push_results
     where result_name = 'same-id-other-account'),
    1::bigint,
    'the second account has an independent revision sequence'
);
select is(
    jsonb_array_length(public.pull_sync_changes(0, 20) -> 'changes'),
    1,
    'the second account receives only its own decision'
);

insert into group_push_results
select 'invalid-mutation-id-batch', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', 'not-a-uuid',
        'entity_type', 'library_group_decision',
        'entity_id', 'invalid-id',
        'operation', 'upsert',
        'payload', payload
    ),
    jsonb_build_object(
        'entity_type', 'library_group_decision',
        'entity_id', 'missing-id',
        'operation', 'upsert',
        'payload', payload
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000097',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000097',
        'operation', 'upsert',
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-000000000097'::text)
        )
    )
))
from group_decision_payload;
select is(
    (select result -> 0 ->> 'reason' from group_push_results
     where result_name = 'invalid-mutation-id-batch'),
    'invalid_mutation_id',
    'a malformed UUID mutation ID is rejected without aborting its batch'
);
select is(
    (select result -> 0 ->> 'mutation_id' from group_push_results
     where result_name = 'invalid-mutation-id-batch'),
    'not-a-uuid',
    'the malformed mutation ID is echoed without being cast or persisted'
);
select is(
    (select result -> 1 ->> 'reason' from group_push_results
     where result_name = 'invalid-mutation-id-batch'),
    'invalid_mutation_id',
    'a missing mutation ID is rejected without aborting its batch'
);
select is(
    (select result -> 2 ->> 'status' from group_push_results
     where result_name = 'invalid-mutation-id-batch'),
    'accepted',
    'a valid sibling mutation is accepted after invalid mutation IDs'
);
select is(
    (select (result -> 2 ->> 'revision')::bigint from group_push_results
     where result_name = 'invalid-mutation-id-batch'),
    2::bigint,
    'invalid mutation IDs are not written to the mutation idempotency table'
);

reset role;
select is(
    (select count(*)::integer from public.sync_mutations
     where cloud_user_id = '10000000-0000-0000-0000-000000000082'),
    2,
    'only valid UUID mutation IDs are persisted for idempotency'
);
set local role authenticated;
set local request.jwt.claim.sub = '10000000-0000-0000-0000-000000000081';
set local request.jwt.claim.role = 'authenticated';
insert into group_push_results
select 'unresolved', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000083',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000083',
        'operation', 'upsert',
        'base_revision', 1,
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-000000000083'::text)
        ) || jsonb_build_object('connection_id', 'installation-local')
    )
)) -> 0
from group_decision_payload;
select is(
    (select result ->> 'reason' from group_push_results where result_name = 'unresolved'),
    'non_portable_member_reference',
    'the backend rejects local connection identity instead of storing it'
);
reset role;
select is(
    (select revision from public.library_group_sync_revisions
     where cloud_user_id = '10000000-0000-0000-0000-000000000081'),
    1::bigint,
    'rejected unresolved identities do not consume a server revision'
);
set local role authenticated;

insert into group_push_results
select 'unselected-media-member', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000085',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000085',
        'operation', 'upsert',
        'base_revision', 1,
        'payload', jsonb_set(
            jsonb_set(
                payload,
                '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000085'::text)
            ),
            '{preferred_media_members}',
            jsonb_build_object(
                'audiobook', jsonb_build_object(
                    'adapter_id', 'audiobookshelf',
                    'backend_id', 'abs-instance',
                    'account_id', 'abs-account',
                    'native_book_id', 'unselected-book'
                )
            )
        )
    )
)) -> 0
from group_decision_payload;
select is(
    (select result ->> 'reason' from group_push_results
     where result_name = 'unselected-media-member'),
    'preferred_media_member_not_selected',
    'media preferences must name a selected decision member'
);
reset role;
select is(
    (select revision from public.library_group_sync_revisions
     where cloud_user_id = '10000000-0000-0000-0000-000000000081'),
    1::bigint,
    'rejected media preferences do not consume a server revision'
);
set local role authenticated;

insert into group_push_results
select 'malformed', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000086',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000086',
        'operation', 'upsert',
        'payload', (payload - 'version') || jsonb_build_object(
            'decision_id', '40000000-0000-0000-0000-000000000086'
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000087',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000087',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000087'::text)),
            '{version}', 'null'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000088',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000088',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000088'::text)),
            '{version}', '2'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000089',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000089',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000089'::text)),
            '{version}', to_jsonb('1'::text)
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008a',
        'entity_id', '40000000-0000-0000-0000-00000000008a',
        'operation', 'upsert',
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008a'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008b',
        'entity_type', null::text,
        'entity_id', '40000000-0000-0000-0000-00000000008b',
        'operation', 'upsert',
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008b'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008c',
        'entity_type', 'library_book',
        'entity_id', '40000000-0000-0000-0000-00000000008c',
        'operation', 'upsert',
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008c'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008d',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-00000000008d',
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008d'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008e',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-00000000008e',
        'operation', null::text,
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008e'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000008f',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-00000000008f',
        'operation', 'delete',
        'payload', jsonb_set(payload, '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000008f'::text))
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000090',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000090',
        'operation', 'upsert',
        'payload', payload - 'decision_type'
            || jsonb_build_object('decision_id', '40000000-0000-0000-0000-000000000090')
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000091',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000091',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000091'::text)),
            '{decision_type}', 'null'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000092',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000092',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000092'::text)),
            '{decision_type}', to_jsonb('relabel'::text)
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000093',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000093',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000093'::text)),
            '{members,0,account_id}', '42'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000094',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000094',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(payload, '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000094'::text)),
            '{preferred_media_members,ebook,account_id}', '42'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000095',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000095',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(
                jsonb_set(
                    jsonb_set(payload, '{decision_id}',
                        to_jsonb('40000000-0000-0000-0000-000000000095'::text)),
                    '{decision_type}', to_jsonb('preferences'::text)
                ),
                '{preferred_metadata_member}', 'null'::jsonb
            ),
            '{preferred_media_members}', '{}'::jsonb
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000096',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000096',
        'operation', 'upsert',
        'payload', (payload - 'members') || jsonb_build_object(
            'decision_id', '40000000-0000-0000-0000-000000000096'
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000097',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000097',
        'operation', 'upsert',
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('not-a-uuid'::text)
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000098',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000098',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(
                payload,
                '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000098'::text)
            ),
            '{target_group_id}',
            to_jsonb('not-a-uuid'::text)
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000099',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000099',
        'operation', 'upsert',
        'payload', jsonb_set(
            jsonb_set(
                payload,
                '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000099'::text)
            ),
            '{retained_group_id}',
            to_jsonb('not-a-uuid'::text)
        )
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000009a',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-00000000009a',
        'operation', 'upsert',
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000009a'::text)
        ) || jsonb_build_object('file_path', '/private/book.epub')
    ),
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-00000000009b',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-00000000009b',
        'operation', 'upsert',
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-00000000009b'::text)
        )
    )
))
from group_decision_payload;

select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000086'),
    'invalid_group_decision',
    'a missing payload version is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000087'),
    'invalid_group_decision',
    'a null payload version is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000088'),
    'invalid_group_decision',
    'an unsupported payload version is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000089'),
    'invalid_group_decision',
    'a string payload version is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008a'),
    'invalid_group_decision',
    'a missing entity type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008b'),
    'invalid_group_decision',
    'a null entity type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008c'),
    'invalid_group_decision',
    'an unsupported entity type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008d'),
    'invalid_group_decision',
    'a missing operation is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008e'),
    'invalid_group_decision',
    'a null operation is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000008f'),
    'invalid_group_decision',
    'an unsupported operation is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000090'),
    'invalid_group_decision',
    'a missing decision type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000091'),
    'invalid_group_decision',
    'a null decision type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000092'),
    'invalid_group_decision',
    'an unsupported decision type is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000093'),
    'non_portable_member_reference',
    'portable member fields must be JSON strings'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000094'),
    'preferred_media_member_invalid',
    'preferred media member fields must be JSON strings'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000095'),
    'preference_required',
    'a preferences decision must select at least one preference'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000096'),
    'members_required',
    'a missing member list is rejected'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000097'),
    'invalid_group_decision_uuid',
    'an invalid decision UUID is rejected without aborting its batch'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000098'),
    'invalid_group_decision_uuid',
    'an invalid target group UUID is rejected without aborting its batch'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-000000000099'),
    'invalid_group_decision_uuid',
    'an invalid retained group UUID is rejected without aborting its batch'
);
select is(
    (select item.value ->> 'reason' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000009a'),
    'unknown_group_decision_field',
    'unknown payload fields are rejected before entering the account feed'
);
select is(
    (select item.value ->> 'status' from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000009b'),
    'accepted',
    'a valid sibling decision is accepted after malformed payload UUIDs'
);
select is(
    (select (item.value ->> 'revision')::bigint from group_push_results result
     cross join lateral jsonb_array_elements(result.result) as item(value)
     where result.result_name = 'malformed'
       and item.value ->> 'mutation_id' = '40000000-0000-0000-0000-00000000009b'),
    2::bigint,
    'malformed payload UUIDs do not consume account revisions'
);

insert into group_push_results
select 'replayed-invalid-payload', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000097',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000097',
        'operation', 'upsert',
        'payload', jsonb_set(
            payload,
            '{decision_id}',
            to_jsonb('40000000-0000-0000-0000-000000000097'::text)
        )
    )
)) -> 0
from group_decision_payload;
select is(
    (select result ->> 'reason' from group_push_results
     where result_name = 'replayed-invalid-payload'),
    'invalid_group_decision_uuid',
    'a valid mutation ID replays its originally persisted rejection'
);
reset role;
select is(
    (select revision from public.library_group_sync_revisions
     where cloud_user_id = '10000000-0000-0000-0000-000000000081'),
    2::bigint,
    'replaying a rejected valid-ID mutation does not consume a revision'
);
set local role authenticated;

insert into group_push_results
select 'stale-base', public.push_library_group_decisions(jsonb_build_array(
    jsonb_build_object(
        'mutation_id', '40000000-0000-0000-0000-000000000084',
        'entity_type', 'library_group_decision',
        'entity_id', '40000000-0000-0000-0000-000000000084',
        'operation', 'upsert',
        'base_revision', 0,
        'payload', jsonb_set(
            jsonb_set(
                payload,
                '{decision_id}',
                to_jsonb('40000000-0000-0000-0000-000000000084'::text)
            ),
            '{target_group_id}',
            to_jsonb('50000000-0000-0000-0000-000000000084'::text)
        )
    )
)) -> 0
from group_decision_payload;
select is(
    (select (result ->> 'revision')::bigint from group_push_results where result_name = 'stale-base'),
    3::bigint,
    'a stale base revision is serialized after the accepted operation and rebased by order'
);
select is(
    jsonb_array_length(public.pull_sync_changes(0, 20) -> 'changes'),
    3,
    'the account feed preserves both overlapping edits for revision-order application'
);

reset role;
select * from finish();
rollback;
