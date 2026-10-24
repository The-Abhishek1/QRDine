// Guest scans a table QR. Verifies the signed token, checks the table is active, optional PIN, returns a guest token + menu.
import { admin, clientIp, HttpError, handler, json, rateLimit, readJson } from "../_shared/http.ts";
import { signGuest, verifyQr } from "../_shared/token.ts";
import { loadMenu } from "../_shared/menu.ts";

Deno.serve(handler(async (req) => {
  if (!rateLimit(`join:${clientIp(req)}`, 30, 60_000)) throw new HttpError(429, "rate_limited");
  const body = await readJson(req);
  const qr = await verifyQr(String(body.token ?? ""));
  if (!qr) throw new HttpError(400, "invalid_qr");

  const db = admin();
  const { data: table } = await db.from("dining_tables")
    .select("id,restaurant_id,label,status,active_session_id,qr_key_version")
    .eq("id", qr.t).eq("restaurant_id", qr.r).maybeSingle();
  if (!table || table.qr_key_version !== qr.kv) throw new HttpError(400, "invalid_qr");   // rotated or forged

  const { data: rest } = await db.from("restaurants").select("id,name,mode,is_open,upi_id,logo_url").eq("id", table.restaurant_id).single();
  if (!rest) throw new HttpError(400, "invalid_qr");
  const base = { restaurant: { name: rest.name, logo_url: rest.logo_url }, table: { label: table.label } };

  const { data: can } = await db.rpc("restaurant_can_take_orders", { p_rid: rest.id });
  if (!can) return json(200, { state: "unavailable", ...base });
  if (!rest.is_open) return json(200, { state: "shop_closed", ...base });

  let sessionId: string | null = table.status === "closed" ? null : table.active_session_id;
  if (!sessionId) {
    if (rest.mode !== "open") return json(200, { state: "table_closed", ...base });
    const { data, error } = await db.rpc("open_session_if_needed", { p_table_id: table.id });
    if (error) throw new Error(error.message);
    sessionId = data as string;
  }

  const pin = body.pin ? String(body.pin).slice(0, 8) : null;
  const { data: pinResult, error: pinErr } = await db.rpc("verify_session_pin", { p_session_id: sessionId, p_pin: pin });
  if (pinErr) throw new Error(pinErr.message);
  if (pinResult === "locked") return json(200, { state: "pin_locked", ...base });
  if (pinResult === "pin_required") return json(200, { state: "pin_required", wrong: false, ...base });
  if (pinResult === "bad") return json(200, { state: "pin_required", wrong: true, ...base });

  const guestToken = await signGuest({ s: sessionId!, r: rest.id, t: table.id });
  const menu = await loadMenu(db, rest.id);
  return json(200, { state: "ready", guest_token: guestToken, ...base, restaurant: { name: rest.name, upi_id: rest.upi_id, logo_url: rest.logo_url }, menu });
}));
