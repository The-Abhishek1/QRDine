alter table public.restaurants
  add column online_orders_enabled boolean not null default false,
  add column online_delivery_enabled boolean not null default false,
  add column online_pickup_enabled boolean not null default false,
  add column online_upi_enabled boolean not null default false,
  add column online_cod_enabled boolean not null default false,
  add column online_delivery_fee int not null default 0 check (online_delivery_fee between 0 and 100000),
  add column online_cod_fee int not null default 0 check (online_cod_fee between 0 and 100000),
  add constraint online_orders_need_fulfilment check (
    not online_orders_enabled or online_delivery_enabled or online_pickup_enabled
  ),
  add constraint online_orders_need_payment check (
    not online_orders_enabled or online_upi_enabled or online_cod_enabled
  ),
  add constraint online_upi_needs_vpa check (
    not online_upi_enabled or upi_id is not null
  );

grant update (
  online_orders_enabled,
  online_delivery_enabled,
  online_pickup_enabled,
  online_upi_enabled,
  online_cod_enabled,
  online_delivery_fee,
  online_cod_fee
) on public.restaurants to authenticated;
