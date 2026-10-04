-- Preserve deletion intent even when a timed-out submission has not arrived yet.
create or replace function public.submit_recap_job(p_session_id text,p_cloud_book_id uuid,p_language text,
 p_ended_at bigint,p_position jsonb,p_excerpt text,p_last_sentence text,p_upload_id uuid default null,p_part_count integer default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); j public.recap_jobs; content text:=p_excerpt; fp text; head text; parts integer; held bigint; field record;
begin
 if actor is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 if not public.cloud_feature_enabled('recap') or exists(select 1 from public.cloud_account_deletion_requests where cloud_user_id=actor)
  or not exists(select 1 from public.recap_consent where user_id=actor and enabled and version=2) then raise exception 'Consent required' using errcode='42501'; end if;
 -- Deletion wins even if this request was delayed before job admission.
 -- A content-free tombstone has no input fingerprint to compare.
 select * into j from public.recap_jobs where user_id=actor and session_id=p_session_id and state='deleted';
 if found then
   delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
   return public.recap_projection(j);
 end if;
 if p_cloud_book_id is not null and not exists(select 1 from public.cloud_books where id=p_cloud_book_id and cloud_user_id=actor and deleted_at is null) then raise exception 'Book unavailable' using errcode='42501'; end if;
 if p_upload_id is not null then
   -- Rejected submissions keep staging; only kept jobs consume it.
   select count(*),string_agg(p.content,'' order by p.part_index) into parts,head
   from public.recap_upload_parts p
   where p.user_id=actor and p.upload_id=p_upload_id and p.part_count=p_part_count;
   if p_part_count is null or parts<>p_part_count-1 then
     delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
     return jsonb_build_object('error','upload_incomplete');
   end if;
   content:=head || coalesce(p_excerpt,'');
 end if;
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
   delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
   return public.recap_projection(j);
 end if;
 perform pg_advisory_xact_lock(hashtextextended('recap-pending-storage',0));
 select coalesce(sum(octet_length(excerpt)),0) into held from public.recap_jobs where state in ('queued','running');
 if held+octet_length(content)>33554432 or (select count(*) from public.recap_jobs where user_id=actor and state in ('queued','running'))>=10 then
   return jsonb_build_object('error','storage_limit');
 end if;
 insert into public.recap_jobs(user_id,session_id,cloud_book_id,fingerprint,language,ended_at,position,excerpt,last_sentence)
 values(actor,p_session_id,p_cloud_book_id,fp,p_language,p_ended_at,p_position,content,p_last_sentence) returning * into j;
 delete from public.recap_upload_parts where user_id=actor and upload_id=p_upload_id;
 perform pgmq.send('recap_jobs',jsonb_build_object('id',j.id));
 perform public.recap_emit(j.id);
 select * into j from public.recap_jobs where id=j.id;
 return public.recap_projection(j);
end; $$;

create or replace function public.delete_recap_job(p_session_id text) returns void
language plpgsql security definer set search_path = public as $$
declare actor uuid:=auth.uid(); job uuid;
begin
 if actor is null then raise exception 'Authentication required'; end if;
 perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
 -- Use the submission lock so deletion wins regardless of request arrival order.
 insert into public.recap_jobs(user_id,session_id,fingerprint,state)
 values(actor,p_session_id,'','deleted')
 on conflict(user_id,session_id) do update set state='deleted',excerpt=null,last_sentence=null,summary=null,model=null,
 language=null,ended_at=null,position=null,lease=null,lease_until=null,error_code=null
 returning id into job;
 perform public.recap_emit(job);
end; $$;
