"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { customerSupabase } from "@/lib/customerSupabase";
import { rupees } from "@/lib/money";

type DishStat = { name: string; qty: number; revenue: number };
type DailyPoint = { day: string; revenue: number; bills: number };
type SalesStats = {
  revenue: number; bills: number; discounts: number; tax: number; items: number; cash: number; upi: number;
  daily: DailyPoint[]; dishes: DishStat[]; hours: { hour: number; orders: number }[];
};
type Feedback = { id: string; rating: number; feedback: string | null; created_at: string };
type OnlineStats = {
  orders: number; sales: number; items: number; dishes: DishStat[]; daily: DailyPoint[];
  ratings_count: number; average_rating: number; feedback: Feedback[];
};
type Review = {
  id: string; rating_food: number | null; rating_service: number | null;
  comment: string | null; reply: string | null; created_at: string;
};
type CashCount = {
  day: string; opening_float: number; cash_sales: number; expected_cash: number;
  counted_cash: number; difference: number; note: string | null;
};
type Invoice = { id: string; fy: string; number: number; issued_at: string; snapshot: Record<string, unknown> };
type InvoiceSnapshot = {
  restaurant?: { name?: string; address?: string | null; phone?: string | null; gstin?: string | null; fssai?: string | null };
  table?: string; paid_at?: string; method?: string; bill?: {
    lines?: BillLine[]; subtotal?: number; discount?: number; coupon?: string | null; service_charge?: number;
    service_charge_pct?: number; cgst?: number; sgst?: number; tax?: number; round_off?: number; total?: number;
  };
};
type BillLine = { name: string; qty: number; price: number; amount: number };
type Channel = "all" | "qr" | "online";

const EMPTY_STATS: SalesStats = { revenue: 0, bills: 0, discounts: 0, tax: 0, items: 0, cash: 0, upi: 0, daily: [], dishes: [], hours: [] };
const EMPTY_ONLINE: OnlineStats = { orders: 0, sales: 0, items: 0, dishes: [], daily: [], ratings_count: 0, average_rating: 0, feedback: [] };

function localDate(date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function toIstIso(date: string, end = false): string {
  return new Date(`${date}T${end ? "23:59:59.999" : "00:00:00"}+05:30`).toISOString();
}

function messageOf(error: unknown): string { return error instanceof Error ? error.message : "The request failed. Please try again."; }

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character] ?? character);
}

export default function RestaurantInsights({ restaurantId }: { restaurantId: string }) {
  const [range, setRange] = useState("7");
  const [channel, setChannel] = useState<Channel>("all");
  const [from, setFrom] = useState(localDate(new Date(Date.now() - 6 * 86400000)));
  const [to, setTo] = useState(localDate());
  const [stats, setStats] = useState<SalesStats | null>(null);
  const [online, setOnline] = useState<OnlineStats | null>(null);
  const [reviews, setReviews] = useState<Review[]>([]);
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [cashCounts, setCashCounts] = useState<CashCount[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [cashOpening, setCashOpening] = useState("");
  const [cashCounted, setCashCounted] = useState("");
  const [cashNote, setCashNote] = useState("");
  const [reply, setReply] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState<string | null>(null);

  const dates = useMemo(() => {
    const today = new Date();
    const end = range === "custom" ? to : localDate(today);
    const start = range === "custom" ? from
      : range === "today" ? end
        : range === "month" ? `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-01`
          : localDate(new Date(today.getTime() - (Number(range) - 1) * 86400000));
    return { start, end };
  }, [range, from, to]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (dates.end < dates.start) throw new Error("End date must be on or after the start date.");
      const pFrom = toIstIso(dates.start);
      const pTo = new Date(new Date(toIstIso(dates.end, true)).getTime() + 1).toISOString();
      const supabase = customerSupabase();
      const [qrResult, onlineResult, reviewsResult, invoicesResult, cashResult] = await Promise.all([
        supabase.rpc("owner_stats", { p_from: pFrom, p_to: pTo }),
        supabase.rpc("restaurant_online_order_insights", { p_from: pFrom, p_to: pTo }),
        supabase.from("reviews").select("id,rating_food,rating_service,comment,reply,created_at")
          .eq("restaurant_id", restaurantId).gte("created_at", pFrom).lt("created_at", pTo).order("created_at", { ascending: false }),
        supabase.from("invoices").select("id,fy,number,issued_at,snapshot").eq("restaurant_id", restaurantId)
          .gte("issued_at", pFrom).lt("issued_at", pTo).order("issued_at", { ascending: false }).limit(100),
        supabase.rpc("owner_cash_counts", { p_limit: 30 }),
      ]);
      const failure = qrResult.error || onlineResult.error || reviewsResult.error || invoicesResult.error || cashResult.error;
      if (failure) throw failure;
      setStats(qrResult.data as SalesStats);
      setOnline(onlineResult.data as OnlineStats);
      setReviews((reviewsResult.data ?? []) as Review[]);
      setInvoices((invoicesResult.data ?? []) as Invoice[]);
      setCashCounts((cashResult.data ?? []) as CashCount[]);
    } catch (loadError) {
      setError(messageOf(loadError));
    } finally {
      setLoading(false);
    }
  }, [dates.end, dates.start, restaurantId]);

  useEffect(() => { void load(); }, [load]);

  const qrIncluded = channel !== "online";
  const onlineIncluded = channel !== "qr";
  const revenue = (qrIncluded ? stats?.revenue ?? 0 : 0) + (onlineIncluded ? online?.sales ?? 0 : 0);
  const orders = (qrIncluded ? stats?.bills ?? 0 : 0) + (onlineIncluded ? online?.orders ?? 0 : 0);
  const items = (qrIncluded ? stats?.items ?? 0 : 0) + (onlineIncluded ? online?.items ?? 0 : 0);
  const dishes = [...(qrIncluded ? stats?.dishes ?? [] : []), ...(onlineIncluded ? online?.dishes ?? [] : [])]
    .reduce<Record<string, DishStat>>((result, dish) => {
      result[dish.name] ??= { name: dish.name, qty: 0, revenue: 0 };
      result[dish.name].qty += dish.qty;
      result[dish.name].revenue += dish.revenue;
      return result;
    }, {});
  const topDishes = Object.values(dishes).sort((a, b) => b.qty - a.qty).slice(0, 12);

  async function saveReviewReply(review: Review) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { error: saveError } = await customerSupabase().from("reviews").update({ reply: reply[review.id]?.trim() || null }).eq("id", review.id);
      if (saveError) throw saveError;
      setNotice("Review reply saved.");
      await load();
    } catch (saveError) {
      setError(messageOf(saveError));
    } finally {
      setBusy(false);
    }
  }

  async function closeCashDay(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const opening = Math.round(Number(cashOpening || "0") * 100);
    const counted = Math.round(Number(cashCounted) * 100);
    if (!Number.isFinite(opening) || opening < 0 || !Number.isFinite(counted) || counted < 0) {
      setError("Enter valid non-negative cash amounts.");
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { error: closeError } = await customerSupabase().rpc("owner_close_day", {
        p_day: localDate(), p_opening: opening, p_counted: counted, p_note: cashNote.trim() || null,
      });
      if (closeError) throw closeError;
      setCashOpening("");
      setCashCounted("");
      setCashNote("");
      setNotice("Today's cash count saved.");
      await load();
    } catch (closeError) {
      setError(messageOf(closeError));
    } finally {
      setBusy(false);
    }
  }

  function exportSalesCsv() {
    const lines = [
      ["Channel", "Date range", "Revenue (paise)", "Transactions", "Items sold"],
      ["All channels", `${dates.start} to ${dates.end}`, String(revenue), String(orders), String(items)],
      ...topDishes.map((dish) => ["Dish", dish.name, String(dish.revenue), String(dish.qty), ""]),
    ];
    const csv = lines.map((row) => row.map((cell) => `"${cell.replace(/"/g, '""')}"`).join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `qrdine-sales-${dates.start}-${dates.end}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  function printInvoice(invoice: Invoice) {
    const popup = window.open("", "_blank");
    if (!popup) {
      setError("Your browser blocked the invoice window. Allow pop-ups and try again.");
      return;
    }
    const snapshot = invoice.snapshot as InvoiceSnapshot;
    const restaurant = snapshot.restaurant ?? {};
    const bill = snapshot.bill ?? {};
    const rows = (bill.lines ?? []).map((line) => `<tr><td>${escapeHtml(line.name)}</td><td>${line.qty}</td><td>${escapeHtml(rupees(line.price))}</td><td>${escapeHtml(rupees(line.amount))}</td></tr>`).join("");
    const amount = (value: number | undefined) => escapeHtml(rupees(value ?? 0));
    const details = [
      restaurant.address, restaurant.phone ? `Phone: ${restaurant.phone}` : null,
      restaurant.gstin ? `GSTIN: ${restaurant.gstin}` : null, restaurant.fssai ? `FSSAI: ${restaurant.fssai}` : null,
    ].filter((value): value is string => Boolean(value)).map((value) => `<div>${escapeHtml(value)}</div>`).join("");
    popup.onload = () => { popup.focus(); popup.print(); };
    popup.document.write(`<!doctype html><html><head><title>Invoice ${escapeHtml(invoice.fy)}/${invoice.number}</title><style>
      body{font:14px Arial,sans-serif;color:#111;max-width:760px;margin:32px auto;padding:0 20px}
      header{text-align:center;border-bottom:1px solid #aaa;padding-bottom:14px}h1{font-size:23px;margin:0 0 7px}
      .meta{display:flex;justify-content:space-between;margin:18px 0}table{width:100%;border-collapse:collapse}
      th,td{text-align:left;padding:8px 5px;border-bottom:1px solid #ddd}th:last-child,td:last-child{text-align:right}
      th:nth-child(2),td:nth-child(2),th:nth-child(3),td:nth-child(3){text-align:right}
      .summary{width:min(100%,360px);margin:18px 0 0 auto}.summary div{display:flex;justify-content:space-between;padding:5px}
      .summary .total{border-top:1px solid #777;font-size:17px;font-weight:bold;margin-top:5px;padding-top:10px}
      @media print{body{margin:0 auto;padding:0}}
      </style></head><body><header><h1>${escapeHtml(restaurant.name ?? "Restaurant")}</h1>${details}<h2>${restaurant.gstin ? "TAX INVOICE" : "BILL"}</h2></header>
      <div class="meta"><span>Invoice ${escapeHtml(invoice.fy)}/${invoice.number}</span><span>Table ${escapeHtml(snapshot.table ?? "—")}</span></div>
      <div class="meta"><span>${escapeHtml(snapshot.paid_at ? new Date(snapshot.paid_at).toLocaleString() : new Date(invoice.issued_at).toLocaleString())}</span><span>Paid by ${escapeHtml((snapshot.method ?? "").toUpperCase())}</span></div>
      <table><thead><tr><th>Item</th><th>Qty</th><th>Rate</th><th>Amount</th></tr></thead><tbody>${rows}</tbody></table>
      <div class="summary"><div><span>Subtotal</span><strong>${amount(bill.subtotal)}</strong></div>
      <div><span>Discount${bill.coupon ? ` (${escapeHtml(bill.coupon)})` : ""}</span><strong>−${amount(bill.discount)}</strong></div>
      <div><span>Service charge${bill.service_charge_pct ? ` (${bill.service_charge_pct}%)` : ""}</span><strong>${amount(bill.service_charge)}</strong></div>
      <div><span>CGST</span><strong>${amount(bill.cgst)}</strong></div><div><span>SGST</span><strong>${amount(bill.sgst)}</strong></div>
      <div><span>Round off</span><strong>${amount(bill.round_off)}</strong></div><div class="total"><span>Total</span><strong>${amount(bill.total)}</strong></div></div></body></html>`);
    popup.document.close();
  }

  return <div className="portal-insights">
    {(error || notice) && <p className={`portal-message ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error || notice}</p>}
    <section className="portal-panel">
      <div className="portal-insights-filters">
        <div className="portal-filter-chips">
          {[["today", "Today"], ["7", "7 days"], ["30", "30 days"], ["month", "This month"], ["custom", "Custom"]].map(([value, label]) =>
            <button type="button" className={`portal-filter-chip ${range === value ? "selected" : ""}`} key={value} onClick={() => setRange(value)}>{label}</button>)}
        </div>
        <div className="portal-filter-chips">
          {([["all", "All channels"], ["qr", "QR / table"], ["online", "Online"]] as [Channel, string][]).map(([value, label]) =>
            <button type="button" className={`portal-filter-chip ${channel === value ? "selected" : ""}`} key={value} onClick={() => setChannel(value)}>{label}</button>)}
        </div>
        {range === "custom" && <div className="portal-date-filter">
          <label className="portal-field"><span>From</span><input type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} /></label>
          <label className="portal-field"><span>To</span><input type="date" value={to} min={from} max={localDate()} onChange={(event) => setTo(event.target.value)} /></label>
        </div>}
        <button className="portal-secondary" onClick={() => void load()} disabled={loading || busy}>↻ Refresh</button>
      </div>
      {loading && <div className="portal-loading-inline"><span className="portal-spinner" /> Loading insights…</div>}
    </section>
    {!loading && stats && online && <>
      <div className="portal-metrics">
        <article><span>Sales</span><strong>{rupees(revenue)}</strong><small>{dates.start} — {dates.end}</small></article>
        <article><span>Transactions</span><strong>{orders}</strong><small>{channel === "qr" ? "Paid table bills" : channel === "online" ? "Online orders" : "Bills and online orders"}</small></article>
        <article><span>Average value</span><strong>{orders ? rupees(Math.round(revenue / orders)) : "—"}</strong><small>Per transaction</small></article>
        <article><span>Dishes sold</span><strong>{items}</strong><small>Across selected channels</small></article>
      </div>
      <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Sales trends</h2><p>Gross sales for each day, in rupees.</p></div><button className="portal-secondary" onClick={exportSalesCsv}>Export CSV</button></div>
        {(qrIncluded ? stats.daily : []).concat(onlineIncluded ? online.daily : []).length ? <div className="portal-chart">
          {Object.values([...(qrIncluded ? stats.daily : []), ...(onlineIncluded ? online.daily : [])]
            .reduce<Record<string, DailyPoint>>((result, point) => {
              result[point.day] ??= { day: point.day, revenue: 0, bills: 0 };
              result[point.day].revenue += point.revenue;
              result[point.day].bills += point.bills;
              return result;
            }, {})).sort((a, b) => a.day.localeCompare(b.day)).map((point) => {
              const max = Math.max(1, ...stats.daily.map((item) => item.revenue), ...online.daily.map((item) => item.revenue));
              return <div className="portal-chart-column" key={point.day} title={`${point.day}: ${rupees(point.revenue)} · ${point.bills} transactions`}>
                <strong>{rupees(point.revenue)}</strong><span style={{ height: `${Math.max(4, point.revenue / max * 100)}%` }} /><small>{point.day.slice(5)}</small>
              </div>;
            })}
        </div> : <div className="portal-empty"><span>No sales for this range.</span></div>}
        {channel !== "online" && <p className="portal-form-hint">Table discounts: {rupees(stats.discounts)} · GST collected: {rupees(stats.tax)} · Cash: {rupees(stats.cash)} · UPI: {rupees(stats.upi)}</p>}
      </section>
      <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Top dishes</h2><p>Ranked by quantity sold.</p></div></div>
        {topDishes.length ? <div className="portal-settings-list">{topDishes.map((dish) => <article className="portal-setting-item" key={dish.name}>
          <div><strong>{dish.name}</strong><span>{dish.qty} sold</span></div><strong>{rupees(dish.revenue)}</strong>
        </article>)}</div> : <p className="portal-form-hint">No dishes sold in this period.</p>}
      </section>
      {onlineIncluded && <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Online ratings and feedback</h2><p>Customer reviews from online orders.</p></div></div>
        <div className="portal-metrics portal-review-metrics"><article><span>Average rating</span><strong>{online.ratings_count ? `${online.average_rating.toFixed(1)} ★` : "—"}</strong><small>{online.ratings_count} customer ratings</small></article></div>
        {online.feedback.length ? online.feedback.map((item) => <article className="portal-feedback-card" key={item.id}>
          <strong>{item.rating} ★</strong><small>{new Date(item.created_at).toLocaleString()}</small><p>{item.feedback || "Rating only"}</p>
        </article>) : <p className="portal-form-hint">No written online feedback in this period.</p>}
      </section>}
      {qrIncluded && <section className="portal-panel">
        <div className="portal-section-title"><div><h2>QR/table reviews</h2><p>Reply to customer feedback submitted from table orders.</p></div></div>
        {reviews.length ? reviews.map((review) => <article className="portal-feedback-card" key={review.id}>
          <strong>Food {review.rating_food ?? "—"} ★ · Service {review.rating_service ?? "—"} ★</strong>
          <small>{new Date(review.created_at).toLocaleString()}</small><p>{review.comment || "No written feedback."}</p>
          <label className="portal-field"><span>Your reply</span><textarea rows={2} maxLength={500} value={reply[review.id] ?? review.reply ?? ""} onChange={(event) => setReply((current) => ({ ...current, [review.id]: event.target.value }))} /></label>
          <button className="portal-secondary" onClick={() => void saveReviewReply(review)} disabled={busy}>Save reply</button>
        </article>) : <p className="portal-form-hint">No QR/table feedback in this period.</p>}
      </section>}
      {qrIncluded && <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Daily cash count</h2><p>Compare the cash in your drawer with recorded cash sales.</p></div></div>
        <form className="portal-coupon-form" onSubmit={(event) => void closeCashDay(event)}>
          <label className="portal-field"><span>Opening float (₹)</span><input type="number" min="0" step="0.01" value={cashOpening} onChange={(event) => setCashOpening(event.target.value)} /></label>
          <label className="portal-field"><span>Counted cash (₹)</span><input type="number" min="0" step="0.01" value={cashCounted} onChange={(event) => setCashCounted(event.target.value)} required /></label>
          <label className="portal-field"><span>Note (optional)</span><input value={cashNote} onChange={(event) => setCashNote(event.target.value)} /></label>
          <button className="portal-action-primary" disabled={busy}>Save today's count</button>
        </form>
        {cashCounts.slice(0, 7).map((count) => <article className="portal-setting-item" key={count.day}>
          <div><strong>{count.day}</strong><span>Expected {rupees(count.expected_cash)} · Counted {rupees(count.counted_cash)}</span></div>
          <strong>{count.difference >= 0 ? "+" : ""}{rupees(count.difference)}</strong>
        </article>)}
      </section>}
      {qrIncluded && <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Paid bills</h2><p>Invoices issued during this period.</p></div></div>
        {invoices.length ? <div className="portal-settings-list">{invoices.slice(0, 20).map((invoice) => <article className="portal-setting-item" key={invoice.id}>
          <div><strong>{invoice.fy}/{invoice.number}</strong><span>{new Date(invoice.issued_at).toLocaleString()} · {String((invoice.snapshot as { table?: string }).table ?? "Table")}</span></div>
          <button type="button" className="portal-secondary" onClick={() => printInvoice(invoice)}>Print invoice</button>
        </article>)}</div> : <p className="portal-form-hint">No invoices issued for this period.</p>}
      </section>}
    </>}
  </div>;
}
