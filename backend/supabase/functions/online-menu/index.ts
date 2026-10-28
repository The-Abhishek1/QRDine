// Public online menu for an eligible restaurant.
import { admin, clientIp, handler, HttpError, json, rateLimit, readJson } from "../_shared/http.ts";
import { loadMenu } from "../_shared/menu.ts";

Deno.serve(handler(async (req) => {
  if (!rateLimit(`online-menu:${clientIp(req)}`, 60, 60_000)) throw new HttpError(429, "rate_limited");
  const body = await readJson(req);
  const restaurantId = String(body.restaurant_id ?? "");
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(restaurantId)) {
    throw new HttpError(400, "bad_restaurant");
  }

  const db = admin();
  const { data: restaurant, error } = await db.from("restaurants")
    .select("id,name,address,logo_url,upi_id,online_delivery_enabled,online_pickup_enabled,online_delivery_fee,online_cod_enabled,online_cod_fee,online_upi_enabled")
    .eq("id", restaurantId)
    .eq("online_orders_enabled", true)
    .eq("online_application_status", "approved")
    .eq("approval", "approved")
    .eq("on_hold", false)
    .maybeSingle();
  if (error) throw new Error(error.message);
  if (!restaurant) throw new HttpError(404, "restaurant_unavailable");

  const { data: canTakeOrders, error: statusError } = await db.rpc("restaurant_can_take_orders", { p_rid: restaurantId });
  if (statusError) throw new Error(statusError.message);
  if (!canTakeOrders) throw new HttpError(404, "restaurant_unavailable");

  const { data: codAvailable, error: codError } = await db.rpc("online_cod_available");
  if (codError) throw new Error(codError.message);
  const menu = await loadMenu(db, restaurantId);
  return json(200, {
    restaurant: {
      ...restaurant,
      online_cod_enabled: codAvailable && restaurant.online_cod_enabled,
      online_cod_fee: codAvailable && restaurant.online_cod_enabled ? restaurant.online_cod_fee : 0,
    },
    menu,
  });
}));
