// A guest rates their visit after the owner has marked the bill paid. One review per session.
import { admin, HttpError, handler, json, mapDbError, rateLimit, readJson } from "../_shared/http.ts";
import { requireGuest } from "../_shared/token.ts";

const rating = (v: unknown): number | null => {
  if (v === undefined || v === null || v === "") return null;
  const n = Number(v);
  if (!Number.isInteger(n) || n < 1 || n > 5) throw new HttpError(400, "bad_request");
  return n;
};

Deno.serve(handler(async (req) => {
  const g = await requireGuest(req);
  if (!rateLimit(`review:${g.s}`, 5, 60_000)) throw new HttpError(429, "rate_limited");
  const b = await readJson(req);
  const { error } = await admin().rpc("submit_review", {
    p_session_id: g.s,
    p_food: rating(b.food),
    p_service: rating(b.service),
    p_comment: b.comment ? String(b.comment).slice(0, 500) : null,
  });
  if (error) throw mapDbError(error);
  return json(200, { ok: true });
}));
