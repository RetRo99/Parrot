-- Renaming the original RPC also changes the implicit PL/pgSQL block label used by
-- parameter-qualified references in its body. Keep those references aligned
-- with the renamed function before the replacement RPC delegates to it.
do $$
declare
    definition text;
begin
    definition := pg_get_functiondef(
        'public.reserve_book_upload_before_orphan_gc(uuid,text,text,text,bigint,text,text,jsonb)'::regprocedure
    );
    definition := replace(definition, 'reserve_book_upload.', 'reserve_book_upload_before_orphan_gc.');
    execute definition;
end;
$$;
