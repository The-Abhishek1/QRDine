"use client";

import type { Session } from "@supabase/supabase-js";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { customerSupabase, getAppRole } from "@/lib/customerSupabase";
import { rupees } from "@/lib/money";

type Restaurant = {
  id: string;
  name: string;
  phone: string | null;
  address: string | null;
  upi_id: string | null;
  online_orders_enabled: boolean;
  online_delivery_enabled: boolean;
  online_pickup_enabled: boolean;
  online_upi_enabled: boolean;
  online_cod_enabled: boolean;
  online_delivery_fee: number;
  online_cod_fee: number;
  online_application_status: "not_applied" | "pending" | "approved" | "rejected";
  online_application_review: string | null;
  online_orders_admin_hold: boolean;
  online_orders_admin_hold_reason: string | null;
  online_orders_paid_through: string | null;
};

type OnlineOrderItem = {
  name: string;
  qty: number;
  unit_price: number;
  variant_name?: string | null;
  addons?: { name: string; price: number }[];
  instructions?: string | null;
};

type OnlineOrder = {
  id: string;
  status: string;
  fulfilment: string;
  payment_method: string;
  payment_status: string;
  customer_name: string;
  customer_phone: string;
  customer_note: string | null;
  delivery_address: string | null;
  postal_code: string | null;
  payment_reference: string | null;
  items_snapshot: OnlineOrderItem[];
  subtotal: number;
  delivery_fee: number;
  cod_fee: number;
  total: number;
  reject_reason: string | null;
  eta_minutes: number | null;
  created_at: string;
};

type MenuDish = {
  id: string;
  category_id: string;
  name: string;
  base_price: number;
  is_available: boolean;
  online_available: boolean;
  stock_qty: number | null;
};

type MenuCategory = { id: string; name: string };
type HotelStatus = {
  state: string;
  hotel_name?: string;
  note?: string | null;
  paid_through?: string | null;
  days_left?: number | null;
};

type PortalTab = "orders" | "menu" | "settings";

function readClaim(token: string, key: string): string | null {
  try {
    const encoded = token.split(".")[1]?.replace(/-/g, "+").replace(/_/g, "/");
    if (!encoded) return null;
    const value = JSON.parse(atob(encoded))[key];
    return typeof value === "string" ? value : null;
  } catch {
    return null;
  }
}

function errorText(error: unknown): string {
  const message = error instanceof Error ? error.message.toLowerCase() : "";
  if (message.includes("application_details_required")) return "Add a restaurant phone, address, and at least one fulfilment option before applying.";
  if (message.includes("application_pending")) return "Your online-order application is already waiting for review.";
  if (message.includes("online_application_required")) return "Your restaurant must be approved for online ordering before you can turn it on.";
  if (message.includes("upi_id_required")) return "Add and save your UPI ID before enabling UPI payments.";
  if (message.includes("payment_not_verified")) return "Verify the customer's UPI payment before accepting this order.";
  if (message.includes("cod_not_available")) return "Cash on delivery is not currently enabled by the platform.";
  if (message.includes("already_owner")) return "This login already has a restaurant. Refresh the page to continue.";
  if (message.includes("not_owner")) return "This account does not have permission to manage this restaurant.";
  return error instanceof Error ? error.message : "Something went wrong. Please try again.";
}

function dateTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function Field({
  label,
  value,
  onChange,
  type = "text",
  required = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  required?: boolean;
}) {
  return (
    <label className="portal-field">
      <span>{label}{required ? " *" : ""}</span>
      <input type={type} value={value} onChange={(event) => onChange(event.target.value)} required={required} />
    </label>
  );
}

export default function RestaurantPortal() {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [restaurantName, setRestaurantName] = useState("");
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");
  const [tab, setTab] = useState<PortalTab>("orders");
  const [restaurant, setRestaurant] = useState<Restaurant | null>(null);
  const [hotelStatus, setHotelStatus] = useState<HotelStatus | null>(null);
  const [orders, setOrders] = useState<OnlineOrder[]>([]);
  const [dishes, setDishes] = useState<MenuDish[]>([]);
  const [categories, setCategories] = useState<MenuCategory[]>([]);
  const [serviceActive, setServiceActive] = useState(false);
  const [serviceMonthlyFee, setServiceMonthlyFee] = useState(0);
  const [codAvailable, setCodAvailable] = useState(false);
  const [applicationNote, setApplicationNote] = useState("");
  const [profileName, setProfileName] = useState("");
  const [profilePhone, setProfilePhone] = useState("");
  const [profileAddress, setProfileAddress] = useState("");
  const [profileUpi, setProfileUpi] = useState("");
  const [deliveryEnabled, setDeliveryEnabled] = useState(false);
  const [pickupEnabled, setPickupEnabled] = useState(false);
  const [upiEnabled, setUpiEnabled] = useState(false);
  const [codEnabled, setCodEnabled] = useState(false);
  const [deliveryFee, setDeliveryFee] = useState("0");
  const [codFee, setCodFee] = useState("0");
  const [etaMinutes, setEtaMinutes] = useState<Record<string, string>>({});
  const rejectedAccountSignout = useRef(false);

  const statusLabel = useMemo(() => {
    if (restaurant?.online_orders_admin_hold) return "Paused by platform";
    if (!serviceActive && restaurant?.online_orders_enabled) return "Service unavailable";
    if (restaurant?.online_orders_enabled) return "Accepting online orders";
    if (restaurant?.online_application_status === "pending") return "Application under review";
    if (restaurant?.online_application_status === "approved") return "Online orders paused";
    return "Online ordering not set up";
  }, [restaurant, serviceActive]);

  const loadRestaurantDashboard = useCallback(async (current: Session) => {
    const role = getAppRole(current.access_token);
    if (role === "owner_blocked") {
      const { data, error: statusError } = await customerSupabase().rpc("my_hotel_status");
      if (statusError) throw statusError;
      setHotelStatus(data as HotelStatus | null);
      setRestaurant(null);
      return;
    }

    if (role !== "owner") {
      setRestaurant(null);
      setHotelStatus(null);
      return;
    }

    const supabase = customerSupabase();
    const restaurantId = readClaim(current.access_token, "restaurant_id");
    if (!restaurantId) throw new Error("Your restaurant could not be identified. Sign out and sign in again.");

    const [restaurantResult, orderResult, dishResult, categoryResult, serviceFeeResult, codResult] = await Promise.all([
      supabase.from("restaurants").select(
        "id,name,phone,address,upi_id,online_orders_enabled,online_delivery_enabled,online_pickup_enabled,online_upi_enabled,online_cod_enabled,online_delivery_fee,online_cod_fee,online_application_status,online_application_review,online_orders_admin_hold,online_orders_admin_hold_reason,online_orders_paid_through",
      ).eq("id", restaurantId).single(),
      supabase.rpc("restaurant_online_orders", { p_limit: 100 }),
      supabase.from("dishes").select("id,category_id,name,base_price,is_available,online_available,stock_qty")
        .eq("restaurant_id", restaurantId).is("deleted_at", null).order("name"),
      supabase.from("categories").select("id,name").eq("restaurant_id", restaurantId).is("deleted_at", null).order("sort"),
      supabase.rpc("online_order_service_monthly_fee"),
      supabase.rpc("online_cod_available"),
    ]);

    const failure = restaurantResult.error || orderResult.error || dishResult.error ||
      categoryResult.error || serviceFeeResult.error || codResult.error;
    if (failure) throw failure;

    const row = restaurantResult.data as Restaurant;
    setRestaurant(row);
    setHotelStatus(null);
    setOrders((orderResult.data ?? []) as OnlineOrder[]);
    setDishes((dishResult.data ?? []) as MenuDish[]);
    setCategories((categoryResult.data ?? []) as MenuCategory[]);
    const serviceFee = Number(serviceFeeResult.data ?? 0);
    setServiceActive(!row.online_orders_admin_hold && (
      serviceFee === 0 || Boolean(row.online_orders_paid_through && new Date(row.online_orders_paid_through).getTime() >= Date.now())
    ));
    setServiceMonthlyFee(serviceFee);
    setCodAvailable(Boolean(codResult.data));
    setProfileName(row.name);
    setProfilePhone(row.phone ?? "");
    setProfileAddress(row.address ?? "");
    setProfileUpi(row.upi_id ?? "");
    setDeliveryEnabled(row.online_delivery_enabled);
    setPickupEnabled(row.online_pickup_enabled);
    setUpiEnabled(row.online_upi_enabled);
    setCodEnabled(row.online_cod_enabled);
    setDeliveryFee((row.online_delivery_fee / 100).toString());
    setCodFee((row.online_cod_fee / 100).toString());
  }, []);

  const acceptSession = useCallback(async (current: Session | null) => {
    setSession(current);
    setError(null);
    setNotice(null);
    if (!current) {
      setRestaurant(null);
      setHotelStatus(null);
      setOrders([]);
      setDishes([]);
      setLoading(false);
      return;
    }
    if (getAppRole(current.access_token) === "customer" || current.user.user_metadata?.account_type === "customer") {
      rejectedAccountSignout.current = true;
      await customerSupabase().auth.signOut();
      setSession(null);
      setError("This is a customer account. Use Customer sign in on the online ordering page.");
      setLoading(false);
      return;
    }
    const role = getAppRole(current.access_token);
    if (role && !["owner", "owner_blocked"].includes(role)) {
      rejectedAccountSignout.current = true;
      await customerSupabase().auth.signOut();
      setSession(null);
      setError("This account does not have restaurant-owner access to the web dashboard.");
      setLoading(false);
      return;
    }
    try {
      await loadRestaurantDashboard(current);
    } catch (loadError) {
      setError(errorText(loadError));
    } finally {
      setLoading(false);
    }
  }, [loadRestaurantDashboard]);

  useEffect(() => {
    let active = true;
    let unsubscribe = () => {};
    try {
      const supabase = customerSupabase();
      const { data } = supabase.auth.onAuthStateChange((event, current) => {
        if (event === "SIGNED_OUT" && rejectedAccountSignout.current) {
          rejectedAccountSignout.current = false;
          return;
        }
        window.setTimeout(() => {
          if (active) void acceptSession(current);
        }, 0);
      });
      unsubscribe = () => data.subscription.unsubscribe();
      supabase.auth.getSession().then(({ data: result, error: sessionError }) => {
        if (!active) return;
        if (sessionError) setError("Could not check your sign-in. Please refresh and try again.");
        void acceptSession(result.session);
      }).catch(() => {
        if (!active) return;
        setError("Could not check your sign-in. Please refresh and try again.");
        setLoading(false);
      });
    } catch (configError) {
      setError(configError instanceof Error ? configError.message : "Restaurant sign-in is not configured.");
      setLoading(false);
    }
    return () => { active = false; unsubscribe(); };
  }, [acceptSession]);

  useEffect(() => {
    if (!session || getAppRole(session.access_token) !== "owner") return;
    const timer = window.setInterval(() => {
      void loadRestaurantDashboard(session).catch((loadError) => setError(errorText(loadError)));
    }, 30_000);
    return () => window.clearInterval(timer);
  }, [session, loadRestaurantDashboard]);

  async function submitAuth(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const supabase = customerSupabase();
      if (mode === "signup") {
        const { data, error: signupError } = await supabase.auth.signUp({
          email: email.trim(),
          password,
          options: { data: { account_type: "restaurant" } },
        });
        if (signupError) throw signupError;
        if (!data.session) {
          setNotice("Account created. Check your email to confirm it, then sign in here to finish restaurant setup.");
          setMode("signin");
        } else {
          await createRestaurant(data.session);
        }
      } else {
        const { data, error: signinError } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
        if (signinError) throw signinError;
        await acceptSession(data.session);
      }
    } catch (authError) {
      setError(errorText(authError));
    } finally {
      setBusy(false);
    }
  }

  async function createRestaurant(current = session) {
    if (!current) return;
    if (!restaurantName.trim() || !phone.trim() || !address.trim()) {
      setError("Enter your restaurant name, phone number, and address to submit your restaurant application.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const { error: createError } = await customerSupabase().rpc("create_restaurant", {
        p_name: restaurantName.trim(),
        p_phone: phone.trim(),
        p_address: address.trim(),
      });
      if (createError) throw createError;
      const { data, error: refreshError } = await customerSupabase().auth.refreshSession();
      if (refreshError) throw refreshError;
      setRestaurantName("");
      setPhone("");
      setAddress("");
      await acceptSession(data.session ?? current);
      setNotice("Restaurant application submitted. You can follow its approval status here.");
    } catch (setupError) {
      setError(errorText(setupError));
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true);
    try {
      const { error: signoutError } = await customerSupabase().auth.signOut();
      if (signoutError) throw signoutError;
      await acceptSession(null);
    } catch (signoutError) {
      setError(errorText(signoutError));
    } finally {
      setBusy(false);
    }
  }

  async function refreshDashboard() {
    if (!session) return;
    setBusy(true);
    setError(null);
    try {
      await loadRestaurantDashboard(session);
    } catch (refreshError) {
      setError(errorText(refreshError));
    } finally {
      setBusy(false);
    }
  }

  async function actOnOrder(order: OnlineOrder, action: "accept" | "reject" | "verify_payment" | "complete") {
    setBusy(true);
    setError(null);
    try {
      const supabase = customerSupabase();
      const args: Record<string, string | number> = { p_order_id: order.id, p_action: action };
      if (action === "accept") args.p_eta_minutes = Number(etaMinutes[order.id] || "30");
      if (action === "reject") {
        const reason = window.prompt("Why are you rejecting this order?", "Unable to fulfil this order");
        if (!reason?.trim()) return;
        args.p_reason = reason.trim();
      }
      const { error: actionError } = await supabase.rpc("restaurant_online_order_action", args);
      if (actionError) throw actionError;
      await refreshDashboard();
    } catch (actionError) {
      setError(errorText(actionError));
    } finally {
      setBusy(false);
    }
  }

  async function toggleDish(dish: MenuDish) {
    setBusy(true);
    setError(null);
    try {
      const { error: toggleError } = await customerSupabase().rpc("set_online_order_dish_availability", {
        p_dish_id: dish.id,
        p_available: !dish.online_available,
      });
      if (toggleError) throw toggleError;
      setDishes((current) => current.map((item) => item.id === dish.id ? { ...item, online_available: !dish.online_available } : item));
    } catch (toggleError) {
      setError(errorText(toggleError));
    } finally {
      setBusy(false);
    }
  }

  async function saveProfile() {
    if (!restaurant) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { error: saveError } = await customerSupabase().from("restaurants").update({
        name: profileName.trim(),
        phone: profilePhone.trim(),
        address: profileAddress.trim(),
        upi_id: profileUpi.trim() || null,
      }).eq("id", restaurant.id);
      if (saveError) throw saveError;
      setNotice("Restaurant details saved.");
      await refreshDashboard();
    } catch (saveError) {
      setError(errorText(saveError));
    } finally {
      setBusy(false);
    }
  }

  async function applyForOnlineOrders(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!restaurant) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const deliveryFeePaise = Math.round(Number(deliveryFee) * 100);
      const codFeePaise = Math.round(Number(codFee) * 100);
      if (!Number.isFinite(deliveryFeePaise) || deliveryFeePaise < 0 || !Number.isFinite(codFeePaise) || codFeePaise < 0) {
        throw new Error("Enter valid non-negative delivery and cash-on-delivery fees.");
      }
      const supabase = customerSupabase();
      const { error: settingsError } = await supabase.from("restaurants").update({
        online_delivery_enabled: deliveryEnabled,
        online_pickup_enabled: pickupEnabled,
        online_delivery_fee: deliveryFeePaise,
        online_cod_fee: codFeePaise,
      }).eq("id", restaurant.id);
      if (settingsError) throw settingsError;
      const { error: applicationError } = await supabase.rpc("submit_online_application", {
        p_note: applicationNote.trim() || null,
      });
      if (applicationError) throw applicationError;
      setNotice("Online-order application submitted for platform review.");
      await refreshDashboard();
    } catch (applicationError) {
      setError(errorText(applicationError));
    } finally {
      setBusy(false);
    }
  }

  async function saveOnlineSettings(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!restaurant) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const deliveryFeePaise = Math.round(Number(deliveryFee) * 100);
      const codFeePaise = Math.round(Number(codFee) * 100);
      const { error: saveError } = await customerSupabase().rpc("save_online_order_settings", {
        p_enabled: restaurant.online_orders_enabled,
        p_delivery: deliveryEnabled,
        p_pickup: pickupEnabled,
        p_upi: upiEnabled,
        p_cod: codEnabled,
        p_delivery_fee: deliveryFeePaise,
        p_cod_fee: codFeePaise,
      });
      if (saveError) throw saveError;
      setNotice("Online-order settings saved.");
      await refreshDashboard();
    } catch (saveError) {
      setError(errorText(saveError));
    } finally {
      setBusy(false);
    }
  }

  async function toggleOnlineService() {
    if (!restaurant) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { error: updateError } = await customerSupabase().rpc("save_online_order_settings", {
        p_enabled: !restaurant.online_orders_enabled,
        p_delivery: deliveryEnabled,
        p_pickup: pickupEnabled,
        p_upi: upiEnabled,
        p_cod: codEnabled,
        p_delivery_fee: Math.round(Number(deliveryFee) * 100),
        p_cod_fee: Math.round(Number(codFee) * 100),
      });
      if (updateError) throw updateError;
      setNotice(restaurant.online_orders_enabled ? "Online ordering paused." : "Your restaurant is accepting online orders.");
      await refreshDashboard();
    } catch (toggleError) {
      setError(errorText(toggleError));
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <main className="portal-loading"><span className="portal-spinner" /> Loading restaurant workspace…</main>;
  }

  if (!session) {
    return (
      <main className="portal-auth-page">
        <header className="portal-public-nav">
          <a className="welcome-brand" href="/"><span className="welcome-brand-mark">Q</span><span>QR<span>Dine</span></span></a>
          <a className="portal-customer-link" href="/explore">Customer ordering <span aria-hidden="true">→</span></a>
        </header>
        <section className="portal-auth-layout">
          <div className="portal-auth-copy">
            <span className="welcome-eyebrow">QR DINE FOR RESTAURANTS</span>
            <h1>Your restaurant,<br /><em>ready for more.</em></h1>
            <p>Manage online orders, keep your menu availability current, and serve customers from a full-sized desktop workspace.</p>
            <ul>
              <li>One place to review incoming delivery and pickup orders</li>
              <li>Verify UPI references before accepting paid orders</li>
              <li>Pause online ordering or hide unavailable dishes</li>
            </ul>
          </div>
          <form className="portal-auth-card" onSubmit={submitAuth}>
            <div className="portal-auth-tabs">
              <button type="button" className={mode === "signin" ? "selected" : ""} onClick={() => { setMode("signin"); setError(null); setNotice(null); }}>Sign in</button>
              <button type="button" className={mode === "signup" ? "selected" : ""} onClick={() => { setMode("signup"); setError(null); setNotice(null); }}>Create account</button>
            </div>
            <h2>{mode === "signin" ? "Restaurant sign in" : "Create a restaurant account"}</h2>
            <p>{mode === "signin" ? "Sign in with your restaurant owner account." : "Register your owner login, then submit your restaurant for review."}</p>
            {mode === "signup" && (
              <>
                <Field label="Restaurant name" value={restaurantName} onChange={setRestaurantName} required />
                <Field label="Restaurant phone" value={phone} onChange={setPhone} type="tel" required />
                <Field label="Restaurant address" value={address} onChange={setAddress} required />
              </>
            )}
            <Field label="Owner email" value={email} onChange={setEmail} type="email" required />
            <Field label="Password" value={password} onChange={setPassword} type="password" required />
            {error && <p className="portal-message error" role="alert">{error}</p>}
            {notice && <p className="portal-message success" role="status">{notice}</p>}
            <button className="welcome-cta portal-submit" type="submit" disabled={busy}>
              {busy ? "Please wait…" : mode === "signin" ? "Sign in to dashboard" : "Create account"}
            </button>
          </form>
        </section>
      </main>
    );
  }

  if (hotelStatus || !restaurant) {
    const canCreate = getAppRole(session.access_token) !== "owner_blocked";
    return (
      <main className="portal-setup-page">
        <header className="portal-public-nav">
          <a className="welcome-brand" href="/"><span className="welcome-brand-mark">Q</span><span>QR<span>Dine</span></span></a>
          <button className="portal-signout" onClick={() => void signOut()} disabled={busy}>Sign out</button>
        </header>
        <section className="portal-setup-card">
          <span className="welcome-eyebrow">{hotelStatus ? "RESTAURANT APPLICATION" : "GET STARTED"}</span>
          <h1>{hotelStatus?.hotel_name || "Set up your restaurant"}</h1>
          {hotelStatus ? (
            <>
              <div className={`portal-status-banner state-${hotelStatus.state}`}>
                <strong>{hotelStatus.state.replaceAll("_", " ")}</strong>
                <span>{hotelStatus.note || "Your restaurant account is awaiting platform review. You can return here to check the status."}</span>
              </div>
              {hotelStatus.paid_through && <p>Plan active through {new Date(hotelStatus.paid_through).toLocaleDateString()}.</p>}
              <button className="portal-secondary" onClick={() => void refreshDashboard()} disabled={busy}>Refresh application status</button>
            </>
          ) : canCreate ? (
            <form className="portal-form-grid" onSubmit={(event) => { event.preventDefault(); void createRestaurant(); }}>
              <p>Your owner account is ready. Add your restaurant details to submit it for platform approval.</p>
              <Field label="Restaurant name" value={restaurantName} onChange={setRestaurantName} required />
              <Field label="Restaurant phone" value={phone} onChange={setPhone} type="tel" required />
              <Field label="Restaurant address" value={address} onChange={setAddress} required />
              {error && <p className="portal-message error" role="alert">{error}</p>}
              <button className="welcome-cta portal-submit" type="submit" disabled={busy}>{busy ? "Submitting…" : "Submit restaurant application"}</button>
            </form>
          ) : (
            <p>Restaurant status is unavailable. Try signing out and signing in again.</p>
          )}
          {error && hotelStatus && <p className="portal-message error" role="alert">{error}</p>}
          <p className="portal-account-caption">Signed in as {session.user.email}</p>
        </section>
      </main>
    );
  }

  const pendingOrders = orders.filter((order) => order.status === "pending");
  const completedOrders = orders.filter((order) => order.status === "completed");
  const categoryName = new Map(categories.map((category) => [category.id, category.name]));

  return (
    <main className="restaurant-portal">
      <header className="portal-topbar">
        <a className="welcome-brand" href="/"><span className="welcome-brand-mark">Q</span><span>QR<span>Dine</span></span></a>
        <div className="portal-topbar-restaurant">
          <strong>{restaurant.name}</strong>
          <span>{session.user.email}</span>
        </div>
        <button className="portal-signout" onClick={() => void signOut()} disabled={busy}>Sign out</button>
      </header>
      <div className="portal-workspace">
        <aside className="portal-sidebar">
          <p>RESTAURANT WORKSPACE</p>
          {([
            ["orders", "Orders", pendingOrders.length ? `${pendingOrders.length} new` : ""],
            ["menu", "Menu availability", ""],
            ["settings", "Online ordering", ""],
          ] as [PortalTab, string, string][]).map(([key, label, badge]) => (
            <button key={key} className={tab === key ? "active" : ""} onClick={() => setTab(key)}>
              <span>{label}</span>{badge && <small>{badge}</small>}
            </button>
          ))}
          <div className="portal-sidebar-bottom">
            <span className={`portal-health-dot ${restaurant.online_orders_enabled && serviceActive ? "online" : ""}`} />
            <span>{statusLabel}</span>
          </div>
        </aside>
        <section className="portal-main">
          <div className="portal-page-heading">
            <div>
              <span className="welcome-eyebrow">{restaurant.name.toUpperCase()}</span>
              <h1>{tab === "orders" ? "Online orders" : tab === "menu" ? "Menu availability" : "Online ordering setup"}</h1>
              <p>{tab === "orders" ? "Review and respond to customer delivery and pickup orders." : tab === "menu" ? "Control which dishes customers can order online." : "Manage your listing, fulfilment options, and application status."}</p>
            </div>
            <button className="portal-secondary" onClick={() => void refreshDashboard()} disabled={busy}>↻ Refresh</button>
          </div>
          {error && <p className="portal-message error" role="alert">{error}</p>}
          {notice && <p className="portal-message success" role="status">{notice}</p>}
          {restaurant.online_orders_admin_hold && (
            <div className="portal-status-banner state-hold">
              <strong>Online orders are on hold</strong>
              <span>{restaurant.online_orders_admin_hold_reason || "Contact platform support for details."}</span>
            </div>
          )}
          {!serviceActive && restaurant.online_orders_enabled && !restaurant.online_orders_admin_hold && (
            <div className="portal-status-banner state-hold">
              <strong>Online-order service is inactive</strong>
              <span>{serviceMonthlyFee > 0 ? `Online-order service fee: ${rupees(serviceMonthlyFee)} per month. Contact the platform administrator to record payment.` : "Contact the platform administrator to review your online-order service access."}</span>
            </div>
          )}
          {tab === "orders" && (
            <>
              <div className="portal-metrics">
                <article><span>Needs attention</span><strong>{pendingOrders.length}</strong><small>Pending customer orders</small></article>
                <article><span>Recent orders</span><strong>{orders.length}</strong><small>Latest orders on this account</small></article>
                <article><span>Completed</span><strong>{completedOrders.length}</strong><small>In the recent order list</small></article>
                <article><span>Listing status</span><strong className="portal-metric-status">{restaurant.online_orders_enabled && serviceActive ? "Live" : "Paused"}</strong><small>{restaurant.online_application_status.replaceAll("_", " ")}</small></article>
              </div>
              <div className="portal-section-title"><div><h2>Latest orders</h2><p>Customer details are only shown to your restaurant team.</p></div></div>
              {orders.length === 0 ? <div className="portal-empty"><strong>No online orders yet</strong><span>New orders will appear here when your online listing is active.</span></div> : (
                <div className="portal-order-list">
                  {orders.map((order) => (
                    <article className="portal-order-card" key={order.id}>
                      <div className="portal-order-heading">
                        <div><span className="portal-order-number">Order #{order.id.slice(0, 8).toUpperCase()}</span><small>{dateTime(order.created_at)}</small></div>
                        <span className={`portal-order-status status-${order.status}`}>{order.status.replaceAll("_", " ")}</span>
                      </div>
                      <div className="portal-order-columns">
                        <div>
                          <h3>{order.customer_name}</h3>
                          <a href={`tel:${order.customer_phone}`}>{order.customer_phone}</a>
                          <p>{order.fulfilment === "delivery" ? "Delivery" : "Pickup"} · {order.payment_method.toUpperCase()} · {order.payment_status.replaceAll("_", " ")}</p>
                          {order.delivery_address && <p>{order.delivery_address}{order.postal_code ? `, ${order.postal_code}` : ""}</p>}
                          {order.customer_note && <p className="portal-order-note">Note: {order.customer_note}</p>}
                        </div>
                        <ul>
                          {order.items_snapshot?.map((item, index) => (
                            <li key={`${item.name}-${index}`}>
                              <span>{item.qty} × {item.name}{item.variant_name ? ` · ${item.variant_name}` : ""}</span>
                              <strong>{rupees(item.qty * item.unit_price)}</strong>
                              {item.addons?.length ? <small>Add-ons: {item.addons.map((addon) => addon.name).join(", ")}</small> : null}
                              {item.instructions && <small>Note: {item.instructions}</small>}
                            </li>
                          ))}
                        </ul>
                      </div>
                      <div className="portal-order-total"><span>Total</span><strong>{rupees(order.total)}</strong></div>
                      {order.payment_reference && <p className="portal-payment-reference">UPI reference: <strong>{order.payment_reference}</strong></p>}
                      {order.status === "pending" && (
                        <div className="portal-order-actions">
                          {order.payment_method === "upi" && order.payment_status === "verification_required" && <button className="portal-secondary" disabled={busy} onClick={() => void actOnOrder(order, "verify_payment")}>Verify UPI payment</button>}
                          {order.payment_method === "upi" && order.payment_status !== "paid" && <span className="portal-action-hint">Verify UPI payment before accepting.</span>}
                          <label className="portal-eta">Preparation time <input type="number" min="1" max="240" value={etaMinutes[order.id] ?? "30"} onChange={(event) => setEtaMinutes((current) => ({ ...current, [order.id]: event.target.value }))} /> min</label>
                          <button className="portal-action-primary" disabled={busy || (order.payment_method === "upi" && order.payment_status !== "paid")} onClick={() => void actOnOrder(order, "accept")}>Accept order</button>
                          <button className="portal-action-danger" disabled={busy} onClick={() => void actOnOrder(order, "reject")}>Reject</button>
                        </div>
                      )}
                      {order.status === "accepted" && <div className="portal-order-actions"><span>{order.eta_minutes ? `Preparation estimate: ${order.eta_minutes} minutes` : "Accepted"}</span><button className="portal-action-primary" disabled={busy} onClick={() => void actOnOrder(order, "complete")}>Mark completed</button></div>}
                    </article>
                  ))}
                </div>
              )}
            </>
          )}
          {tab === "menu" && (
            <div className="portal-menu-panel">
              <div className="portal-section-title"><div><h2>Online availability</h2><p>This only affects the online menu; it does not change your dine-in menu.</p></div></div>
              {categories.map((category) => {
                const categoryDishes = dishes.filter((dish) => dish.category_id === category.id);
                if (!categoryDishes.length) return null;
                return <section className="portal-menu-category" key={category.id}>
                  <h3>{category.name}</h3>
                  {categoryDishes.map((dish) => (
                    <article className="portal-dish-row" key={dish.id}>
                      <div><strong>{dish.name}</strong><span>{rupees(dish.base_price)}{dish.stock_qty !== null ? ` · ${dish.stock_qty} in stock` : ""}{!dish.is_available ? " · Sold out" : ""}</span></div>
                      <label className="portal-switch">
                        <input type="checkbox" checked={dish.online_available} disabled={busy || !dish.is_available} onChange={() => void toggleDish(dish)} />
                        <span>{dish.online_available && dish.is_available ? "Available online" : "Hidden online"}</span>
                      </label>
                    </article>
                  ))}
                </section>;
              })}
              {dishes.length === 0 && <div className="portal-empty"><strong>No menu dishes found</strong><span>Add dishes in your restaurant menu before enabling online ordering.</span></div>}
            </div>
          )}
          {tab === "settings" && (
            <div className="portal-settings-layout">
              <section className="portal-panel">
                <div className="portal-section-title"><div><h2>Restaurant details</h2><p>Customers use these details for orders and directions.</p></div></div>
                <form className="portal-form-grid" onSubmit={(event) => { event.preventDefault(); void saveProfile(); }}>
                  <Field label="Restaurant name" value={profileName} onChange={setProfileName} required />
                  <Field label="Restaurant phone" value={profilePhone} onChange={setProfilePhone} type="tel" required />
                  <Field label="Restaurant address" value={profileAddress} onChange={setProfileAddress} required />
                  <Field label="UPI ID" value={profileUpi} onChange={setProfileUpi} />
                  <button className="portal-action-primary" disabled={busy}>Save restaurant details</button>
                </form>
              </section>
              <section className="portal-panel">
                <div className="portal-section-title">
                  <div><h2>Online-order application</h2><p>Approval is required before customers can see your online listing.</p></div>
                  <span className={`portal-order-status status-${restaurant.online_application_status}`}>{restaurant.online_application_status.replaceAll("_", " ")}</span>
                </div>
                {restaurant.online_application_review && <p className="portal-review-note">{restaurant.online_application_review}</p>}
                {restaurant.online_application_status !== "approved" && (
                  <form className="portal-form-grid" onSubmit={(event) => void applyForOnlineOrders(event)}>
                    <fieldset className="portal-options">
                      <legend>Fulfilment options</legend>
                      <label><input type="checkbox" checked={deliveryEnabled} onChange={(event) => setDeliveryEnabled(event.target.checked)} /> Delivery</label>
                      <label><input type="checkbox" checked={pickupEnabled} onChange={(event) => setPickupEnabled(event.target.checked)} /> Pickup</label>
                    </fieldset>
                    <Field label="Delivery fee (₹)" value={deliveryFee} onChange={setDeliveryFee} type="number" />
                    <Field label="Application note (optional)" value={applicationNote} onChange={setApplicationNote} />
                    <p className="portal-form-hint">Save your restaurant phone and address first. At least one fulfilment option is required.</p>
                    <button className="portal-action-primary" disabled={busy || restaurant.online_application_status === "pending"}>Submit for online-order approval</button>
                  </form>
                )}
                {restaurant.online_application_status === "approved" && (
                  <form className="portal-form-grid" onSubmit={(event) => void saveOnlineSettings(event)}>
                    <fieldset className="portal-options">
                      <legend>Fulfilment options</legend>
                      <label><input type="checkbox" checked={deliveryEnabled} onChange={(event) => setDeliveryEnabled(event.target.checked)} /> Delivery</label>
                      <label><input type="checkbox" checked={pickupEnabled} onChange={(event) => setPickupEnabled(event.target.checked)} /> Pickup</label>
                    </fieldset>
                    <fieldset className="portal-options">
                      <legend>Payment methods</legend>
                      <label><input type="checkbox" checked={upiEnabled} onChange={(event) => setUpiEnabled(event.target.checked)} disabled={!profileUpi.trim()} /> UPI{!profileUpi.trim() ? " (add UPI ID above)" : ""}</label>
                      <label><input type="checkbox" checked={codEnabled} onChange={(event) => setCodEnabled(event.target.checked)} disabled={!codAvailable} /> Cash on delivery{!codAvailable ? " (not enabled by platform)" : ""}</label>
                    </fieldset>
                    <Field label="Delivery fee (₹)" value={deliveryFee} onChange={setDeliveryFee} type="number" />
                    <Field label="Cash-on-delivery fee (₹)" value={codFee} onChange={setCodFee} type="number" />
                    <button className="portal-action-primary" disabled={busy}>Save online-order settings</button>
                  </form>
                )}
                {restaurant.online_application_status === "approved" && (
                  <div className="portal-service-control">
                    <div><strong>{statusLabel}</strong><span>Pause or resume the online listing without changing your menu.</span></div>
                    <button className={restaurant.online_orders_enabled ? "portal-action-danger" : "portal-action-primary"} onClick={() => void toggleOnlineService()} disabled={busy || !serviceActive || restaurant.online_orders_admin_hold}>
                      {restaurant.online_orders_enabled ? "Pause online orders" : "Start accepting orders"}
                    </button>
                  </div>
                )}
              </section>
            </div>
          )}
          <footer className="portal-footer"><span>QR Dine Restaurant Workspace</span><a href="/explore">Customer ordering</a><a href="/privacy">Privacy</a><a href="/terms">Terms</a></footer>
        </section>
      </div>
    </main>
  );
}
