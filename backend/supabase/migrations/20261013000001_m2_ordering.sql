-- QR Dine M2 / 005: ordering functions used by the Edge Functions (service role only),
-- plus void_session for the owner. All logic that touches money or state lives here, atomically.

create or replace function public.restaurant_can_take_orders(p_rid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((
    select r.sub_status in ('active', 'grace') or (r.sub_status = 'trial' and r.trial_ends_at > now())
    from restaurants r where r.id = p_rid
  ), false)
$$;

-- open-ordering mode: first valid scan opens the table
create or replace function public.open_session_if_needed(p_table_id uuid) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_t dining_tables%rowtype; v_sid uuid;
begin
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found then raise exception 'table_not_found'; end if;
  if v_t.status <> 'closed' and v_t.active_session_id is not null then return v_t.active_session_id; end if;
  insert into sessions (restaurant_id, table_id) values (v_t.restaurant_id, v_t.id) returning id into v_sid;
  update dining_tables set status = 'active', active_session_id = v_sid where id = v_t.id;
  return v_sid;
end $$;

-- returns: none | pin_required | ok | bad | locked.  Five wrong PINs lock the session for 10 minutes.
create or replace function public.verify_session_pin(p_session_id uuid, p_pin text) returns text
language plpgsql security definer set search_path = public, extensions as $$
declare v session_secrets%rowtype;
begin
  select * into v from session_secrets where session_id = p_session_id for update;
  if not found then return 'none'; end if;
  if v.locked_until is not null and v.locked_until > now() then return 'locked'; end if;
  if p_pin is null or p_pin = '' then return 'pin_required'; end if;   -- not counted as an attempt
  if v.pin_hash = crypt(p_pin, v.pin_hash) then
    update session_secrets set attempts = 0, locked_until = null where session_id = p_session_id;
    return 'ok';
  end if;
  if v.attempts + 1 >= 5 then
    update session_secrets set attempts = 0, locked_until = now() + interval '10 minutes' where session_id = p_session_id;
  else
    update session_secrets set attempts = attempts + 1 where session_id = p_session_id;
  end if;
  return 'bad';
end $$;

-- Prices come from the dishes table, never from the client. Atomic and idempotent per (session, client_key).
create or replace function public.place_order(
  p_session_id uuid, p_client_key text, p_order_type public.order_type,
  p_note text, p_items jsonb, p_max_total int default 2500000
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_o orders%rowtype; v_d dishes%rowtype;
  v_it jsonb; v_qty int; v_total bigint := 0; v_oid uuid;
begin
  select * into v_s from sessions where id = p_session_id for update;   -- serialises orders per session
  if not found or v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if not public.restaurant_can_take_orders(v_s.restaurant_id) then raise exception 'subscription_inactive'; end if;

  select * into v_o from orders where session_id = p_session_id and client_key = p_client_key;
  if found then return jsonb_build_object('order_id', v_o.id, 'duplicate', true); end if;

  if (select count(*) from orders where session_id = p_session_id and created_at > now() - interval '10 minutes') >= 5
  then raise exception 'rate_limited'; end if;

  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) not between 1 and 40 then raise exception 'bad_items'; end if;

  insert into orders (restaurant_id, session_id, order_type, note, client_key)
  values (v_s.restaurant_id, p_session_id, p_order_type, nullif(left(trim(coalesce(p_note, '')), 300), ''), p_client_key)
  returning id into v_oid;

  for v_it in select * from jsonb_array_elements(p_items) loop
    v_qty := (v_it ->> 'qty')::int;
    if v_qty is null or v_qty not between 1 and 50 then raise exception 'bad_items'; end if;
    select * into v_d from dishes
     where id = (v_it ->> 'dish_id')::uuid and restaurant_id = v_s.restaurant_id and deleted_at is null;
    if not found then raise exception 'dish_not_found'; end if;
    if not v_d.is_available then raise exception 'dish_unavailable:%', v_d.name; end if;
    v_total := v_total + v_d.base_price::bigint * v_qty;
    insert into order_items (restaurant_id, session_id, order_id, dish_id, name_snapshot, qty, unit_price, instructions)
    values (v_s.restaurant_id, p_session_id, v_oid, v_d.id, v_d.name, v_qty, v_d.base_price,
            nullif(left(trim(coalesce(v_it ->> 'instructions', '')), 200), ''));
  end loop;

  if v_total > p_max_total then raise exception 'order_too_large'; end if;

  if v_s.status = 'bill_requested' then   -- reorder after asking for the bill reopens the session
    update sessions set status = 'active' where id = p_session_id;
    update dining_tables set status = 'active' where id = v_s.table_id;
  end if;
  return jsonb_build_object('order_id', v_oid, 'total', v_total, 'duplicate', false);
end $$;

create or replace function public.request_service(p_session_id uuid, p_type public.request_type) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_id uuid;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  select id into v_id from service_requests where session_id = p_session_id and type = p_type and status = 'open' limit 1;
  if found then return jsonb_build_object('request_id', v_id, 'duplicate', true); end if;
  if (select count(*) from service_requests where session_id = p_session_id and created_at > now() - interval '1 hour') >= 6
  then raise exception 'rate_limited'; end if;
  insert into service_requests (restaurant_id, session_id, type) values (v_s.restaurant_id, p_session_id, p_type)
  returning id into v_id;
  if p_type = 'bill' then
    update sessions set status = 'bill_requested' where id = p_session_id;
    update dining_tables set status = 'bill_requested' where id = v_s.table_id;
  end if;
  return jsonb_build_object('request_id', v_id, 'duplicate', false);
end $$;

-- Owner: cancel a session opened by mistake. Blocked once real orders exist (those must be billed).
create or replace function public.void_session(p_table_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_t dining_tables%rowtype;
begin
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found or not public.is_owner_of(v_t.restaurant_id) then raise exception 'table_not_found'; end if;
  if v_t.active_session_id is null then raise exception 'table_not_active'; end if;
  if exists (select 1 from orders where session_id = v_t.active_session_id and status in ('pending', 'accepted', 'completed'))
  then raise exception 'session_has_orders'; end if;
  update sessions set status = 'void', closed_at = now() where id = v_t.active_session_id;
  update dining_tables set status = 'closed', active_session_id = null where id = p_table_id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id)
    values (v_t.restaurant_id, auth.uid(), 'void', 'session', v_t.active_session_id);
end $$;

revoke all on function public.restaurant_can_take_orders(uuid), public.open_session_if_needed(uuid),
  public.verify_session_pin(uuid, text), public.place_order(uuid, text, public.order_type, text, jsonb, int),
  public.request_service(uuid, public.request_type), public.void_session(uuid) from public, anon, authenticated;
grant execute on function public.restaurant_can_take_orders(uuid), public.open_session_if_needed(uuid),
  public.verify_session_pin(uuid, text), public.place_order(uuid, text, public.order_type, text, jsonb, int),
  public.request_service(uuid, public.request_type) to service_role;
grant execute on function public.void_session(uuid) to authenticated;
