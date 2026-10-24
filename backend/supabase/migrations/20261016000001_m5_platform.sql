-- QR Dine M5 / 008: platform admin (the SaaS owner), hotel approval, hold, manual subscription payments, support info.

-- ---------- hotel lifecycle columns ----------
alter table public.restaurants add column approval text not null default 'approved' check (approval in ('pending', 'approved', 'rejected'));
alter table public.restaurants alter column approval set default 'pending';      -- existing hotels stay approved, new ones wait
alter table public.restaurants add column approved_at timestamptz;
update public.restaurants set approved_at = now() where approval = 'approved';
alter table public.restaurants add column reject_reason text;
alter table public.restaurants add column on_hold boolean not null default false;
alter table public.restaurants add column hold_reason text;
alter table public.restaurants add column admin_note text;   -- private to the platform admin
-- (none of these columns is in the owner's UPDATE grant, so a hotel can never approve itself)

-- ---------- platform tables: no client grants, only the functions below ----------
create table public.platform_admins (user_id uuid primary key references auth.users(id) on delete cascade, created_at timestamptz not null default now());
create table public.platform_settings (
  id int primary key default 1 check (id = 1),
  support_phone text, support_email text, payment_info text
);
insert into public.platform_settings (id) values (1);
create table public.subscription_payments (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid references public.restaurants(id) on delete set null,   -- the ledger survives deleting a hotel
  hotel_name text not null,
  amount bigint not null check (amount > 0),
  method text not null check (method in ('cash', 'upi', 'bank')),
  reference text,
  months int not null check (months between 1 and 36),
  paid_on date not null default current_date,
  note text,
  recorded_by uuid,
  created_at timestamptz not null default now()
);
create index on public.subscription_payments (restaurant_id);
create index on public.subscription_payments (paid_on);
create table public.platform_audit (
  id bigint generated always as identity primary key,
  actor uuid, action text not null, restaurant_id uuid, hotel_name text, data jsonb,
  created_at timestamptz not null default now()
);
alter table public.platform_admins enable row level security;
alter table public.platform_settings enable row level security;
alter table public.subscription_payments enable row level security;
alter table public.platform_audit enable row level security;
revoke all on public.platform_admins, public.platform_settings, public.subscription_payments, public.platform_audit from anon, authenticated;

-- ---------- state helpers ----------
-- pending | rejected | hold | trial | active | grace (3 days after paid-through) | expired
create or replace function public.hotel_state(r public.restaurants) returns text
language sql stable as $$
  select case
    when r.approval = 'pending' then 'pending'
    when r.approval = 'rejected' then 'rejected'
    when r.on_hold then 'hold'
    when r.sub_expires_at is not null and now() <= r.sub_expires_at then 'active'
    when now() <= r.trial_ends_at then 'trial'
    when now() <= greatest(r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at)) + interval '3 days' then 'grace'
    else 'expired' end
$$;

create or replace function public.restaurant_active(p_rid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select approval = 'approved' and not on_hold from restaurants where id = p_rid), false)
$$;

create or replace function public.is_platform_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from platform_admins where user_id = auth.uid())
$$;

-- an owner only counts while the hotel is approved and not on hold (checked on every query, so a hold bites at once)
create or replace function public.is_owner_of(rid uuid) returns boolean
language sql stable as $$
  select coalesce(auth.jwt() ->> 'app_role', '') = 'owner' and rid is not null and rid = public.jwt_restaurant_id()
         and public.restaurant_active(rid)
$$;

-- guests can order only while the hotel is approved, not on hold and paid up (trial, active or in grace)
create or replace function public.restaurant_can_take_orders(p_rid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select public.hotel_state(r) in ('trial', 'active', 'grace') from restaurants r where r.id = p_rid), false)
$$;

-- the access-token hook now knows platform admins and blocked owners
create or replace function public.custom_access_token_hook(event jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  claims jsonb := event -> 'claims';
  uid uuid := (event ->> 'user_id')::uuid;
  rid uuid; r public.restaurants;
begin
  if exists (select 1 from platform_admins where user_id = uid) then
    claims := jsonb_set(claims, '{app_role}', '"platform_admin"');
  else
    select restaurant_id into rid from owners where user_id = uid;
    if rid is not null then
      select * into r from restaurants where id = rid;
      claims := jsonb_set(claims, '{restaurant_id}', to_jsonb(rid::text));
      claims := jsonb_set(claims, '{hotel_status}', to_jsonb(public.hotel_state(r)));
      claims := jsonb_set(claims, '{app_role}', to_jsonb(case when r.approval = 'approved' and not r.on_hold then 'owner' else 'owner_blocked' end));
    end if;
  end if;
  return jsonb_set(event, '{claims}', claims);
end $$;

-- ---------- hotel side ----------
drop function if exists public.create_restaurant(text);
create or replace function public.create_restaurant(p_name text, p_phone text default null, p_address text default null) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); v_rid uuid;
begin
  if v_uid is null or coalesce(auth.jwt() ->> 'app_role', '') in ('guest', 'platform_admin') then raise exception 'not_authenticated'; end if;
  if exists (select 1 from owners where user_id = v_uid) then raise exception 'already_owner'; end if;
  if p_name is null or char_length(trim(p_name)) = 0 then raise exception 'name_required'; end if;
  insert into restaurants (name, phone, address)
  values (trim(p_name), nullif(left(trim(coalesce(p_phone, '')), 20), ''), nullif(left(trim(coalesce(p_address, '')), 200), ''))
  returning id into v_rid;
  insert into owners (user_id, restaurant_id) values (v_uid, v_rid);
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id) values (v_rid, v_uid, 'apply', 'restaurant', v_rid);
  return v_rid;   -- the hotel waits for approval; the app refreshes its token afterwards
end $$;

-- works for blocked owners too (that is how the waiting screen learns it was approved)
create or replace function public.my_hotel_status() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare r restaurants%rowtype; st text; pt timestamptz; s platform_settings%rowtype;
begin
  select rs.* into r from restaurants rs join owners o on o.restaurant_id = rs.id where o.user_id = auth.uid();
  if not found then return null; end if;
  select * into s from platform_settings where id = 1;
  st := public.hotel_state(r);
  pt := greatest(r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at));
  return jsonb_build_object(
    'state', st, 'hotel_name', r.name,
    'note', case st when 'hold' then r.hold_reason when 'rejected' then r.reject_reason else null end,
    'paid_through', pt, 'days_left', ceil(extract(epoch from (pt - now())) / 86400)::int,
    'support', jsonb_build_object('phone', s.support_phone, 'email', s.support_email, 'payment_info', s.payment_info));
end $$;

-- ---------- platform admin functions ----------
create or replace function public.platform_log(p_action text, p_rid uuid, p_name text, p_data jsonb default null) returns void
language sql security definer set search_path = public as $$
  insert into platform_audit (actor, action, restaurant_id, hotel_name, data) values (auth.uid(), p_action, p_rid, p_name, p_data)
$$;

create or replace function public.platform_hotels() returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', r.id, 'name', r.name, 'phone', r.phone, 'address', r.address, 'gstin', r.gstin,
      'owner_email', (select u.email from owners o join auth.users u on u.id = o.user_id where o.restaurant_id = r.id limit 1),
      'approval', r.approval, 'on_hold', r.on_hold, 'hold_reason', r.hold_reason, 'admin_note', r.admin_note,
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

create or replace function public.platform_summary() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare v jsonb;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select jsonb_build_object(
    'pending', count(*) filter (where s = 'pending'), 'rejected', count(*) filter (where s = 'rejected'),
    'hold', count(*) filter (where s = 'hold'), 'trial', count(*) filter (where s = 'trial'),
    'active', count(*) filter (where s = 'active'), 'grace', count(*) filter (where s = 'grace'),
    'expired', count(*) filter (where s = 'expired'),
    'expiring_soon', count(*) filter (where s in ('trial', 'active') and pt <= now() + interval '7 days'),
    'collected_month', (select coalesce(sum(amount), 0) from subscription_payments
                         where paid_on >= date_trunc('month', now() at time zone 'Asia/Kolkata')::date),
    'collected_total', (select coalesce(sum(amount), 0) from subscription_payments)
  ) into v
  from (select public.hotel_state(r) as s, greatest(r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at)) as pt from restaurants r) x;
  return v;
end $$;

create or replace function public.platform_review(p_rid uuid, p_approve boolean, p_reason text default null, p_trial_days int default 30) returns void
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  if p_approve then
    if p_trial_days is null or p_trial_days not between 0 and 365 then raise exception 'bad_days'; end if;
    update restaurants set approval = 'approved', reject_reason = null,
           approved_at = coalesce(approved_at, now()),
           trial_ends_at = case when approved_at is null then now() + make_interval(days => p_trial_days) else trial_ends_at end
     where id = p_rid;
  else
    if coalesce(trim(p_reason), '') = '' then raise exception 'reason_required'; end if;
    update restaurants set approval = 'rejected', reject_reason = left(trim(p_reason), 200) where id = p_rid;
  end if;
  perform public.platform_log(case when p_approve then 'approve' else 'reject' end, p_rid, r.name,
                              jsonb_build_object('reason', p_reason, 'trial_days', p_trial_days));
end $$;

create or replace function public.platform_set_hold(p_rid uuid, p_hold boolean, p_reason text default null) returns void
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  update restaurants set on_hold = p_hold, hold_reason = case when p_hold then nullif(left(trim(coalesce(p_reason, '')), 200), '') else null end where id = p_rid;
  perform public.platform_log(case when p_hold then 'hold' else 'unhold' end, p_rid, r.name, jsonb_build_object('reason', p_reason));
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
         gstin = nullif(upper(trim(coalesce(p_gstin, ''))), ''), admin_note = nullif(left(trim(coalesce(p_admin_note, '')), 500), '')
   where id = p_rid;
  perform public.platform_log('update', p_rid, trim(p_name), null);
end $$;

create or replace function public.platform_extend(p_rid uuid, p_days int) returns void
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if p_days is null or p_days not between 1 and 365 then raise exception 'bad_days'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  if r.sub_expires_at is not null and r.sub_expires_at >= r.trial_ends_at then
    update restaurants set sub_expires_at = greatest(sub_expires_at, now()) + make_interval(days => p_days) where id = p_rid;
  else
    update restaurants set trial_ends_at = greatest(trial_ends_at, now()) + make_interval(days => p_days) where id = p_rid;
  end if;
  perform public.platform_log('extend', p_rid, r.name, jsonb_build_object('days', p_days));
end $$;

create or replace function public.platform_set_dates(p_rid uuid, p_trial_ends_at timestamptz, p_sub_expires_at timestamptz) returns void
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  if p_trial_ends_at is null then raise exception 'bad_dates'; end if;
  update restaurants set trial_ends_at = p_trial_ends_at, sub_expires_at = p_sub_expires_at where id = p_rid;
  perform public.platform_log('set_dates', p_rid, r.name, jsonb_build_object('trial_ends_at', p_trial_ends_at, 'sub_expires_at', p_sub_expires_at));
end $$;

create or replace function public.platform_record_payment(
  p_rid uuid, p_amount bigint, p_method text, p_reference text, p_months int, p_paid_on date, p_note text
) returns uuid
language plpgsql security definer set search_path = public as $$
declare r restaurants%rowtype; v_id uuid; v_new timestamptz;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  if p_method is null or p_method not in ('cash', 'upi', 'bank') then raise exception 'bad_method'; end if;
  if p_amount is null or p_amount <= 0 then raise exception 'bad_amount'; end if;
  if p_months is null or p_months not between 1 and 36 then raise exception 'bad_months'; end if;
  select * into r from restaurants where id = p_rid for update;
  if not found then raise exception 'hotel_not_found'; end if;
  -- paid time is added after whatever the hotel already has (running trial or running subscription)
  v_new := greatest(now(), r.trial_ends_at, coalesce(r.sub_expires_at, r.trial_ends_at)) + make_interval(months => p_months);
  insert into subscription_payments (restaurant_id, hotel_name, amount, method, reference, months, paid_on, note, recorded_by)
  values (p_rid, r.name, p_amount, p_method, nullif(left(trim(coalesce(p_reference, '')), 80), ''), p_months,
          coalesce(p_paid_on, current_date), nullif(left(trim(coalesce(p_note, '')), 300), ''), auth.uid())
  returning id into v_id;
  update restaurants set sub_expires_at = v_new, sub_status = 'active' where id = p_rid;
  perform public.platform_log('payment', p_rid, r.name, jsonb_build_object('amount', p_amount, 'method', p_method, 'months', p_months));
  return v_id;
end $$;

create or replace function public.platform_delete_payment(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare p subscription_payments%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  delete from subscription_payments where id = p_id returning * into p;
  if not found then raise exception 'payment_not_found'; end if;
  perform public.platform_log('payment_removed', p.restaurant_id, p.hotel_name, jsonb_build_object('amount', p.amount, 'method', p.method));
end $$;

create or replace function public.platform_payments(p_from date default null, p_to date default null) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', p.id, 'restaurant_id', p.restaurant_id, 'hotel_name', p.hotel_name, 'amount', p.amount, 'method', p.method,
      'reference', p.reference, 'months', p.months, 'paid_on', p.paid_on, 'note', p.note, 'created_at', p.created_at
    ) order by p.paid_on desc, p.created_at desc)
    from (select * from subscription_payments
           where (p_from is null or paid_on >= p_from) and (p_to is null or paid_on <= p_to)
           order by paid_on desc, created_at desc limit 1000) p
  ), '[]'::jsonb);
end $$;

create or replace function public.platform_get_support() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare s platform_settings%rowtype;
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  select * into s from platform_settings where id = 1;
  return jsonb_build_object('phone', s.support_phone, 'email', s.support_email, 'payment_info', s.payment_info);
end $$;

create or replace function public.platform_set_support(p_phone text, p_email text, p_payment_info text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  update platform_settings set support_phone = nullif(left(trim(coalesce(p_phone, '')), 30), ''),
         support_email = nullif(left(trim(coalesce(p_email, '')), 120), ''),
         payment_info = nullif(left(trim(coalesce(p_payment_info, '')), 600), '') where id = 1;
  perform public.platform_log('support_info', null, null, null);
end $$;

create or replace function public.platform_activity(p_limit int default 50) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_platform_admin() then raise exception 'not_platform_admin'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object('id', a.id, 'action', a.action, 'hotel_name', a.hotel_name, 'data', a.data, 'created_at', a.created_at) order by a.id desc)
    from (select * from platform_audit order by id desc limit least(coalesce(p_limit, 50), 200)) a
  ), '[]'::jsonb);
end $$;

-- ---------- grants ----------
revoke all on function public.hotel_state(public.restaurants), public.platform_log(text, uuid, text, jsonb) from public, anon, authenticated;
grant execute on function public.restaurant_active(uuid), public.is_platform_admin() to authenticated;
grant execute on function public.create_restaurant(text, text, text), public.my_hotel_status() to authenticated;
revoke all on function public.platform_hotels(), public.platform_summary(), public.platform_review(uuid, boolean, text, int),
  public.platform_set_hold(uuid, boolean, text), public.platform_update_hotel(uuid, text, text, text, text, text),
  public.platform_extend(uuid, int), public.platform_set_dates(uuid, timestamptz, timestamptz),
  public.platform_record_payment(uuid, bigint, text, text, int, date, text), public.platform_delete_payment(uuid),
  public.platform_payments(date, date), public.platform_get_support(), public.platform_set_support(text, text, text),
  public.platform_activity(int), public.create_restaurant(text, text, text), public.my_hotel_status() from public, anon;
grant execute on function public.platform_hotels(), public.platform_summary(), public.platform_review(uuid, boolean, text, int),
  public.platform_set_hold(uuid, boolean, text), public.platform_update_hotel(uuid, text, text, text, text, text),
  public.platform_extend(uuid, int), public.platform_set_dates(uuid, timestamptz, timestamptz),
  public.platform_record_payment(uuid, bigint, text, text, int, date, text), public.platform_delete_payment(uuid),
  public.platform_payments(date, date), public.platform_get_support(), public.platform_set_support(text, text, text),
  public.platform_activity(int), public.create_restaurant(text, text, text), public.my_hotel_status() to authenticated;
