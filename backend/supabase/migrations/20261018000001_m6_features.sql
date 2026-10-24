-- QR Dine M6 / 009: variants and add-ons, stock, timed menus, languages, staff roles, item cancel, ready status,
-- move/merge tables, manager PIN discounts, guest coupons, cash count, suggestions, live-update pings.

-- ---------- columns ----------
alter table public.order_items add column cancel_reason text, add column cancelled_by uuid;
alter table public.orders add column ready_at timestamptz,
  add column source text not null default 'guest' check (source in ('guest', 'staff'));
alter table public.categories add column name_i18n jsonb, add column avail_from time, add column avail_to time;
alter table public.dishes add column name_i18n jsonb, add column description_i18n jsonb,
  add column stock_qty int check (stock_qty is null or stock_qty >= 0);
alter table public.restaurants add column languages text[] not null default '{}';
grant update (languages) on public.restaurants to authenticated;
alter table public.sessions add column guest_coupon text;
alter table public.owners add column role text not null default 'owner' check (role in ('owner', 'waiter', 'kitchen')),
  add column display_name text, add column active boolean not null default true;

create table public.manager_pins (
  restaurant_id uuid primary key references public.restaurants(id) on delete cascade,
  pin_hash text not null, attempts int not null default 0, locked_until timestamptz
);
create table public.cash_counts (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  day date not null, opening_float bigint not null default 0, cash_sales bigint not null default 0,
  expected_cash bigint not null, counted_cash bigint not null, difference bigint not null,
  note text, created_by uuid, created_at timestamptz not null default now(),
  unique (restaurant_id, day)
);
alter table public.manager_pins enable row level security;
alter table public.cash_counts enable row level security;
revoke all on public.manager_pins, public.cash_counts from anon, authenticated;

-- ---------- role helpers ----------
create or replace function public.has_role(rid uuid, roles text[]) returns boolean
language sql stable as $$
  select coalesce(auth.jwt() ->> 'app_role', '') = any (roles) and rid is not null
         and rid = public.jwt_restaurant_id() and public.restaurant_active(rid)
$$;
create or replace function public.is_staff_of(rid uuid) returns boolean
language sql stable as $$ select public.has_role(rid, array['owner', 'waiter', 'kitchen']) $$;
grant execute on function public.has_role(uuid, text[]), public.is_staff_of(uuid) to authenticated;

-- staff (waiter, kitchen) can read what they need; only owners can write menu and settings
create policy staff_select on public.restaurants for select to authenticated using (public.is_staff_of(id));
do $$
declare t text;
begin
  foreach t in array array['dining_tables', 'sessions', 'orders', 'order_items', 'service_requests', 'dishes', 'categories',
                           'dish_variants', 'addon_groups', 'addons', 'dish_addon_groups'] loop
    execute format('create policy staff_select on public.%I for select to authenticated using (public.is_staff_of(restaurant_id))', t);
  end loop;
end $$;
create policy staff_update on public.service_requests for update to authenticated
  using (public.has_role(restaurant_id, array['owner', 'waiter'])) with check (public.has_role(restaurant_id, array['owner', 'waiter']));

-- token hook: owner / waiter / kitchen, or blocked (hotel not approved, on hold, or the staff login disabled)
create or replace function public.custom_access_token_hook(event jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  claims jsonb := event -> 'claims';
  uid uuid := (event ->> 'user_id')::uuid;
  rid uuid; rrole text; ractive boolean; r public.restaurants;
begin
  if exists (select 1 from platform_admins where user_id = uid) then
    claims := jsonb_set(claims, '{app_role}', '"platform_admin"');
  else
    select restaurant_id, role, active into rid, rrole, ractive from owners where user_id = uid;
    if rid is not null then
      select * into r from restaurants where id = rid;
      claims := jsonb_set(claims, '{restaurant_id}', to_jsonb(rid::text));
      claims := jsonb_set(claims, '{hotel_status}', to_jsonb(case when not ractive then 'disabled' else public.hotel_state(r) end));
      claims := jsonb_set(claims, '{app_role}', to_jsonb(case when r.approval = 'approved' and not r.on_hold and ractive then rrole else 'owner_blocked' end));
    end if;
  end if;
  return jsonb_set(event, '{claims}', claims);
end $$;

-- ---------- bill label: "Paneer (Half) + Extra cheese" ----------
create or replace function public.item_label(p_name text, p_variant text, p_addons jsonb) returns text
language sql immutable as $$
  select p_name || coalesce(' (' || p_variant || ')', '')
         || coalesce((select ' + ' || string_agg(a ->> 'name', ', ') from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb)) a), '')
$$;
grant execute on function public.item_label(text, text, jsonb) to authenticated, service_role;

-- ---------- one place that prices and stores order items (guest orders and staff orders both use it) ----------
create or replace function public.add_items(p_order_id uuid, p_session_id uuid, p_rid uuid, p_items jsonb) returns bigint
language plpgsql security definer set search_path = public as $$
declare
  v_it jsonb; v_qty int; v_d dishes%rowtype; v_c categories%rowtype; v_v dish_variants%rowtype; v_addon addons%rowtype; g record;
  v_price bigint; v_variant text; v_total bigint := 0; v_now time; v_in boolean; v_sel uuid[]; v_addons jsonb; v_addon_total bigint; v_cnt int;
begin
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) not between 1 and 40 then raise exception 'bad_items'; end if;
  for v_it in select * from jsonb_array_elements(p_items) loop
    v_qty := (v_it ->> 'qty')::int;
    if v_qty is null or v_qty not between 1 and 50 then raise exception 'bad_items'; end if;
    select * into v_d from dishes where id = (v_it ->> 'dish_id')::uuid and restaurant_id = p_rid and deleted_at is null for update;
    if not found then raise exception 'dish_not_found'; end if;
    if not v_d.is_available then raise exception 'dish_unavailable:%', v_d.name; end if;

    select * into v_c from categories where id = v_d.category_id;
    if v_c.avail_from is not null and v_c.avail_to is not null then          -- timed menu (India time)
      v_now := (now() at time zone 'Asia/Kolkata')::time;
      v_in := case when v_c.avail_from <= v_c.avail_to then v_now >= v_c.avail_from and v_now < v_c.avail_to
                   else v_now >= v_c.avail_from or v_now < v_c.avail_to end;
      if not v_in then raise exception 'dish_unavailable:% (not served right now)', v_d.name; end if;
    end if;

    if v_d.stock_qty is not null then
      if v_d.stock_qty < v_qty then raise exception 'dish_unavailable:% (only % left)', v_d.name, v_d.stock_qty; end if;
      update dishes set stock_qty = stock_qty - v_qty, is_available = case when stock_qty - v_qty = 0 then false else is_available end where id = v_d.id;
    end if;

    v_price := v_d.base_price; v_variant := null;
    if nullif(v_it ->> 'variant_id', '') is not null then
      select * into v_v from dish_variants where id = (v_it ->> 'variant_id')::uuid and dish_id = v_d.id and restaurant_id = p_rid;
      if not found then raise exception 'bad_items'; end if;
      v_price := v_v.price; v_variant := v_v.name;
    elsif exists (select 1 from dish_variants where dish_id = v_d.id) then
      raise exception 'variant_required:%', v_d.name;
    end if;

    v_sel := coalesce(array(select distinct value::uuid from jsonb_array_elements_text(coalesce(v_it -> 'addon_ids', '[]'::jsonb))), '{}'::uuid[]);
    v_addons := '[]'::jsonb; v_addon_total := 0;
    if cardinality(v_sel) > 0 then
      for v_addon in select a.* from addons a join dish_addon_groups dg on dg.group_id = a.group_id and dg.dish_id = v_d.id
                      where a.id = any (v_sel) and a.restaurant_id = p_rid loop
        if not v_addon.is_available then raise exception 'dish_unavailable:% (%)', v_d.name, v_addon.name; end if;
        v_addons := v_addons || jsonb_build_object('id', v_addon.id, 'name', v_addon.name, 'price', v_addon.price);
        v_addon_total := v_addon_total + v_addon.price;
      end loop;
      if jsonb_array_length(v_addons) <> cardinality(v_sel) then raise exception 'bad_items'; end if;
    end if;
    for g in select ag.* from addon_groups ag join dish_addon_groups dg on dg.group_id = ag.id where dg.dish_id = v_d.id loop
      select count(*) into v_cnt from addons a where a.id = any (v_sel) and a.group_id = g.id;
      if v_cnt < g.min_select or v_cnt > g.max_select then raise exception 'addon_choice:%', g.name; end if;
    end loop;

    insert into order_items (restaurant_id, session_id, order_id, dish_id, name_snapshot, variant_snapshot, qty, unit_price, addons, instructions)
    values (p_rid, p_session_id, p_order_id, v_d.id, v_d.name, v_variant, v_qty, (v_price + v_addon_total)::int, v_addons,
            nullif(left(trim(coalesce(v_it ->> 'instructions', '')), 200), ''));
    v_total := v_total + (v_price + v_addon_total) * v_qty;
  end loop;
  return v_total;
end $$;
revoke all on function public.add_items(uuid, uuid, uuid, jsonb) from public, anon, authenticated;

create or replace function public.place_order(
  p_session_id uuid, p_client_key text, p_order_type public.order_type, p_note text, p_items jsonb, p_max_total int default 2500000
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_o orders%rowtype; v_total bigint; v_oid uuid;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if not public.restaurant_can_take_orders(v_s.restaurant_id) then raise exception 'subscription_inactive'; end if;
  select * into v_o from orders where session_id = p_session_id and client_key = p_client_key;
  if found then return jsonb_build_object('order_id', v_o.id, 'duplicate', true); end if;
  if (select count(*) from orders where session_id = p_session_id and source = 'guest' and created_at > now() - interval '10 minutes') >= 5
  then raise exception 'rate_limited'; end if;

  insert into orders (restaurant_id, session_id, order_type, note, client_key)
  values (v_s.restaurant_id, p_session_id, p_order_type, nullif(left(trim(coalesce(p_note, '')), 300), ''), p_client_key)
  returning id into v_oid;
  v_total := public.add_items(v_oid, p_session_id, v_s.restaurant_id, p_items);
  if v_total > p_max_total then raise exception 'order_too_large'; end if;

  if v_s.status = 'bill_requested' then
    update sessions set status = 'active' where id = p_session_id;
    update dining_tables set status = 'active' where id = v_s.table_id;
    update service_requests set status = 'resolved', resolved_at = now() where session_id = p_session_id and type = 'bill' and status = 'open';
  end if;
  return jsonb_build_object('order_id', v_oid, 'total', v_total, 'duplicate', false);
end $$;

-- ---------- staff actions (owner and waiter; kitchen may only mark ready) ----------
create or replace function public.owner_add_order(p_session_id uuid, p_items jsonb, p_order_type public.order_type default 'dine_in', p_note text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_oid uuid; v_total bigint;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or not public.has_role(v_s.restaurant_id, array['owner', 'waiter']) then raise exception 'session_not_found'; end if;
  if v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  insert into orders (restaurant_id, session_id, order_type, status, accepted_at, note, client_key, source)
  values (v_s.restaurant_id, p_session_id, p_order_type, 'accepted', now(), nullif(left(trim(coalesce(p_note, '')), 300), ''),
          'staff-' || gen_random_uuid()::text, 'staff')
  returning id into v_oid;
  v_total := public.add_items(v_oid, p_session_id, v_s.restaurant_id, p_items);
  if v_s.status = 'bill_requested' then
    update sessions set status = 'active' where id = p_session_id;
    update dining_tables set status = 'active' where id = v_s.table_id;
    update service_requests set status = 'resolved', resolved_at = now() where session_id = p_session_id and type = 'bill' and status = 'open';
  end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_s.restaurant_id, auth.uid(), 'staff_order', 'order', v_oid, jsonb_build_object('total', v_total));
  return jsonb_build_object('order_id', v_oid, 'total', v_total);
end $$;

create or replace function public.cancel_order_item(p_item_id uuid, p_reason text) returns void
language plpgsql security definer set search_path = public as $$
declare i order_items%rowtype; o orders%rowtype;
begin
  select * into i from order_items where id = p_item_id for update;
  if not found or not public.has_role(i.restaurant_id, array['owner', 'waiter']) then raise exception 'item_not_found'; end if;
  if i.cancelled then raise exception 'item_cancelled'; end if;
  if coalesce(trim(p_reason), '') = '' then raise exception 'reason_required'; end if;
  select * into o from orders where id = i.order_id for update;
  if o.status not in ('pending', 'accepted', 'completed') then raise exception 'order_not_active'; end if;
  if not exists (select 1 from sessions where id = i.session_id and status in ('active', 'bill_requested')) then raise exception 'session_closed'; end if;
  update order_items set cancelled = true, cancel_reason = left(trim(p_reason), 120), cancelled_by = auth.uid() where id = p_item_id;
  update dishes set is_available = case when stock_qty = 0 then true else is_available end, stock_qty = stock_qty + i.qty
   where id = i.dish_id and stock_qty is not null;
  if not exists (select 1 from order_items where order_id = i.order_id and not cancelled) then
    update orders set status = 'cancelled' where id = i.order_id;
  end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (i.restaurant_id, auth.uid(), 'cancel_item', 'order_item', p_item_id, jsonb_build_object('reason', left(trim(p_reason), 120), 'name', i.name_snapshot));
end $$;

create or replace function public.mark_order_ready(p_order_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter', 'kitchen']) then raise exception 'order_not_found'; end if;
  update orders set ready_at = now() where id = p_order_id and status = 'accepted' and ready_at is null;
  if not found then raise exception 'order_not_accepted'; end if;
end $$;

create or replace function public.accept_order(p_order_id uuid, p_eta_minutes int) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'order_not_found'; end if;
  if p_eta_minutes is null or p_eta_minutes not between 1 and 240 then raise exception 'bad_eta'; end if;
  update orders set status = 'accepted', eta_minutes = p_eta_minutes, accepted_at = now() where id = p_order_id and status = 'pending';
  if not found then raise exception 'order_not_pending'; end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_rid, auth.uid(), 'accept', 'order', p_order_id, jsonb_build_object('eta', p_eta_minutes));
end $$;

create or replace function public.reject_order(p_order_id uuid, p_reason text) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'order_not_found'; end if;
  if p_reason is null or char_length(trim(p_reason)) = 0 then raise exception 'reason_required'; end if;
  update orders set status = 'rejected', reject_reason = left(trim(p_reason), 200) where id = p_order_id and status = 'pending';
  if not found then raise exception 'order_not_pending'; end if;
  update dishes d set is_available = case when d.stock_qty = 0 then true else d.is_available end, stock_qty = d.stock_qty + x.q
    from (select dish_id, sum(qty) as q from order_items where order_id = p_order_id and not cancelled and dish_id is not null group by dish_id) x
   where d.id = x.dish_id and d.stock_qty is not null;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_rid, auth.uid(), 'reject', 'order', p_order_id, jsonb_build_object('reason', left(trim(p_reason), 200)));
end $$;

create or replace function public.complete_order(p_order_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'order_not_found'; end if;
  update orders set status = 'completed', ready_at = coalesce(ready_at, now()) where id = p_order_id and status = 'accepted';
  if not found then raise exception 'order_not_accepted'; end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_rid, auth.uid(), 'complete', 'order', p_order_id);
end $$;

create or replace function public.open_table(p_table_id uuid, p_pin text default null) returns uuid
language plpgsql security definer set search_path = public, extensions as $$
declare v_t dining_tables%rowtype; v_sid uuid;
begin
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found or not public.has_role(v_t.restaurant_id, array['owner', 'waiter']) then raise exception 'table_not_found'; end if;
  if v_t.status <> 'closed' then raise exception 'table_not_closed'; end if;
  insert into sessions (restaurant_id, table_id, opened_by) values (v_t.restaurant_id, v_t.id, auth.uid()) returning id into v_sid;
  if p_pin is not null and p_pin <> '' then
    if p_pin !~ '^[0-9]{4}$' then raise exception 'bad_pin'; end if;
    insert into session_secrets (session_id, restaurant_id, pin_hash) values (v_sid, v_t.restaurant_id, crypt(p_pin, gen_salt('bf')));
  end if;
  update dining_tables set status = 'active', active_session_id = v_sid where id = v_t.id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_t.restaurant_id, auth.uid(), 'open', 'session', v_sid);
  return v_sid;
end $$;

create or replace function public.void_session(p_table_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_t dining_tables%rowtype;
begin
  select * into v_t from dining_tables where id = p_table_id for update;
  if not found or not public.has_role(v_t.restaurant_id, array['owner', 'waiter']) then raise exception 'table_not_found'; end if;
  if v_t.active_session_id is null then raise exception 'table_not_active'; end if;
  if exists (select 1 from orders where session_id = v_t.active_session_id and status in ('pending', 'accepted', 'completed'))
  then raise exception 'session_has_orders'; end if;
  update sessions set status = 'void', closed_at = now() where id = v_t.active_session_id;
  update dining_tables set status = 'closed', active_session_id = null where id = p_table_id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_t.restaurant_id, auth.uid(), 'void', 'session', v_t.active_session_id);
end $$;

-- move a seated party to a free table, or merge two tables into one bill
create or replace function public.move_session(p_session_id uuid, p_to_table_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_t dining_tables%rowtype;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or not public.has_role(v_s.restaurant_id, array['owner', 'waiter']) then raise exception 'session_not_found'; end if;
  if v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  select * into v_t from dining_tables where id = p_to_table_id and restaurant_id = v_s.restaurant_id for update;
  if not found then raise exception 'table_not_found'; end if;
  if v_t.status <> 'closed' then raise exception 'table_not_closed'; end if;
  update dining_tables set status = 'closed', active_session_id = null where id = v_s.table_id;
  update sessions set table_id = p_to_table_id where id = p_session_id;
  update dining_tables set status = case when v_s.status = 'bill_requested' then 'bill_requested' else 'active' end::public.table_status,
         active_session_id = p_session_id where id = p_to_table_id;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_s.restaurant_id, auth.uid(), 'move', 'session', p_session_id, jsonb_build_object('to_table', p_to_table_id));
end $$;

create or replace function public.merge_sessions(p_from uuid, p_into uuid) returns void
language plpgsql security definer set search_path = public as $$
declare a sessions%rowtype; b sessions%rowtype;
begin
  if p_from = p_into then raise exception 'same_session'; end if;
  select * into a from sessions where id = p_from for update;
  select * into b from sessions where id = p_into for update;
  if a.id is null or b.id is null or a.restaurant_id <> b.restaurant_id or not public.has_role(a.restaurant_id, array['owner', 'waiter']) then
    raise exception 'session_not_found';
  end if;
  if a.status not in ('active', 'bill_requested') or b.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  update orders set session_id = p_into, client_key = left(client_key, 50) || '-m' || substr(id::text, 1, 6) where session_id = p_from;
  update order_items set session_id = p_into where session_id = p_from;
  update service_requests set session_id = p_into where session_id = p_from;
  update sessions set status = 'void', closed_at = now() where id = p_from;
  update dining_tables set status = 'closed', active_session_id = null where id = a.table_id;
  update sessions set status = 'active' where id = p_into;
  update dining_tables set status = 'active' where id = b.table_id;
  update service_requests set status = 'resolved', resolved_at = now() where session_id = p_into and type = 'bill' and status = 'open';
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (a.restaurant_id, auth.uid(), 'merge', 'session', p_into, jsonb_build_object('from', p_from));
end $$;

-- ---------- manager PIN (discounts need it) ----------
-- returns ok | wrong | locked | none | missing. Never raises, so a wrong attempt is kept (5 wrong = 10 minute lock).
create or replace function public.verify_manager_pin(p_rid uuid, p_pin text) returns text
language plpgsql security definer set search_path = public, extensions as $$
declare m manager_pins%rowtype;
begin
  select * into m from manager_pins where restaurant_id = p_rid for update;
  if not found then return 'none'; end if;
  if m.locked_until is not null and m.locked_until > now() then return 'locked'; end if;
  if p_pin is null or p_pin = '' then return 'missing'; end if;
  if m.pin_hash = crypt(p_pin, m.pin_hash) then
    update manager_pins set attempts = 0, locked_until = null where restaurant_id = p_rid;
    return 'ok';
  end if;
  if m.attempts + 1 >= 5 then update manager_pins set attempts = 0, locked_until = now() + interval '10 minutes' where restaurant_id = p_rid;
  else update manager_pins set attempts = attempts + 1 where restaurant_id = p_rid; end if;
  return 'wrong';
end $$;

create or replace function public.set_manager_pin(p_pin text, p_old_pin text default null) returns jsonb
language plpgsql security definer set search_path = public, extensions as $$
declare v_rid uuid := public.jwt_restaurant_id(); r text;
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  if p_pin is null or p_pin !~ '^[0-9]{4,6}$' then raise exception 'bad_pin'; end if;
  if exists (select 1 from manager_pins where restaurant_id = v_rid) then
    r := public.verify_manager_pin(v_rid, p_old_pin);
    if r <> 'ok' then
      return jsonb_build_object('error', case r when 'locked' then 'pin_locked' when 'missing' then 'old_pin_required' else 'old_pin_wrong' end);
    end if;
  end if;
  insert into manager_pins (restaurant_id, pin_hash) values (v_rid, crypt(p_pin, gen_salt('bf')))
  on conflict (restaurant_id) do update set pin_hash = excluded.pin_hash, attempts = 0, locked_until = null;
  return jsonb_build_object('ok', true);
end $$;

create or replace function public.has_manager_pin() returns boolean
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'not_staff'; end if;
  return exists (select 1 from manager_pins where restaurant_id = v_rid);
end $$;

-- ---------- billing: manual discount, guest coupon ----------
drop function if exists public.compute_bill(uuid, text);
create or replace function public.compute_bill(p_session_id uuid, p_coupon_code text default null, p_manual_discount bigint default 0) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_r restaurants%rowtype; v_label text;
  v_subtotal bigint := 0; v_cdisc bigint := 0; v_manual bigint := 0; v_discount bigint := 0; v_service bigint := 0; v_taxable bigint;
  v_tax bigint := 0; v_cgst bigint := 0; v_sgst bigint := 0; v_total bigint; v_final bigint; v_round bigint;
  v_c coupons%rowtype; v_coupon text := null; v_code text; v_explicit boolean; v_lines jsonb; v_orders jsonb;
begin
  select * into v_s from sessions where id = p_session_id;
  if not found then raise exception 'session_not_found'; end if;
  select * into v_r from restaurants where id = v_s.restaurant_id;
  select label into v_label from dining_tables where id = v_s.table_id;

  select coalesce(jsonb_agg(jsonb_build_object('name', t.label, 'qty', t.qty, 'price', t.price, 'amount', t.qty * t.price) order by t.label), '[]'::jsonb),
         coalesce(sum(t.qty * t.price), 0)
    into v_lines, v_subtotal
    from (select public.item_label(oi.name_snapshot, oi.variant_snapshot, oi.addons) as label, oi.unit_price::bigint as price, sum(oi.qty)::bigint as qty
            from order_items oi join orders o on o.id = oi.order_id
           where oi.session_id = p_session_id and not oi.cancelled and o.status in ('accepted', 'completed')
           group by 1, 2) t;

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', o.id, 'status', o.status, 'type', o.order_type, 'eta', o.eta_minutes, 'ready', o.ready_at is not null,
           'source', o.source, 'created_at', o.created_at,
           'items', (select coalesce(jsonb_agg(jsonb_build_object('id', i.id, 'name', public.item_label(i.name_snapshot, i.variant_snapshot, i.addons),
                                                                  'qty', i.qty, 'price', i.unit_price, 'cancelled', i.cancelled, 'reason', i.cancel_reason)
                                              order by i.name_snapshot), '[]'::jsonb)
                       from order_items i where i.order_id = o.id)
         ) order by o.created_at), '[]'::jsonb)
    into v_orders
    from orders o where o.session_id = p_session_id and o.status in ('pending', 'accepted', 'completed');

  v_code := nullif(trim(coalesce(p_coupon_code, '')), '');
  v_explicit := v_code is not null;
  if not v_explicit then v_code := v_s.guest_coupon; end if;     -- a coupon the guest entered; ignored quietly if it stops applying
  if v_code is not null then
    select * into v_c from coupons
     where restaurant_id = v_s.restaurant_id and upper(code) = upper(v_code) and active
       and (valid_from is null or valid_from <= now()) and (valid_to is null or valid_to >= now())
       and (max_uses is null or used_count < max_uses) and min_bill <= v_subtotal;
    if found then
      v_coupon := v_c.code;
      v_cdisc := case v_c.type when 'percent' then round(v_subtotal * least(v_c.value, 100) / 100.0) else least(v_c.value, v_subtotal) end;
    elsif v_explicit then
      raise exception 'coupon_invalid';
    end if;
  end if;
  v_manual := least(greatest(coalesce(p_manual_discount, 0), 0), v_subtotal - v_cdisc);
  v_discount := v_cdisc + v_manual;

  v_service := round((v_subtotal - v_discount) * v_r.service_charge_pct / 100.0);
  if v_r.gst_mode = 'regular' then
    v_taxable := v_subtotal - v_discount + v_service;
    v_tax := round(v_taxable * v_r.gst_rate / 100.0);
    v_cgst := v_tax / 2;
    v_sgst := v_tax - v_cgst;
  end if;
  v_total := v_subtotal - v_discount + v_service + v_tax;
  v_final := round(v_total / 100.0) * 100;
  v_round := v_final - v_total;

  return jsonb_build_object(
    'session_id', v_s.id, 'table', v_label, 'status', v_s.status, 'opened_at', v_s.opened_at,
    'lines', v_lines, 'orders', v_orders,
    'subtotal', v_subtotal, 'discount', v_discount, 'coupon', v_coupon, 'coupon_discount', v_cdisc, 'manual_discount', v_manual,
    'service_charge', v_service, 'service_charge_pct', v_r.service_charge_pct,
    'tax', v_tax, 'cgst', v_cgst, 'sgst', v_sgst,
    'gst_rate', case when v_r.gst_mode = 'regular' then v_r.gst_rate else 0 end,
    'round_off', v_round, 'total', v_final);
end $$;

drop function if exists public.get_bill(uuid, text);
create or replace function public.get_bill(p_session_id uuid, p_coupon_code text default null, p_manual_discount bigint default 0) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from sessions where id = p_session_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'session_not_found'; end if;
  return public.compute_bill(p_session_id, p_coupon_code, p_manual_discount);
end $$;

drop function if exists public.close_session(uuid, public.pay_method, jsonb, text);
create or replace function public.close_session(
  p_session_id uuid, p_method public.pay_method, p_split jsonb default null, p_coupon_code text default null,
  p_manual_discount bigint default 0, p_pin text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_r restaurants%rowtype; v_b jsonb; v_total bigint; v_label text; v_pin text;
  v_ist timestamp; v_year int; v_fy text; v_num int; v_inv uuid; v_cid uuid; v_cash bigint; v_upi bigint;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or not public.has_role(v_s.restaurant_id, array['owner', 'waiter']) then raise exception 'session_not_found'; end if;
  if v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if exists (select 1 from orders where session_id = p_session_id and status = 'pending') then raise exception 'orders_pending'; end if;

  if coalesce(p_manual_discount, 0) > 0 then
    v_pin := public.verify_manager_pin(v_s.restaurant_id, p_pin);
    if v_pin <> 'ok' then
      return jsonb_build_object('error', case v_pin when 'none' then 'pin_not_set' when 'locked' then 'pin_locked' when 'missing' then 'pin_required' else 'pin_wrong' end);
    end if;
  end if;

  update orders set status = 'completed', ready_at = coalesce(ready_at, now()) where session_id = p_session_id and status = 'accepted';
  v_b := public.compute_bill(p_session_id, p_coupon_code, p_manual_discount);
  v_total := (v_b ->> 'total')::bigint;
  if v_total <= 0 then raise exception 'nothing_to_bill'; end if;

  if p_method = 'split' then
    v_cash := coalesce((p_split ->> 'cash')::bigint, 0);
    v_upi := coalesce((p_split ->> 'upi')::bigint, 0);
    if v_cash < 0 or v_upi < 0 or v_cash + v_upi <> v_total then raise exception 'split_mismatch'; end if;
  else
    p_split := null;
  end if;

  select * into v_r from restaurants where id = v_s.restaurant_id;
  select label into v_label from dining_tables where id = v_s.table_id;
  v_ist := now() at time zone 'Asia/Kolkata';
  v_year := extract(year from v_ist)::int;
  v_fy := case when extract(month from v_ist) >= 4
               then v_year || '-' || lpad(((v_year + 1) % 100)::text, 2, '0')
               else (v_year - 1) || '-' || lpad((v_year % 100)::text, 2, '0') end;
  insert into invoice_counters (restaurant_id, fy, last_number) values (v_s.restaurant_id, v_fy, 1)
  on conflict (restaurant_id, fy) do update set last_number = invoice_counters.last_number + 1
  returning last_number into v_num;

  if (v_b ->> 'coupon') is not null then
    select id into v_cid from coupons where restaurant_id = v_s.restaurant_id and upper(code) = upper(v_b ->> 'coupon');
    update coupons set used_count = used_count + 1 where id = v_cid;
  end if;

  insert into invoices (restaurant_id, session_id, fy, number, snapshot)
  values (v_s.restaurant_id, p_session_id, v_fy, v_num, jsonb_build_object(
    'restaurant', jsonb_build_object('name', v_r.name, 'address', v_r.address, 'gstin', v_r.gstin, 'gst_mode', v_r.gst_mode, 'phone', v_r.phone, 'fssai', v_r.fssai),
    'table', v_label, 'paid_at', now(), 'method', p_method, 'split', p_split, 'bill', v_b))
  returning id into v_inv;

  update sessions set status = 'paid', closed_at = now(),
         subtotal = (v_b ->> 'subtotal')::int, discount = (v_b ->> 'discount')::int, tax = (v_b ->> 'tax')::int, total = v_total::int,
         coupon_id = v_cid, payment_method = p_method, payment_split = p_split, invoice_id = v_inv
   where id = p_session_id;
  update dining_tables set status = 'closed', active_session_id = null where id = v_s.table_id;
  update service_requests set status = 'resolved', resolved_at = now() where session_id = p_session_id and status = 'open';
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_s.restaurant_id, auth.uid(), 'paid', 'session', p_session_id,
            jsonb_build_object('total', v_total, 'method', p_method, 'manual_discount', v_b -> 'manual_discount'));
  return jsonb_build_object('invoice_id', v_inv, 'fy', v_fy, 'number', v_num, 'total', v_total);
end $$;

create or replace function public.apply_session_coupon(p_session_id uuid, p_code text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_b jsonb;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if nullif(trim(coalesce(p_code, '')), '') is null then raise exception 'coupon_invalid'; end if;
  v_b := public.compute_bill(p_session_id, p_code);           -- raises coupon_invalid when it does not apply
  update sessions set guest_coupon = upper(trim(p_code)) where id = p_session_id;
  return jsonb_build_object('discount', (v_b ->> 'discount')::bigint, 'coupon', v_b ->> 'coupon');
end $$;

create or replace function public.clear_session_coupon(p_session_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from sessions where id = p_session_id;
  if v_rid is null or not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'session_not_found'; end if;
  update sessions set guest_coupon = null where id = p_session_id and status in ('active', 'bill_requested');
end $$;

-- ---------- serving and stats (shared by owner and platform views) ----------
create or replace function public.restaurant_serving(p_rid uuid) returns jsonb
language sql stable security definer set search_path = public as $$
  select coalesce(jsonb_agg(jsonb_build_object(
      'session_id', s.id, 'table', t.label, 'status', s.status, 'opened_at', s.opened_at,
      'total', (public.compute_bill(s.id, null, 0) ->> 'total')::bigint,
      'items', (select coalesce(sum(oi.qty), 0) from order_items oi join orders o on o.id = oi.order_id
                 where oi.session_id = s.id and not oi.cancelled and o.status in ('accepted', 'completed')),
      'pending', (select count(*) from orders o where o.session_id = s.id and o.status = 'pending'),
      'cooking', (select count(*) from orders o where o.session_id = s.id and o.status = 'accepted'),
      'served',  (select count(*) from orders o where o.session_id = s.id and o.status = 'completed')
    ) order by s.opened_at), '[]'::jsonb)
  from sessions s join dining_tables t on t.id = s.table_id
  where s.restaurant_id = p_rid and s.status in ('active', 'bill_requested')
$$;

create or replace function public.owner_serving() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.has_role(v_rid, array['owner', 'waiter']) then raise exception 'not_owner'; end if;
  return public.restaurant_serving(v_rid);
end $$;

create or replace function public.restaurant_stats(p_rid uuid, p_from timestamptz, p_to timestamptz) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_out jsonb;
begin
  with ps as (
    select * from sessions where restaurant_id = p_rid and status = 'paid' and closed_at >= p_from and closed_at < p_to
  )
  select jsonb_build_object(
    'revenue',   coalesce((select sum(total) from ps), 0),
    'bills',     (select count(*) from ps),
    'discounts', coalesce((select sum(discount) from ps), 0),
    'tax',       coalesce((select sum(tax) from ps), 0),
    'items',     coalesce((select sum(oi.qty) from order_items oi join orders o on o.id = oi.order_id
                            where oi.session_id in (select id from ps) and not oi.cancelled and o.status = 'completed'), 0),
    'cash',      coalesce((select sum(case payment_method when 'cash' then total when 'split' then coalesce((payment_split ->> 'cash')::bigint, 0) else 0 end) from ps), 0),
    'upi',       coalesce((select sum(case payment_method when 'upi' then total when 'split' then coalesce((payment_split ->> 'upi')::bigint, 0) else 0 end) from ps), 0),
    'daily',     coalesce((select jsonb_agg(jsonb_build_object('day', d, 'revenue', r, 'bills', b) order by d)
                            from (select (closed_at at time zone 'Asia/Kolkata')::date as d, sum(total) as r, count(*) as b from ps group by 1) x), '[]'::jsonb),
    'dishes',    coalesce((select jsonb_agg(jsonb_build_object('name', n, 'qty', q, 'revenue', rev) order by q desc, n)
                            from (select public.item_label(oi.name_snapshot, null, null) as n, sum(oi.qty) as q, sum(oi.qty * oi.unit_price) as rev
                                    from order_items oi join orders o on o.id = oi.order_id
                                   where oi.session_id in (select id from ps) and not oi.cancelled and o.status = 'completed'
                                   group by 1) y), '[]'::jsonb),
    'hours',     coalesce((select jsonb_agg(jsonb_build_object('hour', h, 'orders', c) order by h)
                            from (select extract(hour from o.created_at at time zone 'Asia/Kolkata')::int as h, count(*) as c
                                    from orders o
                                   where o.restaurant_id = p_rid and o.status in ('accepted', 'completed') and o.created_at >= p_from and o.created_at < p_to
                                   group by 1) z), '[]'::jsonb)
  ) into v_out;
  return v_out;
end $$;

create or replace function public.owner_stats(p_from timestamptz, p_to timestamptz) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  return public.restaurant_stats(v_rid, p_from, p_to);
end $$;

-- ---------- cash count at the end of the day ----------
create or replace function public.owner_close_day(p_day date, p_opening bigint, p_counted bigint, p_note text default null) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id(); v_from timestamptz; v_to timestamptz; v_sales bigint; v_expected bigint; v_row cash_counts%rowtype;
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  if p_day is null or p_counted is null or p_counted < 0 or coalesce(p_opening, 0) < 0 then raise exception 'bad_amount'; end if;
  v_from := p_day::timestamp at time zone 'Asia/Kolkata';
  v_to := (p_day + 1)::timestamp at time zone 'Asia/Kolkata';
  select coalesce(sum(case payment_method when 'cash' then total when 'split' then coalesce((payment_split ->> 'cash')::bigint, 0) else 0 end), 0)
    into v_sales from sessions where restaurant_id = v_rid and status = 'paid' and closed_at >= v_from and closed_at < v_to;
  v_expected := coalesce(p_opening, 0) + v_sales;
  insert into cash_counts (restaurant_id, day, opening_float, cash_sales, expected_cash, counted_cash, difference, note, created_by)
  values (v_rid, p_day, coalesce(p_opening, 0), v_sales, v_expected, p_counted, p_counted - v_expected, nullif(left(trim(coalesce(p_note, '')), 300), ''), auth.uid())
  on conflict (restaurant_id, day) do update set opening_float = excluded.opening_float, cash_sales = excluded.cash_sales, expected_cash = excluded.expected_cash,
     counted_cash = excluded.counted_cash, difference = excluded.difference, note = excluded.note, created_by = excluded.created_by, created_at = now()
  returning * into v_row;
  return to_jsonb(v_row);
end $$;

create or replace function public.owner_cash_counts(p_limit int default 30) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  return coalesce((select jsonb_agg(to_jsonb(c) order by c.day desc) from (select * from cash_counts where restaurant_id = v_rid order by day desc limit least(coalesce(p_limit, 30), 120)) c), '[]'::jsonb);
end $$;

-- ---------- staff list (accounts themselves are created by the staff-admin Edge Function) ----------
create or replace function public.owner_staff() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object('user_id', o.user_id, 'role', o.role, 'name', o.display_name, 'active', o.active, 'email', u.email) order by o.created_at)
                     from owners o join auth.users u on u.id = o.user_id where o.restaurant_id = v_rid and o.role <> 'owner'), '[]'::jsonb);
end $$;

-- ---------- guest page suggestions: bestsellers and "people also order" ----------
create or replace function public.menu_insights(p_rid uuid) returns jsonb
language sql stable security definer set search_path = public as $$
  with sold as (
    select oi.dish_id, sum(oi.qty) as q from order_items oi join orders o on o.id = oi.order_id
     where oi.restaurant_id = p_rid and not oi.cancelled and o.status in ('accepted', 'completed')
       and o.created_at > now() - interval '30 days' and oi.dish_id is not null group by oi.dish_id),
  top as (select dish_id from sold where q >= 3 order by q desc limit 5),
  per as (
    select distinct oi.session_id, oi.dish_id from order_items oi join orders o on o.id = oi.order_id
     where oi.restaurant_id = p_rid and not oi.cancelled and o.status in ('accepted', 'completed')
       and o.created_at > now() - interval '60 days' and oi.dish_id is not null),
  pairs as (select a.dish_id as a, b.dish_id as b, count(*) as c from per a join per b on a.session_id = b.session_id and a.dish_id <> b.dish_id group by 1, 2),
  ranked as (select a, b, c, row_number() over (partition by a order by c desc) as rn from pairs where c >= 2)
  select jsonb_build_object(
    'bestsellers', coalesce((select jsonb_agg(dish_id) from top), '[]'::jsonb),
    'pairs', coalesce((select jsonb_object_agg(a::text, ids) from (select a, jsonb_agg(b order by c desc) as ids from ranked where rn <= 3 group by a) x), '{}'::jsonb))
$$;

-- ---------- live updates: a tiny "something changed" ping on the session's channel (never blocks the write) ----------
create or replace function public.ping_session() returns trigger
language plpgsql security definer set search_path = public as $$
declare r record; sid text;
begin
  if tg_op = 'DELETE' then r := old; else r := new; end if;
  sid := coalesce(to_jsonb(r) ->> 'session_id', to_jsonb(r) ->> 'id');
  begin
    perform realtime.send(jsonb_build_object('t', extract(epoch from now())), 'ping', 'session:' || sid, false);
  exception when others then
    null;   -- realtime not available: guests simply fall back to polling
  end;
  return r;
end $$;
create trigger ping_orders after insert or update on public.orders for each row execute function public.ping_session();
create trigger ping_items after update on public.order_items for each row execute function public.ping_session();
create trigger ping_requests after insert or update on public.service_requests for each row execute function public.ping_session();
create trigger ping_sessions after update on public.sessions for each row execute function public.ping_session();

-- ---------- grants ----------
revoke all on function public.verify_manager_pin(uuid, text), public.restaurant_serving(uuid), public.restaurant_stats(uuid, timestamptz, timestamptz),
  public.ping_session() from public, anon, authenticated;
grant execute on function public.restaurant_serving(uuid), public.restaurant_stats(uuid, timestamptz, timestamptz) to service_role;
revoke all on function public.compute_bill(uuid, text, bigint), public.apply_session_coupon(uuid, text), public.menu_insights(uuid) from public, anon, authenticated;
grant execute on function public.compute_bill(uuid, text, bigint), public.apply_session_coupon(uuid, text), public.menu_insights(uuid) to service_role;
revoke all on function public.owner_add_order(uuid, jsonb, public.order_type, text), public.cancel_order_item(uuid, text), public.mark_order_ready(uuid),
  public.move_session(uuid, uuid), public.merge_sessions(uuid, uuid), public.set_manager_pin(text, text), public.has_manager_pin(),
  public.clear_session_coupon(uuid), public.owner_close_day(date, bigint, bigint, text), public.owner_cash_counts(int), public.owner_staff(),
  public.get_bill(uuid, text, bigint), public.close_session(uuid, public.pay_method, jsonb, text, bigint, text) from public, anon;
grant execute on function public.owner_add_order(uuid, jsonb, public.order_type, text), public.cancel_order_item(uuid, text), public.mark_order_ready(uuid),
  public.move_session(uuid, uuid), public.merge_sessions(uuid, uuid), public.set_manager_pin(text, text), public.has_manager_pin(),
  public.clear_session_coupon(uuid), public.owner_close_day(date, bigint, bigint, text), public.owner_cash_counts(int), public.owner_staff(),
  public.get_bill(uuid, text, bigint), public.close_session(uuid, public.pay_method, jsonb, text, bigint, text) to authenticated;
