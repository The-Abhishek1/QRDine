// Guest taps "Call waiter" or "Request bill".
import { admin, HttpError, handler, json, mapDbError, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";

Deno.serve(handler(async (req) => {
  const g = await requireGuest(req);
  if (!rateLimit(`svc:${g.s}`, 10, 60_000)) throw new HttpError(429, "rate_limited");
  const b = await readJson(req);
  if (b.type !== "waiter" && b.type !== "bill") throw new HttpError(400, "bad_request");
  const { data, error } = await admin().rpc("request_service", { p_session_id: g.s, p_type: b.type });
  if (error) throw mapDbError(error);
  return json(200, data);
}));
