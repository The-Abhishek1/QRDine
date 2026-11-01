alter table public.dishes
  add column avail_from time,
  add column avail_to time,
  add constraint dishes_serving_window_pair
    check ((avail_from is null) = (avail_to is null));

grant update (avail_from, avail_to) on public.dishes to authenticated;

create or replace function public.dish_serving_window_open(
  p_from time,
  p_to time,
  p_at timestamptz default now()
) returns boolean
language sql stable
set search_path = public
as $$
  select case
    when p_from is null and p_to is null then true
    when p_from is null or p_to is null then false
    when p_from <= p_to then
      (p_at at time zone 'Asia/Kolkata')::time >= p_from
      and (p_at at time zone 'Asia/Kolkata')::time < p_to
    else
      (p_at at time zone 'Asia/Kolkata')::time >= p_from
      or (p_at at time zone 'Asia/Kolkata')::time < p_to
  end
$$;
revoke all on function public.dish_serving_window_open(time, time, timestamptz) from public, anon, authenticated;

create or replace function public.enforce_dish_serving_window_on_table_order()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  dish public.dishes%rowtype;
begin
  if new.dish_id is null then return new; end if;

  select * into dish
  from public.dishes
  where id = new.dish_id and restaurant_id = new.restaurant_id;

  if found and not public.dish_serving_window_open(dish.avail_from, dish.avail_to) then
    raise exception 'dish_unavailable:% (not served right now)', dish.name;
  end if;
  return new;
end
$$;
revoke all on function public.enforce_dish_serving_window_on_table_order() from public, anon, authenticated;

create trigger order_items_dish_serving_window
before insert on public.order_items
for each row execute function public.enforce_dish_serving_window_on_table_order();

create or replace function public.enforce_dish_serving_window_on_online_order()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  item jsonb;
  dish public.dishes%rowtype;
begin
  for item in select value from jsonb_array_elements(new.items_snapshot)
  loop
    select * into dish
    from public.dishes
    where id = (item ->> 'dish_id')::uuid and restaurant_id = new.restaurant_id;

    if found and not public.dish_serving_window_open(dish.avail_from, dish.avail_to) then
      raise exception 'dish_unavailable:% (not served right now)', dish.name;
    end if;
  end loop;
  return new;
end
$$;
revoke all on function public.enforce_dish_serving_window_on_online_order() from public, anon, authenticated;

create trigger online_orders_dish_serving_window
before insert on public.online_orders
for each row execute function public.enforce_dish_serving_window_on_online_order();
