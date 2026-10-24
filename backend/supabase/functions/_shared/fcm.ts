// Minimal FCM HTTP v1 client for Deno: service-account JWT (RS256) -> OAuth token -> messages:send.
import { b64url } from "./token.ts";

export type ServiceAccount = { project_id: string; client_email: string; private_key: string };
const enc = new TextEncoder();

export function pemToDer(pem: string): Uint8Array {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

export async function buildAssertion(sa: ServiceAccount, now = Math.floor(Date.now() / 1000)): Promise<string> {
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const key = await crypto.subtle.importKey(
    "pkcs8", pemToDer(sa.private_key) as BufferSource, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claim}`)));
  return `${header}.${claim}.${b64url(sig)}`;
}

let cached: { token: string; exp: number } | null = null;

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: await buildAssertion(sa, now) }),
  });
  if (!res.ok) throw new Error(`fcm_auth_failed_${res.status}`);
  const j = await res.json();
  cached = { token: j.access_token, exp: now + Number(j.expires_in ?? 3000) };
  return cached.token;
}

/** Data-only, high-priority message so the app can raise its own loud alert even when it was killed. */
export async function sendFcm(sa: ServiceAccount, deviceToken: string, data: Record<string, string>): Promise<"ok" | "unregistered" | "error"> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken(sa)}`, "Content-Type": "application/json" },
    body: JSON.stringify({ message: { token: deviceToken, data, android: { priority: "HIGH", ttl: "120s" } } }),
  });
  if (res.ok) return "ok";
  const text = await res.text();
  if (res.status === 404 || text.includes("UNREGISTERED")) return "unregistered";
  console.error("fcm send failed", res.status, text.slice(0, 300));
  return "error";
}
