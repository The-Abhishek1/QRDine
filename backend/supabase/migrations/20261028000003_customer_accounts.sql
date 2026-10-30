-- Customer accounts use Supabase Auth like owner accounts, but receive a separate
-- application role. Enable this function as the custom access-token hook in
-- Supabase Auth settings so customer RPCs receive app_role=customer.
create or replace function public.custom_access_token_hook(event jsonb) returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare
  claims jsonb := event -> 'claims';
  uid uuid := (event ->> 'user_id')::uuid;
  rid uuid; rrole text; ractive boolean; r public.restaurants;
  account_type text;
begin
  if exists (select 1 from platform_admins where user_id = uid) then
    claims := jsonb_set(claims, '{app_role}', '"platform_admin"');
  else
    select restaurant_id, role, active into rid, rrole, ractive from owners where user_id = uid;
    if rid is not null then
      select * into r from restaurants where id = rid;
      claims := jsonb_set(claims, '{restaurant_id}', to_jsonb(rid::text));
      claims := jsonb_set(claims, '{hotel_status}', to_jsonb(case when not ractive then 'disabled' else public.hotel_state(r) end));
      claims := jsonb_set(claims, '{app_role}', to_jsonb(case when r.approval = 'approved' and not r.on_hold and ractive then rrole else 'owner_blocked' end));
    else
      select raw_user_meta_data ->> 'account_type' into account_type from auth.users where id = uid;
      if account_type = 'customer' then
        claims := jsonb_set(claims, '{app_role}', '"customer"');
      end if;
    end if;
  end if;
  return jsonb_set(event, '{claims}', claims);
end $$;

grant execute on function public.custom_access_token_hook(jsonb) to supabase_auth_admin;

create or replace function public.create_restaurant(p_name text, p_phone text default null, p_address text default null) returns uuid
language plpgsql security definer set search_path = public as $$
declare v_uid uuid := auth.uid(); v_rid uuid;
begin
  if v_uid is null or coalesce(auth.jwt() ->> 'app_role', '') in ('guest', 'customer', 'platform_admin') then
    raise exception 'not_authenticated';
  end if;
  if exists (select 1 from owners where user_id = v_uid) then raise exception 'already_owner'; end if;
  if p_name is null or char_length(trim(p_name)) = 0 then raise exception 'name_required'; end if;
  insert into restaurants (name, phone, address)
  values (trim(p_name), nullif(left(trim(coalesce(p_phone, '')), 20), ''), nullif(left(trim(coalesce(p_address, '')), 200), ''))
  returning id into v_rid;
  insert into owners (user_id, restaurant_id) values (v_uid, v_rid);
  insert into audit_logs (restaurant_id, actor, action, entity, entity_id)
  values (v_rid, v_uid, 'apply', 'restaurant', v_rid);
  return v_rid;
end $$;
