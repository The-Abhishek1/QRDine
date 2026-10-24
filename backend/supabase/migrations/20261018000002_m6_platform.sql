-- QR Dine M6 / 010: platform side. Private notes move out of the hotel-readable row (security fix),
-- insights, read-only "view as hotel", broadcast, payment receipts, business details, staff-aware status.

-- ---------- security fix: your private note about a hotel must not live in a row the hotel can read ----------
create table public.hotel_notes (restaurant_id uuid primary key references public.restaurants(id) on delete cascade, note text);
alter table public.hotel_notes enable row level security;
revoke all on public.hotel_notes from anon, authenticated;
insert into public.hotel_notes (restaurant_id, note) select id, admin_note from public.restaurants where admin_note is not null;

-- ---------- new columns and tables ----------
alter table public.subscription_payments add column receipt_no bigint generated always as identity;
alter table public.subscription_payments add column valid_from timestamptz, add column valid_until timestamptz;
alter table public.platform_settings add column business_name text, add column business_address text, add column business_gstin text;
create table public.platform_broadcasts (
  id bigint generated always as identity primary key,
  message text not null check (char_length(message) between 1 and 400),
  created_at timestamptz not null default now(), expires_at timestamptz not null
);
alter table public.platform_broadcasts enable row level security;
revoke all on public.platform_broadcasts from anon, authenticated;

-- ---------- hotels list (owner = the owner login only; note from hotel_notes) ----------
create or replace function public.platform_hotels() returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', r.id, 'name', r.name, 'phone', r.phone, 'address', r.address, 'gstin', r.gstin,
      'owner_email', (select u.email from owners o join auth.users u on u.id = o.user_id where o.restaurant_id = r.id and o.role = 'owner' limit 1),
      'approval', r.approval, 'on_hold', r.on_hold, 'hold_reason', r.hold_reason,
      'admin_note', (select n.note from hotel_notes n where n.restaurant_id = r.id),
      'state', public.hotel_state(r), 'trial_ends_at', r.trial_ends_at, 'sub_expires_at', r.sub_expires_at,
      'paid_through', greatest(r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at)),
      'created_at', r.created_at, 'approved_at', r.approved_at,
      'last_order_at', (select max(o.created_at) from orders o where o.restaurant_id = r.id),
      'orders_30d', (select count(*) from orders o where o.restaurant_id = r.id and o.created_at > now() - interval '30 days'),
      'tables', (select count(*) from dining_tables t where t.restaurant_id = r.id),
      'total_paid', (select coalesce(sum(p.amount), 0) from subscription_payments p where p.restaurant_id = r.id)
    ) order by (r.approval = 'pending') desc, r.created_at desc)
    from restaurants r
  ), '[]'::jsonb);
end $$;

create or replace function public.platform_update_hotel(p_rid uuid, p_name text, p_phone text, p_address text, p_gstin text, p_admin_note text) returns void
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  if p_name is null or char_length(trim(p_name)) = 0 then raise exception 'name_required'; end if;
  update restaurants set name = trim(p_name),
         phone = nullif(left(trim(coalesce(p_phone, '')), 20), ''), address = nullif(left(trim(coalesce(p_address, '')), 200), ''),
         gstin = nullif(upper(trim(coalesce(p_gstin, ''))), '')
   where id = p_rid;
  insert into hotel_notes (restaurant_id, note) values (p_rid, nullif(left(trim(coalesce(p_admin_note, '')), 500), ''))
  on conflict (restaurant_id) do update set note = excluded.note;
  perform public.platform_log('update', p_rid, trim(p_name), null);
end $$;

alter table public.restaurants drop column admin_note;

-- ---------- payments with receipt numbers and the period they cover ----------
create or replace function public.platform_record_payment(
  p_rid uuid, p_amount bigint, p_method text, p_reference text, p_months int, p_paid_on date, p_note text
) returns uuid
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype; v_id uuid; v_from timestamptz; v_new timestamptz;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if p_method is null or p_method not in ('cash', 'upi', 'bank') then raise exception 'bad_method'; end if;
  if p_amount is null or p_amount <= 0 then raise exception 'bad_amount'; end if;
  if p_months is null or p_months not between 1 and 36 then raise exception 'bad_months'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  v_from := greatest(now(), r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at));
  v_new := v_from + make_interval(months => p_months);
  insert into subscription_payments (restaurant_id, hotel_name, amount, method, reference, months, paid_on, note, recorded_by, valid_from, valid_until)
  values (p_rid, r.name, p_amount, p_method, nullif(left(trim(coalesce(p_reference, '')), 80), ''), p_months,
          coalesce(p_paid_on, current_date), nullif(left(trim(coalesce(p_note, '')), 300), ''), auth.uid(), v_from, v_new)
  returning id into v_id;
  update restaurants set sub_expires_at = v_new, sub_status = 'active' where id = p_rid;
  perform public.platform_log('payment', p_rid, r.name, jsonb_build_object('amount', p_amount, 'method', p_method, 'months', p_months));
  return v_id;
end $$;

create or replace function public.platform_payments(p_from date default null, p_to date default null) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', p.id, 'restaurant_id', p.restaurant_id, 'hotel_name', p.hotel_name, 'amount', p.amount, 'method', p.method,
      'reference', p.reference, 'months', p.months, 'paid_on', p.paid_on, 'note', p.note, 'created_at', p.created_at,
      'receipt_no', p.receipt_no, 'valid_from', p.valid_from, 'valid_until', p.valid_until
    ) order by p.paid_on desc, p.created_at desc)
    from (select * from subscription_payments
           where (p_from is null or paid_on >= p_from) and (p_to is null or paid_on <= p_to)
           order by paid_on desc, created_at desc limit 1000) p
  ), '[]'::jsonb);
end $$;

-- ---------- your business details (printed on receipts), support details, broadcast ----------
create or replace function public.platform_get_support() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare s platform_settings%rowtype; b platform_broadcasts%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into s from platform_settings where id = 1;
  select * into b from platform_broadcasts where expires_at > now() order by id desc limit 1;
  return jsonb_build_object('phone', s.support_phone, 'email', s.support_email, 'payment_info', s.payment_info,
    'business_name', s.business_name, 'business_address', s.business_address, 'business_gstin', s.business_gstin,
    'broadcast', case when b.id is null then null else b.message end);
end $$;

drop function if exists public.platform_set_support(text, text, text);
create or replace function public.platform_set_support(
  p_phone text, p_email text, p_payment_info text, p_business_name text default null, p_business_address text default null, p_business_gstin text default null
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  update platform_settings set support_phone = nullif(left(trim(coalesce(p_phone, '')), 30), ''),
         support_email = nullif(left(trim(coalesce(p_email, '')), 120), ''),
         payment_info = nullif(left(trim(coalesce(p_payment_info, '')), 600), ''),
         business_name = nullif(left(trim(coalesce(p_business_name, '')), 120), ''),
         business_address = nullif(left(trim(coalesce(p_business_address, '')), 250), ''),
         business_gstin = nullif(upper(left(trim(coalesce(p_business_gstin, '')), 15)), '') where id = 1;
  perform public.platform_log('support_info', null, null, null);
end $$;

create or replace function public.platform_set_broadcast(p_message text, p_days int default 7) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  update platform_broadcasts set expires_at = now() where expires_at > now();
  if nullif(trim(coalesce(p_message, '')), '') is not null then
    if p_days is null or p_days not between 1 and 60 then raise exception 'bad_days'; end if;
    insert into platform_broadcasts (message, expires_at) values (left(trim(p_message), 400), now() + make_interval(days => p_days));
  end if;
  perform public.platform_log('broadcast', null, null, jsonb_build_object('cleared', nullif(trim(coalesce(p_message, '')), '') is null));
end $$;

-- hotel owner / staff: status, renewal info, and the current broadcast
create or replace function public.my_hotel_status() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare r restaurants%rowtype; o owners%rowtype; st text; pt timestamptz; s platform_settings%rowtype; b platform_broadcasts%rowtype;
begin
  select * into o from owners where user_id = auth.uid();
  if not found then return null; end if;
  select * into r from restaurants where id = o.restaurant_id;
  select * into s from platform_settings where id = 1;
  select * into b from platform_broadcasts where expires_at > now() order by id desc limit 1;
  st := case when not o.active then 'disabled' else public.hotel_state(r) end;
  pt := greatest(r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at));
  return jsonb_build_object(
    'state', st, 'role', o.role, 'hotel_name', r.name,
    'note', case st when 'hold' then r.hold_reason when 'rejected' then r.reject_reason else null end,
    'paid_through', pt, 'days_left', ceil(extract(epoch from (pt - now())) / 86400)::int,
    'support', jsonb_build_object('phone', s.support_phone, 'email', s.support_email, 'payment_info', s.payment_info),
    'broadcast', case when b.id is null then null else jsonb_build_object('id', b.id, 'message', b.message) end);
end $$;

-- ---------- insights ----------
create or replace function public.platform_insights() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v jsonb;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select jsonb_build_object(
    'monthly', coalesce((select jsonb_agg(jsonb_build_object('month', to_char(m, 'YYYY-MM'),
                  'amount', coalesce((select sum(p.amount) from subscription_payments p where date_trunc('month', p.paid_on::timestamp) = m), 0)) order by m)
                from generate_series(date_trunc('month', current_date::timestamp) - interval '11 months', date_trunc('month', current_date::timestamp), interval '1 month') m), '[]'::jsonb),
    'approved_total', (select count(*) from restaurants where approval = 'approved'),
    'ever_paid', (select count(distinct restaurant_id) from subscription_payments where restaurant_id is not null),
    'trial_ended_unpaid', (select count(*) from restaurants r where r.approval = 'approved' and r.trial_ends_at < now()
                             and not exists (select 1 from subscription_payments p where p.restaurant_id = r.id)),
    'no_menu', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'name', r.name)) from restaurants r
                          where r.approval = 'approved' and not exists (select 1 from dishes d where d.restaurant_id = r.id and d.deleted_at is null)), '[]'::jsonb),
    'no_orders', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'name', r.name)) from restaurants r
                            where r.approval = 'approved' and r.approved_at < now() - interval '2 days'
                              and exists (select 1 from dishes d where d.restaurant_id = r.id and d.deleted_at is null)
                              and not exists (select 1 from orders o where o.restaurant_id = r.id)), '[]'::jsonb),
    'quiet', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'name', r.name, 'last_order_at', lo.t)) from restaurants r
                        join lateral (select max(o.created_at) as t from orders o where o.restaurant_id = r.id) lo on true
                        where r.approval = 'approved' and lo.t is not null and lo.t < now() - interval '14 days'), '[]'::jsonb)
  ) into v;
  return v;
end $$;

-- read-only look at a hotel for support; every look is logged
create or replace function public.platform_view_hotel(p_rid uuid) returns jsonb
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype; v_from timestamptz; v_to timestamptz;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from restaurants where id = p_rid;
  if not found then raise exception 'hotel_not_found'; end if;
  v_from := date_trunc('day', now() at time zone 'Asia/Kolkata') at time zone 'Asia/Kolkata';
  v_to := v_from + interval '1 day';
  perform public.platform_log('view_as', p_rid, r.name, null);
  return jsonb_build_object(
    'hotel', jsonb_build_object('name', r.name, 'phone', r.phone, 'address', r.address, 'gstin', r.gstin, 'mode', r.mode, 'gst_mode', r.gst_mode,
                                'gst_rate', r.gst_rate, 'upi_id', r.upi_id, 'is_open', r.is_open, 'languages', to_jsonb(r.languages), 'state', public.hotel_state(r)),
    'serving', public.restaurant_serving(p_rid),
    'today', public.restaurant_stats(p_rid, v_from, v_to),
    'menu', jsonb_build_object(
      'categories', (select count(*) from categories where restaurant_id = p_rid and deleted_at is null),
      'dishes', (select count(*) from dishes where restaurant_id = p_rid and deleted_at is null),
      'unavailable', (select count(*) from dishes where restaurant_id = p_rid and deleted_at is null and not is_available)),
    'staff', coalesce((select jsonb_agg(jsonb_build_object('name', coalesce(o.display_name, u.email), 'role', o.role, 'active', o.active) order by o.created_at)
                         from owners o join auth.users u on u.id = o.user_id where o.restaurant_id = p_rid), '[]'::jsonb),
    'recent_orders', coalesce((select jsonb_agg(jsonb_build_object('table', t.label, 'status', x.status, 'created_at', x.created_at,
                          'total', (select coalesce(sum(i.qty * i.unit_price), 0) from order_items i where i.order_id = x.id and not i.cancelled)) order by x.created_at desc)
                         from (select * from orders where restaurant_id = p_rid order by created_at desc limit 15) x
                         join sessions s on s.id = x.session_id join dining_tables t on t.id = s.table_id), '[]'::jsonb),
    'recent_bills', coalesce((select jsonb_agg(jsonb_build_object('fy', i.fy, 'number', i.number, 'total', (i.snapshot -> 'bill' ->> 'total')::bigint,
                          'table', i.snapshot ->> 'table', 'paid_at', i.snapshot ->> 'paid_at') order by i.issued_at desc)
                         from (select * from invoices where restaurant_id = p_rid order by issued_at desc limit 10) i), '[]'::jsonb));
end $$;

-- ---------- grants ----------
revoke all on function public.platform_insights(), public.platform_view_hotel(uuid), public.platform_set_broadcast(text, int),
  public.platform_set_support(text, text, text, text, text, text) from public, anon;
grant execute on function public.platform_insights(), public.platform_view_hotel(uuid), public.platform_set_broadcast(text, int),
  public.platform_set_support(text, text, text, text, text, text) to authenticated;
