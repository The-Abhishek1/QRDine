// Called by Supabase Database Webhooks for restaurant activity and online-order updates.
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

  const body = await readJson(req) as { type?: string; table?: string; record?: Record<string, unknown>; old_record?: Record<string, unknown> };
  if (!["INSERT", "UPDATE"].includes(body.type ?? "") || !body.record) return json(200, { skipped: true });
  const sa = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON") ?? "null") as ServiceAccount | null;
  if (!sa) throw new Error("FCM_SERVICE_ACCOUNT_JSON not set");

  const db = admin();
  const rec = body.record;
  let devices: { id: string | null; customerId?: string; fcm_token: string }[] = [];
  let data: Record<string, string>;
  if (body.table === "online_orders") {
    if (body.type === "UPDATE") {
      const old = body.old_record ?? {};
      if (old.status === rec.status && old.payment_status === rec.payment_status && old.refund_status === rec.refund_status) {
        return json(200, { skipped: true });
      }
      const messages: Record<string, { title: string; body: string }> = {
        accepted: { title: "Restaurant accepted your order", body: "Your order is confirmed and preparation will begin soon." },
        preparing: { title: "Your order is being prepared", body: "The restaurant has started preparing your order." },
        ready: { title: "Your order is ready", body: "Your order is ready for pickup or dispatch." },
        completed: { title: "Order completed", body: "Your online order is complete. Thank you." },
        rejected: { title: "Restaurant could not accept your order", body: String(rec.reject_reason ?? "Open QR Dine to view the order details.") },
        cancelled: { title: "Order cancelled", body: rec.refund_status === "required" ? "The restaurant will review whether a UPI refund is due." : "Your order was cancelled." },
      };
      const progress = old.status !== rec.status ? messages[String(rec.status)] : undefined;
      const paymentChanged = old.payment_status !== rec.payment_status && rec.payment_status === "paid";
      const refundChanged = old.refund_status !== rec.refund_status;
      if (!progress && !paymentChanged && !refundChanged) return json(200, { skipped: true });
      const message = progress ?? (refundChanged && rec.refund_status === "refunded"
        ? { title: "UPI refund recorded", body: "The restaurant has marked your refund as sent." }
        : paymentChanged
          ? { title: "UPI payment verified", body: "The restaurant verified your payment." }
          : { title: "Refund status updated", body: "Open QR Dine to review your refund status." });
      data = {
        kind: "online_order_update",
        title: message.title,
        body: message.body.slice(0, 180),
        order_id: String(rec.id),
        status: String(rec.status),
      };
      const { data: customerDevices, error: deviceError } = await db
        .from("customer_push_devices").select("customer_id,token").eq("customer_id", String(rec.customer_id));
      if (deviceError) throw deviceError;
      devices = (customerDevices ?? []).map((device) => ({
        id: null,
        customerId: device.customer_id,
        fcm_token: device.token,
      }));
    } else {
      const items = Array.isArray(rec.items_snapshot) ? rec.items_snapshot as Record<string, unknown>[] : [];
      const count = items.reduce((n, item) => n + Number(item.qty ?? 0), 0);
      const fulfilment = rec.fulfilment === "delivery" ? "Delivery" : "Pickup";
      data = {
        kind: "online_order",
        title: `New online ${fulfilment.toLowerCase()} order`,
        body: `${String(rec.customer_name ?? "Customer")} · ${count} ${count === 1 ? "item" : "items"} · ${rupees(Number(rec.total ?? 0))}`,
        order_id: String(rec.id),
      };
      const { data: restaurantDevices, error: deviceError } = await db
        .from("devices").select("id,fcm_token").eq("restaurant_id", String(rec.restaurant_id));
      if (deviceError) throw deviceError;
      devices = (restaurantDevices ?? []).map((device) => ({ ...device, customerId: undefined }));
    }
  } else {
    if (body.type !== "INSERT") return json(200, { skipped: true });
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

    const { data: restaurantDevices, error: deviceError } = await db
      .from("devices").select("id,fcm_token").eq("restaurant_id", String(rec.restaurant_id));
    if (deviceError) throw deviceError;
    devices = (restaurantDevices ?? []).map((device) => ({ ...device, customerId: undefined }));
  }

  let sent = 0;
  for (const d of devices ?? []) {
    const r = await sendFcm(sa, d.fcm_token, data);
    if (r === "ok") sent++;
    if (r === "unregistered") {
      if (body.table === "online_orders" && body.type === "UPDATE") {
        if (!d.customerId) throw new Error("customer_push_device_owner_missing");
        const { error: deleteError } = await db.from("customer_push_devices")
          .delete().eq("customer_id", d.customerId).eq("token", d.fcm_token);
        if (deleteError) throw deleteError;
      } else {
        if (!d.id) throw new Error("restaurant_push_device_id_missing");
        const { error: deleteError } = await db.from("devices").delete().eq("id", d.id);
        if (deleteError) throw deleteError;
      }
    }
  }
  return json(200, { sent });
}));
