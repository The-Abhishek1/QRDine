import { admin, HttpError, handler, json, mapDbError, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(handler(async (req) => {
  const guest = await requireGuest(req);
  if (!rateLimit(`cancel:${guest.s}`, 20, 60_000)) throw new HttpError(429, "rate_limited");
  const body = await readJson(req);
  const orderId = String(body.order_id ?? "");
  if (!UUID.test(orderId)) throw new HttpError(400, "bad_request");

  const { error } = await admin().rpc("cancel_guest_order", {
    p_session_id: guest.s,
    p_order_id: orderId,
  });
  if (error) throw mapDbError(error);
  return json(200, { ok: true });
}));
