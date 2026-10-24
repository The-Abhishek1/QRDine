-- QR Dine M4 / 007: billing, mark paid, invoices, serving view, insights, reviews, category delete, bill rules.

alter table public.restaurants add column phone text check (phone is null or char_length(phone) <= 20);
alter table public.restaurants add column fssai text check (fssai is null or char_length(fssai) <= 20);
grant update (phone, fssai) on public.restaurants to authenticated;

alter table public.categories add column deleted_at timestamptz;

-- gap-free invoice numbers per restaurant per financial year (allocated inside the closing transaction)
create table public.invoice_counters (
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  fy text not null,
  last_number int not null default 0,
  primary key (restaurant_id, fy)
);
alter table public.invoice_counters enable row level security;
revoke all on public.invoice_counters from anon, authenticated;

-- Bill for a session: served/accepted orders only. Money in paise. Internal: service role and other functions only.
create or replace function public.compute_bill(p_session_id uuid, p_coupon_code text default null) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_r restaurants%rowtype; v_label text;
  v_subtotal bigint := 0; v_discount bigint := 0; v_service bigint := 0; v_taxable bigint;
  v_tax bigint := 0; v_cgst bigint := 0; v_sgst bigint := 0; v_total bigint; v_final bigint; v_round bigint;
  v_c coupons%rowtype; v_coupon text := null; v_lines jsonb; v_orders jsonb;
begin
  select * into v_s from sessions where id = p_session_id;
  if not found then raise exception 'session_not_found'; end if;
  select * into v_r from restaurants where id = v_s.restaurant_id;
  select label into v_label from dining_tables where id = v_s.table_id;

  select coalesce(jsonb_agg(jsonb_build_object('name', t.name, 'qty', t.qty, 'price', t.price, 'amount', t.qty * t.price) order by t.name), '[]'::jsonb),
         coalesce(sum(t.qty * t.price), 0)
    into v_lines, v_subtotal
    from (select oi.name_snapshot as name, oi.unit_price::bigint as price, sum(oi.qty)::bigint as qty
            from order_items oi join orders o on o.id = oi.order_id
           where oi.session_id = p_session_id and not oi.cancelled and o.status in ('accepted', 'completed')
           group by oi.name_snapshot, oi.unit_price) t;

  select coalesce(jsonb_agg(jsonb_build_object(
           'id', o.id, 'status', o.status, 'type', o.order_type, 'eta', o.eta_minutes, 'created_at', o.created_at,
           'items', (select coalesce(jsonb_agg(jsonb_build_object('name', i.name_snapshot, 'qty', i.qty, 'price', i.unit_price, 'cancelled', i.cancelled) order by i.name_snapshot), '[]'::jsonb)
                       from order_items i where i.order_id = o.id)
         ) order by o.created_at), '[]'::jsonb)
    into v_orders
    from orders o where o.session_id = p_session_id and o.status in ('pending', 'accepted', 'completed');

  if p_coupon_code is not null and trim(p_coupon_code) <> '' then
    select * into v_c from coupons
     where restaurant_id = v_s.restaurant_id and upper(code) = upper(trim(p_coupon_code)) and active
       and (valid_from is null or valid_from <= now()) and (valid_to is null or valid_to >= now())
       and (max_uses is null or used_count < max_uses) and min_bill <= v_subtotal;
    if not found then raise exception 'coupon_invalid'; end if;
    v_coupon := v_c.code;
    -- percent: value is a whole percentage; flat: value is in paise
    v_discount := case v_c.type when 'percent' then round(v_subtotal * least(v_c.value, 100) / 100.0) else least(v_c.value, v_subtotal) end;
  end if;

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
    'subtotal', v_subtotal, 'discount', v_discount, 'coupon', v_coupon,
    'service_charge', v_service, 'service_charge_pct', v_r.service_charge_pct,
    'tax', v_tax, 'cgst', v_cgst, 'sgst', v_sgst,
    'gst_rate', case when v_r.gst_mode = 'regular' then v_r.gst_rate else 0 end,
    'round_off', v_round, 'total', v_final);
end $$;

create or replace function public.get_bill(p_session_id uuid, p_coupon_code text default null) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from sessions where id = p_session_id;
  if v_rid is null or not public.is_owner_of(v_rid) then raise exception 'session_not_found'; end if;
  return public.compute_bill(p_session_id, p_coupon_code);
end $$;

-- Mark paid: serves leftover cooking orders, prices the bill, numbers the invoice, closes the session and the table.
create or replace function public.close_session(
  p_session_id uuid, p_method public.pay_method, p_split jsonb default null, p_coupon_code text default null
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_r restaurants%rowtype; v_b jsonb; v_total bigint; v_label text;
  v_ist timestamp; v_year int; v_fy text; v_num int; v_inv uuid; v_cid uuid; v_cash bigint; v_upi bigint;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or not public.is_owner_of(v_s.restaurant_id) then raise exception 'session_not_found'; end if;
  if v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if exists (select 1 from orders where session_id = p_session_id and status = 'pending') then raise exception 'orders_pending'; end if;

  update orders set status = 'completed' where session_id = p_session_id and status = 'accepted';
  v_b := public.compute_bill(p_session_id, p_coupon_code);
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
    'restaurant', jsonb_build_object('name', v_r.name, 'address', v_r.address, 'gstin', v_r.gstin, 'gst_mode', v_r.gst_mode,
                                     'phone', v_r.phone, 'fssai', v_r.fssai),
    'table', v_label, 'paid_at', now(), 'method', p_method, 'split', p_split, 'bill', v_b))
  returning id into v_inv;

  update sessions set status = 'paid', closed_at = now(),
         subtotal = (v_b ->> 'subtotal')::int, discount = (v_b ->> 'discount')::int, tax = (v_b ->> 'tax')::int, total = v_total::int,
         coupon_id = v_cid, payment_method = p_method, payment_split = p_split, invoice_id = v_inv
   where id = p_session_id;
  update dining_tables set status = 'closed', active_session_id = null where id = v_s.table_id;
  update service_requests set status = 'resolved', resolved_at = now() where session_id = p_session_id and status = 'open';
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id, data)
    values (v_s.restaurant_id, auth.uid(), 'paid', 'session', p_session_id, jsonb_build_object('total', v_total, 'method', p_method));

  return jsonb_build_object('invoice_id', v_inv, 'fy', v_fy, 'number', v_num, 'total', v_total);
end $$;

-- Tables currently in service with their running totals (the "Serving" screen)
create or replace function public.owner_serving() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id();
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'session_id', s.id, 'table', t.label, 'status', s.status, 'opened_at', s.opened_at,
      'total', (public.compute_bill(s.id, null) ->> 'total')::bigint,
      'items', (select coalesce(sum(oi.qty), 0) from order_items oi join orders o on o.id = oi.order_id
                 where oi.session_id = s.id and not oi.cancelled and o.status in ('accepted', 'completed')),
      'pending', (select count(*) from orders o where o.session_id = s.id and o.status = 'pending'),
      'cooking', (select count(*) from orders o where o.session_id = s.id and o.status = 'accepted'),
      'served',  (select count(*) from orders o where o.session_id = s.id and o.status = 'completed')
    ) order by s.opened_at)
    from sessions s join dining_tables t on t.id = s.table_id
    where s.restaurant_id = v_rid and s.status in ('active', 'bill_requested')
  ), '[]'::jsonb);
end $$;

-- Insights for a date range (paid bills in the range). Times in India time.
create or replace function public.owner_stats(p_from timestamptz, p_to timestamptz) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v_rid uuid := public.jwt_restaurant_id(); v_out jsonb;
begin
  if not public.is_owner_of(v_rid) then raise exception 'not_owner'; end if;
  with ps as (
    select * from sessions where restaurant_id = v_rid and status = 'paid' and closed_at >= p_from and closed_at < p_to
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
                            from (select oi.name_snapshot as n, sum(oi.qty) as q, sum(oi.qty * oi.unit_price) as rev
                                    from order_items oi join orders o on o.id = oi.order_id
                                   where oi.session_id in (select id from ps) and not oi.cancelled and o.status = 'completed'
                                   group by oi.name_snapshot) y), '[]'::jsonb),
    'hours',     coalesce((select jsonb_agg(jsonb_build_object('hour', h, 'orders', c) order by h)
                            from (select extract(hour from o.created_at at time zone 'Asia/Kolkata')::int as h, count(*) as c
                                    from orders o
                                   where o.restaurant_id = v_rid and o.status in ('accepted', 'completed') and o.created_at >= p_from and o.created_at < p_to
                                   group by 1) z), '[]'::jsonb)
  ) into v_out;
  return v_out;
end $$;

create or replace function public.delete_category(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from categories where id = p_id;
  if v_rid is null or not public.is_owner_of(v_rid) then raise exception 'category_not_found'; end if;
  if exists (select 1 from dishes where category_id = p_id and deleted_at is null) then raise exception 'category_has_dishes'; end if;
  update categories set deleted_at = now() where id = p_id;
end $$;

create or replace function public.submit_review(p_session_id uuid, p_food int, p_service int, p_comment text) returns void
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype;
begin
  select * into v_s from sessions where id = p_session_id;
  if not found or v_s.status <> 'paid' then raise exception 'not_paid'; end if;
  if exists (select 1 from reviews where session_id = p_session_id) then raise exception 'already_reviewed'; end if;
  if p_food is null and p_service is null and coalesce(trim(p_comment), '') = '' then raise exception 'empty_review'; end if;
  insert into reviews (restaurant_id, session_id, rating_food, rating_service, comment)
  values (v_s.restaurant_id, p_session_id, p_food, p_service, nullif(left(trim(coalesce(p_comment, '')), 500), ''));
end $$;

-- Bill rules: the bill can only be requested once every order is served (or rejected), and something was served.
-- Re-ordering after a bill request cancels that request, so the guest never has a stale one.
create or replace function public.request_service(p_session_id uuid, p_type public.request_type) returns jsonb
language plpgsql security definer set search_path = public as $$
declare v_s sessions%rowtype; v_id uuid;
begin
  select * into v_s from sessions where id = p_session_id for update;
  if not found or v_s.status not in ('active', 'bill_requested') then raise exception 'session_closed'; end if;
  if p_type = 'bill' then
    if exists (select 1 from orders where session_id = p_session_id and status in ('pending', 'accepted')) then raise exception 'orders_not_served'; end if;
    if not exists (select 1 from orders where session_id = p_session_id and status = 'completed') then raise exception 'nothing_to_bill'; end if;
  end if;
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

create or replace function public.place_order(
  p_session_id uuid, p_client_key text, p_order_type public.order_type,
  p_note text, p_items jsonb, p_max_total int default 2500000
) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
  v_s sessions%rowtype; v_o orders%rowtype; v_d dishes%rowtype;
  v_it jsonb; v_qty int; v_total bigint := 0; v_oid uuid;
begin
  select * into v_s from sessions where id = p_session_id for update;
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

  if v_s.status = 'bill_requested' then
    update sessions set status = 'active' where id = p_session_id;
    update dining_tables set status = 'active' where id = v_s.table_id;
    update service_requests set status = 'resolved', resolved_at = now()
     where session_id = p_session_id and type = 'bill' and status = 'open';
  end if;
  return jsonb_build_object('order_id', v_oid, 'total', v_total, 'duplicate', false);
end $$;

revoke all on function public.compute_bill(uuid, text), public.submit_review(uuid, int, int, text) from public, anon, authenticated;
grant execute on function public.compute_bill(uuid, text), public.submit_review(uuid, int, int, text) to service_role;
revoke all on function public.get_bill(uuid, text), public.close_session(uuid, public.pay_method, jsonb, text),
  public.owner_serving(), public.owner_stats(timestamptz, timestamptz), public.delete_category(uuid) from public, anon;
grant execute on function public.get_bill(uuid, text), public.close_session(uuid, public.pay_method, jsonb, text),
  public.owner_serving(), public.owner_stats(timestamptz, timestamptz), public.delete_category(uuid) to authenticated;
