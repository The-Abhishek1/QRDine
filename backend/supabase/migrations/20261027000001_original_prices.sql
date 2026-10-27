-- Nullable fields preserve historical prices and existing menus unchanged.
alter table public.dishes
  add column original_price int,
  add constraint dishes_original_price_gt_base
    check (original_price is null or original_price > base_price);

alter table public.dish_variants
  add column original_price int,
  add constraint dish_variants_original_price_gt_price
    check (original_price is null or original_price > price);
