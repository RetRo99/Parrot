-- Unknown-session fences are necessary for delayed submissions, but must not
-- provide an unrestricted permanent-storage API. Existing jobs remain deletable
-- even after consent/allowlist withdrawal or exhaustion of the fence budget.
create index recap_unknown_deletion_fences on public.recap_jobs(user_id,created_at)
where state='deleted' and fingerprint='';

create or replace function public.delete_recap_job(p_session_id text) returns void
language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); j public.recap_jobs;
begin
 if actor is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then
   raise exception 'Authentication required' using errcode='42501';
 end if;
 if p_session_id is null or length(p_session_id) not between 1 and 128 then
   raise exception 'Invalid session' using errcode='22023';
 end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 select * into j from public.recap_jobs where user_id=actor and session_id=p_session_id;
 if found then
   -- Idempotent privacy commands must not grow the feed on every replay.
   if j.state='deleted' then return; end if;
   update public.recap_jobs set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
     language=null,ended_at=null,position=null,lease=null,lease_until=null,error_code=null where id=j.id;
 else
   if not public.cloud_feature_enabled('recap') or
     not exists(select 1 from public.recap_consent where user_id=actor and version=2) then
     raise exception 'Recap access denied' using errcode='42501';
   end if;
   -- Serialize the deployment-wide bound across accounts, in addition to the
   -- account lock shared with submission. Only unknown fences consume it.
   perform pg_advisory_xact_lock(hashtextextended('recap-deletion-storage',0));
   if (select count(*) from public.recap_jobs where user_id=actor and state='deleted' and fingerprint=''
       and created_at>=date_trunc('day',now() at time zone 'UTC') at time zone 'UTC')>=100
     or (select count(*) from public.recap_jobs where user_id=actor and state='deleted' and fingerprint='')>=1000
     or (select count(*) from public.recap_jobs where state='deleted' and fingerprint='')>=10000 then
     raise exception 'Recap deletion limit reached' using errcode='54000';
   end if;
   insert into public.recap_jobs(user_id,session_id,fingerprint,state)
     values(actor,p_session_id,'','deleted') returning * into j;
 end if;
 perform public.recap_emit(j.id);
end; $$;

-- Update already-installed schedulers too. Fresh deployments install the
-- guarded trigger from recap-scheduler.sql after migrations are applied.
do $$ begin
 if to_regprocedure('public.recap_insert_wakeup()') is not null then
   drop trigger if exists recap_insert_wakeup on public.recap_jobs;
   create trigger recap_insert_wakeup after insert on public.recap_jobs for each row
     when (new.state='queued') execute function public.recap_insert_wakeup();
 end if;
end; $$;
