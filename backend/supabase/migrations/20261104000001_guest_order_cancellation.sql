create or replace function public.cancel_guest_order(p_session_id uuid, p_order_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  s public.sessions%rowtype;
  o public.orders%rowtype;
begin
  select * into s
  from public.sessions
  where id = p_session_id
  for update;
  if not found or s.status not in ('active', 'bill_requested') then
    raise exception 'session_closed';
  end if;

  select * into o
  from public.orders
  where id = p_order_id and session_id = s.id
  for update;
  if not found or o.source <> 'guest' then
    raise exception 'order_not_found';
  end if;
  if o.status <> 'pending' then
    raise exception 'order_not_pending';
  end if;

  update public.orders
  set status = 'cancelled'
  where id = o.id;

  with cancelled_items as (
    update public.order_items
    set cancelled = true, cancel_reason = 'Cancelled by customer'
    where order_id = o.id and not cancelled
    returning dish_id, qty
  ), restored_stock as (
    select dish_id, sum(qty)::int as qty
    from cancelled_items
    where dish_id is not null
    group by dish_id
  )
  update public.dishes d
  set is_available = case when d.stock_qty = 0 then true else d.is_available end,
      stock_qty = d.stock_qty + restored_stock.qty
  from restored_stock
  where d.id = restored_stock.dish_id
    and d.restaurant_id = o.restaurant_id
    and d.stock_qty is not null;
end
$$;

revoke all on function public.cancel_guest_order(uuid, uuid) from public, anon, authenticated;
grant execute on function public.cancel_guest_order(uuid, uuid) to service_role;
