-- QR Dine M0 / 004: dish image bucket (public read, owner write under <restaurant_id>/) and Realtime tables.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('dish-images', 'dish-images', true, 1048576, array['image/jpeg','image/png','image/webp'])
on conflict (id) do nothing;

create policy dish_images_owner_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'dish-images' and public.is_owner_of(((storage.foldername(name))[1])::uuid));
create policy dish_images_owner_update on storage.objects for update to authenticated
  using (bucket_id = 'dish-images' and public.is_owner_of(((storage.foldername(name))[1])::uuid));
create policy dish_images_owner_delete on storage.objects for delete to authenticated
  using (bucket_id = 'dish-images' and public.is_owner_of(((storage.foldername(name))[1])::uuid));

alter publication supabase_realtime add table public.orders, public.service_requests, public.sessions, public.dining_tables;
