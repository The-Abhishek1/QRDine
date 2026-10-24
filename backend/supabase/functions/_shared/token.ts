// Signed tokens: QR tokens (printed on tables) and guest tokens (issued per table session).
// Format: base64url(JSON).base64url(HMAC-SHA256(secret, purpose + "." + body)). The purpose prefix stops a QR
// token being replayed as a guest token or the other way round.
import { HttpError } from "./http.ts";

const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export function b64urlDecode(s: string): Uint8Array {
  const pad = "=".repeat((4 - (s.length % 4)) % 4);
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/") + pad);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

function secret(): string {
  const s = Deno.env.get("QR_SIGNING_SECRET");
  if (!s || s.length < 32) throw new Error("QR_SIGNING_SECRET must be set (32+ chars)");
  return s;
}
const key = (usage: KeyUsage[]) =>
  crypto.subtle.importKey("raw", enc.encode(secret()), { name: "HMAC", hash: "SHA-256" }, false, usage);

async function sign(purpose: string, payload: object): Promise<string> {
  const body = b64url(enc.encode(JSON.stringify(payload)));
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", await key(["sign"]), enc.encode(`${purpose}.${body}`)));
  return `${body}.${b64url(sig)}`;
}

async function verify<T>(purpose: string, token: string): Promise<T | null> {
  try {
    const [body, sig] = token.split(".");
    if (!body || !sig) return null;
    const ok = await crypto.subtle.verify("HMAC", await key(["verify"]), b64urlDecode(sig) as BufferSource, enc.encode(`${purpose}.${body}`));
    if (!ok) return null;
    return JSON.parse(dec.decode(b64urlDecode(body))) as T;
  } catch { return null; }
}

export type QrPayload = { r: string; t: string; kv: number };
export const signQr = (p: QrPayload) => sign("qr", p);
export const verifyQr = (token: string) => verify<QrPayload>("qr", token);

export type GuestPayload = { s: string; r: string; t: string; exp: number };
export const signGuest = (p: Omit<GuestPayload, "exp">, ttlSeconds = 12 * 3600) =>
  sign("guest", { ...p, exp: Math.floor(Date.now() / 1000) + ttlSeconds });

export async function verifyGuest(token: string): Promise<GuestPayload | null> {
  const p = await verify<GuestPayload>("guest", token);
  if (!p || typeof p.exp !== "number" || p.exp < Date.now() / 1000) return null;
  return p;
}

export async function requireGuest(req: Request): Promise<GuestPayload> {
  const t = req.headers.get("x-guest-token") ?? "";
  const p = t ? await verifyGuest(t) : null;
  if (!p) throw new HttpError(401, "invalid_session");
  return p;
}
