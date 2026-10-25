create table public.offers (
  id uuid primary key default gen_random_uuid(),
  restaurant_id uuid not null references public.restaurants(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  buy_qty int not null check (buy_qty between 1 and 50),
  get_qty int not null check (get_qty between 1 and 50),
  buy_dish_id uuid not null,
  reward_dish_id uuid not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  foreign key (buy_dish_id, restaurant_id) references public.dishes(id, restaurant_id) on delete cascade,
  foreign key (reward_dish_id, restaurant_id) references public.dishes(id, restaurant_id) on delete cascade
);

alter table public.offers enable row level security;
create policy owner_all on public.offers for all to authenticated
  using (public.is_owner_of(restaurant_id)) with check (public.is_owner_of(restaurant_id));
grant select, insert, update, delete on public.offers to authenticated;

update public.addon_groups set min_select = 0 where min_select <> 0;

alter table public.order_items add column offer_free boolean not null default false;

create or replace function public.apply_order_offers(p_order_id uuid, p_rid uuid) returns bigint
language plpgsql security definer set search_path = public as $$
declare
  v_offer offers%rowtype; v_qualifying int; v_free int; v_remaining int; v_take int;
  v_item order_items%rowtype; v_saved bigint := 0;
begin
  for v_offer in
    select o.* from offers o
     where o.restaurant_id = p_rid and o.active
       and exists (select 1 from dishes d where d.id = o.buy_dish_id and d.restaurant_id = p_rid and d.deleted_at is null)
       and exists (select 1 from dishes d where d.id = o.reward_dish_id and d.restaurant_id = p_rid and d.deleted_at is null)
     order by o.created_at, o.id
  loop
    select coalesce(sum(qty), 0) into v_qualifying
      from order_items where order_id = p_order_id and dish_id = v_offer.buy_dish_id and not offer_free;

    if v_offer.buy_dish_id = v_offer.reward_dish_id then
      v_free := (v_qualifying / (v_offer.buy_qty + v_offer.get_qty)) * v_offer.get_qty;
    else
      v_free := (v_qualifying / v_offer.buy_qty) * v_offer.get_qty;
    end if;

    select least(v_free, coalesce(sum(qty), 0)) into v_free
      from order_items where order_id = p_order_id and dish_id = v_offer.reward_dish_id and not offer_free;
    v_remaining := v_free;

    for v_item in
      select * from order_items
       where order_id = p_order_id and dish_id = v_offer.reward_dish_id and not offer_free
       order by unit_price, id for update
    loop
      exit when v_remaining <= 0;
      v_take := least(v_remaining, v_item.qty);
      v_saved := v_saved + v_item.unit_price::bigint * v_take;

      if v_take = v_item.qty then
        update order_items
           set unit_price = 0, offer_free = true, name_snapshot = name_snapshot || ' (Offer: ' || v_offer.name || ')'
         where id = v_item.id;
      else
        update order_items set qty = qty - v_take where id = v_item.id;
        insert into order_items (
          restaurant_id, session_id, order_id, dish_id, name_snapshot, variant_snapshot, qty,
          unit_price, addons, instructions, offer_free
        ) values (
          v_item.restaurant_id, v_item.session_id, v_item.order_id, v_item.dish_id,
          v_item.name_snapshot || ' (Offer: ' || v_offer.name || ')', v_item.variant_snapshot,
          v_take, 0, v_item.addons, v_item.instructions, true
        );
      end if;
      v_remaining := v_remaining - v_take;
    end loop;
  end loop;
  return v_saved;
end $$;

revoke all on function public.apply_order_offers(uuid, uuid) from public, anon, authenticated;
grant execute on function public.apply_order_offers(uuid, uuid) to service_role;

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
  v_total := v_total - public.apply_order_offers(v_oid, v_s.restaurant_id);
  if v_total > p_max_total then raise exception 'order_too_large'; end if;

  if v_s.status = 'bill_requested' then
    update sessions set status = 'active' where id = p_session_id;
    update dining_tables set status = 'active' where id = v_s.table_id;
    update service_requests set status = 'resolved', resolved_at = now() where session_id = p_session_id and type = 'bill' and status = 'open';
  end if;
  return jsonb_build_object('order_id', v_oid, 'total', v_total, 'duplicate', false);
end $$;

revoke all on function public.place_order(uuid, text, public.order_type, text, jsonb, int) from public, anon, authenticated;
grant execute on function public.place_order(uuid, text, public.order_type, text, jsonb, int) to service_role;
