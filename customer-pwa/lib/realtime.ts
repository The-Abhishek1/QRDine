// "Something changed" pings from the database, so the page refreshes at once instead of waiting for the next poll.
// Optional: needs NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY. Without them the page just polls.
export function sessionIdFromToken(token: string): string | null {
  try {
    const body = token.split(".")[0].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(body + "=".repeat((4 - (body.length % 4)) % 4))).s ?? null;
  } catch { return null; }
}

export function subscribeSession(sessionId: string, onPing: () => void): () => void {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  if (!url || !key) return () => {};
  let stop = () => {};
  let cancelled = false;
  import("@supabase/supabase-js").then(({ createClient }) => {
    if (cancelled) return;
    const sb = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
    const ch = sb.channel(`session:${sessionId}`).on("broadcast", { event: "ping" }, () => onPing()).subscribe();
    stop = () => { sb.removeChannel(ch); };
  }).catch(() => {});
  return () => { cancelled = true; stop(); };
}
