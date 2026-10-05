-- Deploy before clients that push words. Existing clients need the unknown-type reader fix
-- before words are shared with them; already installed binaries cannot be changed by SQL.
create or replace function public.apply_saved_item_mutation(
    actor uuid,
    mutation_id_value uuid,
    operation_value text,
    entity_id_value text,
    mutation_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    max_items constant integer := 50000;
    item_id_value uuid;
    book_key_value text;
    type_value text;
    href_value text;
    color_value text;
    note_value text;
    quote_value text;
    incoming_updated_at timestamptz;
    incoming_created_at timestamptz;
    incoming_deleted_at timestamptz;
    stored record;
    new_revision_value bigint;
    change_id_value bigint;
    stored_payload jsonb;
    live_count integer;
begin
    if jsonb_typeof(mutation_payload) is distinct from 'object' then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'invalid_saved_item'
        );
    end if;

    begin
        item_id_value := (mutation_payload ->> 'item_id')::uuid;
        incoming_updated_at := (mutation_payload ->> 'updated_at')::timestamptz;
        incoming_created_at := coalesce(
            (mutation_payload ->> 'created_at')::timestamptz,
            incoming_updated_at
        );
        incoming_deleted_at := nullif(mutation_payload ->> 'deleted_at', '')::timestamptz;
    exception when others then
        item_id_value := null;
    end;

    book_key_value := nullif(mutation_payload ->> 'book_key', '');
    type_value := mutation_payload ->> 'type';
    href_value := nullif(mutation_payload ->> 'href', '');
    color_value := nullif(mutation_payload ->> 'color', '');
    note_value := nullif(mutation_payload ->> 'note', '');
    quote_value := nullif(mutation_payload ->> 'text_quote', '');

    if item_id_value is null
        or entity_id_value is distinct from item_id_value::text
        or incoming_updated_at is null
        or book_key_value is null
        or char_length(book_key_value) > 512
        or type_value is null
        or type_value not in ('bookmark', 'highlight', 'word')
        or (type_value = 'word' and (
            nullif(mutation_payload ->> 'word_headword', '') is null
            or char_length(mutation_payload ->> 'word_headword') > 100
            or nullif(mutation_payload ->> 'word_selected', '') is null
            or char_length(mutation_payload ->> 'word_selected') > 100
            or mutation_payload ->> 'word_language' is distinct from 'en'
            or nullif(mutation_payload ->> 'word_gloss', '') is null
            or char_length(mutation_payload ->> 'word_gloss') > 160
            or char_length(coalesce(mutation_payload ->> 'word_part_of_speech', '')) > 32
        ))
        or href_value is null
        or char_length(href_value) > 1024
        or (color_value is not null and color_value not in ('amber', 'rose', 'sage', 'sky'))
        or char_length(coalesce(mutation_payload ->> 'text_before', '')) > 256
        or char_length(coalesce(mutation_payload ->> 'text_after', '')) > 256
        or char_length(coalesce(mutation_payload ->> 'chapter_title', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'book_title', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'book_author', '')) > 512
        or char_length(coalesce(mutation_payload ->> 'audio_href', '')) > 1024
    then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'invalid_saved_item'
        );
    end if;

    if char_length(coalesce(quote_value, '')) > 1500 or char_length(coalesce(note_value, '')) > 2000 then
        return jsonb_build_object(
            'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'saved_item_too_long'
        );
    end if;

    -- A device with a clock far ahead must not win every future edit.
    incoming_updated_at := least(incoming_updated_at, now() + interval '5 minutes');
    if operation_value = 'delete' then
        incoming_deleted_at := coalesce(incoming_deleted_at, incoming_updated_at);
    elsif incoming_deleted_at is not null then
        incoming_deleted_at := least(incoming_deleted_at, now() + interval '5 minutes');
    end if;

    select item.client_updated_at, item.last_mutation_id, item.revision, item.payload
    into stored
    from public.cloud_saved_items as item
    where item.cloud_user_id = actor
      and item.item_id = item_id_value
    for update;

    if found and (
        stored.client_updated_at > incoming_updated_at
        or (stored.client_updated_at = incoming_updated_at
            and stored.last_mutation_id >= mutation_id_value)
    ) then
        -- The stored version is newer: the client takes it.
        return jsonb_build_object(
            'mutation_id', mutation_id_value,
            'status', 'conflict',
            'item_id', item_id_value,
            'revision', stored.revision,
            'payload', stored.payload
        );
    end if;

    if not found and incoming_deleted_at is null then
        select count(*)::integer
        into live_count
        from public.cloud_saved_items as item
        where item.cloud_user_id = actor
          and item.deleted_at is null;
        if live_count >= max_items then
            return jsonb_build_object(
                'mutation_id', mutation_id_value, 'status', 'rejected', 'reason', 'saved_item_limit'
            );
        end if;
    end if;

    new_revision_value := coalesce(stored.revision, 0) + 1;
    stored_payload := jsonb_build_object(
        'item_id', item_id_value::text,
        'book_key', book_key_value,
        'book_title', mutation_payload -> 'book_title',
        'book_author', mutation_payload -> 'book_author',
        'type', type_value,
        'word_selected', case when type_value = 'word' then mutation_payload -> 'word_selected' else null end,
        'word_headword', case when type_value = 'word' then mutation_payload -> 'word_headword' else null end,
        'word_language', case when type_value = 'word' then mutation_payload -> 'word_language' else null end,
        'word_gloss', case when type_value = 'word' then mutation_payload -> 'word_gloss' else null end,
        'word_part_of_speech', case when type_value = 'word' then mutation_payload -> 'word_part_of_speech' else null end,
        'href', href_value,
        'media_type', mutation_payload -> 'media_type',
        'progression', mutation_payload -> 'progression',
        'total_progression', mutation_payload -> 'total_progression',
        'position', mutation_payload -> 'position',
        'chapter_title', mutation_payload -> 'chapter_title',
        'text_before', mutation_payload -> 'text_before',
        'text_quote', to_jsonb(quote_value),
        'text_after', mutation_payload -> 'text_after',
        'color', to_jsonb(color_value),
        'note', to_jsonb(note_value),
        'audio_href', mutation_payload -> 'audio_href',
        'audio_ms', mutation_payload -> 'audio_ms',
        'created_at', incoming_created_at,
        'updated_at', incoming_updated_at,
        'deleted_at', incoming_deleted_at,
        'remote_revision', new_revision_value
    );

    insert into public.cloud_saved_items(
        cloud_user_id,
        item_id,
        book_key,
        payload,
        client_updated_at,
        last_mutation_id,
        deleted_at,
        revision
    )
    values (
        actor,
        item_id_value,
        book_key_value,
        stored_payload,
        incoming_updated_at,
        mutation_id_value,
        incoming_deleted_at,
        new_revision_value
    )
    on conflict (cloud_user_id, item_id) do update
    set book_key = excluded.book_key,
        payload = excluded.payload,
        client_updated_at = excluded.client_updated_at,
        last_mutation_id = excluded.last_mutation_id,
        deleted_at = excluded.deleted_at,
        revision = excluded.revision,
        updated_at = timezone('utc', now());

    insert into public.sync_changes(
        cloud_user_id,
        entity_type,
        entity_id,
        operation,
        payload,
        revision
    )
    values (
        actor,
        'saved_item',
        item_id_value::text,
        case when incoming_deleted_at is null then 'upsert' else 'delete' end,
        stored_payload,
        new_revision_value
    )
    returning change_id into change_id_value;

    return jsonb_build_object(
        'mutation_id', mutation_id_value,
        'status', 'accepted',
        'item_id', item_id_value,
        'revision', new_revision_value,
        'change_id', change_id_value,
        'payload', stored_payload
    );
end;
$$;
