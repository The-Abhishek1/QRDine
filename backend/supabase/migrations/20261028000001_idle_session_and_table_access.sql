-- Expire abandoned empty sessions when the next guest scans, and restore explicit
-- table privileges in case an older deployment missed the original grant.
create or replace function public.open_session_if_needed(p_table_id uuid) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_t dining_tables%rowtype;
  v_s sessions%rowtype;
  v_sid uuid;
  v_pin_hash text;
begin
  loop
    select * into v_t from dining_tables where id = p_table_id;
    if not found then raise exception 'table_not_found'; end if;

    if v_t.status <> 'closed' and v_t.active_session_id is not null then
      v_sid := v_t.active_session_id;
      select * into v_s from sessions where id = v_sid for update;
      if not found then raise exception 'table_session_not_found'; end if;

      select * into v_t from dining_tables where id = p_table_id for update;
      if not found then raise exception 'table_not_found'; end if;
      if v_t.active_session_id is distinct from v_sid then continue; end if;
      if v_t.status = 'closed' then raise exception 'table_session_inconsistent'; end if;
      if v_s.status not in ('active', 'bill_requested') then
        update dining_tables set status = 'closed', active_session_id = null where id = v_t.id;
        continue;
      end if;

      if v_s.status <> 'active'
         or v_s.opened_at > now() - interval '5 minutes'
         or exists (select 1 from orders where session_id = v_s.id)
         or exists (select 1 from service_requests where session_id = v_s.id and status = 'open')
      then
        return v_s.id;
      end if;

      select pin_hash into v_pin_hash from session_secrets where session_id = v_s.id;
      update sessions set status = 'void', closed_at = now() where id = v_s.id;
      insert into sessions (restaurant_id, table_id) values (v_t.restaurant_id, v_t.id) returning id into v_sid;
      if v_pin_hash is not null then
        insert into session_secrets (session_id, restaurant_id, pin_hash)
        values (v_sid, v_t.restaurant_id, v_pin_hash);
      end if;
      update dining_tables set status = 'active', active_session_id = v_sid where id = v_t.id;
      return v_sid;
    end if;

    select * into v_t from dining_tables where id = p_table_id for update;
    if not found then raise exception 'table_not_found'; end if;
    if v_t.status <> 'closed' and v_t.active_session_id is not null then continue; end if;

    insert into sessions (restaurant_id, table_id) values (v_t.restaurant_id, v_t.id) returning id into v_sid;
    update dining_tables set status = 'active', active_session_id = v_sid where id = v_t.id;
    return v_sid;
  end loop;
end $$;

revoke all on function public.open_session_if_needed(uuid) from public, anon, authenticated;
grant execute on function public.open_session_if_needed(uuid) to service_role;

grant select, insert, delete on table public.dining_tables to authenticated;
grant update (label, qr_key_version) on table public.dining_tables to authenticated;

-- Keep the same session-then-table lock order as ordering and billing.
create or replace function public.void_session(p_table_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  v_t dining_tables%rowtype;
  v_sid uuid;
begin
  select * into v_t from dining_tables where id = p_table_id;
  if not found or not public.has_role(v_t.restaurant_id, array['owner', 'waiter']) then raise exception 'table_not_found'; end if;
  if v_t.active_session_id is null then raise exception 'table_not_active'; end if;
  v_sid := v_t.active_session_id;

  perform 1 from sessions where id = v_sid for update;
  if not found then raise exception 'table_not_active'; end if;
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found or not public.has_role(v_t.restaurant_id, array['owner', 'waiter']) then raise exception 'table_not_found'; end if;
  if v_t.active_session_id is distinct from v_sid then raise exception 'table_not_active'; end if;
  if exists (select 1 from orders where session_id = v_sid and status in ('pending', 'accepted', 'completed'))
  then raise exception 'session_has_orders'; end if;
  update sessions set status = 'void', closed_at = now() where id = v_sid;
  update dining_tables set status = 'closed', active_session_id = null where id = p_table_id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id)
    values (v_t.restaurant_id, auth.uid(), 'void', 'session', v_sid);
end $$;
