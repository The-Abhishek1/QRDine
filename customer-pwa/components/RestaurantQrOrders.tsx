"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { customerSupabase } from "@/lib/customerSupabase";
import { rupees } from "@/lib/money";
import { playOwnerAlertSound } from "@/lib/ownerAlerts";

type Order = {
  id: string; session_id: string; order_type: string; status: string; eta_minutes: number | null;
  note: string | null; created_at: string; ready_at: string | null; source: string;
};
type OrderItem = {
  id: string; order_id: string; name_snapshot: string; variant_snapshot: string | null;
  addons: { name: string; price: number }[]; qty: number; unit_price: number;
  instructions: string | null; cancelled: boolean; cancel_reason: string | null; offer_free?: boolean;
};
type DiningTable = { id: string; label: string };
type Session = { id: string; table_id: string; status: string; opened_at: string };
type Request = { id: string; session_id: string; type: string; created_at: string };
type BillLine = { name: string; qty: number; price: number; amount: number };
type Bill = {
  session_id: string; table: string; status: string; lines: BillLine[]; subtotal: number; discount: number;
  coupon: string | null; service_charge: number; service_charge_pct: number; tax: number; cgst: number;
  sgst: number; gst_rate: number; round_off: number; total: number;
};
type OrderData = { orders: Order[]; items: OrderItem[]; sessions: Session[]; tables: DiningTable[]; requests: Request[] };
type StaffDish = { id: string; name: string; base_price: number; is_available: boolean };
type StaffVariant = { id: string; dish_id: string; name: string; price: number };
type StaffAddonGroup = { id: string; name: string; min_select: number; max_select: number };
type StaffAddon = { id: string; group_id: string; name: string; price: number; is_available: boolean };
type StaffDishGroup = { dish_id: string; group_id: string };
type StaffMenu = { dishes: StaffDish[]; variants: StaffVariant[]; groups: StaffAddonGroup[]; addons: StaffAddon[]; links: StaffDishGroup[] };
type StaffLine = { key: string; dishId: string; qty: number; variantId: string; addonIds: string[] };

const EMPTY: OrderData = { orders: [], items: [], sessions: [], tables: [], requests: [] };
const EMPTY_MENU: StaffMenu = { dishes: [], variants: [], groups: [], addons: [], links: [] };
function messageOf(error: unknown): string { return error instanceof Error ? error.message : "The request failed. Please try again."; }

export default function RestaurantQrOrders({ restaurantId }: { restaurantId: string }) {
  const [data, setData] = useState<OrderData>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [eta, setEta] = useState<Record<string, string>>({});
  const [bill, setBill] = useState<Bill | null>(null);
  const [coupon, setCoupon] = useState("");
  const [manualDiscount, setManualDiscount] = useState("");
  const [managerPin, setManagerPin] = useState("");
  const [paymentMethod, setPaymentMethod] = useState<"cash" | "upi" | "split">("cash");
  const [splitCash, setSplitCash] = useState("");
  const [splitUpi, setSplitUpi] = useState("");
  const [staffSession, setStaffSession] = useState<Session | null>(null);
  const [staffMenu, setStaffMenu] = useState<StaffMenu>(EMPTY_MENU);
  const [staffMenuLoading, setStaffMenuLoading] = useState(false);
  const [staffLines, setStaffLines] = useState<StaffLine[]>([]);
  const [staffNote, setStaffNote] = useState("");
  const seenPendingOrderIds = useRef<Set<string> | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const supabase = customerSupabase();
      const [orders, requests, sessions, tables] = await Promise.all([
        supabase.from("orders").select("id,session_id,order_type,status,eta_minutes,note,created_at,ready_at,source")
          .eq("restaurant_id", restaurantId).in("status", ["pending", "accepted"]).order("created_at"),
        supabase.from("service_requests").select("id,session_id,type,created_at").eq("restaurant_id", restaurantId).eq("status", "open").order("created_at"),
        supabase.from("sessions").select("id,table_id,status,opened_at").eq("restaurant_id", restaurantId).in("status", ["active", "bill_requested"]),
        supabase.from("dining_tables").select("id,label").eq("restaurant_id", restaurantId),
      ]);
      const failure = orders.error || requests.error || sessions.error || tables.error;
      if (failure) throw failure;
      const orderRows = (orders.data ?? []) as Order[];
      const sessionRows = (sessions.data ?? []) as Session[];
      const pendingOrderIds = new Set(orderRows.filter((order) => order.status === "pending").map((order) => order.id));
      if (seenPendingOrderIds.current && [...pendingOrderIds].some((id) => !seenPendingOrderIds.current?.has(id)) &&
        window.localStorage.getItem("qrdine:restaurant-alert-sound") === "on") playOwnerAlertSound();
      seenPendingOrderIds.current = pendingOrderIds;
      const sessionIds = [...new Set([...orderRows.map((order) => order.session_id), ...sessionRows.map((session) => session.id), ...(requests.data ?? []).map((request) => request.session_id)])];
      const orderIds = orderRows.map((order) => order.id);
      const [items, allSessions] = await Promise.all([
        orderIds.length ? supabase.from("order_items").select("id,order_id,name_snapshot,variant_snapshot,addons,qty,unit_price,instructions,cancelled,cancel_reason,offer_free").in("order_id", orderIds) : Promise.resolve({ data: [], error: null }),
        sessionIds.length ? supabase.from("sessions").select("id,table_id,status,opened_at").in("id", sessionIds) : Promise.resolve({ data: [], error: null }),
      ]);
      if (items.error || allSessions.error) throw items.error || allSessions.error;
      setData({
        orders: orderRows,
        items: (items.data ?? []) as OrderItem[],
        sessions: allSessions.data as Session[],
        tables: (tables.data ?? []) as DiningTable[],
        requests: (requests.data ?? []) as Request[],
      });
    } catch (loadError) {
      setError(messageOf(loadError));
    } finally {
      setLoading(false);
    }
  }, [restaurantId]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 15_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function act(action: () => Promise<void>, success?: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      if (success) setNotice(success);
      await refresh();
    } catch (actionError) {
      setError(messageOf(actionError));
    } finally {
      setBusy(false);
    }
  }

  function tableForSession(sessionId: string): string {
    const session = data.sessions.find((item) => item.id === sessionId);
    return data.tables.find((table) => table.id === session?.table_id)?.label ?? "Table";
  }

  async function orderAction(order: Order, action: "accept" | "reject" | "ready" | "complete") {
    let reason: string | undefined;
    if (action === "reject") {
      reason = window.prompt("Why are you rejecting this order?", "Unable to fulfil this order")?.trim();
      if (!reason) return;
    }
    await act(async () => {
      const supabase = customerSupabase();
      const rpc = action === "accept" ? "accept_order" : action === "reject" ? "reject_order" : action === "ready" ? "mark_order_ready" : "complete_order";
      const args: Record<string, string | number> = action === "accept"
        ? { p_order_id: order.id, p_eta_minutes: Number(eta[order.id] ?? 30) }
        : action === "reject" ? { p_order_id: order.id, p_reason: reason! } : { p_order_id: order.id };
      const { error: actionError } = await supabase.rpc(rpc, args);
      if (actionError) throw actionError;
    }, action === "accept" ? "Order accepted." : action === "reject" ? "Order rejected." : action === "ready" ? "Order marked ready." : "Order marked served.");
  }

  async function resolveRequest(request: Request) {
    await act(async () => {
      const { error: updateError } = await customerSupabase().from("service_requests").update({
        status: "resolved", resolved_at: new Date().toISOString(),
      }).eq("id", request.id);
      if (updateError) throw updateError;
    }, "Guest request resolved.");
  }

  async function cancelItem(item: OrderItem) {
    const reason = window.prompt(`Why are you cancelling ${item.name_snapshot}?`, "Out of stock")?.trim();
    if (!reason) return;
    await act(async () => {
      const { error: cancelError } = await customerSupabase().rpc("cancel_order_item", {
        p_item_id: item.id, p_reason: reason,
      });
      if (cancelError) throw cancelError;
    }, "Order item cancelled.");
  }

  async function openStaffOrder(session: Session) {
    setError(null);
    setStaffSession(session);
    setStaffLines([{ key: crypto.randomUUID(), dishId: "", qty: 1, variantId: "", addonIds: [] }]);
    setStaffNote("");
    setStaffMenuLoading(true);
    try {
      const supabase = customerSupabase();
      const [dishes, variants, groups, addons, links] = await Promise.all([
        supabase.from("dishes").select("id,name,base_price,is_available").eq("restaurant_id", restaurantId).eq("is_available", true).is("deleted_at", null).order("name"),
        supabase.from("dish_variants").select("id,dish_id,name,price").eq("restaurant_id", restaurantId).order("price"),
        supabase.from("addon_groups").select("id,name,min_select,max_select").eq("restaurant_id", restaurantId).order("name"),
        supabase.from("addons").select("id,group_id,name,price,is_available").eq("restaurant_id", restaurantId).eq("is_available", true).order("name"),
        supabase.from("dish_addon_groups").select("dish_id,group_id").eq("restaurant_id", restaurantId),
      ]);
      const failure = dishes.error || variants.error || groups.error || addons.error || links.error;
      if (failure) throw failure;
      setStaffMenu({
        dishes: (dishes.data ?? []) as StaffDish[], variants: (variants.data ?? []) as StaffVariant[],
        groups: (groups.data ?? []) as StaffAddonGroup[], addons: (addons.data ?? []) as StaffAddon[],
        links: (links.data ?? []) as StaffDishGroup[],
      });
    } catch (menuError) {
      setStaffSession(null);
      setError(messageOf(menuError));
    } finally {
      setStaffMenuLoading(false);
    }
  }

  function updateStaffLine(key: string, update: Partial<StaffLine>) {
    setStaffLines((current) => current.map((line) => line.key === key ? { ...line, ...update } : line));
  }

  async function submitStaffOrder(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!staffSession) return;
    for (const line of staffLines) {
      const dish = staffMenu.dishes.find((item) => item.id === line.dishId);
      if (!dish) { setError("Choose a dish for every order line."); return; }
      const variants = staffMenu.variants.filter((variant) => variant.dish_id === dish.id);
      if (variants.length && !variants.some((variant) => variant.id === line.variantId)) {
        setError(`Choose a size or variant for ${dish.name}.`);
        return;
      }
      const linkedGroups = staffMenu.links.filter((link) => link.dish_id === dish.id).map((link) => staffMenu.groups.find((group) => group.id === link.group_id)).filter((group): group is StaffAddonGroup => Boolean(group));
      for (const group of linkedGroups) {
        const groupAddonIds = new Set(staffMenu.addons.filter((addon) => addon.group_id === group.id).map((addon) => addon.id));
        const count = line.addonIds.filter((id) => groupAddonIds.has(id)).length;
        if (count < group.min_select || count > group.max_select) {
          setError(`${dish.name}: select ${group.min_select === group.max_select ? group.min_select : `${group.min_select}–${group.max_select}`} ${group.name.toLowerCase()}.`);
          return;
        }
      }
    }
    await act(async () => {
      const { error: addError } = await customerSupabase().rpc("owner_add_order", {
        p_session_id: staffSession.id,
        p_items: staffLines.map((line) => ({
          dish_id: line.dishId, qty: line.qty, variant_id: line.variantId || null, addon_ids: line.addonIds,
        })),
        p_order_type: "dine_in",
        p_note: staffNote.trim() || null,
      });
      if (addError) throw addError;
      setStaffSession(null);
    }, "Staff order sent to the kitchen.");
  }

  async function openBill(sessionId: string) {
    setBusy(true);
    setError(null);
    try {
      const { data: result, error: billError } = await customerSupabase().rpc("get_bill", {
        p_session_id: sessionId, p_coupon_code: coupon.trim() || null,
        p_manual_discount: Math.round(Number(manualDiscount || "0") * 100),
      });
      if (billError) throw billError;
      setBill(result as Bill);
    } catch (billError) {
      setError(messageOf(billError));
    } finally {
      setBusy(false);
    }
  }

  async function updateBill() {
    if (!bill) return;
    await openBill(bill.session_id);
  }

  async function closeBill(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!bill) return;
    const manualPaise = Math.round(Number(manualDiscount || "0") * 100);
    const cashPaise = Math.round(Number(splitCash || "0") * 100);
    const upiPaise = Math.round(Number(splitUpi || "0") * 100);
    if (![manualPaise, cashPaise, upiPaise].every(Number.isFinite) || manualPaise < 0 ||
      (paymentMethod === "split" && (cashPaise < 0 || upiPaise < 0 || cashPaise + upiPaise !== bill.total))) {
      setError(paymentMethod === "split" ? `Split payment amounts must add up to ${rupees(bill.total)}.` : "Enter valid payment values.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const { data: result, error: closeError } = await customerSupabase().rpc("close_session", {
        p_session_id: bill.session_id,
        p_method: paymentMethod,
        p_split: paymentMethod === "split" ? { cash: cashPaise, upi: upiPaise } : null,
        p_coupon_code: coupon.trim() || null,
        p_manual_discount: manualPaise,
        p_pin: manualPaise > 0 ? managerPin : null,
      });
      if (closeError) throw closeError;
      const closeResult = result as { error?: string; fy?: string; number?: number } | null;
      if (closeResult?.error) throw new Error(closeResult.error.replaceAll("_", " "));
      setNotice(closeResult?.fy && closeResult.number
        ? `Payment recorded. Invoice ${closeResult.fy}/${closeResult.number} created.`
        : "Payment recorded and table closed.");
      setBill(null);
      setCoupon("");
      setManualDiscount("");
      setManagerPin("");
      await refresh();
    } catch (closeError) {
      setError(messageOf(closeError));
    } finally {
      setBusy(false);
    }
  }

  const pending = data.orders.filter((order) => order.status === "pending");
  const accepted = data.orders.filter((order) => order.status === "accepted");
  const activeSessions = data.sessions.filter((session) => session.status !== "paid");

  return <div className="portal-qr-orders">
    {(error || notice) && <p className={`portal-message ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error || notice}</p>}
    <div className="portal-metrics">
      <article><span>Needs attention</span><strong>{pending.length}</strong><small>Waiting for approval</small></article>
      <article><span>In kitchen</span><strong>{accepted.length}</strong><small>Accepted orders</small></article>
      <article><span>Open tables</span><strong>{activeSessions.length}</strong><small>Guest sessions</small></article>
      <article><span>Guest requests</span><strong>{data.requests.length}</strong><small>Waiter and bill requests</small></article>
    </div>
    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Guest requests</h2><p>Resolve waiter calls and bill requests.</p></div><button className="portal-secondary" onClick={() => void refresh()} disabled={loading}>↻ Refresh</button></div>
      {data.requests.length ? <div className="portal-settings-list">{data.requests.map((request) => <article className="portal-setting-item" key={request.id}>
        <div><strong>{tableForSession(request.session_id)} · {request.type === "bill" ? "Bill requested" : "Waiter requested"}</strong><span>{new Date(request.created_at).toLocaleString()}</span></div>
        {request.type === "bill" && <button className="portal-action-primary" onClick={() => void openBill(request.session_id)} disabled={busy}>Open bill</button>}
        <button className="portal-secondary" onClick={() => void resolveRequest(request)} disabled={busy}>Resolve</button>
      </article>)}</div> : <p className="portal-form-hint">No open guest requests.</p>}
    </section>
    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Table QR orders</h2><p>Review new orders and follow preparation progress.</p></div></div>
      {loading ? <div className="portal-loading-inline"><span className="portal-spinner" /> Loading orders…</div> : !data.orders.length
        ? <p className="portal-form-hint">No active QR/table orders.</p>
        : <div className="portal-order-list">{data.orders.map((order) => {
          const items = data.items.filter((item) => item.order_id === order.id);
          const session = data.sessions.find((row) => row.id === order.session_id);
          return <article className="portal-order-card" key={order.id}>
            <div className="portal-order-heading"><div><span className="portal-order-number">{tableForSession(order.session_id)} · {order.order_type.replaceAll("_", " ")}</span><small>{new Date(order.created_at).toLocaleString()} · {order.source === "staff" ? "Staff order" : "Guest order"}</small></div><span className={`portal-order-status status-${order.status}`}>{order.status}</span></div>
            {order.note && <p className="portal-order-note">{order.note}</p>}
            <ul className="portal-qr-order-items">{items.map((item) => <li key={item.id}>
              <span>{item.qty} × {item.name_snapshot}{item.variant_snapshot ? ` · ${item.variant_snapshot}` : ""}{item.offer_free ? " · FREE OFFER ITEM" : ""}</span>
              <strong>{item.cancelled ? "Cancelled" : rupees(item.qty * item.unit_price)}</strong>
              {item.addons?.length ? <small>Extras: {item.addons.map((addon) => addon.name).join(", ")}</small> : null}
              {item.instructions && <small>Note: {item.instructions}</small>}
              {item.cancel_reason && <small>Cancellation: {item.cancel_reason}</small>}
              {!item.cancelled && <button type="button" className="portal-secondary portal-cancel-item" onClick={() => void cancelItem(item)} disabled={busy}>Cancel item</button>}
            </li>)}</ul>
            <div className="portal-order-actions">
              {order.status === "pending" && <>
                <label className="portal-eta">Preparation time <input type="number" min="1" max="240" value={eta[order.id] ?? "30"} onChange={(event) => setEta((current) => ({ ...current, [order.id]: event.target.value }))} /> min</label>
                <button className="portal-action-primary" onClick={() => void orderAction(order, "accept")} disabled={busy}>Accept order</button>
                <button className="portal-action-danger" onClick={() => void orderAction(order, "reject")} disabled={busy}>Reject</button>
              </>}
              {order.status === "accepted" && <>
                <span>{order.ready_at ? "Ready for serving" : order.eta_minutes ? `ETA ${order.eta_minutes} min` : "Preparing"}</span>
                {!order.ready_at && <button className="portal-secondary" onClick={() => void orderAction(order, "ready")} disabled={busy}>Mark ready</button>}
                <button className="portal-action-primary" onClick={() => void orderAction(order, "complete")} disabled={busy}>Mark served</button>
              </>}
              {session && <button className="portal-secondary" onClick={() => void openBill(session.id)} disabled={busy}>View bill</button>}
            </div>
          </article>;
        })}</div>}
    </section>
    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Open table bills</h2><p>Review the bill, apply a coupon or manager discount, and record payment.</p></div></div>
      {activeSessions.length ? <div className="portal-settings-list">{activeSessions.map((session) => <article className="portal-setting-item" key={session.id}>
        <div><strong>{tableForSession(session.id)}</strong><span>{session.status.replaceAll("_", " ")} · Opened {new Date(session.opened_at).toLocaleString()}</span></div>
        {session.status === "active" && <button className="portal-secondary" onClick={() => void openStaffOrder(session)} disabled={busy || staffMenuLoading}>Add staff order</button>}
        <button className="portal-action-primary" onClick={() => void openBill(session.id)} disabled={busy}>Review bill</button>
      </article>)}</div> : <p className="portal-form-hint">No open table bills.</p>}
    </section>
    {staffSession && <div className="portal-modal-backdrop"><form className="portal-dialog" onSubmit={(event) => void submitStaffOrder(event)}>
      <header><div><h2>Add staff order · {tableForSession(staffSession.id)}</h2><p>Send an order from the restaurant team to the kitchen.</p></div><button type="button" className="portal-secondary" onClick={() => setStaffSession(null)}>Close</button></header>
      {staffMenuLoading ? <div className="portal-loading-inline"><span className="portal-spinner" /> Loading menu options…</div> : staffMenu.dishes.length === 0
        ? <p className="portal-form-hint">No available dishes are configured for this menu.</p>
        : <div className="portal-staff-order-lines">{staffLines.map((line) => {
          const selectedDish = staffMenu.dishes.find((dish) => dish.id === line.dishId);
          const variants = staffMenu.variants.filter((variant) => variant.dish_id === line.dishId);
          const linkedGroups = staffMenu.links.filter((link) => link.dish_id === line.dishId)
            .map((link) => staffMenu.groups.find((group) => group.id === link.group_id))
            .filter((group): group is StaffAddonGroup => Boolean(group));
          return <article className="portal-staff-order-line" key={line.key}>
            <div className="portal-staff-order-line-heading"><strong>Order item</strong>{staffLines.length > 1 && <button type="button" className="portal-action-danger" onClick={() => setStaffLines((current) => current.filter((item) => item.key !== line.key))}>Remove</button>}</div>
            <label className="portal-field"><span>Dish</span><select value={line.dishId} onChange={(event) => updateStaffLine(line.key, { dishId: event.target.value, variantId: "", addonIds: [] })} required>
              <option value="">Choose a dish</option>{staffMenu.dishes.map((dish) => <option value={dish.id} key={dish.id}>{dish.name} · {rupees(dish.base_price)}</option>)}
            </select></label>
            <label className="portal-field"><span>Quantity</span><input type="number" min="1" max="50" value={line.qty} onChange={(event) => updateStaffLine(line.key, { qty: Math.max(1, Number(event.target.value) || 1) })} required /></label>
            {variants.length > 0 && <label className="portal-field"><span>Size / variant</span><select value={line.variantId} onChange={(event) => updateStaffLine(line.key, { variantId: event.target.value })} required>
              <option value="">Choose a variant</option>{variants.map((variant) => <option value={variant.id} key={variant.id}>{variant.name} · {rupees(variant.price)}</option>)}
            </select></label>}
            {selectedDish && linkedGroups.map((group) => <fieldset className="portal-options" key={group.id}>
              <legend>{group.name} · {group.min_select ? `Choose ${group.min_select === group.max_select ? group.min_select : `${group.min_select}–${group.max_select}`}` : "Optional"}</legend>
              {staffMenu.addons.filter((addon) => addon.group_id === group.id).map((addon) => {
                const checked = line.addonIds.includes(addon.id);
                const maxReached = line.addonIds.filter((id) => staffMenu.addons.find((item) => item.id === id)?.group_id === group.id).length >= group.max_select;
                return <label key={addon.id}><input type="checkbox" checked={checked} disabled={!checked && maxReached} onChange={(event) => updateStaffLine(line.key, {
                  addonIds: event.target.checked ? [...line.addonIds, addon.id] : line.addonIds.filter((id) => id !== addon.id),
                })} />{addon.name} · {rupees(addon.price)}</label>;
              })}
            </fieldset>)}
          </article>;
        })}</div>}
      <button type="button" className="portal-secondary" onClick={() => setStaffLines((current) => [...current, { key: crypto.randomUUID(), dishId: "", qty: 1, variantId: "", addonIds: [] }])} disabled={staffMenuLoading}>+ Add another item</button>
      <label className="portal-field"><span>Kitchen note (optional)</span><input maxLength={300} value={staffNote} onChange={(event) => setStaffNote(event.target.value)} /></label>
      <footer><button type="button" className="portal-secondary" onClick={() => setStaffSession(null)}>Cancel</button><button className="portal-action-primary" disabled={busy || staffMenuLoading || !staffMenu.dishes.length}>{busy ? "Sending…" : "Send to kitchen"}</button></footer>
    </form></div>}
    {bill && <div className="portal-modal-backdrop"><form className="portal-dialog portal-bill-dialog" onSubmit={(event) => void closeBill(event)}>
      <header><div><h2>Bill · {bill.table}</h2><p>Settle the bill for this table session.</p></div><button type="button" className="portal-secondary" onClick={() => setBill(null)}>Close</button></header>
      <ul className="portal-bill-lines">{bill.lines.map((line, index) => <li key={`${line.name}-${index}`}><span>{line.qty} × {line.name}</span><strong>{rupees(line.amount)}</strong></li>)}</ul>
      <label className="portal-field"><span>Coupon code (optional)</span><div className="portal-inline-input"><input value={coupon} onChange={(event) => setCoupon(event.target.value.toUpperCase())} /><button type="button" className="portal-secondary" onClick={() => void updateBill()} disabled={busy}>Apply</button></div></label>
      <label className="portal-field"><span>Manual discount (₹)</span><div className="portal-inline-input"><input type="number" min="0" step="0.01" value={manualDiscount} onChange={(event) => setManualDiscount(event.target.value)} /><button type="button" className="portal-secondary" onClick={() => void updateBill()} disabled={busy}>Recalculate</button></div></label>
      {Number(manualDiscount) > 0 && <label className="portal-field"><span>Manager PIN</span><input type="password" inputMode="numeric" maxLength={6} value={managerPin} onChange={(event) => setManagerPin(event.target.value.replace(/\D/g, "").slice(0, 6))} /></label>}
      <div className="portal-bill-summary">
        <span>Subtotal</span><strong>{rupees(bill.subtotal)}</strong>
        <span>Discount{bill.coupon ? ` (${bill.coupon})` : ""}</span><strong>−{rupees(bill.discount)}</strong>
        <span>Service charge ({bill.service_charge_pct}%)</span><strong>{rupees(bill.service_charge)}</strong>
        <span>GST ({bill.gst_rate}%)</span><strong>{rupees(bill.tax)}</strong>
        <span>Round off</span><strong>{rupees(bill.round_off)}</strong>
        <span className="total">Total to collect</span><strong className="total">{rupees(bill.total)}</strong>
      </div>
      <fieldset className="portal-options"><legend>Payment method</legend>
        {(["cash", "upi", "split"] as const).map((method) => <label key={method}><input type="radio" name="payment-method" checked={paymentMethod === method} onChange={() => setPaymentMethod(method)} />{method === "upi" ? "UPI" : method === "split" ? "Split cash + UPI" : "Cash"}</label>)}
      </fieldset>
      {paymentMethod === "split" && <div className="portal-coupon-form">
        <label className="portal-field"><span>Cash (₹)</span><input type="number" min="0" step="0.01" value={splitCash} onChange={(event) => setSplitCash(event.target.value)} /></label>
        <label className="portal-field"><span>UPI (₹)</span><input type="number" min="0" step="0.01" value={splitUpi} onChange={(event) => setSplitUpi(event.target.value)} /></label>
      </div>}
      <footer><button type="button" className="portal-secondary" onClick={() => setBill(null)}>Cancel</button><button className="portal-action-primary" disabled={busy || (Number(manualDiscount) > 0 && managerPin.length < 4)}>{busy ? "Saving…" : "Mark paid and close table"}</button></footer>
    </form></div>}
  </div>;
}
