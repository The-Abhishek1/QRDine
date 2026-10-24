// The hotel owner creates and manages waiter and kitchen logins. The caller must be the (active, approved) owner of the hotel,
// checked in the database. Expected failures return 200 {ok:false,error}.
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
  const { data: me } = await db.from("owners").select("restaurant_id,role,active").eq("user_id", u.user.id).maybeSingle();
  if (!me || me.role !== "owner" || !me.active) throw new HttpError(403, "forbidden");
  const { data: hotel } = await db.from("restaurants").select("approval,on_hold").eq("id", me.restaurant_id).single();
  if (!hotel || hotel.approval !== "approved" || hotel.on_hold) throw new HttpError(403, "forbidden");
  const rid = me.restaurant_id as string;

  const b = await readJson(req);
  const targetId = String(b.user_id ?? "");
  const own = async () => {
    const { data } = await db.from("owners").select("user_id,role").eq("user_id", targetId).eq("restaurant_id", rid).neq("role", "owner").maybeSingle();
    return data;
  };

  switch (b.action) {
    case "create": {
      const email = String(b.email ?? "").trim().toLowerCase();
      const password = String(b.password ?? "");
      const name = String(b.name ?? "").trim().slice(0, 60);
      const role = String(b.role ?? "");
      if (!EMAIL.test(email)) return fail("bad_email");
      if (password.length < 8) return fail("weak_password");
      if (!name) return fail("name_required");
      if (role !== "waiter" && role !== "kitchen") return fail("bad_role");
      const { count } = await db.from("owners").select("user_id", { count: "exact", head: true }).eq("restaurant_id", rid);
      if ((count ?? 0) >= 21) return fail("too_many_staff");
      const created = await db.auth.admin.createUser({ email, password, email_confirm: true });
      if (created.error || !created.data.user) return fail(/already|registered|exists/i.test(created.error?.message ?? "") ? "email_taken" : "create_failed");
      const { error } = await db.from("owners").insert({ user_id: created.data.user.id, restaurant_id: rid, role, display_name: name, active: true });
      if (error) { await db.auth.admin.deleteUser(created.data.user.id); return fail("create_failed"); }
      return json(200, { ok: true });
    }
    case "set_active": {
      if (!(await own())) return fail("not_found");
      const { error } = await db.from("owners").update({ active: b.active === true }).eq("user_id", targetId);
      return error ? fail("update_failed") : json(200, { ok: true });
    }
    case "reset_password": {
      const password = String(b.new_password ?? "");
      if (password.length < 8) return fail("weak_password");
      if (!(await own())) return fail("not_found");
      const { error } = await db.auth.admin.updateUserById(targetId, { password });
      return error ? fail("update_failed") : json(200, { ok: true });
    }
    case "delete": {
      if (!(await own())) return fail("not_found");
      await db.from("owners").delete().eq("user_id", targetId);
      await db.auth.admin.deleteUser(targetId);
      return json(200, { ok: true });
    }
    default:
      return fail("unknown_action");
  }
}));
