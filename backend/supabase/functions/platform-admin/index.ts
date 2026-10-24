// Platform admin actions that need the Supabase Auth admin API: create a hotel account, reset its password, delete it.
// The caller must be a signed-in user listed in platform_admins (checked in the database, not from the token claims).
// Expected failures return 200 {ok:false,error}; only authentication problems return 401/403.
import { createClient } from "npm:@supabase/supabase-js@2";
import { admin, HttpError, handler, json, readJson } from "../_shared/http.ts";

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const fail = (error: string) => json(200, { ok: false, error });

Deno.serve(handler(async (req) => {
  const auth = req.headers.get("authorization") ?? "";
  if (!auth.toLowerCase().startsWith("bearer ")) throw new HttpError(401, "unauthorized");
  const userDb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } }, auth: { persistSession: false },
  });
  const { data: u, error: uErr } = await userDb.auth.getUser();
  if (uErr || !u.user) throw new HttpError(401, "unauthorized");

  const db = admin();
  const { data: pa } = await db.from("platform_admins").select("user_id").eq("user_id", u.user.id).maybeSingle();
  if (!pa) throw new HttpError(403, "forbidden");

  const b = await readJson(req);
  const log = (action: string, rid: string | null, name: string | null, data: Record<string, unknown> = {}) =>
    db.from("platform_audit").insert({ actor: u.user.id, action, restaurant_id: rid, hotel_name: name, data });

  switch (b.action) {
    case "create_hotel": {
      const email = String(b.email ?? "").trim().toLowerCase();
      const password = String(b.password ?? "");
      const name = String(b.name ?? "").trim();
      const days = Number.isInteger(b.trial_days) ? Number(b.trial_days) : 30;
      if (!EMAIL.test(email)) return fail("bad_email");
      if (password.length < 8) return fail("weak_password");
      if (!name || name.length > 120) return fail("name_required");
      if (days < 0 || days > 365) return fail("bad_days");

      const created = await db.auth.admin.createUser({ email, password, email_confirm: true });
      if (created.error || !created.data.user) {
        return fail(/already|registered|exists/i.test(created.error?.message ?? "") ? "email_taken" : "create_failed");
      }
      const uid = created.data.user.id;
      const { data: rest, error: rErr } = await db.from("restaurants").insert({
        name, phone: b.phone ? String(b.phone).slice(0, 20) : null, address: b.address ? String(b.address).slice(0, 200) : null,
        approval: "approved", approved_at: new Date().toISOString(),
        trial_ends_at: new Date(Date.now() + days * 86_400_000).toISOString(),
      }).select("id").single();
      if (rErr || !rest) { await db.auth.admin.deleteUser(uid); return fail("create_failed"); }
      const { error: oErr } = await db.from("owners").insert({ user_id: uid, restaurant_id: rest.id });
      if (oErr) { await db.from("restaurants").delete().eq("id", rest.id); await db.auth.admin.deleteUser(uid); return fail("create_failed"); }
      await log("create_hotel", rest.id, name, { email, trial_days: days });
      return json(200, { ok: true, restaurant_id: rest.id });
    }

    case "reset_password": {
      const password = String(b.new_password ?? "");
      if (password.length < 8) return fail("weak_password");
      const { data: owner } = await db.from("owners").select("user_id").eq("restaurant_id", String(b.restaurant_id ?? "")).eq("role", "owner").maybeSingle();
      if (!owner) return fail("hotel_not_found");
      const { error } = await db.auth.admin.updateUserById(owner.user_id, { password });
      if (error) return fail("update_failed");
      const { data: r } = await db.from("restaurants").select("name").eq("id", String(b.restaurant_id)).maybeSingle();
      await log("reset_password", String(b.restaurant_id), r?.name ?? null);
      return json(200, { ok: true });
    }

    case "delete_hotel": {
      const rid = String(b.restaurant_id ?? "");
      const { data: r } = await db.from("restaurants").select("id,name").eq("id", rid).maybeSingle();
      if (!r) return fail("hotel_not_found");
      if (String(b.confirm_name ?? "").trim() !== r.name) return fail("name_mismatch");
      const { data: owners } = await db.from("owners").select("user_id").eq("restaurant_id", rid);
      // payments keep their rows (restaurant_id becomes null); everything else cascades
      const { error } = await db.from("restaurants").delete().eq("id", rid);
      if (error) return fail("delete_failed");
      for (const o of owners ?? []) {
        const { data: isAdmin } = await db.from("platform_admins").select("user_id").eq("user_id", o.user_id).maybeSingle();
        if (!isAdmin) await db.auth.admin.deleteUser(o.user_id);
      }
      await log("delete_hotel", null, r.name, { owners: (owners ?? []).length });
      return json(200, { ok: true });
    }

    default:
      return fail("unknown_action");
  }
}));
