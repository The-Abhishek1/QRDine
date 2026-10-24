// Owner app asks for printable QR links for its tables. The signing secret never leaves the server.
import { createClient } from "npm:@supabase/supabase-js@2";
import { HttpError, handler, json } from "../_shared/http.ts";
import { signQr } from "../_shared/token.ts";

Deno.serve(handler(async (req) => {
  const auth = req.headers.get("authorization") ?? "";
  if (!auth.toLowerCase().startsWith("bearer ")) throw new HttpError(401, "unauthorized");

  // user-scoped client: RLS decides which tables this person may see
  const userDb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false },
  });
  const { data: u, error: uErr } = await userDb.auth.getUser();
  if (uErr || !u.user) throw new HttpError(401, "unauthorized");

  const { data: tables, error } = await userDb.from("dining_tables")
    .select("id,restaurant_id,label,qr_key_version").order("label");
  if (error) throw new HttpError(500, "server_error");

  const base = (Deno.env.get("PWA_BASE_URL") ?? "").replace(/\/+$/, "");
  if (!base) throw new Error("PWA_BASE_URL not set");
  const out = await Promise.all((tables ?? []).map(async (t) => ({
    table_id: t.id,
    label: t.label,
    url: `${base}/t/${await signQr({ r: t.restaurant_id, t: t.id, kv: t.qr_key_version })}`,
  })));
  return json(200, { tables: out });
}));
