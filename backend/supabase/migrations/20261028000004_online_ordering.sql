alter table public.dishes
  add column online_available boolean not null default true;
grant update (online_available) on public.dishes to authenticated;

alter table public.restaurants
  add column online_application_status text not null default 'not_applied'
    check (online_application_status in ('not_applied', 'pending', 'approved', 'rejected')),
  add column online_application_note text,
  add column online_application_review text,
  add column online_application_submitted_at timestamptz,
  add column online_application_reviewed_at timestamptz;

alter table public.platform_settings
  add column online_cod_globally_enabled boolean not null default false;

revoke update (online_orders_enabled) on public.restaurants from authenticated;

update public.restaurants set
  online_orders_enabled = online_orders_enabled and online_upi_enabled,
  online_cod_enabled = false,
  online_cod_fee = 0;

create table public.customer_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null check (char_length(full_name) between 2 and 80),
  phone text not null check (phone ~ '^(\+91)?[6-9][0-9]{9}$'),
  address text not null check (char_length(address) between 5 and 250),
  postal_code text not null check (postal_code ~ '^[0-9]{6}$'),
  updated_at timestamptz not null default now()
);
alter table public.customer_profiles enable row level security;
revoke all on public.customer_profiles from anon, authenticated;

create table public.online_orders (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  customer_id uuid not null references auth.users(id) on delete cascade,
  client_key text not null check (char_length(client_key) between 8 and 64),
  status text not null default 'pending' check (status in ('pending', 'accepted', 'rejected', 'completed', 'cancelled', 'expired')),
  fulfilment text not null check (fulfilment in ('delivery', 'pickup')),
  payment_method text not null check (payment_method in ('upi', 'cod')),
  payment_status text not null default 'awaiting_payment'
    check (payment_status in ('awaiting_payment', 'verification_required', 'paid', 'cod_due', 'failed')),
  customer_name text not null,
  customer_phone text not null,
  delivery_address text,
  postal_code text,
  customer_note text check (customer_note is null or char_length(customer_note) <= 300),
  payment_reference text check (payment_reference is null or char_length(payment_reference) <= 100),
  items_snapshot jsonb not null check (jsonb_typeof(items_snapshot) = 'array'),
  subtotal int not null check (subtotal >= 0),
  delivery_fee int not null default 0 check (delivery_fee >= 0),
  cod_fee int not null default 0 check (cod_fee >= 0),
  total int not null check (total >= 0),
  reject_reason text,
  eta_minutes int check (eta_minutes is null or eta_minutes between 1 and 240),
  created_at timestamptz not null default now(),
  accepted_at timestamptz,
  updated_at timestamptz not null default now(),
  unique (customer_id, client_key)
);
create index online_orders_restaurant_recent on public.online_orders (restaurant_id, created_at desc);
create index online_orders_customer_recent on public.online_orders (customer_id, created_at desc);
create index online_orders_unpaid_expiry on public.online_orders (created_at)
  where status = 'pending' and payment_method = 'upi' and payment_status = 'awaiting_payment';
alter table public.online_orders enable row level security;
create policy customer_read_own_online_orders on public.online_orders
  for select to authenticated using (
    auth.uid() = customer_id and coalesce(auth.jwt() ->> 'app_role', '') = 'customer'
  );
create policy hotel_read_online_orders on public.online_orders
  for select to authenticated using (public.is_owner_of(restaurant_id));
grant select on public.online_orders to authenticated;

create or replace function public.expire_unpaid_online_orders(p_restaurant_id uuid default null)
returns void
language plpgsql security definer set search_path = public as $$
declare o public.online_orders%rowtype; item jsonb;
begin
  for o in
    select * from public.online_orders
    where (p_restaurant_id is null or restaurant_id = p_restaurant_id)
      and status = 'pending' and payment_method = 'upi' and payment_status = 'awaiting_payment'
      and created_at < now() - interval '15 minutes'
    order by created_at
    for update skip locked
  loop
    update public.online_orders set status = 'expired', updated_at = now() where id = o.id;
    for item in select value from jsonb_array_elements(o.items_snapshot) loop
      update public.dishes set stock_qty = stock_qty + (item ->> 'qty')::int
      where id = (item ->> 'dish_id')::uuid and restaurant_id = o.restaurant_id and stock_qty is not null;
    end loop;
  end loop;
end $$;

create or replace function public.get_customer_profile()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare p public.customer_profiles;
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then
    raise exception 'not_customer';
  end if;
  select * into p from public.customer_profiles where user_id = auth.uid();
  return case when found then to_jsonb(p) else null end;
end $$;

create or replace function public.save_customer_profile(
  p_full_name text, p_phone text, p_address text, p_postal_code text
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then
    raise exception 'not_customer';
  end if;
  if char_length(trim(coalesce(p_full_name, ''))) not between 2 and 80 then raise exception 'bad_name'; end if;
  if trim(coalesce(p_phone, '')) !~ '^(\+91)?[6-9][0-9]{9}$' then raise exception 'bad_phone'; end if;
  if char_length(trim(coalesce(p_address, ''))) not between 5 and 250 then raise exception 'bad_address'; end if;
  if trim(coalesce(p_postal_code, '')) !~ '^[0-9]{6}$' then raise exception 'bad_postal_code'; end if;
  insert into public.customer_profiles(user_id, full_name, phone, address, postal_code, updated_at)
  values (auth.uid(), trim(p_full_name), trim(p_phone), trim(p_address), trim(p_postal_code), now())
  on conflict (user_id) do update set
    full_name = excluded.full_name, phone = excluded.phone, address = excluded.address,
    postal_code = excluded.postal_code, updated_at = now();
end $$;

create or replace function public.submit_online_application(p_note text default null)
returns text
language plpgsql security definer set search_path = public as $$
declare rid uuid := public.jwt_restaurant_id(); current_status text;
begin
  if not public.is_owner_of(rid) then raise exception 'not_owner'; end if;
  if char_length(trim(coalesce(p_note, ''))) > 500 then raise exception 'bad_application'; end if;
  if not exists (
    select 1 from public.restaurants where id = rid and phone is not null and address is not null
      and (online_delivery_enabled or online_pickup_enabled)
  ) then raise exception 'application_details_required'; end if;
  select online_application_status into current_status from public.restaurants where id = rid for update;
  if current_status = 'pending' then raise exception 'application_pending'; end if;
  if current_status = 'approved' then raise exception 'application_already_approved'; end if;
  update public.restaurants set
    online_application_status = 'pending',
    online_application_note = nullif(trim(p_note), ''),
    online_application_review = null,
    online_application_submitted_at = now(),
    online_application_reviewed_at = null,
    online_orders_enabled = false
  where id = rid;
  return 'pending';
end $$;

create or replace function public.save_online_order_settings(
  p_enabled boolean, p_delivery boolean, p_pickup boolean, p_upi boolean, p_cod boolean,
  p_delivery_fee int, p_cod_fee int
) returns void
language plpgsql security definer set search_path = public as $$
declare rid uuid := public.jwt_restaurant_id(); cod_global boolean;
begin
  if not public.is_owner_of(rid) then raise exception 'not_owner'; end if;
  select online_cod_globally_enabled into cod_global from public.platform_settings where id = 1;
  if p_enabled and not exists (
    select 1 from public.restaurants where id = rid and online_application_status = 'approved'
  ) then raise exception 'online_application_required'; end if;
  if p_enabled and not (p_delivery or p_pickup) then raise exception 'fulfilment_required'; end if;
  if p_enabled and not (p_upi or p_cod) then raise exception 'payment_required'; end if;
  if p_cod and not coalesce(cod_global, false) then raise exception 'cod_not_available'; end if;
  if p_upi and not exists (select 1 from public.restaurants where id = rid and upi_id is not null) then
    raise exception 'upi_id_required';
  end if;
  if p_delivery_fee not between 0 and 100000 or p_cod_fee not between 0 and 100000 then raise exception 'bad_fee'; end if;
  update public.restaurants set
    online_orders_enabled = p_enabled,
    online_delivery_enabled = p_delivery,
    online_pickup_enabled = p_pickup,
    online_upi_enabled = p_upi,
    online_cod_enabled = p_cod,
    online_delivery_fee = p_delivery_fee,
    online_cod_fee = p_cod_fee
  where id = rid;
end $$;

create or replace function public.platform_online_applications()
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', id, 'name', name, 'phone', phone, 'address', address,
      'online_application_status', online_application_status,
      'online_application_note', online_application_note,
      'online_application_submitted_at', online_application_submitted_at,
      'online_application_review', online_application_review
    ) order by online_application_submitted_at)
    from public.restaurants where online_application_status in ('pending', 'approved', 'rejected')
  ), '[]'::jsonb);
end $$;

create or replace function public.platform_review_online_application(
  p_rid uuid, p_approve boolean, p_reason text default null
) returns void
language plpgsql security definer set search_path = public as $$
declare r public.restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from public.restaurants where id = p_rid for update;
  if not found or r.online_application_status <> 'pending' then raise exception 'online_application_not_pending'; end if;
  if not p_approve and char_length(trim(coalesce(p_reason, ''))) not between 1 and 500 then raise exception 'reason_required'; end if;
  update public.restaurants set
    online_application_status = case when p_approve then 'approved' else 'rejected' end,
    online_application_review = case when p_approve then null else left(trim(p_reason), 500) end,
    online_application_reviewed_at = now(),
    online_orders_enabled = false
  where id = p_rid;
  insert into public.platform_audit(actor, action, restaurant_id, hotel_name, data)
  values (auth.uid(), case when p_approve then 'online_approve' else 'online_reject' end,
    p_rid, r.name, jsonb_build_object('reason', nullif(trim(coalesce(p_reason, '')), '')));
end $$;

create or replace function public.platform_set_online_cod(p_enabled boolean)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  update public.platform_settings set online_cod_globally_enabled = p_enabled where id = 1;
  if not p_enabled then
    update public.restaurants set
      online_orders_enabled = online_orders_enabled and online_upi_enabled,
      online_cod_enabled = false,
      online_cod_fee = 0;
  end if;
  insert into public.platform_audit(actor, action, data)
  values (auth.uid(), 'online_cod_gate', jsonb_build_object('enabled', p_enabled));
end $$;

create or replace function public.online_cod_available()
returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select online_cod_globally_enabled from public.platform_settings where id = 1), false)
$$;

create or replace function public.place_online_order(
  p_restaurant_id uuid, p_client_key text, p_fulfilment text, p_payment text, p_items jsonb, p_note text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  r public.restaurants%rowtype; profile public.customer_profiles%rowtype; existing public.online_orders%rowtype;
  item jsonb; d public.dishes%rowtype; c public.categories%rowtype; v public.dish_variants%rowtype;
  a public.addons%rowtype; g record; selected uuid[]; addons_json jsonb; snapshot jsonb := '[]'::jsonb;
  base_price bigint; addon_total bigint; subtotal bigint := 0; qty int; count_selected int;
  item_count int := 0; fee_delivery int := 0; fee_cod int := 0; total bigint;
  current_minute int; category_start int; category_end int; open_now boolean;
  order_id uuid;
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then raise exception 'not_customer'; end if;
  if p_client_key is null or char_length(p_client_key) not between 8 and 64 then raise exception 'bad_client_key'; end if;
  select * into profile from public.customer_profiles where user_id = auth.uid();
  if not found then raise exception 'profile_required'; end if;
  select * into r from public.restaurants where id = p_restaurant_id for share;
  if not found or r.online_application_status <> 'approved' or not r.online_orders_enabled
    or r.approval <> 'approved' or r.on_hold or not public.restaurant_can_take_orders(r.id)
  then raise exception 'restaurant_unavailable'; end if;
  if p_fulfilment not in ('delivery', 'pickup') then raise exception 'bad_fulfilment'; end if;
  if p_fulfilment = 'delivery' and not r.online_delivery_enabled then raise exception 'delivery_unavailable'; end if;
  if p_fulfilment = 'pickup' and not r.online_pickup_enabled then raise exception 'pickup_unavailable'; end if;
  if p_payment not in ('upi', 'cod') then raise exception 'bad_payment'; end if;
  if p_payment = 'upi' and (not r.online_upi_enabled or r.upi_id is null) then raise exception 'upi_unavailable'; end if;
  if p_payment = 'cod' and (not r.online_cod_enabled or not public.online_cod_available()) then raise exception 'cod_unavailable'; end if;
  if char_length(trim(coalesce(p_note, ''))) > 300 then raise exception 'bad_note'; end if;
  if p_items is null or jsonb_typeof(p_items) <> 'array' then raise exception 'bad_items'; end if;
  if jsonb_array_length(p_items) not between 1 and 30 then raise exception 'bad_items'; end if;
  perform public.expire_unpaid_online_orders(p_restaurant_id);

  select * into existing from public.online_orders where customer_id = auth.uid() and client_key = p_client_key;
  if found then return jsonb_build_object('order_id', existing.id, 'duplicate', true, 'total', existing.total); end if;
  if (select count(*) from public.online_orders where customer_id = auth.uid()
      and created_at > now() - interval '10 minutes' and status <> 'expired') >= 5 then
    raise exception 'rate_limited';
  end if;

  if p_fulfilment = 'delivery' then fee_delivery := r.online_delivery_fee; end if;
  if p_payment = 'cod' then fee_cod := r.online_cod_fee; end if;
  current_minute := extract(hour from (now() at time zone 'Asia/Kolkata'))::int * 60
                    + extract(minute from (now() at time zone 'Asia/Kolkata'))::int;

  for item in select value from jsonb_array_elements(p_items) loop
    item_count := item_count + 1;
    qty := (item ->> 'qty')::int;
    if qty is null or qty not between 1 and 50 then raise exception 'bad_items'; end if;
    select * into d from public.dishes where id = (item ->> 'dish_id')::uuid
      and restaurant_id = r.id and deleted_at is null for update;
    if not found or not d.is_available or not d.online_available then raise exception 'dish_unavailable'; end if;
    select * into c from public.categories where id = d.category_id and restaurant_id = r.id;
    if c.avail_from is not null and c.avail_to is not null then
      category_start := extract(hour from c.avail_from)::int * 60 + extract(minute from c.avail_from)::int;
      category_end := extract(hour from c.avail_to)::int * 60 + extract(minute from c.avail_to)::int;
      open_now := case when category_start <= category_end then current_minute >= category_start and current_minute < category_end
                       else current_minute >= category_start or current_minute < category_end end;
      if not open_now then raise exception 'dish_unavailable'; end if;
    end if;
    if d.stock_qty is not null then
      if d.stock_qty < qty then raise exception 'dish_unavailable'; end if;
      update public.dishes set stock_qty = stock_qty - qty where id = d.id;
    end if;

    base_price := d.base_price;
    if nullif(item ->> 'variant_id', '') is not null then
      select * into v from public.dish_variants where id = (item ->> 'variant_id')::uuid and dish_id = d.id and restaurant_id = r.id;
      if not found then raise exception 'bad_items'; end if;
      base_price := v.price;
    elsif exists (select 1 from public.dish_variants where dish_id = d.id) then
      raise exception 'variant_required';
    end if;
    selected := coalesce(array(select distinct value::uuid
      from jsonb_array_elements_text(coalesce(item -> 'addon_ids', '[]'::jsonb))), '{}'::uuid[]);
    addons_json := '[]'::jsonb;
    addon_total := 0;
    for a in select ad.* from public.addons ad
      join public.dish_addon_groups dg on dg.group_id = ad.group_id and dg.dish_id = d.id
      where ad.id = any(selected) and ad.restaurant_id = r.id
    loop
      if not a.is_available then raise exception 'addon_unavailable'; end if;
      addons_json := addons_json || jsonb_build_array(jsonb_build_object('id', a.id, 'name', a.name, 'price', a.price));
      addon_total := addon_total + a.price;
    end loop;
    if jsonb_array_length(addons_json) <> cardinality(selected) then raise exception 'bad_items'; end if;
    for g in select ag.* from public.addon_groups ag
      join public.dish_addon_groups dg on dg.group_id = ag.id where dg.dish_id = d.id
    loop
      select count(*) into count_selected from public.addons ad where ad.id = any(selected) and ad.group_id = g.id;
      if count_selected < g.min_select or count_selected > g.max_select then raise exception 'addon_choice'; end if;
    end loop;

    subtotal := subtotal + (base_price + addon_total) * qty;
    snapshot := snapshot || jsonb_build_array(jsonb_build_object(
      'dish_id', d.id, 'name', d.name, 'qty', qty, 'variant_id', v.id,
      'variant_name', case when nullif(item ->> 'variant_id', '') is null then null else v.name end,
      'addons', addons_json, 'unit_price', (base_price + addon_total), 'instructions', left(trim(coalesce(item ->> 'instructions', '')), 200)
    ));
    v := null;
  end loop;
  if item_count > 30 or subtotal > 2500000 then raise exception 'order_too_large'; end if;
  total := subtotal + fee_delivery + fee_cod;
  insert into public.online_orders(
    restaurant_id, customer_id, client_key, fulfilment, payment_method, payment_status,
    customer_name, customer_phone, delivery_address, postal_code, customer_note,
    items_snapshot, subtotal, delivery_fee, cod_fee, total
  ) values (
    r.id, auth.uid(), p_client_key, p_fulfilment, p_payment,
    case when p_payment = 'cod' then 'cod_due' else 'awaiting_payment' end,
    profile.full_name, profile.phone,
    case when p_fulfilment = 'delivery' then profile.address else null end,
    case when p_fulfilment = 'delivery' then profile.postal_code else null end,
    nullif(trim(p_note), ''), snapshot, subtotal::int, fee_delivery, fee_cod, total::int
  ) returning id into order_id;
  return jsonb_build_object('order_id', order_id, 'duplicate', false, 'total', total);
end $$;

create or replace function public.submit_online_payment_reference(p_order_id uuid, p_reference text)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then raise exception 'not_customer'; end if;
  if char_length(trim(coalesce(p_reference, ''))) not between 6 and 100 then raise exception 'bad_payment_reference'; end if;
  update public.online_orders set payment_reference = trim(p_reference), payment_status = 'verification_required',
    updated_at = now()
  where id = p_order_id and customer_id = auth.uid() and payment_method = 'upi'
    and payment_status in ('awaiting_payment', 'verification_required') and status = 'pending';
  if not found then raise exception 'online_order_not_found'; end if;
end $$;

create or replace function public.restaurant_online_orders(p_limit int default 50)
returns setof public.online_orders
language plpgsql security definer set search_path = public as $$
begin
  if not public.has_role(public.jwt_restaurant_id(), array['owner', 'waiter']) then raise exception 'not_owner'; end if;
  perform public.expire_unpaid_online_orders(public.jwt_restaurant_id());
  return query select * from public.online_orders where restaurant_id = public.jwt_restaurant_id()
    order by created_at desc limit greatest(1, least(p_limit, 100));
end $$;

create or replace function public.set_online_order_dish_availability(p_dish_id uuid, p_available boolean)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner_of(public.jwt_restaurant_id()) then raise exception 'not_owner'; end if;
  update public.dishes set online_available = p_available
    where id = p_dish_id and restaurant_id = public.jwt_restaurant_id() and deleted_at is null;
  if not found then raise exception 'dish_not_found'; end if;
end $$;

create or replace function public.customer_online_orders(p_limit int default 50)
returns setof public.online_orders
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then raise exception 'not_customer'; end if;
  perform public.expire_unpaid_online_orders();
  return query select * from public.online_orders where customer_id = auth.uid()
    order by created_at desc limit greatest(1, least(p_limit, 100));
end $$;

create or replace function public.restaurant_online_order_action(
  p_order_id uuid, p_action text, p_reason text default null, p_eta_minutes int default null
) returns void
language plpgsql security definer set search_path = public as $$
declare o public.online_orders%rowtype; item jsonb;
begin
  select * into o from public.online_orders where id = p_order_id for update;
  if not found or not public.has_role(o.restaurant_id, array['owner', 'waiter']) then raise exception 'online_order_not_found'; end if;
  if p_action = 'accept' then
    if o.status <> 'pending' then raise exception 'online_order_not_pending'; end if;
    if o.payment_method = 'upi' and o.payment_status <> 'paid' then raise exception 'payment_not_verified'; end if;
    if p_eta_minutes not between 1 and 240 then raise exception 'bad_eta'; end if;
    update public.online_orders set status = 'accepted', accepted_at = now(), eta_minutes = p_eta_minutes, updated_at = now() where id = p_order_id;
  elsif p_action = 'verify_payment' then
    if o.status <> 'pending' or o.payment_method <> 'upi' or o.payment_status <> 'verification_required'
      or o.payment_reference is null then raise exception 'payment_not_verifiable'; end if;
    update public.online_orders set payment_status = 'paid', updated_at = now() where id = p_order_id;
  elsif p_action = 'reject' then
    if o.status <> 'pending' then raise exception 'online_order_not_pending'; end if;
    if char_length(trim(coalesce(p_reason, ''))) not between 1 and 200 then raise exception 'reason_required'; end if;
    update public.online_orders set status = 'rejected', reject_reason = left(trim(p_reason), 200), updated_at = now() where id = p_order_id;
    for item in select value from jsonb_array_elements(o.items_snapshot) loop
      update public.dishes set stock_qty = stock_qty + (item ->> 'qty')::int
      where id = (item ->> 'dish_id')::uuid and restaurant_id = o.restaurant_id and stock_qty is not null;
    end loop;
  elsif p_action = 'complete' then
    if o.status <> 'accepted' then raise exception 'online_order_not_accepted'; end if;
    update public.online_orders set status = 'completed', updated_at = now() where id = p_order_id;
  else raise exception 'bad_online_order_action';
  end if;
end $$;

create or replace function public.set_customer_online_order_offline(p_offline boolean)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_owner_of(public.jwt_restaurant_id()) then raise exception 'not_owner'; end if;
  update public.restaurants set online_orders_enabled = not p_offline where id = public.jwt_restaurant_id()
    and online_application_status = 'approved';
  if not found then raise exception 'online_application_required'; end if;
end $$;

revoke all on function public.get_customer_profile(), public.save_customer_profile(text, text, text, text),
  public.place_online_order(uuid, text, text, text, jsonb, text), public.submit_online_payment_reference(uuid, text),
  public.customer_online_orders(int), public.expire_unpaid_online_orders(uuid) from public, anon, authenticated;
grant execute on function public.get_customer_profile(), public.save_customer_profile(text, text, text, text),
  public.place_online_order(uuid, text, text, text, jsonb, text), public.submit_online_payment_reference(uuid, text),
  public.customer_online_orders(int) to authenticated;

revoke all on function public.submit_online_application(text), public.save_online_order_settings(boolean, boolean, boolean, boolean, boolean, int, int),
  public.restaurant_online_orders(int), public.restaurant_online_order_action(uuid, text, text, int),
  public.set_online_order_dish_availability(uuid, boolean),
  public.set_customer_online_order_offline(boolean), public.platform_online_applications(),
  public.platform_review_online_application(uuid, boolean, text), public.platform_set_online_cod(boolean),
  public.online_cod_available() from public, anon;
grant execute on function public.submit_online_application(text), public.save_online_order_settings(boolean, boolean, boolean, boolean, boolean, int, int),
  public.restaurant_online_orders(int), public.restaurant_online_order_action(uuid, text, text, int),
  public.set_online_order_dish_availability(uuid, boolean),
  public.set_customer_online_order_offline(boolean), public.platform_online_applications(),
  public.platform_review_online_application(uuid, boolean, text), public.platform_set_online_cod(boolean),
  public.online_cod_available() to authenticated, service_role;
