function assertEquals(a: unknown, b: unknown) { if (a !== b) throw new Error(`expected ${b}, got ${a}`); }
Deno.env.set("QR_SIGNING_SECRET", "x".repeat(40));
const { signQr, verifyQr, signGuest, verifyGuest } = await import("./token.ts");

Deno.test("qr token round trip and tamper", async () => {
  const t = await signQr({ r: "r1", t: "t1", kv: 1 });
  assertEquals((await verifyQr(t))?.t, "t1");
  const [body, sig] = t.split(".");
  const forged = btoa(JSON.stringify({ r: "r2", t: "t1", kv: 1 })).replace(/=+$/, "") + "." + sig;
  assertEquals(await verifyQr(forged), null);
  assertEquals(await verifyQr(body + ".AAAA"), null);
  assertEquals(await verifyQr("garbage"), null);
});

Deno.test("guest token expires and is not a qr token", async () => {
  const g = await signGuest({ s: "s", r: "r", t: "t" }, 60);
  assertEquals((await verifyGuest(g))?.s, "s");
  assertEquals(await verifyQr(g), null);            // purpose separation
  const old = await signGuest({ s: "s", r: "r", t: "t" }, -5);
  assertEquals(await verifyGuest(old), null);
});
