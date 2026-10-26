import { createClient } from "npm:@supabase/supabase-js@2";
import { admin, HttpError, handler, json, rateLimit } from "../_shared/http.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SAFE_TOKEN = /^[A-Za-z0-9_.$<>:/-]{1,180}$/;
const fail = (error: string) => json(200, { ok: false, error });

Deno.serve(handler(async (req) => {
  const authorization = req.headers.get("authorization") ?? "";
  if (!authorization.toLowerCase().startsWith("bearer ")) throw new HttpError(401, "unauthorized");

  const userDb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  });
  const { data: authData, error: authError } = await userDb.auth.getUser();
  if (authError || !authData.user) throw new HttpError(401, "unauthorized");
  const userId = authData.user.id;
  if (!rateLimit(`diagnostics:${userId}`, 10, 60_000)) throw new HttpError(429, "rate_limited");

  const rawBody = await req.text();
  if (rawBody.length > 64 * 1024) throw new HttpError(413, "payload_too_large");
  let body: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(rawBody);
    body = parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {};
  } catch {
    throw new HttpError(400, "bad_request");
  }
  const db = admin();
  if (body.action === "list") {
    const { data: adminUser } = await db.from("platform_admins").select("user_id").eq("user_id", userId).maybeSingle();
    if (!adminUser) throw new HttpError(403, "forbidden");
    const { error: cleanupError } = await db.from("app_diagnostic_reports")
      .delete().lt("received_at", new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString());
    if (cleanupError) throw new HttpError(500, "diagnostics_unavailable");
    const { data, error } = await db.from("app_diagnostic_reports")
      .select("id,restaurant_id,kind,error_type,area,app_version,os_version,device_model,frames,occurred_at,received_at")
      .gte("received_at", new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString())
      .order("received_at", { ascending: false })
      .limit(100);
    if (error) throw new HttpError(500, "diagnostics_unavailable");
    const restaurantIds = [...new Set((data ?? []).map((row) => row.restaurant_id).filter((id): id is string => Boolean(id)))];
    const names = new Map<string, string>();
    if (restaurantIds.length) {
      const { data: hotels, error: hotelsError } = await db.from("restaurants").select("id,name").in("id", restaurantIds);
      if (hotelsError) throw new HttpError(500, "diagnostics_unavailable");
      for (const hotel of hotels ?? []) names.set(hotel.id, hotel.name);
    }
    const reports = (data ?? []).map((row) => ({
      ...row,
      restaurant_name: row.restaurant_id ? names.get(row.restaurant_id) ?? null : null,
    }));
    return json(200, { ok: true, reports });
  }

  const { data: platformAdmin } = await db.from("platform_admins").select("user_id").eq("user_id", userId).maybeSingle();
  const { data: owner } = await db.from("owners").select("restaurant_id,active").eq("user_id", userId).maybeSingle();
  if (!platformAdmin && (!owner || !owner.active)) throw new HttpError(403, "forbidden");
  const reports = Array.isArray(body.reports) ? body.reports.slice(0, 20) : [];
  if (reports.length === 0) return fail("bad_reports");

  const rows = reports.flatMap((value) => {
    if (!value || typeof value !== "object" || Array.isArray(value)) return [];
    const report = value as Record<string, unknown>;
    const eventId = String(report.event_id ?? "");
    const reportedRestaurantId = report.restaurant_id == null ? null : String(report.restaurant_id);
    const kind = report.kind === "crash" ? "crash" : report.kind === "error" ? "error" : null;
    const errorType = String(report.error_type ?? "");
    const area = String(report.area ?? "");
    const appVersion = String(report.app_version ?? "");
    const osVersion = String(report.os_version ?? "");
    const deviceModel = String(report.device_model ?? "");
    const occurredAt = String(report.occurred_at ?? "");
    const date = new Date(occurredAt);
    const frames = Array.isArray(report.frames)
      ? report.frames.filter((frame): frame is string => typeof frame === "string" && SAFE_TOKEN.test(frame)).slice(0, 8)
      : [];
    if (
      !UUID.test(eventId) || (reportedRestaurantId !== null && !UUID.test(reportedRestaurantId)) ||
      (reportedRestaurantId !== null && (!owner || reportedRestaurantId !== owner.restaurant_id)) ||
      !kind || !/^[A-Za-z0-9_.$]{1,120}$/.test(errorType) ||
      !/^[a-z_]{1,40}$/.test(area) || !/^[A-Za-z0-9._+-]{1,30}$/.test(appVersion) ||
      !/^[A-Za-z0-9._+-]{1,30}$/.test(osVersion) || !/^[A-Za-z0-9 _.-]{1,80}$/.test(deviceModel) ||
      !Number.isFinite(date.getTime()) || date.getTime() > Date.now() + 5 * 60_000
    ) return [];
    return [{
      event_id: eventId,
      restaurant_id: reportedRestaurantId,
      kind,
      error_type: errorType,
      area,
      app_version: appVersion,
      os_version: osVersion,
      device_model: deviceModel,
      frames,
      occurred_at: date.toISOString(),
    }];
  });
  if (rows.length === 0) return fail("bad_reports");

  const { error } = await db.from("app_diagnostic_reports").upsert(rows, { onConflict: "event_id", ignoreDuplicates: true });
  if (error) throw new HttpError(500, "diagnostics_unavailable");
  const { error: cleanupError } = await db.from("app_diagnostic_reports")
    .delete().lt("received_at", new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString());
  if (cleanupError) throw new HttpError(500, "diagnostics_unavailable");
  return json(200, { ok: true, accepted: rows.length });
}));
