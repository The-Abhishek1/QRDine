// Called by Supabase Database Webhooks on INSERT into orders, service_requests, and online_orders.
// Pushes a high-priority FCM data message to every registered device of that restaurant.
import { admin, HttpError, handler, json, readJson } from "../_shared/http.ts";
import { sendFcm, type ServiceAccount } from "../_shared/fcm.ts";

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}
const rupees = (p: number) => "₹" + (p % 100 === 0 ? String(p / 100) : (p / 100).toFixed(2));

Deno.serve(handler(async (req) => {
  const secret = Deno.env.get("WEBHOOK_SECRET") ?? "";
  if (!secret || !safeEqual(req.headers.get("x-webhook-secret") ?? "", secret)) throw new HttpError(401, "unauthorized");

  const body = await readJson(req) as { type?: string; table?: string; record?: Record<string, unknown> };
  if (body.type !== "INSERT" || !body.record) return json(200, { skipped: true });
  const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "null") as ServiceAccount | null;
  if (!sa) throw new Error("FCM_SERVICE_ACCOUNT_JSON not set");

  const db = admin();
  const rec = body.record;
  const rid = String(rec.restaurant_id);
  let data: Record<string, string>;
  if (body.table === "online_orders") {
    const items = Array.isArray(rec.items_snapshot) ? rec.items_snapshot as Record<string, unknown>[] : [];
    const count = items.reduce((n, item) => n + Number(item.qty ?? 0), 0);
    const fulfilment = rec.fulfilment === "delivery" ? "Delivery" : "Pickup";
    data = {
      kind: "online_order",
      title: `New online ${fulfilment.toLowerCase()} order`,
      body: `${String(rec.customer_name ?? "Customer")} · ${count} ${count === 1 ? "item" : "items"} · ${rupees(Number(rec.total ?? 0))}`,
      order_id: String(rec.id),
    };
  } else {
    const { data: sess } = await db.from("sessions").select("table_id").eq("id", String(rec.session_id)).maybeSingle();
    const { data: tbl } = sess
      ? await db.from("dining_tables").select("label").eq("id", sess.table_id).maybeSingle()
      : { data: null };
    const label = tbl?.label ?? "?";

    if (body.table === "orders") {
      const { data: items } = await db.from("order_items").select("qty,unit_price").eq("order_id", String(rec.id));
      const count = (items ?? []).reduce((n, i) => n + i.qty, 0);
      const total = (items ?? []).reduce((n, i) => n + i.qty * i.unit_price, 0);
      data = { kind: "order", title: `New order · Table ${label}`, body: `${count} ${count === 1 ? "item" : "items"} · ${rupees(total)}`, order_id: String(rec.id) };
    } else if (body.table === "service_requests") {
      const bill = rec.type === "bill";
      data = { kind: "request", title: bill ? `Table ${label} wants the bill` : `Table ${label} is calling a waiter`, body: "Open the app", request_id: String(rec.id) };
    } else if (body.table === "reviews") {
      const ratings = [rec.rating_food, rec.rating_service].filter((n): n is number => typeof n === "number");
      const low = ratings.length ? Math.min(...ratings) : 5;
      if (low > 2) return json(200, { skipped: true });            // only unhappy guests wake the owner
      data = { kind: "review", title: `Low rating (${low} of 5) · Table ${label}`, body: String(rec.comment ?? "Open Insights to follow up.").slice(0, 120), review_id: String(rec.id) };
    } else return json(200, { skipped: true });
  }

  const { data: devices } = await db.from("devices").select("id,fcm_token").eq("restaurant_id", rid);
  let sent = 0;
  for (const d of devices ?? []) {
    const r = await sendFcm(sa, d.fcm_token, data);
    if (r === "ok") sent++;
    if (r === "unregistered") await db.from("devices").delete().eq("id", d.id);
  }
  return json(200, { sent });
}));
