// Polled by the PWA every few seconds: orders with status/ETA, the bill with taxes, open requests.
// When the owner marks the bill paid this returns state "ended" with the amount so the guest can be thanked.
import { admin, HttpError, handler, json, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";
import { loadMenu } from "../_shared/menu.ts";

type Item = { order_id: string; name_snapshot: string; variant_snapshot: string | null; addons: { name: string }[] | null; qty: number; unit_price: number; cancelled: boolean; cancel_reason: string | null };
const label = (i: Item) => i.name_snapshot + (i.variant_snapshot ? ` (${i.variant_snapshot})` : "") + (i.addons?.length ? " + " + i.addons.map((a) => a.name).join(", ") : "");

Deno.serve(handler(async (req) => {
  const g = await requireGuest(req);
  if (!rateLimit(`state:${g.s}`, 60, 60_000)) throw new HttpError(429, "rate_limited");
  const b = await readJson(req);
  const db = admin();

  const { data: s } = await db.from("sessions")
    .select("id,status,table_id,restaurant_id,total,payment_method").eq("id", g.s).maybeSingle();
  if (!s) throw new HttpError(404, "session_not_found");

  const { data: rest } = await db.from("restaurants").select("name,upi_id,logo_url").eq("id", s.restaurant_id).single();
  const restaurant = { name: rest?.name ?? "", upi_id: rest?.upi_id ?? null, logo_url: rest?.logo_url ?? null };

  if (s.status === "paid" || s.status === "void") {
    const { data: rv } = await db.from("reviews").select("id").eq("session_id", g.s).maybeSingle();
    return json(200, {
      state: "ended",
      session_status: s.status,
      restaurant,
      paid: s.status === "paid" ? { total: s.total, method: s.payment_method } : null,
      reviewed: !!rv,
    });
  }

  const [orders, items, reqs, tbl, bill] = await Promise.all([
    db.from("orders").select("id,order_type,status,eta_minutes,accepted_at,ready_at,reject_reason,note,created_at").eq("session_id", g.s).order("created_at"),
    db.from("order_items").select("order_id,name_snapshot,variant_snapshot,addons,qty,unit_price,cancelled,cancel_reason").eq("session_id", g.s),
    db.from("service_requests").select("type").eq("session_id", g.s).eq("status", "open"),
    db.from("dining_tables").select("label").eq("id", s.table_id).single(),
    db.rpc("compute_bill", { p_session_id: g.s }),
  ]);
  if (orders.error || items.error || reqs.error || bill.error) throw new Error("state_load_failed");

  const byOrder = new Map<string, Item[]>();
  for (const it of (items.data ?? []) as Item[]) byOrder.set(it.order_id, [...(byOrder.get(it.order_id) ?? []), it]);
  const lineTotal = (list: Item[]) => list.filter((i) => !i.cancelled).reduce((n, i) => n + i.qty * i.unit_price, 0);

  let pending = 0;
  const out = (orders.data ?? []).map((o) => {
    const list = byOrder.get(o.id) ?? [];
    const total = lineTotal(list);
    if (o.status === "pending") pending += total;
    return { ...o, ready: !!o.ready_at, total, items: list.map((i) => ({ name: label(i), qty: i.qty, price: i.unit_price, cancelled: i.cancelled, reason: i.cancel_reason })) };
  });

  const bl = bill.data as Record<string, unknown>;
  return json(200, {
    state: "active",
    restaurant,
    table: { label: tbl.data?.label ?? "" },
    session_status: s.status,
    orders: out,
    bill: {
      subtotal: bl.subtotal, discount: bl.discount, coupon: bl.coupon ?? null, service_charge: bl.service_charge, service_charge_pct: bl.service_charge_pct,
      tax: bl.tax, cgst: bl.cgst, sgst: bl.sgst, gst_rate: bl.gst_rate, round_off: bl.round_off, total: bl.total, pending,
    },
    open_requests: (reqs.data ?? []).map((r) => r.type),
    menu: b.include_menu ? await loadMenu(db, g.r) : undefined,
  });
}));
