import { createClient } from "npm:@supabase/supabase-js@2";

const ORIGIN = Deno.env.get("PWA_ORIGIN") ?? "*";
export const cors = {
  "Access-Control-Allow-Origin": ORIGIN,
  "Access-Control-Allow-Headers": "content-type, x-guest-token, authorization, apikey, x-client-info",
  "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
  "Vary": "Origin",
};

export class HttpError extends Error {
  constructor(public status: number, public code: string, public detail?: string) { super(code); }
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
}

/** Service-role client: bypasses RLS. Only ever used after the caller's token has been verified. */
export function admin() {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
}

export async function readJson(req: Request): Promise<Record<string, unknown>> {
  if (req.method === "GET") return {};
  try {
    const b = await req.json();
    return b && typeof b === "object" ? b as Record<string, unknown> : {};
  } catch { return {}; }
}

export function clientIp(req: Request): string {
  return req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
}

// Best-effort per-instance limiter. The real limits live in SQL (place_order, request_service, verify_session_pin).
const hits = new Map<string, number[]>();
export function rateLimit(key: string, max: number, windowMs: number): boolean {
  const now = Date.now();
  const list = (hits.get(key) ?? []).filter((t) => now - t < windowMs);
  if (list.length >= max) { hits.set(key, list); return false; }
  list.push(now); hits.set(key, list);
  if (hits.size > 5000) hits.clear();
  return true;
}

/** Turns the error codes raised by our SQL functions into HTTP responses. */
export function mapDbError(e: { message?: string }): HttpError {
  const m = e.message ?? "";
  if (m.startsWith("session_closed")) return new HttpError(409, "session_closed");
  if (m.startsWith("subscription_inactive")) return new HttpError(403, "subscription_inactive");
  if (m.startsWith("rate_limited")) return new HttpError(429, "rate_limited");
  if (m.startsWith("dish_unavailable")) return new HttpError(409, "dish_unavailable", m.split(":").slice(1).join(":"));
  if (m.startsWith("dish_not_found")) return new HttpError(400, "dish_not_found");
  if (m.startsWith("order_too_large")) return new HttpError(400, "order_too_large");
  if (m.startsWith("bad_items")) return new HttpError(400, "bad_items");
  if (m.startsWith("orders_not_served")) return new HttpError(409, "orders_not_served");
  if (m.startsWith("nothing_to_bill")) return new HttpError(409, "nothing_to_bill");
  if (m.startsWith("already_reviewed")) return new HttpError(409, "already_reviewed");
  if (m.startsWith("not_paid")) return new HttpError(409, "not_paid");
  if (m.startsWith("empty_review")) return new HttpError(400, "empty_review");
  if (m.startsWith("variant_required")) return new HttpError(400, "variant_required", m.split(":").slice(1).join(":"));
  if (m.startsWith("addon_choice")) return new HttpError(400, "addon_choice", m.split(":").slice(1).join(":"));
  if (m.startsWith("coupon_invalid")) return new HttpError(400, "coupon_invalid");
  console.error("db error", m);
  return new HttpError(500, "server_error");
}

export function handler(fn: (req: Request) => Promise<Response>) {
  return async (req: Request): Promise<Response> => {
    if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
    try {
      return await fn(req);
    } catch (e) {
      if (e instanceof HttpError) return json(e.status, { error: e.code, detail: e.detail });
      console.error(e);
      return json(500, { error: "server_error" });
    }
  };
}
