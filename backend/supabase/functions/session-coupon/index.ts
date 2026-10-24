// A guest enters a coupon code for their bill. The staff still see it and can remove it.
import { admin, HttpError, handler, json, mapDbError, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";

Deno.serve(handler(async (req) => {
  const g = await requireGuest(req);
  if (!rateLimit(`coupon:${g.s}`, 10, 60_000)) throw new HttpError(429, "rate_limited");
  const b = await readJson(req);
  const code = String(b.code ?? "").trim().slice(0, 20);
  if (code.length < 3) throw new HttpError(400, "coupon_invalid");
  const { data, error } = await admin().rpc("apply_session_coupon", { p_session_id: g.s, p_code: code });
  if (error) throw mapDbError(error);
  return json(200, data);
}));
