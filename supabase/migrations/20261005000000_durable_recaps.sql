-- Account-owned recap records are authoritative; pgmq contains IDs only.
create extension if not exists pgmq;
select pgmq.create('recap_jobs');
revoke all on schema pgmq from anon,authenticated;
revoke all on all tables in schema pgmq from public,anon,authenticated;
revoke all on all functions in schema pgmq from public,anon,authenticated;

create table public.recap_consent (
    user_id uuid primary key references auth.users(id) on delete cascade,
    enabled boolean not null default false,
    version integer not null default 2,
    updated_at timestamptz not null default now()
);
create table public.recap_jobs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    session_id text not null check (length(session_id) between 1 and 128),
    -- Retain identity in content-free tombstones even after physical book deletion.
    cloud_book_id uuid,
    fingerprint text not null,
    state text not null default 'queued' check (state in ('queued','running','completed','not_enough','failed','deleted')),
    language text,
    ended_at bigint,
    position jsonb,
    excerpt text,
    last_sentence text,
    summary text check (length(summary) <= 600),
    model text,
    error_code text,
    charged boolean not null default false,
    attempts integer not null default 0,
    lease uuid,
    lease_until timestamptz,
    next_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default now() + interval '24 hours',
    result_expires_at timestamptz not null default now() + interval '180 days',
    change_id bigint not null default 0,
    unique (user_id, session_id)
);
create index recap_jobs_due on public.recap_jobs(next_at) where state in ('queued','running');
create index recap_jobs_feed on public.recap_jobs(user_id, cloud_book_id, change_id);
alter table public.recap_jobs enable row level security;
alter table public.recap_consent enable row level security;
revoke all on public.recap_jobs, public.recap_consent from anon, authenticated;
grant select, insert, update, delete on public.recap_jobs, public.recap_consent to service_role;
-- Only RPC projections expose results, never temporary text or the fingerprint.

create function public.recap_projection(j public.recap_jobs) returns jsonb
language sql stable set search_path = public as $$
 select jsonb_build_object('sessionId',j.session_id,'cloudBookId',j.cloud_book_id,
 'state',case when j.result_expires_at<=now() or (j.state in ('queued','running') and j.expires_at<=now()) then 'deleted' else j.state end,
 'language',j.language,'endedAt',j.ended_at,'position',j.position,
 'summary',case when j.result_expires_at>now() then j.summary end,'model',j.model,'errorCode',j.error_code,
 'changeId',j.change_id,'expiresAt',floor(extract(epoch from j.result_expires_at)*1000)::bigint);
$$;
revoke all on function public.recap_projection(public.recap_jobs) from public, anon, authenticated;

create function public.recap_emit(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare j public.recap_jobs; c bigint;
begin
 select * into j from public.recap_jobs where id=p_id;
 if not found or not exists(select 1 from auth.users where id=j.user_id) then return; end if;
 perform pg_advisory_xact_lock(hashtextextended(j.user_id::text,0));
 -- Old events must not retain summaries after deletion/expiry/withdrawal.
 update public.sync_changes set payload='{}'::jsonb
 where cloud_user_id=j.user_id and entity_type='recap' and entity_id=j.session_id;
 insert into public.sync_changes(cloud_user_id,entity_type,entity_id,operation,payload,revision)
 values(j.user_id,'recap',j.session_id,case when j.state='deleted' then 'delete' else 'upsert' end,
 public.recap_projection(j),1) returning change_id into c;
 update public.recap_jobs set change_id=c where id=p_id;
end; $$;
revoke all on function public.recap_emit(uuid) from public, anon, authenticated, service_role;

create function public.set_recap_consent(p_enabled boolean) returns void
language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); j record;
begin
 if actor is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 if p_enabled and (not public.cloud_feature_enabled('recap') or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=actor)) then raise exception 'Recaps unavailable'; end if;
 insert into public.recap_consent(user_id,enabled) values(actor,p_enabled)
 on conflict(user_id) do update set enabled=excluded.enabled,version=2,updated_at=now();
 if not p_enabled then
   delete from public.recap_upload_parts where user_id=actor;
   for j in update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,
       summary=null,model=null,language=null,ended_at=null,position=null,lease=null,lease_until=null,error_code='CONSENT_WITHDRAWN'
       where user_id=actor and state<>'deleted' returning id loop
     perform public.recap_emit(j.id);
   end loop;
 end if;
end; $$;
revoke all on function public.set_recap_consent(boolean) from public,anon;
grant execute on function public.set_recap_consent(boolean) to authenticated;

create function public.submit_recap_job(p_session_id text,p_cloud_book_id uuid,p_language text,
 p_ended_at bigint,p_position jsonb,p_excerpt text,p_last_sentence text,p_upload_id uuid default null,p_part_count integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); j public.recap_jobs; content text:=p_excerpt; fp text; head text; parts integer; held bigint; field record;
begin
 if actor is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 if not public.cloud_feature_enabled('recap') or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=actor)
  or not exists(select 1 from public.recap_consent where user_id=actor and enabled and version=2) then raise exception 'Consent required' using errcode='42501'; end if;
 if p_cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=p_cloud_book_id and cloud_user_id=actor and deleted_at is null) then raise exception 'Book unavailable' using errcode='42501'; end if;
 if p_upload_id is not null then
   -- Join without consuming. take_recap_upload deletes unconditionally, so
   -- the soft rejections below destroyed the client's staged text and left
   -- the invited retry with nothing to resubmit. Only the paths that keep a
   -- job delete the parts.
   select count(*),string_agg(p.content,'' order by p.part_index) into parts,head
   from public.recap_upload_parts p
   where p.user_id=actor and p.upload_id=p_upload_id and p.part_count=p_part_count;
   if p_part_count is null or parts<>p_part_count-1 then
     -- Unusable staging: a retry starts over with a new id, as take_recap_upload
     -- documented. Staged text is dropped only here and on the kept-job paths.
     delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
     return jsonb_build_object('error','upload_incomplete');
   end if;
   content:=head || coalesce(p_excerpt,'');
 end if;
 -- Match the provider's existing most-recent-text budget; reject oversized metadata.
 content:=right(content,2000000);
 if p_session_id is null or length(p_session_id) not between 1 and 128 or content is null or length(trim(content))<80
 or p_language is null or p_language not in ('en','sl','de','fr','es','it','hr')
 or p_ended_at is null or p_ended_at<0 or p_ended_at>floor(extract(epoch from now())*1000)+300000
 or p_position is null or jsonb_typeof(p_position)<>'object' or octet_length(p_position::text)>4096
 or length(coalesce(p_last_sentence,''))>300 then raise exception 'Invalid recap input' using errcode='22023'; end if;
 for field in select key,value from jsonb_each(p_position) loop
   if field.key not in ('href','progression','totalProgression') then raise exception 'Invalid position' using errcode='22023'; end if;
   if field.value='null'::jsonb then continue; end if;
   if field.key='href' then
     if jsonb_typeof(field.value)<>'string' or length(field.value#>>'{}')>2048 then raise exception 'Invalid position' using errcode='22023'; end if;
   elsif jsonb_typeof(field.value)<>'number' then raise exception 'Invalid position' using errcode='22023';
   elsif (field.value#>>'{}')::numeric not between 0 and 1 then raise exception 'Invalid position' using errcode='22023'; end if;
 end loop;
 fp:=encode(extensions.digest(convert_to(jsonb_build_array(p_cloud_book_id,p_language,p_ended_at,p_position,content,p_last_sentence)::text,'UTF8'),'sha256'),'hex');
 select * into j from public.recap_jobs where user_id=actor and session_id=p_session_id;
 if found then
   if j.fingerprint<>fp then return jsonb_build_object('error','conflict'); end if;
   -- The existing job owns the text; the staging is consumed only now.
   delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
   return public.recap_projection(j);
 end if;
 perform pg_advisory_xact_lock(hashtextextended('recap-pending-storage',0));
 select coalesce(sum(octet_length(excerpt)),0) into held from public.recap_jobs where state in ('queued','running');
 -- Deployment-wide 32 MiB pending text budget leaves room on Free's 500 MiB DB.
 if held+octet_length(content)>33554432 or (select count(*) from public.recap_jobs where user_id=actor and state in ('queued','running'))>=10 then
   return jsonb_build_object('error','storage_limit');
 end if;
 insert into public.recap_jobs(user_id,session_id,cloud_book_id,fingerprint,language,ended_at,position,excerpt,last_sentence)
 values(actor,p_session_id,p_cloud_book_id,fp,p_language,p_ended_at,p_position,content,p_last_sentence) returning * into j;
 -- A kept job owns the joined text; the staging is consumed only now.
 delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
 perform pgmq.send('recap_jobs',jsonb_build_object('id',j.id));
 perform public.recap_emit(j.id);
 select * into j from public.recap_jobs where id=j.id;
 return public.recap_projection(j);
end; $$;
revoke all on function public.submit_recap_job(text,uuid,text,bigint,jsonb,text,text,uuid,integer) from public,anon;
grant execute on function public.submit_recap_job(text,uuid,text,bigint,jsonb,text,text,uuid,integer) to authenticated;

create function public.fetch_recap_jobs(p_session_id text default null,p_cloud_book_id uuid default null,p_cursor bigint default 0,p_limit integer default 100)
returns jsonb language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); items jsonb; last_id bigint; more boolean;
begin
 if actor is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 if p_cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=p_cloud_book_id and cloud_user_id=actor) then raise exception 'Book unavailable' using errcode='42501'; end if;
 select coalesce(jsonb_agg(public.recap_projection(r) order by r.change_id),'[]'),coalesce(max(r.change_id),p_cursor)
 into items,last_id from (select * from public.recap_jobs where user_id=actor
 and (p_session_id is null or session_id=p_session_id) and (p_cloud_book_id is null or cloud_book_id=p_cloud_book_id)
 and change_id>greatest(p_cursor,0) order by change_id limit least(greatest(p_limit,1),100)) r;
 select exists(select 1 from public.recap_jobs where user_id=actor and change_id>last_id
 and (p_session_id is null or session_id=p_session_id) and (p_cloud_book_id is null or cloud_book_id=p_cloud_book_id)) into more;
 return jsonb_build_object('items',items,'nextCursor',last_id,'hasMore',more,
 'consentEnabled',exists(select 1 from public.recap_consent where user_id=actor and enabled and version=2));
end; $$;
revoke all on function public.fetch_recap_jobs(text,uuid,bigint,integer) from public,anon;
grant execute on function public.fetch_recap_jobs(text,uuid,bigint,integer) to authenticated;

create function public.delete_recap_job(p_session_id text) returns void
language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); job uuid;
begin
 if actor is null then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
 language=null,ended_at=null,position=null,lease=null,lease_until=null,error_code=null
 where user_id=actor and session_id=p_session_id returning id into job;
 if job is not null then perform public.recap_emit(job); end if;
end; $$;
revoke all on function public.delete_recap_job(text) from public,anon;
grant execute on function public.delete_recap_job(text) to authenticated;

create function public.claim_recap_job(p_limit integer) returns jsonb
language plpgsql security definer set search_path = public as $$
declare m record; j public.recap_jobs; saved_claims text; saved_sub text; allowed boolean; token uuid;
begin
 if auth.role()<>'service_role' then raise exception 'Service role required'; end if;
 -- One job per invocation fits Free's 150 s wall-clock budget.
 select * into m from pgmq.read('recap_jobs',180,1);
 if not found then return null; end if;
 select * into j from public.recap_jobs where id=(m.message->>'id')::uuid;
 if not found then perform pgmq.delete('recap_jobs',m.msg_id); return null; end if;
 -- Lock account BEFORE row, same order as consent, completion and sync.
 perform pg_advisory_xact_lock(hashtextextended(j.user_id::text,0));
 select * into j from public.recap_jobs where id=j.id for update;
 if not found or j.state not in ('queued','running') then perform pgmq.delete('recap_jobs',m.msg_id); return null; end if;
 if j.expires_at<=now() or j.attempts>=5 or not exists(select 1 from public.recap_consent where user_id=j.user_id and enabled)
 or not exists(select 1 from public.cloud_feature_allowlist where cloud_user_id=j.user_id and feature='recap')
 or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=j.user_id)
 or (j.cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=j.cloud_book_id and deleted_at is null)) then
   update public.recap_jobs set state='failed',error_code=case when j.attempts>=5 then 'MAX_ATTEMPTS' else 'EXCERPT_EXPIRED' end,
   excerpt=null,last_sentence=null,lease=null,lease_until=null where id=j.id;
   perform pgmq.delete('recap_jobs',m.msg_id); perform public.recap_emit(j.id); return null;
 end if;
 if j.next_at>now() or (j.state='running' and j.lease_until>now()) then return null; end if;
 if not j.charged then
   -- Reuse the hardened quota algorithm, within this admission transaction.
   -- Caller is service-only; temporary claims are restored before returning.
   saved_claims:=current_setting('request.jwt.claims',true);
   saved_sub:=current_setting('request.jwt.claim.sub',true);
   perform set_config('request.jwt.claim.sub',j.user_id::text,true);
   perform set_config('request.jwt.claims',jsonb_build_object('sub',j.user_id,'role','authenticated','is_anonymous',false)::text,true);
   allowed:=public.consume_recap_quota(p_limit);
   perform set_config('request.jwt.claims',coalesce(saved_claims,''),true);
   perform set_config('request.jwt.claim.sub',coalesce(saved_sub,''),true);
   if not allowed then
     update public.recap_jobs set next_at=(date_trunc('day',now() at time zone 'UTC') at time zone 'UTC')+interval '1 day' where id=j.id returning * into j;
     perform pgmq.set_vt('recap_jobs',m.msg_id,greatest(1,ceil(extract(epoch from j.next_at-now()))::integer));
     return null;
   end if;
 end if;
 token:=gen_random_uuid();
 update public.recap_jobs set state='running',charged=true,attempts=attempts+1,lease=token,lease_until=now()+interval '3 minutes'
 where id=j.id returning * into j;
 perform public.recap_emit(j.id);
 return jsonb_build_object('id',j.id,'lease',token,'messageId',m.msg_id,'excerpt',j.excerpt,'lastSentence',j.last_sentence,'language',j.language);
end; $$;
revoke all on function public.claim_recap_job(integer) from public,anon,authenticated;
grant execute on function public.claim_recap_job(integer) to service_role;

create function public.finish_recap_job(p_id uuid,p_lease uuid,p_message_id bigint,p_state text,p_summary text,p_model text,p_retry boolean,p_retry_after integer default null)
returns boolean language plpgsql security definer set search_path = public as $$
declare j public.recap_jobs; backoff_seconds integer;
begin
 if auth.role()<>'service_role' then raise exception 'Service role required'; end if;
 select * into j from public.recap_jobs where id=p_id;
 if not found then return false; end if;
 perform pg_advisory_xact_lock(hashtextextended(j.user_id::text,0));
 select * into j from public.recap_jobs where id=p_id for update;
 if not found or j.state<>'running' or j.lease is distinct from p_lease or j.lease_until<=now()
 or j.expires_at<=now() or not exists(select 1 from public.recap_consent where user_id=j.user_id and enabled)
 or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=j.user_id)
 or not exists(select 1 from public.cloud_feature_allowlist where cloud_user_id=j.user_id and feature='recap')
 or (j.cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=j.cloud_book_id and deleted_at is null)) then return false; end if;
 if p_state not in ('completed','not_enough','failed') or (p_state='completed' and (p_summary is null or length(trim(p_summary)) not between 1 and 600)) then raise exception 'Invalid outcome'; end if;
 if p_state='failed' and p_retry and j.attempts<5 then
   backoff_seconds:=greatest(least(21600,60*power(2,j.attempts-1)::integer),least(86400,coalesce(p_retry_after,0)));
   update public.recap_jobs set state='queued',lease=null,lease_until=null,error_code='PROVIDER_ERROR',next_at=now()+make_interval(secs=>backoff_seconds) where id=p_id;
   perform pgmq.set_vt('recap_jobs',p_message_id,backoff_seconds);
 else
   update public.recap_jobs set state=p_state,summary=case when p_state='completed' then p_summary end,
   model=left(p_model,100),error_code=case when p_state='failed' then 'PROVIDER_ERROR' end,
   excerpt=null,last_sentence=null,lease=null,lease_until=null,result_expires_at=now()+interval '180 days' where id=p_id;
   perform pgmq.delete('recap_jobs',p_message_id);
 end if;
 perform public.recap_emit(p_id); return true;
end; $$;
revoke all on function public.finish_recap_job(uuid,uuid,bigint,text,text,text,boolean,integer) from public,anon,authenticated;
grant execute on function public.finish_recap_job(uuid,uuid,bigint,text,text,text,boolean,integer) to service_role;

create function public.cleanup_recap_jobs() returns void
language plpgsql security definer set search_path = public as $$
declare account record; j record;
begin
 if auth.role()<>'service_role' then raise exception 'Service role required'; end if;
 for account in select distinct user_id from public.recap_jobs where
 (state in ('queued','running') and expires_at<=now()) or (state<>'deleted' and result_expires_at<=now()) loop
   perform pg_advisory_xact_lock(hashtextextended(account.user_id::text,0));
   for j in update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
       language=null,ended_at=null,position=null,lease=null,lease_until=null,error_code='EXCERPT_EXPIRED'
       where user_id=account.user_id and ((state in ('queued','running') and expires_at<=now()) or (state<>'deleted' and result_expires_at<=now())) returning id loop
     perform public.recap_emit(j.id);
   end loop;
 end loop;
 delete from public.recap_upload_parts where created_at<now()-interval '1 hour';
 -- Delete terminal/missing queue messages, not archive them.
 delete from pgmq.q_recap_jobs q where not exists(select 1 from public.recap_jobs job_row where job_row.id=(q.message->>'id')::uuid and job_row.state in ('queued','running'));
end; $$;
revoke all on function public.cleanup_recap_jobs() from public,anon,authenticated;
grant execute on function public.cleanup_recap_jobs() to service_role;

-- Cancel and scrub immediately when a cloud book or account deletion begins.
create function public.cancel_deleted_book_recaps() returns trigger
language plpgsql security definer set search_path = public as $$
declare j record;
begin
 perform pg_advisory_xact_lock(hashtextextended(coalesce(new.cloud_user_id,old.cloud_user_id)::text,0));
 for j in update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
 language=null,ended_at=null,position=null,lease=null,lease_until=null where cloud_book_id=coalesce(new.id,old.id) and state<>'deleted' returning id loop
 perform public.recap_emit(j.id); end loop;
 if tg_op='DELETE' then return old; end if;
 return new;
end; $$;
create trigger recap_book_deleted after update of deleted_at on public.cloud_books for each row when (new.deleted_at is not null) execute function public.cancel_deleted_book_recaps();
create trigger recap_book_purged before delete on public.cloud_books for each row execute function public.cancel_deleted_book_recaps();
create function public.cancel_account_recaps() returns trigger
language plpgsql security definer set search_path = public as $$
declare j record;
begin
 perform pg_advisory_xact_lock(hashtextextended(new.cloud_user_id::text,0));
 delete from public.recap_upload_parts where user_id=new.cloud_user_id;
 update public.recap_consent set enabled=false where user_id=new.cloud_user_id;
 for j in update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
 language=null,ended_at=null,position=null,lease=null,lease_until=null where user_id=new.cloud_user_id and state<>'deleted' returning id loop
 perform public.recap_emit(j.id); end loop;
 return new;
end; $$;
create trigger recap_account_deleted after insert or update on public.cloud_account_deletion_requests for each row execute function public.cancel_account_recaps();
revoke all on function public.cancel_deleted_book_recaps(), public.cancel_account_recaps() from public,anon,authenticated,service_role;

-- Disable legacy direct quota/upload paths: old clients must obtain new consent.
revoke execute on function public.consume_recap_quota(integer) from authenticated;
alter function public.put_recap_upload_part(uuid,integer,integer,text) rename to put_recap_upload_part_legacy;
revoke all on function public.put_recap_upload_part_legacy(uuid,integer,integer,text) from public,anon,authenticated,service_role;
create function public.put_recap_upload_part(p_upload_id uuid,p_part_index integer,p_part_count integer,p_content text)
returns boolean language plpgsql security definer set search_path = public as $$
begin
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,0));
 if not exists(select 1 from public.recap_consent where user_id=auth.uid() and enabled and version=2)
 or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=auth.uid()) then return false; end if;
 -- Bound staging storage globally, including parallel accounts.
 perform pg_advisory_xact_lock(hashtextextended('recap-staging',0));
 if (select coalesce(sum(octet_length(content)),0) from public.recap_upload_parts)+octet_length(p_content)>16777216 then return false; end if;
 return public.put_recap_upload_part_legacy(p_upload_id,p_part_index,p_part_count,p_content);
end; $$;
revoke all on function public.put_recap_upload_part(uuid,integer,integer,text) from public,anon,service_role;
grant execute on function public.put_recap_upload_part(uuid,integer,integer,text) to authenticated;
revoke execute on function public.take_recap_upload(uuid,integer) from authenticated;
