"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { rupees } from "@/lib/money";
import { newKey } from "@/lib/uuid";
import { type CartLine, isSimple, lineKey, parseCart, unitPrice } from "@/lib/cart";
import { I18nContext, LANGS, makeI18n } from "@/lib/i18n";
import { sessionIdFromToken, subscribeSession } from "@/lib/realtime";
import type { ActiveState, Dish, EndedState, JoinResult, Menu } from "@/lib/types";
import MenuView from "./MenuView";
import DishSheet from "./DishSheet";
import CartSheet, { type CartRow } from "./CartSheet";
import OrdersView from "./OrdersView";
import ThankYou from "./ThankYou";

type Phase =
  | { kind: "loading" }
  | { kind: "gate"; join: JoinResult }
  | { kind: "ended"; info: EndedState | null }
  | { kind: "error"; code: string }
  | { kind: "ready" };

const SERVICE_ERRORS: Record<string, string> = {
  orders_not_served: "You can request the bill once all your dishes are served.",
  nothing_to_bill: "There is nothing to bill yet.",
  rate_limited: "Please wait a moment before trying again.",
  session_closed: "This table's session has ended.",
};

const ORDER_ERRORS: Record<string, string> = {
  session_closed: "This table's session has ended. Scan the QR code again to start a new order.",
  rate_limited: "Too many orders in a short time. Wait a few minutes or call the waiter.",
  subscription_inactive: "This restaurant isn't taking orders through the app right now. Please ask the staff.",
  order_too_large: "That order is too large. Split it into two orders or ask the waiter.",
  network: "No connection. Your cart is saved, so try again in a moment.",
};

export default function TableApp({ token }: { token: string }) {
  const sk = token.slice(-24);
  const gKey = `qd:g:${sk}`, cKey = `qd:c2:${sk}`, kKey = `qd:k:${sk}`;

  const [phase, setPhase] = useState<Phase>({ kind: "loading" });
  const [restaurant, setRestaurant] = useState("");
  const [tableLabel, setTableLabel] = useState("");
  const [logo, setLogo] = useState<string | null>(null);
  const [menu, setMenu] = useState<Menu | null>(null);
  const [session, setSession] = useState<ActiveState | null>(null);
  const [online, setOnline] = useState(true);
  const [tab, setTab] = useState<"menu" | "orders">("menu");
  const [cart, setCart] = useState<CartLine[]>([]);
  const [configuring, setConfiguring] = useState<Dish | null>(null);
  const [note, setNote] = useState("");
  const [orderType, setOrderType] = useState<"dine_in" | "parcel">("dine_in");
  const [cartOpen, setCartOpen] = useState(false);
  const [placing, setPlacing] = useState(false);
  const [cartError, setCartError] = useState<string | null>(null);
  const [svcBusy, setSvcBusy] = useState(false);
  const [svcNotice, setSvcNotice] = useState<string | null>(null);
  const [pin, setPin] = useState("");
  const [lang, setLang] = useState("en");
  const guest = useRef<string | null>(null);

  const i18n = useMemo(() => makeI18n(lang), [lang]);
  const { t, pick } = i18n;

  useEffect(() => { try { const l = localStorage.getItem("qd:lang"); if (l) setLang(l); } catch {} }, []);
  const chooseLang = (l: string) => { setLang(l); try { localStorage.setItem("qd:lang", l); } catch {} };

  const clearStorage = useCallback(() => {
    try { localStorage.removeItem(gKey); localStorage.removeItem(kKey); localStorage.removeItem(cKey); } catch {}
  }, [gKey, kKey, cKey]);
  const forgetSession = useCallback(() => { guest.current = null; clearStorage(); }, [clearStorage]);

  const join = useCallback(async (pinValue?: string) => {
    try {
      const r = await api.join(token, pinValue);
      setRestaurant(r.restaurant.name);
      setLogo(r.restaurant.logo_url ?? null);
      setTableLabel(r.table.label);
      if (r.state === "ready" && r.guest_token && r.menu) {
        guest.current = r.guest_token;
        try { localStorage.setItem(gKey, r.guest_token); } catch {}
        setMenu(r.menu);
        setPhase({ kind: "ready" });
        const s = await api.state(r.guest_token, false).catch(() => null);
        if (s?.state === "active") setSession(s);
      } else {
        setPhase({ kind: "gate", join: r });
      }
    } catch (e) {
      setPhase({ kind: "error", code: e instanceof ApiError ? e.code : "network" });
    }
  }, [token, gKey]);

  const refresh = useCallback(async (includeMenu = false) => {
    const g = guest.current;
    if (!g) return;
    try {
      const s = await api.state(g, includeMenu);
      setOnline(true);
      if (s.state === "ended") { clearStorage(); setPhase({ kind: "ended", info: s }); return; }
      setSession(s);
      setLogo(s.restaurant.logo_url ?? null);
      setRestaurant((r) => s.restaurant.name || r);
      setTableLabel((l) => s.table.label || l);
      if (s.menu) setMenu(s.menu);
    } catch (e) {
      if (e instanceof ApiError && (e.code === "invalid_session" || e.code === "session_not_found")) {
        forgetSession(); setPhase({ kind: "loading" }); void join();
      } else setOnline(false);
    }
  }, [forgetSession, clearStorage, join]);
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  // boot: reuse a saved guest token if it is still valid, otherwise join from the QR
  useEffect(() => {
    let saved: string | null = null;
    try { saved = localStorage.getItem(gKey); setCart(parseCart(localStorage.getItem(cKey))); } catch {}
    (async () => {
      if (saved) {
        try {
          const s = await api.state(saved, true);
          if (s.state === "active" && s.menu) {
            guest.current = saved; setSession(s); setMenu(s.menu);
            setRestaurant(s.restaurant.name); setTableLabel(s.table.label); setLogo(s.restaurant.logo_url ?? null);
            setPhase({ kind: "ready" });
            return;
          }
        } catch {}
        forgetSession();
      }
      await join();
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // refresh quickly while something is in progress, slowly otherwise; instant pings when realtime is configured
  const inProgress = !!session && (session.session_status === "bill_requested" || session.orders.some((o) => o.status === "pending" || (o.status === "accepted" && !o.ready)));
  useEffect(() => {
    if (phase.kind !== "ready") return;
    const tick = () => { if (document.visibilityState === "visible") void refreshRef.current(); };
    const id = setInterval(tick, inProgress ? 4000 : 12000);
    document.addEventListener("visibilitychange", tick);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", tick); };
  }, [phase.kind, inProgress]);
  useEffect(() => {
    if (phase.kind !== "ready" || !guest.current) return;
    const sid = sessionIdFromToken(guest.current);
    if (!sid) return;
    return subscribeSession(sid, () => void refreshRef.current());
  }, [phase.kind]);

  // persist the cart; a changed cart gets a fresh idempotency key
  useEffect(() => {
    try { localStorage.setItem(cKey, JSON.stringify(cart)); localStorage.removeItem(kKey); } catch {}
  }, [cart, note, orderType, cKey, kKey]);

  const dishById = useMemo(() => new Map((menu?.dishes ?? []).map((d) => [d.id, d])), [menu]);
  const catById = useMemo(() => new Map((menu?.categories ?? []).map((c) => [c.id, c])), [menu]);

  const rows: CartRow[] = useMemo(() => cart.flatMap((line) => {
    const d = dishById.get(line.dishId);
    if (!d) return [];
    const c = catById.get(d.category_id);
    const problem = !d.is_available ? t("soldOut") : c && !c.open_now ? t("notNow") : null;
    const variant = d.variants.find((v) => v.id === line.variantId);
    const addons = d.addon_groups.flatMap((g) => g.addons).filter((a) => line.addonIds.includes(a.id));
    const label = pick(d.name, d.name_i18n) + (variant ? ` (${variant.name})` : "") + (addons.length ? ` + ${addons.map((a) => a.name).join(", ")}` : "");
    return [{ line, dish: d, unit: unitPrice(d, line.variantId, line.addonIds), label, problem }];
  }), [cart, dishById, catById, t, pick]);
  const count = rows.reduce((n, r) => n + r.line.qty, 0);
  const total = rows.reduce((n, r) => n + r.unit * r.line.qty, 0);

  const suggestions = useMemo(() => {
    if (!menu) return [] as Dish[];
    const inCart = new Set(cart.map((l) => l.dishId));
    const ids: string[] = [];
    for (const id of inCart) for (const p of menu.pairs[id] ?? []) if (!inCart.has(p) && !ids.includes(p)) ids.push(p);
    if (ids.length === 0) for (const b of menu.bestsellers) if (!inCart.has(b)) ids.push(b);
    return ids.map((id) => dishById.get(id)).filter((d): d is Dish => !!d && d.is_available && (catById.get(d.category_id)?.open_now ?? true)).slice(0, 3);
  }, [menu, cart, dishById, catById]);

  const addLine = (l: CartLine) => setCart((c) => {
    const i = c.findIndex((x) => x.key === l.key);
    return i >= 0 ? c.map((x, j) => (j === i ? { ...x, qty: Math.min(x.qty + l.qty, 50) } : x)) : [...c, l];
  });
  const incKey = (key: string) => setCart((c) => c.map((x) => (x.key === key ? { ...x, qty: Math.min(x.qty + 1, 50) } : x)));
  const decKey = (key: string) => setCart((c) => c.flatMap((x) => (x.key !== key ? [x] : x.qty <= 1 ? [] : [{ ...x, qty: x.qty - 1 }])));
  const quickAdd = (id: string) => addLine({ key: lineKey(id, null, [], ""), dishId: id, qty: 1, variantId: null, addonIds: [], instructions: "" });
  const quickRemove = (id: string) => decKey(lineKey(id, null, [], ""));
  const suggest = (d: Dish) => (isSimple(d) ? quickAdd(d.id) : setConfiguring(d));

  async function placeOrder() {
    const g = guest.current;
    if (!g || placing || rows.length === 0) return;
    setPlacing(true); setCartError(null);
    let key: string | null = null;
    try { key = localStorage.getItem(kKey); } catch {}
    if (!key) { key = newKey(); try { localStorage.setItem(kKey, key); } catch {} }
    try {
      await api.order(g, {
        order_type: orderType, client_key: key, note: note.trim() || undefined,
        items: rows.map((r) => ({ dish_id: r.dish.id, qty: r.line.qty, variant_id: r.line.variantId, addon_ids: r.line.addonIds, instructions: r.line.instructions })),
      });
      try { localStorage.removeItem(kKey); } catch {}
      setCart([]); setNote(""); setCartOpen(false); setTab("orders");
      await refresh();
    } catch (e) {
      const code = e instanceof ApiError ? e.code : "network";
      const detail = e instanceof ApiError ? e.detail : undefined;
      if (code === "dish_unavailable") { setCartError(`${detail ?? "A dish"} is not available. Remove it to continue.`); void refresh(true); }
      else if (code === "variant_required" || code === "addon_choice") { setCartError(`Please check the options for ${detail ?? "a dish"}.`); void refresh(true); }
      else if (code === "session_closed") { forgetSession(); setCartOpen(false); setPhase({ kind: "ended", info: null }); }
      else setCartError(ORDER_ERRORS[code] ?? "Something went wrong. Please try again.");
    } finally { setPlacing(false); }
  }

  async function service(type: "waiter" | "bill") {
    const g = guest.current;
    if (!g) return;
    setSvcBusy(true); setSvcNotice(null);
    try { await api.service(g, type); await refresh(); }
    catch (e) {
      if (e instanceof ApiError && e.code !== "network") setSvcNotice(SERVICE_ERRORS[e.code] ?? "Something went wrong. Please try again.");
      else setOnline(false);
      void refresh();
    } finally { setSvcBusy(false); }
  }

  async function applyCoupon(code: string): Promise<string | null> {
    const g = guest.current;
    if (!g) return null;
    try { await api.coupon(g, code); await refresh(); return null; }
    catch (e) { return e instanceof ApiError && e.code === "coupon_invalid" ? t("couponBad") : "Could not apply the coupon. Please try again."; }
  }

  // ---------- screens ----------
  const wrap = (node: React.ReactNode) => <I18nContext.Provider value={i18n}>{node}</I18nContext.Provider>;

  if (phase.kind === "loading") return wrap(<Screen title="Opening the menu…" />);
  if (phase.kind === "ended") {
    const info = phase.info;
    return wrap(
      <ThankYou
        name={info?.restaurant?.name || restaurant}
        paid={info?.paid ?? null}
        canReview={!!guest.current}
        reviewed={info?.reviewed ?? false}
        onSubmit={async (r) => { if (guest.current) await api.review(guest.current, r); }}
      />,
    );
  }
  if (phase.kind === "error") {
    return wrap(phase.code === "invalid_qr"
      ? <Screen title="This QR code isn't valid" body="It may have been replaced. Please ask the staff for the current one." />
      : <Screen title="Can't reach the restaurant" body="Check your connection and try again." action={{ label: "Try again", run: () => { setPhase({ kind: "loading" }); void join(); } }} />);
  }
  if (phase.kind === "gate") {
    const j = phase.join;
    const head = <Header name={j.restaurant.name} table={j.table.label} logo={j.restaurant.logo_url ?? null} langs={[]} lang={lang} onLang={chooseLang} />;
    if (j.state === "pin_required" || j.state === "pin_locked") {
      return wrap(
        <div className="shell">{head}
          <main className="screen inline">
            <h1 className="screen-title">Enter the table code</h1>
            <p className="screen-body">The staff will give you a 4-digit code for this table.</p>
            {j.state === "pin_locked" ? <p className="error">Too many wrong codes. Ask the staff for help.</p> : (
              <form onSubmit={(e) => { e.preventDefault(); void join(pin); }} className="pin-form">
                <input inputMode="numeric" pattern="[0-9]*" maxLength={4} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} aria-label="Table code" autoFocus />
                {j.wrong && <p className="error" role="alert">That code is wrong. Try again.</p>}
                <button className="btn-primary" disabled={pin.length !== 4}>Continue</button>
              </form>
            )}
          </main>
        </div>,
      );
    }
    const copy: Record<string, [string, string]> = {
      table_closed: ["This table isn't open yet", "Ask the staff to open it, then check again."],
      shop_closed: ["Not taking orders right now", "Please ask the staff for help."],
      unavailable: ["Ordering from the table isn't available", "Please ask the staff for a menu."],
    };
    const [ti, bo] = copy[j.state] ?? copy.unavailable;
    return wrap(
      <div className="shell">{head}
        <Screen inline title={ti} body={bo} action={j.state === "table_closed" ? { label: "Check again", run: () => { setPhase({ kind: "loading" }); void join(); } } : undefined} />
      </div>,
    );
  }

  const open = session?.session_status === "bill_requested";
  const langs = menu?.languages ?? [];
  return wrap(
    <div className="shell">
      <Header name={restaurant} table={tableLabel} logo={logo} langs={langs} lang={lang} onLang={chooseLang} />
      {!online && <div className="offline" role="status">Can't reach the restaurant. Retrying…</div>}
      {menu?.announcement && <div className="announce">{menu.announcement}</div>}
      <nav className="tabs" aria-label="Sections">
        <button className={tab === "menu" ? "on" : ""} onClick={() => setTab("menu")}>{t("menu")}</button>
        <button className={tab === "orders" ? "on" : ""} onClick={() => { setTab("orders"); void refresh(); }}>
          {t("myOrders")}{session && session.orders.length > 0 ? ` (${session.orders.length})` : ""}
        </button>
      </nav>
      <main className="content">
        {tab === "menu" && menu && <MenuView menu={menu} lines={cart} onQuickAdd={quickAdd} onQuickRemove={quickRemove} onConfigure={setConfiguring} />}
        {tab === "orders" && (session
          ? <OrdersView s={session} tableLabel={tableLabel} busy={svcBusy} notice={svcNotice} onService={service} onCoupon={applyCoupon} />
          : <p className="empty">…</p>)}
      </main>

      {tab === "menu" && count > 0 && (
        <button className="cartbar" onClick={() => { setCartError(null); setCartOpen(true); }}>
          <span>{count} {count === 1 ? t("item") : t("items")}</span>
          <span>{t("viewOrder")} · {rupees(total)}</span>
        </button>
      )}
      {tab === "menu" && count === 0 && open && <div className="cartbar-note">{t("billRequested")}</div>}

      {cartOpen && (
        <CartSheet
          rows={rows} suggestions={suggestions} orderType={orderType} setOrderType={setOrderType} note={note} setNote={setNote}
          error={cartError} placing={placing} onInc={incKey} onDec={decKey} onSuggest={suggest} onPlace={placeOrder} onClose={() => setCartOpen(false)}
        />
      )}
      {configuring && <DishSheet dish={configuring} onAdd={addLine} onClose={() => setConfiguring(null)} />}
    </div>,
  );
}

function Header({ name, table, logo, langs, lang, onLang }: { name: string; table: string; logo: string | null; langs: string[]; lang: string; onLang: (l: string) => void }) {
  return (
    <header className="top">
      {logo && <img className="logo" src={logo} alt="" width={40} height={40} referrerPolicy="no-referrer" />}
      <h1>{name || "Welcome"}</h1>
      {langs.length > 0 && (
        <select className="langsel" aria-label="Language" value={lang} onChange={(e) => onLang(e.target.value)}>
          {["en", ...langs].map((l) => <option key={l} value={l}>{LANGS[l] ?? l}</option>)}
        </select>
      )}
      {table && <span className="table-chip" aria-label={`Table ${table}`}><small>Table</small><b>{table}</b></span>}
    </header>
  );
}

function Screen({ title, body, action, inline }: { title: string; body?: string; action?: { label: string; run: () => void }; inline?: boolean }) {
  return (
    <main className={`screen ${inline ? "inline" : ""}`}>
      <h1 className="screen-title">{title}</h1>
      {body && <p className="screen-body">{body}</p>}
      {action && <button className="btn-primary" onClick={action.run}>{action.label}</button>}
    </main>
  );
}
