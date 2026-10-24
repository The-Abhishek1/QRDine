-- QR Dine M0 / 003: state-changing functions the Android app calls via supabase.rpc(...).
-- SECURITY DEFINER, but each one re-checks ownership from the JWT, and none leak whether a row exists.

create or replace function public.create_restaurant(p_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); v_rid uuid;
begin
  if v_uid is null or coalesce(auth.jwt() ->> 'app_role', '') = 'guest' then raise exception 'not_authenticated'; end if;
  if exists (select 1 from owners where user_id = v_uid) then raise exception 'already_owner'; end if;
  insert into restaurants (name) values (trim(p_name)) returning id into v_rid;
  insert into owners (user_id, restaurant_id) values (v_uid, v_rid);
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_rid, v_uid, 'create', 'restaurant', v_rid);
  return v_rid;   -- app must refresh its session afterwards so the JWT picks up restaurant_id
end $$;

create or replace function public.open_table(p_table_id uuid, p_pin text default null) returns uuid
language plpgsql security definer set search_path = public, extensions as $$
declare v_t dining_tables%rowtype; v_sid uuid;
begin
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found or not public.is_owner_of(v_t.restaurant_id) then raise exception 'table_not_found'; end if;
  if v_t.status <> 'closed' then raise exception 'table_not_closed'; end if;
  insert into sessions (restaurant_id, table_id, opened_by) values (v_t.restaurant_id, v_t.id, auth.uid()) returning id into v_sid;
  if p_pin is not null and p_pin <> '' then
    if p_pin !~ '^[0-9]{4}$' then raise exception 'bad_pin'; end if;
    insert into session_secrets (session_id, restaurant_id, pin_hash)
      values (v_sid, v_t.restaurant_id, crypt(p_pin, gen_salt('bf')));
  end if;
  update dining_tables set status = 'active', active_session_id = v_sid where id = v_t.id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_t.restaurant_id, auth.uid(), 'open', 'session', v_sid);
  return v_sid;
end $$;

create or replace function public.accept_order(p_order_id uuid, p_eta_minutes int) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.is_owner_of(v_rid) then raise exception 'order_not_found'; end if;
  if p_eta_minutes is null or p_eta_minutes not between 1 and 240 then raise exception 'bad_eta'; end if;
  update orders set status = 'accepted', eta_minutes = p_eta_minutes, accepted_at = now()
   where id = p_order_id and status = 'pending';          -- first device to act wins
  if not found then raise exception 'order_not_pending'; end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_rid, auth.uid(), 'accept', 'order', p_order_id, jsonb_build_object('eta', p_eta_minutes));
end $$;

create or replace function public.reject_order(p_order_id uuid, p_reason text) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.is_owner_of(v_rid) then raise exception 'order_not_found'; end if;
  if p_reason is null or char_length(trim(p_reason)) = 0 then raise exception 'reason_required'; end if;
  update orders set status = 'rejected', reject_reason = left(trim(p_reason), 200)
   where id = p_order_id and status = 'pending';
  if not found then raise exception 'order_not_pending'; end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_rid, auth.uid(), 'reject', 'order', p_order_id, jsonb_build_object('reason', left(trim(p_reason), 200)));
end $$;

revoke all on function public.create_restaurant(text), public.open_table(uuid, text),
                       public.accept_order(uuid, int), public.reject_order(uuid, text) from public, anon;
grant execute on function public.create_restaurant(text), public.open_table(uuid, text),
                          public.accept_order(uuid, int), public.reject_order(uuid, text) to authenticated;
