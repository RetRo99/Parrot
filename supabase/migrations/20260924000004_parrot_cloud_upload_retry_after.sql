-- Preserve the existing reservation/expiry logic while attaching a retry hint
-- to quota rejections so clients can avoid retrying sooner than the server asks.
alter function public.reserve_book_upload(uuid, text, text, text, bigint, text, text, jsonb)
    rename to reserve_book_upload_before_retry_after;
revoke execute on function public.reserve_book_upload_before_retry_after(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, authenticated, service_role;

create function public.reserve_book_upload(
    cloud_book_id uuid,
    media_type text,
    relative_path text,
    file_name text,
    size_bytes bigint,
    content_hash_algorithm text,
    content_hash text,
    rights_attestation jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    response jsonb;
begin
    response := public.reserve_book_upload_before_retry_after(
        cloud_book_id,
        media_type,
        relative_path,
        file_name,
        size_bytes,
        content_hash_algorithm,
        content_hash,
        rights_attestation
    );

    if response ->> 'status' = 'rejected'
        and response ->> 'reason' = 'quota_exceeded'
    then
        return response || jsonb_build_object('retry_after_ms', 60000);
    end if;

    return response;
end;
$$;

revoke execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) from public, anon, service_role;
grant execute on function public.reserve_book_upload(
    uuid, text, text, text, bigint, text, text, jsonb
) to authenticated;
