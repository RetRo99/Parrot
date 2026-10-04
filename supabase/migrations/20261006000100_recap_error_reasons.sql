-- Preserve accurate, content-free lifecycle reasons. No changes to admission,
-- fingerprinting, charging, leases or retention windows.
-- Saved items already occupy version 20261006000000 on the demo project.
create or replace function public.recap_projection(j public.recap_jobs) returns jsonb
language sql stable set search_path = public as $$
 select jsonb_build_object('sessionId',j.session_id,'cloudBookId',j.cloud_book_id,
 'state',case when j.result_expires_at<=now() or (j.state in ('queued','running') and j.expires_at<=now()) then 'deleted' else j.state end,
 'language',j.language,'endedAt',j.ended_at,'position',j.position,
 'summary',case when j.result_expires_at>now() then j.summary end,'model',j.model,
 'errorCode',case when j.result_expires_at<=now() or (j.state in ('queued','running') and j.expires_at<=now()) then 'EXCERPT_EXPIRED' else j.error_code end,
 'changeId',j.change_id,'expiresAt',floor(extract(epoch from j.result_expires_at)*1000)::bigint);
$$;

create function public.recap_lifecycle_reason() returns trigger
language plpgsql security definer set search_path = public as $$
begin
 if new.state='failed' and new.error_code='EXCERPT_EXPIRED' and old.expires_at>now() and old.attempts<5 then
   new.error_code := case
     when exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=old.user_id) then 'ACCOUNT_DELETED'
     when not exists(select 1 from public.recap_consent where user_id=old.user_id and enabled) then 'CONSENT_WITHDRAWN'
     when not exists(select 1 from public.cloud_feature_allowlist where cloud_user_id=old.user_id and feature='recap') then 'ACCESS_REMOVED'
     when old.cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=old.cloud_book_id and deleted_at is null) then 'BOOK_DELETED'
     else 'EXCERPT_EXPIRED' end;
 elsif new.state='deleted' and new.error_code is distinct from 'CONSENT_WITHDRAWN' then
   new.error_code := case
     when exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=old.user_id) then 'ACCOUNT_DELETED'
     when old.cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=old.cloud_book_id and deleted_at is null) then 'BOOK_DELETED'
     else coalesce(new.error_code,'UNKNOWN') end;
 end if;
 return new;
end; $$;
revoke all on function public.recap_lifecycle_reason() from public,anon,authenticated,service_role;
create trigger recap_lifecycle_reason before update on public.recap_jobs
for each row execute function public.recap_lifecycle_reason();
