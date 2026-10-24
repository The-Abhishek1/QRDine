-- QR Dine M3 / 006: owner marks an accepted order as served.
create or replace function public.complete_order(p_order_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare v_rid uuid;
begin
  select restaurant_id into v_rid from orders where id = p_order_id;
  if v_rid is null or not public.is_owner_of(v_rid) then raise exception 'order_not_found'; end if;
  update orders set status = 'completed' where id = p_order_id and status = 'accepted';
  if not found then raise exception 'order_not_accepted'; end if;
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id)
    values (v_rid, auth.uid(), 'complete', 'order', p_order_id);
end $$;

revoke all on function public.complete_order(uuid) from public, anon;
grant execute on function public.complete_order(uuid) to authenticated;
