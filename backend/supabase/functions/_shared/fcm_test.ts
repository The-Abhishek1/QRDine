function assert(c: unknown, m: string) { if (!c) throw new Error(m); }
Deno.env.set("QR_SIGNING_SECRET", "x".repeat(40));
const { buildAssertion } = await import("./fcm.ts");
const { b64urlDecode } = await import("./token.ts");

Deno.test("service-account assertion is a valid RS256 JWT", async () => {
  const pair = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  let bin = ""; for (const b of der) bin += String.fromCharCode(b);
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(bin).replace(/(.{64})/g, "$1\n")}\n-----END PRIVATE KEY-----\n`;
  const jwt = await buildAssertion({ project_id: "p", client_email: "svc@p.iam.gserviceaccount.com", private_key: pem }, 1000);
  const [h, c, s] = jwt.split(".");
  const claim = JSON.parse(new TextDecoder().decode(b64urlDecode(c)));
  assert(claim.iss === "svc@p.iam.gserviceaccount.com" && claim.exp === 4600 && claim.scope.endsWith("firebase.messaging"), "claims");
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", pair.publicKey, b64urlDecode(s) as BufferSource, new TextEncoder().encode(`${h}.${c}`));
  assert(ok, "signature does not verify");
});
