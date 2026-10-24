// Guest places an order. Prices are looked up in SQL; the client only sends dish ids and quantities.
import { admin, HttpError, handler, json, mapDbError, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_ORDER_PAISE = Number(Deno.env.get("MAX_ORDER_PAISE") ?? 2_500_000);   // 25,000 rupees

Deno.serve(handler(async (req) => {
  const g = await requireGuest(req);
  if (!rateLimit(`order:${g.s}`, 20, 60_000)) throw new HttpError(429, "rate_limited");
  const b = await readJson(req);

  const orderType = b.order_type === "parcel" ? "parcel" : b.order_type === "dine_in" ? "dine_in" : null;
  const clientKey = String(b.client_key ?? "");
  const items = Array.isArray(b.items) ? b.items : null;
  if (!orderType || clientKey.length < 8 || clientKey.length > 64 || !items || items.length < 1 || items.length > 40) {
    throw new HttpError(400, "bad_request");
  }
  const clean = items.map((i: Record<string, unknown>) => {
    const qty = Number(i?.qty);
    if (!UUID.test(String(i?.dish_id)) || !Number.isInteger(qty) || qty < 1 || qty > 50) throw new HttpError(400, "bad_request");
    const variant = i.variant_id ? String(i.variant_id) : null;
    if (variant && !UUID.test(variant)) throw new HttpError(400, "bad_request");
    const addons = Array.isArray(i.addon_ids) ? i.addon_ids.map(String) : [];
    if (addons.length > 10 || addons.some((a) => !UUID.test(a))) throw new HttpError(400, "bad_request");
    return { dish_id: String(i.dish_id), qty, instructions: String(i.instructions ?? "").slice(0, 200), variant_id: variant, addon_ids: addons };
  });

  const { data, error } = await admin().rpc("place_order", {
    p_session_id: g.s,
    p_client_key: clientKey,
    p_order_type: orderType,
    p_note: b.note ? String(b.note).slice(0, 300) : null,
    p_items: clean,
    p_max_total: MAX_ORDER_PAISE,
  });
  if (error) throw mapDbError(error);
  return json(200, data);
}));
