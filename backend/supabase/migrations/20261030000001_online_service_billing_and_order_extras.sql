alter table public.platform_settings
  add column online_order_monthly_fee int not null default 0
    check (online_order_monthly_fee between 0 and 1000000);

alter table public.restaurants
  add column online_orders_paid_through timestamptz,
  add column online_orders_admin_hold boolean not null default false,
  add column online_orders_admin_hold_reason text;

create table public.online_order_service_payments (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  amount int not null check (amount > 0),
  months int not null check (months between 1 and 36),
  paid_on date not null default current_date,
  reference text check (reference is null or char_length(reference) <= 100),
  note text check (note is null or char_length(note) <= 300),
  period_start date not null,
  period_end date not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  check (period_end > period_start)
);
create index online_order_service_payments_recent
  on public.online_order_service_payments (restaurant_id, created_at desc);
alter table public.online_order_service_payments enable row level security;
revoke all on public.online_order_service_payments from public, anon, authenticated;

alter table public.online_orders
  add column offer_discount int not null default 0 check (offer_discount >= 0),
  add column customer_rating int check (customer_rating between 1 and 5),
  add column customer_feedback text check (customer_feedback is null or char_length(customer_feedback) <= 500),
  add column customer_feedback_at timestamptz;

create or replace function public.online_order_service_monthly_fee()
returns int
language sql stable security definer set search_path = public as $$
  select coalesce((select online_order_monthly_fee from public.platform_settings where id = 1), 0)
$$;

create or replace function public.online_order_service_active(p_rid uuid)
returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((
    select not r.online_orders_admin_hold
      and (
        coalesce((select s.online_order_monthly_fee from public.platform_settings s where s.id = 1), 0) = 0
        or r.online_orders_paid_through > now()
      )
    from public.restaurants r
    where r.id = p_rid
  ), false)
$$;

create or replace function public.platform_set_online_order_monthly_fee(p_amount int)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if p_amount not between 0 and 1000000 then raise exception 'bad_online_order_fee'; end if;
  update public.platform_settings set online_order_monthly_fee = p_amount where id = 1;
  insert into public.platform_audit(actor, action, data)
  values (auth.uid(), 'online_order_fee_set', jsonb_build_object('monthly_fee', p_amount));
end $$;

create or replace function public.platform_record_online_order_payment(
  p_rid uuid, p_amount int, p_months int, p_reference text default null,
  p_paid_on date default current_date, p_note text default null
) returns void
language plpgsql security definer set search_path = public as $$
declare
  v_restaurant public.restaurants%rowtype;
  v_monthly_fee int;
  v_start date;
  v_end date;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if p_amount is null or p_amount <= 0 then raise exception 'bad_amount'; end if;
  if p_months is null or p_months not between 1 and 36 then raise exception 'bad_months'; end if;
  if char_length(trim(coalesce(p_reference, ''))) > 100 or char_length(trim(coalesce(p_note, ''))) > 300 then
    raise exception 'bad_online_order_payment';
  end if;
  select * into v_restaurant from public.restaurants where id = p_rid for update;
  if not found or v_restaurant.online_application_status <> 'approved' then raise exception 'online_application_required'; end if;
  v_monthly_fee := public.online_order_service_monthly_fee();
  if v_monthly_fee <= 0 then raise exception 'online_order_fee_not_configured'; end if;
  if p_amount < v_monthly_fee * p_months then raise exception 'online_order_payment_insufficient'; end if;

  v_start := greatest(current_date, coalesce(v_restaurant.online_orders_paid_through::date, current_date));
  v_end := (v_start + make_interval(months => p_months))::date;
  insert into public.online_order_service_payments(
    restaurant_id, amount, months, paid_on, reference, note, period_start, period_end, created_by
  ) values (
    p_rid, p_amount, p_months, coalesce(p_paid_on, current_date),
    nullif(trim(p_reference), ''), nullif(trim(p_note), ''), v_start, v_end, auth.uid()
  );
  update public.restaurants set online_orders_paid_through = v_end::timestamptz where id = p_rid;
  insert into public.platform_audit(actor, action, restaurant_id, hotel_name, data)
  values (auth.uid(), 'online_order_fee_payment', p_rid, v_restaurant.name,
    jsonb_build_object('amount', p_amount, 'months', p_months, 'period_start', v_start, 'period_end', v_end));
end $$;

create or replace function public.platform_set_online_order_hold(
  p_rid uuid, p_hold boolean, p_reason text default null
) returns void
language plpgsql security definer set search_path = public as $$
declare v_name text;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if char_length(trim(coalesce(p_reason, ''))) > 300 then raise exception 'bad_online_order_hold_reason'; end if;
  update public.restaurants
  set online_orders_admin_hold = p_hold,
      online_orders_admin_hold_reason = case when p_hold then nullif(trim(p_reason), '') else null end
  where id = p_rid and online_application_status = 'approved'
  returning name into v_name;
  if not found then raise exception 'online_application_required'; end if;
  insert into public.platform_audit(actor, action, restaurant_id, hotel_name, data)
  values (auth.uid(), case when p_hold then 'online_order_hold' else 'online_order_unhold' end,
    p_rid, v_name, jsonb_build_object('reason', nullif(trim(coalesce(p_reason, '')), '')));
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
      'online_application_review', online_application_review,
      'online_orders_enabled', online_orders_enabled,
      'online_orders_admin_hold', online_orders_admin_hold,
      'online_orders_admin_hold_reason', online_orders_admin_hold_reason,
      'online_orders_paid_through', online_orders_paid_through
    ) order by online_application_submitted_at desc nulls last)
    from public.restaurants where online_application_status <> 'not_applied'
  ), '[]'::jsonb);
end $$;

create or replace function public.platform_online_order_payments(p_limit int default 50)
returns setof public.online_order_service_payments
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return query select * from public.online_order_service_payments
    order by paid_on desc, created_at desc limit greatest(1, least(p_limit, 100));
end $$;

create or replace function public.apply_online_order_offers()
returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_offer record;
  v_item record;
  v_line jsonb;
  v_snapshot jsonb := '[]'::jsonb;
  v_free_units int[];
  v_count int;
  v_qualifying int;
  v_reward_qty int;
  v_bundle int;
  v_remaining int;
  v_take int;
  v_discount bigint := 0;
  i int;
begin
  v_count := jsonb_array_length(new.items_snapshot);
  if v_count = 0 then return new; end if;
  v_free_units := array_fill(0, array[v_count]);

  for v_offer in
    select o.* from public.offers o
    where o.restaurant_id = new.restaurant_id and o.active
      and exists (select 1 from public.dishes d where d.id = o.buy_dish_id and d.restaurant_id = o.restaurant_id and d.deleted_at is null)
      and exists (select 1 from public.dishes d where d.id = o.reward_dish_id and d.restaurant_id = o.restaurant_id and d.deleted_at is null)
    order by o.created_at, o.id
  loop
    v_qualifying := 0;
    for i in 1..v_count loop
      v_line := new.items_snapshot -> (i - 1);
      if v_line ->> 'dish_id' = v_offer.buy_dish_id::text then
        v_qualifying := v_qualifying + greatest(0, (v_line ->> 'qty')::int - v_free_units[i]);
      end if;
    end loop;
    v_bundle := v_offer.buy_qty + case when v_offer.buy_dish_id = v_offer.reward_dish_id then v_offer.get_qty else 0 end;
    v_remaining := (v_qualifying / v_bundle) * v_offer.get_qty;
    select coalesce(sum(greatest(0, (value ->> 'qty')::int - v_free_units[ordinality::int])), 0)
      into v_reward_qty
      from jsonb_array_elements(new.items_snapshot) with ordinality
      where value ->> 'dish_id' = v_offer.reward_dish_id::text;
    v_remaining := least(v_remaining, v_reward_qty);

    for v_item in
      select ordinality::int as item_index, value
      from jsonb_array_elements(new.items_snapshot) with ordinality
      where value ->> 'dish_id' = v_offer.reward_dish_id::text
      order by (value ->> 'unit_price')::int, ordinality
    loop
      exit when v_remaining <= 0;
      v_take := least(v_remaining, greatest(0, (v_item.value ->> 'qty')::int - v_free_units[v_item.item_index]));
      if v_take > 0 then
        v_free_units[v_item.item_index] := v_free_units[v_item.item_index] + v_take;
        v_discount := v_discount + (v_item.value ->> 'unit_price')::int::bigint * v_take;
        v_remaining := v_remaining - v_take;
      end if;
    end loop;
  end loop;

  for i in 1..v_count loop
    v_line := new.items_snapshot -> (i - 1);
    if v_free_units[i] > 0 then
      v_line := jsonb_set(v_line, '{offer_free_qty}', to_jsonb(v_free_units[i]), true);
    end if;
    v_snapshot := v_snapshot || jsonb_build_array(v_line);
  end loop;
  new.items_snapshot := v_snapshot;
  new.offer_discount := v_discount::int;
  new.total := (new.total::bigint - v_discount)::int;
  return new;
end $$;

create trigger apply_online_order_offers_before_insert
before insert on public.online_orders
for each row execute function public.apply_online_order_offers();

alter function public.place_online_order(uuid, text, text, text, jsonb, text)
  rename to place_online_order_without_fee_gate;
revoke all on function public.place_online_order_without_fee_gate(uuid, text, text, text, jsonb, text)
  from public, anon, authenticated;

create or replace function public.place_online_order(
  p_restaurant_id uuid, p_client_key text, p_fulfilment text, p_payment text, p_items jsonb, p_note text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
begin
  if not public.online_order_service_active(p_restaurant_id) then
    if exists (select 1 from public.restaurants where id = p_restaurant_id and online_orders_admin_hold) then
      raise exception 'online_orders_admin_hold';
    end if;
    raise exception 'online_service_payment_due';
  end if;
  return public.place_online_order_without_fee_gate(
    p_restaurant_id, p_client_key, p_fulfilment, p_payment, p_items, p_note
  );
end $$;

create or replace function public.submit_online_order_review(
  p_order_id uuid, p_rating int, p_feedback text default null
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then raise exception 'not_customer'; end if;
  if p_rating not between 1 and 5 then raise exception 'bad_rating'; end if;
  if char_length(trim(coalesce(p_feedback, ''))) > 500 then raise exception 'bad_feedback'; end if;
  update public.online_orders set
    customer_rating = p_rating,
    customer_feedback = nullif(trim(p_feedback), ''),
    customer_feedback_at = now(),
    updated_at = now()
  where id = p_order_id and customer_id = auth.uid() and status = 'completed' and customer_rating is null;
  if not found then raise exception 'online_order_review_not_allowed'; end if;
end $$;

create or replace function public.restaurant_online_order_insights(p_from timestamptz, p_to timestamptz)
returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.has_role(public.jwt_restaurant_id(), array['owner', 'waiter']) then raise exception 'not_owner'; end if;
  if p_from is null or p_to is null or p_to <= p_from then raise exception 'bad_dates'; end if;
  return coalesce((
    select jsonb_build_object(
      'orders', count(*),
      'sales', coalesce(sum(total), 0),
      'items', coalesce(sum((select sum((item ->> 'qty')::int) from jsonb_array_elements(items_snapshot) as line(item))), 0)
    )
    from public.online_orders
    where restaurant_id = public.jwt_restaurant_id()
      and status = 'completed' and created_at >= p_from and created_at < p_to
  ), jsonb_build_object('orders', 0, 'sales', 0, 'items', 0));
end $$;

revoke all on function public.online_order_service_monthly_fee(),
  public.online_order_service_active(uuid),
  public.platform_set_online_order_monthly_fee(int),
  public.platform_record_online_order_payment(uuid, int, int, text, date, text),
  public.platform_set_online_order_hold(uuid, boolean, text),
  public.platform_online_applications(),
  public.platform_online_order_payments(int),
  public.place_online_order(uuid, text, text, text, jsonb, text),
  public.submit_online_order_review(uuid, int, text),
  public.restaurant_online_order_insights(timestamptz, timestamptz)
from public, anon, authenticated;
grant execute on function public.online_order_service_monthly_fee(),
  public.online_order_service_active(uuid),
  public.platform_set_online_order_monthly_fee(int),
  public.platform_record_online_order_payment(uuid, int, int, text, date, text),
  public.platform_set_online_order_hold(uuid, boolean, text),
  public.platform_online_applications(),
  public.platform_online_order_payments(int)
to authenticated, service_role;
grant execute on function public.online_order_service_active(uuid) to service_role;
grant execute on function public.place_online_order(uuid, text, text, text, jsonb, text),
  public.submit_online_order_review(uuid, int, text)
to authenticated;
grant execute on function public.restaurant_online_order_insights(timestamptz, timestamptz)
to authenticated, service_role;
