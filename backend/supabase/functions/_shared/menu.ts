import type { SupabaseClient } from "npm:@supabase/supabase-js@2";

// Announcements and timed menus use the restaurant's local time (India, UTC+5:30).
const IST_MS = 5.5 * 3600_000;
const today = () => new Date(Date.now() + IST_MS).toISOString().slice(0, 10);
const minutesNow = () => { const d = new Date(Date.now() + IST_MS); return d.getUTCHours() * 60 + d.getUTCMinutes(); };
const toMin = (t: string) => { const [h, m] = t.split(":"); return Number(h) * 60 + Number(m); };

function openNow(from: string | null, to: string | null): boolean {
  if (!from || !to) return true;
  const a = toMin(from), b = toMin(to), n = minutesNow();
  return a <= b ? n >= a && n < b : n >= a || n < b;     // a window like 22:00 to 02:00 runs past midnight
}

export async function loadMenu(db: SupabaseClient, restaurantId: string) {
  const [cats, dishes, ann, rest, vars, groups, addons, links, ins] = await Promise.all([
    db.from("categories").select("id,name,kind,sort,name_i18n,avail_from,avail_to")
      .eq("restaurant_id", restaurantId).is("deleted_at", null).order("sort").order("name"),
    db.from("dishes").select("id,category_id,name,description,image_url,base_price,is_veg,is_available,sort,name_i18n,description_i18n,stock_qty")
      .eq("restaurant_id", restaurantId).is("deleted_at", null).order("sort").order("name"),
    db.from("announcements").select("text").eq("restaurant_id", restaurantId).eq("show_date", today()).order("id", { ascending: false }).limit(1),
    db.from("restaurants").select("languages").eq("id", restaurantId).single(),
    db.from("dish_variants").select("id,dish_id,name,price").eq("restaurant_id", restaurantId),
    db.from("addon_groups").select("id,name,min_select,max_select").eq("restaurant_id", restaurantId),
    db.from("addons").select("id,group_id,name,price,is_available").eq("restaurant_id", restaurantId),
    db.from("dish_addon_groups").select("dish_id,group_id").eq("restaurant_id", restaurantId),
    db.rpc("menu_insights", { p_rid: restaurantId }),
  ]);
  if (cats.error || dishes.error) throw new Error("menu_load_failed");

  const addonsByGroup = new Map<string, unknown[]>();
  for (const a of addons.data ?? []) addonsByGroup.set(a.group_id, [...(addonsByGroup.get(a.group_id) ?? []), { id: a.id, name: a.name, price: a.price, is_available: a.is_available }]);
  const groupById = new Map((groups.data ?? []).map((g) => [g.id, { ...g, addons: addonsByGroup.get(g.id) ?? [] }]));
  const groupsByDish = new Map<string, unknown[]>();
  for (const l of links.data ?? []) { const g = groupById.get(l.group_id); if (g) groupsByDish.set(l.dish_id, [...(groupsByDish.get(l.dish_id) ?? []), g]); }
  const varsByDish = new Map<string, unknown[]>();
  for (const v of (vars.data ?? []).sort((a, b) => a.price - b.price)) varsByDish.set(v.dish_id, [...(varsByDish.get(v.dish_id) ?? []), { id: v.id, name: v.name, price: v.price }]);

  const categories = (cats.data ?? []).map((c) => ({ id: c.id, name: c.name, kind: c.kind, sort: c.sort, name_i18n: c.name_i18n, avail_from: c.avail_from, avail_to: c.avail_to, open_now: openNow(c.avail_from, c.avail_to) }));
  const dishList = (dishes.data ?? []).map((d) => ({
    id: d.id, category_id: d.category_id, name: d.name, description: d.description, image_url: d.image_url, base_price: d.base_price,
    is_veg: d.is_veg, is_available: d.is_available, sort: d.sort, name_i18n: d.name_i18n, description_i18n: d.description_i18n,
    stock_left: d.stock_qty, variants: varsByDish.get(d.id) ?? [], addon_groups: groupsByDish.get(d.id) ?? [],
  }));
  const insights = (ins.data ?? {}) as { bestsellers?: string[]; pairs?: Record<string, string[]> };
  return {
    categories, dishes: dishList, announcement: ann.data?.[0]?.text ?? null, languages: (rest.data?.languages ?? []) as string[],
    bestsellers: insights.bestsellers ?? [], pairs: insights.pairs ?? {},
  };
}
