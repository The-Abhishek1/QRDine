alter table public.online_orders
  add column payment_screenshot_path text;

drop policy hotel_read_online_orders on public.online_orders;
create policy hotel_read_online_orders on public.online_orders
  for select to authenticated using (public.has_role(restaurant_id, array['owner', 'waiter']));

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'payment-proofs',
  'payment-proofs',
  false,
  5242880,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update set
  public = false,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create policy payment_proofs_customer_insert on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'payment-proofs'
    and coalesce(auth.jwt() ->> 'app_role', '') = 'customer'
    and exists (
      select 1
      from public.online_orders o
      where o.id::text = (storage.foldername(name))[1]
        and o.customer_id = auth.uid()
        and o.payment_method = 'upi'
        and o.status = 'pending'
        and o.payment_status in ('awaiting_payment', 'verification_required')
    )
  );

create policy payment_proofs_customer_or_restaurant_read on storage.objects
  for select to authenticated
  using (
    bucket_id = 'payment-proofs'
    and exists (
      select 1
      from public.online_orders o
      where o.id::text = (storage.foldername(name))[1]
        and o.payment_screenshot_path = name
        and (
          (o.customer_id = auth.uid() and coalesce(auth.jwt() ->> 'app_role', '') = 'customer')
          or public.has_role(o.restaurant_id, array['owner', 'waiter'])
        )
    )
  );

drop function public.submit_online_payment_reference(uuid, text);

create function public.submit_online_payment_reference(
  p_order_id uuid,
  p_reference text,
  p_screenshot_path text
) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or coalesce(auth.jwt() ->> 'app_role', '') <> 'customer' then
    raise exception 'not_customer';
  end if;
  if char_length(trim(coalesce(p_reference, ''))) not between 6 and 100 then
    raise exception 'bad_payment_reference';
  end if;
  if p_screenshot_path is null
    or p_screenshot_path !~ ('^' || p_order_id::text || '/[A-Za-z0-9-]+\.(jpg|png|webp)$')
    or not exists (
      select 1 from storage.objects
      where bucket_id = 'payment-proofs'
        and name = p_screenshot_path
        and coalesce((metadata ->> 'size')::bigint, 0) between 1 and 5242880
        and metadata ->> 'mimetype' in ('image/jpeg', 'image/png', 'image/webp')
    )
  then
    raise exception 'payment_screenshot_required';
  end if;

  update public.online_orders
  set payment_reference = trim(p_reference),
      payment_screenshot_path = p_screenshot_path,
      payment_status = 'verification_required',
      updated_at = now()
  where id = p_order_id
    and customer_id = auth.uid()
    and payment_method = 'upi'
    and payment_status in ('awaiting_payment', 'verification_required')
    and status = 'pending';
  if not found then raise exception 'online_order_not_found'; end if;
end $$;

revoke all on function public.submit_online_payment_reference(uuid, text, text)
  from public, anon, authenticated;
grant execute on function public.submit_online_payment_reference(uuid, text, text)
  to authenticated;
