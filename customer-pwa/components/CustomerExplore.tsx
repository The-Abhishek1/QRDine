"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import type { Session } from "@supabase/supabase-js";
import type { CartLine } from "@/lib/cart";
import { lineKey, unitPrice } from "@/lib/cart";
import { customerSupabase, hasCustomerRole } from "@/lib/customerSupabase";
import { I18nContext, makeI18n } from "@/lib/i18n";
import type { Dish, Menu } from "@/lib/types";
import MenuView from "@/components/MenuView";
import DishSheet from "@/components/DishSheet";

type OnlineRestaurant = {
  id: string;
  name: string;
  address: string | null;
  phone: string | null;
  logo_url: string | null;
  online_delivery_enabled: boolean;
  online_pickup_enabled: boolean;
  online_delivery_fee: number;
  online_cod_enabled: boolean;
  online_cod_fee: number;
  online_upi_enabled: boolean;
};

function rupees(paise: number) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(paise / 100);
}

export default function CustomerExplore() {
  const [session, setSession] = useState<Session | null>(null);
  const [restaurants, setRestaurants] = useState<OnlineRestaurant[]>([]);
  const [selectedRestaurant, setSelectedRestaurant] = useState<OnlineRestaurant | null>(null);
  const [menu, setMenu] = useState<Menu | null>(null);
  const [menuLoading, setMenuLoading] = useState(false);
  const [menuRetry, setMenuRetry] = useState(0);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [configuring, setConfiguring] = useState<Dish | null>(null);
  const [search, setSearch] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [signUp, setSignUp] = useState(false);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    let unsubscribe = () => {};
    try {
      const supabase = customerSupabase();
      supabase.auth.getSession().then(({ data, error: sessionError }) => {
        if (!active) return;
        if (sessionError) setError("Could not check your sign-in. Please refresh and try again.");
        const current = data.session;
        if (current && hasCustomerRole(current.access_token)) {
          setSession(current);
        } else if (current) {
          void supabase.auth.signOut();
          setError("This is not a customer account. Use the restaurant app sign-in instead.");
        }
        setLoading(false);
      });
      const { data } = supabase.auth.onAuthStateChange((_event, current) => {
        if (!active || !current) return;
        if (hasCustomerRole(current.access_token)) {
          setSession(current);
          setError(null);
        } else {
          setSession(null);
        }
      });
      unsubscribe = () => data.subscription.unsubscribe();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Customer sign-in is not configured.");
      setLoading(false);
    }
    return () => { active = false; unsubscribe(); };
  }, []);

  useEffect(() => {
    if (!session) return;
    let active = true;
    customerSupabase().functions.invoke("online-restaurants").then(({ data, error: invokeError }) => {
      if (!active) return;
      if (invokeError) {
        setError("Could not load restaurants. Check your connection and try again.");
        return;
      }
      const result = data as { restaurants?: OnlineRestaurant[] } | null;
      setRestaurants(Array.isArray(result?.restaurants) ? result.restaurants : []);
      setError(null);
    }).catch(() => {
      if (active) setError("Could not load restaurants. Check your connection and try again.");
    });
    return () => { active = false; };
  }, [session]);

  useEffect(() => {
    if (!session || !selectedRestaurant) return;
    let active = true;
    setMenuLoading(true);
    setMenu(null);
    setCart([]);
    customerSupabase().functions.invoke("online-menu", {
      body: { restaurant_id: selectedRestaurant.id },
    }).then(({ data, error: invokeError }) => {
      if (!active) return;
      if (invokeError) {
        setError("Could not load this restaurant's menu. Please try again.");
        return;
      }
      const result = data as { restaurant?: OnlineRestaurant; menu?: Menu } | null;
      if (!result?.menu || !result.restaurant) {
        setError("This restaurant is not currently accepting online orders.");
        return;
      }
      setSelectedRestaurant(result.restaurant);
      setMenu(result.menu);
      setError(null);
    }).catch(() => {
      if (active) setError("Could not load this restaurant's menu. Please try again.");
    }).finally(() => {
      if (active) setMenuLoading(false);
    });
    return () => { active = false; };
  }, [session, selectedRestaurant?.id, menuRetry]);

  const visible = useMemo(() => {
    const term = search.trim().toLocaleLowerCase();
    return restaurants.filter((restaurant) =>
      !term || restaurant.name.toLocaleLowerCase().includes(term) || restaurant.address?.toLocaleLowerCase().includes(term),
    );
  }, [restaurants, search]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setMessage(null);
    try {
      const supabase = customerSupabase();
      if (signUp) {
        const { data, error: authError } = await supabase.auth.signUp({
          email: email.trim(),
          password,
          options: { data: { account_type: "customer", full_name: name.trim() } },
        });
        if (authError) throw authError;
        if (data.session && hasCustomerRole(data.session.access_token)) setSession(data.session);
        else setMessage("Check your email to confirm your customer account, then sign in.");
      } else {
        const { data, error: authError } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
        if (authError) throw authError;
        if (!hasCustomerRole(data.session.access_token)) {
          await supabase.auth.signOut();
          throw new Error("This is not a customer account. Use the restaurant sign-in instead.");
        }
        setSession(data.session);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "We couldn't complete sign-in. Check your details and try again.");
    } finally {
      setLoading(false);
    }
  }

  async function signOut() {
    setLoading(true);
    try {
      const { error: authError } = await customerSupabase().auth.signOut();
      if (authError) throw authError;
      setSession(null);
      setRestaurants([]);
      setSelectedRestaurant(null);
      setMenu(null);
      setCart([]);
    } catch {
      setError("Could not sign out. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  const addLine = (line: CartLine) => setCart((current) => {
    const dish = menu?.dishes.find((item) => item.id === line.dishId);
    const quantity = current.filter((item) => item.dishId === line.dishId).reduce((sum, item) => sum + item.qty, 0);
    const stockLeft = dish?.stock_left ?? Number.MAX_SAFE_INTEGER;
    const allowed = Math.min(line.qty, Math.max(0, Math.min(50 - quantity, stockLeft - quantity)));
    if (allowed < 1) return current;
    const existing = current.findIndex((item) => item.key === line.key);
    return existing < 0
      ? [...current, { ...line, qty: allowed }]
      : current.map((item, index) => index === existing ? { ...item, qty: item.qty + allowed } : item);
  });
  const quickAdd = (dishId: string) => {
    const dish = menu?.dishes.find((item) => item.id === dishId);
    if (!dish) return;
    addLine({ key: lineKey(dishId, null, [], ""), dishId, qty: 1, variantId: null, addonIds: [], instructions: "" });
  };
  const quickRemove = (dishId: string) => setCart((current) => current.flatMap((line) =>
    line.key !== lineKey(dishId, null, [], "") ? [line] : line.qty <= 1 ? [] : [{ ...line, qty: line.qty - 1 }],
  ));
  const removeLine = (key: string) => setCart((current) => current.flatMap((line) =>
    line.key !== key ? [line] : line.qty <= 1 ? [] : [{ ...line, qty: line.qty - 1 }],
  ));
  const addExistingLine = (line: CartLine) => addLine({ ...line, qty: 1 });
  const itemCount = cart.reduce((sum, line) => sum + line.qty, 0);
  const subtotal = cart.reduce((sum, line) => {
    const dish = menu?.dishes.find((item) => item.id === line.dishId);
    return sum + (dish ? unitPrice(dish, line.variantId, line.addonIds) * line.qty : 0);
  }, 0);
  const i18n = useMemo(() => makeI18n("en"), []);

  return (
    <I18nContext.Provider value={i18n}>
    <main className="marketplace-page">
      <header className="marketplace-header">
        <a className="welcome-brand" href="/" aria-label="QR Dine home">
          <span className="welcome-brand-mark">Q</span><span>QR<span>Dine</span></span>
        </a>
        {session && <button className="link" onClick={() => void signOut()} disabled={loading}>Sign out</button>}
      </header>
      <section className="marketplace-intro">
        <span className="welcome-eyebrow">ORDER FROM LOCAL RESTAURANTS</span>
        <h1>Find your next favourite.</h1>
        <p>Sign in to browse restaurants that accept customer orders. Delivery and pickup options and fees are shown up front.</p>
      </section>

      {loading && !session ? <p role="status">Loading…</p> : !session ? (
        <form className="marketplace-auth" onSubmit={submit}>
          <h2>{signUp ? "Create a customer account" : "Customer sign in"}</h2>
          {signUp && <label>Your name<input autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} /></label>}
          <label>Email<input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
          <label>Password<input type="password" autoComplete={signUp ? "new-password" : "current-password"} value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} /></label>
          {message && <p className="marketplace-message" role="status">{message}</p>}
          {error && <p className="error" role="alert">{error}</p>}
          <button className="btn-primary" disabled={loading}>{loading ? "Please wait…" : signUp ? "Create account" : "Sign in"}</button>
          <button className="link" type="button" onClick={() => { setSignUp((value) => !value); setError(null); setMessage(null); }}>
            {signUp ? "Already have an account? Sign in" : "New to QR Dine? Create a customer account"}
          </button>
        </form>
      ) : (
        <section className="marketplace-list">
          {selectedRestaurant ? (
            <div className="online-menu-page">
              <button className="link" onClick={() => { setSelectedRestaurant(null); setMenu(null); setCart([]); setError(null); }}>← All restaurants</button>
              <div className="online-menu-heading">
                {selectedRestaurant.logo_url && <img src={selectedRestaurant.logo_url} alt="" width={64} height={64} />}
                <div><h2>{selectedRestaurant.name}</h2><p>{selectedRestaurant.address}</p></div>
              </div>
              <p className="marketplace-fees">
                {selectedRestaurant.online_delivery_enabled && `Delivery ${rupees(selectedRestaurant.online_delivery_fee)}`}
                {selectedRestaurant.online_delivery_enabled && selectedRestaurant.online_pickup_enabled && " · "}
                {selectedRestaurant.online_pickup_enabled && "Pickup available"}
              </p>
              {menuLoading ? <p role="status">Loading menu…</p> : error ? (
                <div><p className="error" role="alert">{error}</p><button className="btn-outline" onClick={() => setMenuRetry((retry) => retry + 1)}>Try again</button></div>
              ) : menu ? (
                <>
                  {menu.announcement && <p className="announce">{menu.announcement}</p>}
                  <MenuView menu={menu} lines={cart} onQuickAdd={quickAdd} onQuickRemove={quickRemove} onConfigure={setConfiguring} />
                  {itemCount > 0 && (
                    <section className="online-cart" aria-label="Your selection">
                      <div><strong>{itemCount} {itemCount === 1 ? "item" : "items"}</strong><span> · {rupees(subtotal)}</span></div>
                      <ul>
                        {cart.map((line) => {
                          const dish = menu.dishes.find((item) => item.id === line.dishId);
                          if (!dish) return null;
                          const totalForDish = cart.filter((item) => item.dishId === line.dishId).reduce((sum, item) => sum + item.qty, 0);
                          const stockLeft = dish.stock_left ?? 50;
                          const variantName = dish.variants.find((variant) => variant.id === line.variantId)?.name;
                          const addonNames = dish.addon_groups.flatMap((group) =>
                            group.addons.filter((addon) => line.addonIds.includes(addon.id)).map((addon) => addon.name),
                          );
                          const details = [variantName, ...addonNames, line.instructions && `Note: ${line.instructions}`].filter(Boolean).join(" · ");
                          return (
                            <li key={line.key}>
                              <span>
                                {line.qty} × {dish.name} — {rupees(unitPrice(dish, line.variantId, line.addonIds))}
                                {details && <small className="online-cart-details">{details}</small>}
                              </span>
                              <div className="online-cart-stepper" role="group" aria-label={`${dish.name} quantity`}>
                                <button onClick={() => removeLine(line.key)} aria-label={`Remove one ${dish.name}`}>−</button>
                                <button disabled={totalForDish >= stockLeft || line.qty >= 50} onClick={() => addExistingLine(line)} aria-label={`Add one ${dish.name}`}>+</button>
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                      <small>Checkout and order placement will be available in the next stage.</small>
                    </section>
                  )}
                  {configuring && (
                    <DishSheet
                      dish={configuring}
                      maxQty={Math.max(0, (configuring.stock_left ?? 50) - cart.filter((line) => line.dishId === configuring.id).reduce((sum, line) => sum + line.qty, 0))}
                      onAdd={addLine}
                      onClose={() => setConfiguring(null)}
                    />
                  )}
                </>
              ) : null}
            </div>
          ) : <>
          <div className="marketplace-search">
            <label htmlFor="restaurant-search">Search restaurants or area</label>
            <input id="restaurant-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          {error && <p className="error" role="alert">{error}</p>}
          {!error && visible.length === 0 && (
            <p className="empty">{restaurants.length === 0 ? "No restaurants are accepting online orders yet." : "No restaurants match your search."}</p>
          )}
          <div className="marketplace-cards">
            {visible.map((restaurant) => (
              <article className="marketplace-card" key={restaurant.id}>
                {restaurant.logo_url && <img src={restaurant.logo_url} alt="" width={64} height={64} />}
                <div className="marketplace-card-main">
                  <h2>{restaurant.name}</h2>
                  {restaurant.address && <p>{restaurant.address}</p>}
                  <div className="marketplace-tags">
                    {restaurant.online_delivery_enabled && <span>Delivery</span>}
                    {restaurant.online_pickup_enabled && <span>Pickup</span>}
                    {restaurant.online_upi_enabled && <span>UPI</span>}
                    {restaurant.online_cod_enabled && <span>Cash on delivery</span>}
                  </div>
                  <p className="marketplace-fees">
                    {restaurant.online_delivery_enabled && `Delivery ${rupees(restaurant.online_delivery_fee)}`}
                    {restaurant.online_delivery_enabled && restaurant.online_cod_enabled && " · "}
                    {restaurant.online_cod_enabled && `COD fee ${rupees(restaurant.online_cod_fee)}`}
                  </p>
                  <button className="btn-outline" onClick={() => { setSelectedRestaurant(restaurant); setError(null); }}>
                    View menu
                  </button>
                </div>
              </article>
            ))}
          </div>
          </>}
        </section>
      )}
      <a className="marketplace-back" href="/">Back to QR table ordering</a>
    </main>
    </I18nContext.Provider>
  );
}
