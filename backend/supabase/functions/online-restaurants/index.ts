// Public directory for restaurants that explicitly enabled customer online orders.
import { admin, clientIp, handler, json, rateLimit } from "../_shared/http.ts";

Deno.serve(handler(async (req) => {
  if (!rateLimit(`online-restaurants:${clientIp(req)}`, 60, 60_000)) {
    return json(429, { error: "rate_limited" });
  }

  const db = admin();
  const { data: codAvailable, error: codError } = await db.rpc("online_cod_available");
  if (codError) throw new Error(codError.message);
  const { data: candidates, error } = await db.from("restaurants")
    .select("id,name,address,phone,logo_url,online_delivery_enabled,online_pickup_enabled,online_delivery_fee,online_cod_enabled,online_cod_fee,online_upi_enabled")
    .eq("online_orders_enabled", true)
    .eq("online_application_status", "approved")
    .eq("approval", "approved")
    .eq("on_hold", false)
    .order("name");
  if (error) throw new Error(error.message);

  const available = [];
  for (const restaurant of candidates ?? []) {
    const { data: canTakeOrders, error: statusError } = await db.rpc("restaurant_can_take_orders", { p_rid: restaurant.id });
    if (statusError) throw new Error(statusError.message);
    if (canTakeOrders) available.push({
      ...restaurant,
      online_cod_enabled: codAvailable && restaurant.online_cod_enabled,
      online_cod_fee: codAvailable && restaurant.online_cod_enabled ? restaurant.online_cod_fee : 0,
    });
  }
  return json(200, { restaurants: available });
}));
