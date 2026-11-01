alter table public.online_orders
  drop constraint online_orders_status_check,
  add constraint online_orders_status_check
    check (status in ('pending', 'accepted', 'preparing', 'ready', 'rejected', 'completed', 'cancelled', 'expired')),
  add column refund_status text not null default 'not_required'
    check (refund_status in ('not_required', 'required', 'refunded')),
  add column refund_reference text check (refund_reference is null or char_length(refund_reference) <= 100),
  add column refund_note text check (refund_note is null or char_length(refund_note) <= 200),
  add column refund_processed_at timestamptz;

update public.online_orders
set refund_status = 'required'
where payment_method = 'upi'
  and payment_status in ('paid', 'verification_required')
  and status in ('rejected', 'cancelled');

create table public.customer_push_devices (
  customer_id uuid not null references auth.users(id) on delete cascade,
  token text not null check (char_length(token) between 20 and 4096),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (customer_id, token)
);

alter table public.customer_push_devices enable row level security;
create policy customer_manage_own_push_devices on public.customer_push_devices
  for all to authenticated
  using (customer_id = auth.uid() and coalesce(auth.jwt() ->> 'app_role', '') = 'customer')
  with check (customer_id = auth.uid() and coalesce(auth.jwt() ->> 'app_role', '') = 'customer');
grant select, insert, update, delete on public.customer_push_devices to authenticated;

create or replace function public.customer_cancel_online_order(p_order_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  o public.online_orders%rowtype;
  item jsonb;
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then
    raise exception 'not_customer';
  end if;

  select * into o
  from public.online_orders
  where id = p_order_id and customer_id = auth.uid()
  for update;
  if not found then raise exception 'online_order_not_found'; end if;
  if o.status <> 'pending' then raise exception 'online_order_cannot_be_cancelled'; end if;

  update public.online_orders
  set status = 'cancelled',
      refund_status = case
        when payment_method = 'upi' and payment_status in ('paid', 'verification_required') then 'required'
        else 'not_required'
      end,
      updated_at = now()
  where id = p_order_id;

  for item in select value from jsonb_array_elements(o.items_snapshot)
  loop
    update public.dishes
    set stock_qty = stock_qty + (item ->> 'qty')::int
    where id = (item ->> 'dish_id')::uuid
      and restaurant_id = o.restaurant_id
      and stock_qty is not null;
  end loop;
end
$$;

create or replace function public.restaurant_online_order_action(
  p_order_id uuid, p_action text, p_reason text default null, p_eta_minutes int default null
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  o public.online_orders%rowtype;
  item jsonb;
  refund_ref text;
begin
  select * into o from public.online_orders where id = p_order_id for update;
  if not found or not public.has_role(o.restaurant_id, array['owner', 'waiter']) then
    raise exception 'online_order_not_found';
  end if;

  if p_action = 'accept' then
    if o.status <> 'pending' then raise exception 'online_order_not_pending'; end if;
    if o.payment_method = 'upi' and o.payment_status <> 'paid' then raise exception 'payment_not_verified'; end if;
    if p_eta_minutes not between 1 and 240 then raise exception 'bad_eta'; end if;
    update public.online_orders
    set status = 'accepted', accepted_at = now(), eta_minutes = p_eta_minutes, updated_at = now()
    where id = p_order_id;
  elsif p_action = 'verify_payment' then
    if o.status <> 'pending' or o.payment_method <> 'upi' or o.payment_status <> 'verification_required'
      or o.payment_reference is null then raise exception 'payment_not_verifiable'; end if;
    update public.online_orders set payment_status = 'paid', updated_at = now() where id = p_order_id;
  elsif p_action = 'reject' then
    if o.status <> 'pending' then raise exception 'online_order_not_pending'; end if;
    if char_length(trim(coalesce(p_reason, ''))) not between 1 and 200 then raise exception 'reason_required'; end if;
    update public.online_orders
    set status = 'rejected',
        reject_reason = left(trim(p_reason), 200),
        refund_status = case
          when payment_method = 'upi' and payment_status in ('paid', 'verification_required') then 'required'
          else 'not_required'
        end,
        updated_at = now()
    where id = p_order_id;
    for item in select value from jsonb_array_elements(o.items_snapshot) loop
      update public.dishes set stock_qty = stock_qty + (item ->> 'qty')::int
      where id = (item ->> 'dish_id')::uuid and restaurant_id = o.restaurant_id and stock_qty is not null;
    end loop;
  elsif p_action = 'preparing' then
    if o.status <> 'accepted' then raise exception 'online_order_not_accepted'; end if;
    update public.online_orders set status = 'preparing', updated_at = now() where id = p_order_id;
  elsif p_action = 'ready' then
    if o.status not in ('accepted', 'preparing') then raise exception 'online_order_not_in_progress'; end if;
    update public.online_orders set status = 'ready', updated_at = now() where id = p_order_id;
  elsif p_action = 'complete' then
    if o.status not in ('accepted', 'preparing', 'ready') then raise exception 'online_order_not_in_progress'; end if;
    update public.online_orders set status = 'completed', updated_at = now() where id = p_order_id;
  elsif p_action = 'mark_refunded' then
    if o.status not in ('rejected', 'cancelled') or o.refund_status <> 'required' then
      raise exception 'refund_not_required';
    end if;
    refund_ref := nullif(trim(coalesce(p_reason, '')), '');
    if refund_ref is null or char_length(refund_ref) > 100 then raise exception 'refund_reference_required'; end if;
    update public.online_orders
    set refund_status = 'refunded', refund_reference = refund_ref, refund_note = null,
        refund_processed_at = now(), updated_at = now()
    where id = p_order_id;
  elsif p_action = 'mark_no_refund' then
    if o.status not in ('rejected', 'cancelled') or o.refund_status <> 'required' then
      raise exception 'refund_not_required';
    end if;
    if char_length(trim(coalesce(p_reason, ''))) not between 1 and 200 then raise exception 'reason_required'; end if;
    update public.online_orders
    set refund_status = 'not_required', refund_reference = null, refund_note = left(trim(p_reason), 200),
        refund_processed_at = now(), updated_at = now()
    where id = p_order_id;
  else
    raise exception 'bad_online_order_action';
  end if;
end
$$;

revoke all on function public.customer_cancel_online_order(uuid) from public, anon, authenticated;
grant execute on function public.customer_cancel_online_order(uuid) to authenticated;
revoke all on function public.restaurant_online_order_action(uuid, text, text, int) from public, anon;
grant execute on function public.restaurant_online_order_action(uuid, text, text, int) to authenticated, service_role;
