"use client";
import { useEffect, useState } from "react";
import type { ActiveState, OrderView } from "@/lib/types";
import { rupees } from "@/lib/money";
import { useI18n } from "@/lib/i18n";

function useNow(ms = 15000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(id); }, [ms]);
  return now;
}

function statusLine(o: OrderView, now: number, lang: string, t: (k: string, v?: Record<string, string | number>) => string): { text: string; tone: "wait" | "ok" | "bad" | "done" } {
  switch (o.status) {
    case "pending": {
      const waited = (now - new Date(o.created_at).getTime()) / 60000;
      return { text: waited > 3 ? `${t("waitingAccept")}… ${t("callWaiter")}` : t("waitingAccept"), tone: "wait" };
    }
    case "accepted": {
      if (o.ready) return { text: t("ready"), tone: "done" };
      if (!o.accepted_at || !o.eta_minutes) return { text: t("beingPrepared"), tone: "ok" };
      const ready = new Date(o.accepted_at).getTime() + o.eta_minutes * 60000;
      const mins = Math.ceil((ready - now) / 60000);
      const at = new Date(ready).toLocaleTimeString(`${lang}-IN`, { hour: "numeric", minute: "2-digit" });
      return { text: mins > 0 ? t("readyAround", { time: at, n: mins }) : t("readyAnyMoment"), tone: "ok" };
    }
    case "rejected": return { text: `${t("notAccepted")}${o.reject_reason ? `: ${o.reject_reason}` : ""}`, tone: "bad" };
    case "cancelled": return { text: t("cancelled"), tone: "bad" };
    case "completed": return { text: t("served"), tone: "done" };
  }
}

export default function OrdersView({ s, tableLabel, busy, notice, onService, onCoupon }: {
  s: ActiveState; tableLabel: string; busy: boolean; notice: string | null; onService: (t: "waiter" | "bill") => void;
  onCoupon: (code: string) => Promise<string | null>;
}) {
  const { t, lang } = useI18n();
  const now = useNow();
  const [code, setCode] = useState("");
  const [couponMsg, setCouponMsg] = useState<string | null>(null);
  const [couponBusy, setCouponBusy] = useState(false);
  const waiterOpen = s.open_requests.includes("waiter");
  const billOpen = s.session_status === "bill_requested" || s.open_requests.includes("bill");
  const settled = s.orders.every((o) => o.status === "completed" || o.status === "rejected" || o.status === "cancelled");
  const canRequestBill = s.orders.some((o) => o.status === "completed") && settled;
  const b = s.bill;
  const upi = s.restaurant.upi_id?.trim();
  const validUpi = upi && /^[A-Za-z0-9._-]{2,64}@[A-Za-z0-9.-]{2,64}$/.test(upi);
  const upiLink = validUpi && b.total > 0
    ? `upi://pay?pa=${upi}&pn=${encodeURIComponent(s.restaurant.name.slice(0, 50))}&am=${(b.total / 100).toFixed(2)}&cu=INR&tn=${encodeURIComponent(`Table ${tableLabel}`.slice(0, 50))}`
    : null;

  return (
    <div>
      {s.orders.length === 0 && <p className="empty">{t("noOrders")}</p>}
      <ul className="slips">
        {s.orders.map((o, i) => {
          const st = statusLine(o, now, lang, t);
          return (
            <li key={o.id} className={`slip slip-${st.tone}`}>
              <div className="slip-head">
                <span className="slip-no">{t("orderN", { n: i + 1 })}</span>
                <span className="slip-type">{o.order_type === "parcel" ? t("parcel") : t("eatHere")}</span>
              </div>
              <p className="slip-status">{st.text}</p>
              <ul className="slip-items">
                {o.items.map((it, k) => (
                  <li key={k} className={it.cancelled ? "struck" : ""}><span>{it.qty} × {it.name}{it.cancelled && it.reason ? ` (${it.reason})` : ""}</span><span>{rupees(it.qty * it.price)}</span></li>
                ))}
              </ul>
              {o.note && <p className="slip-note">{t("notePrefix", { note: o.note })}</p>}
              <div className="slip-total"><span>{t("orderTotal")}</span><strong>{rupees(o.total)}</strong></div>
            </li>
          );
        })}
      </ul>

      {b.total > 0 && (
        <section className="bill-card" aria-label={t("yourBill")}>
          <h3>{t("yourBill")}</h3>
          <div className="bill-row"><span>{t("billItems")}</span><span>{rupees(b.subtotal)}</span></div>
          {b.discount > 0 && <div className="bill-row"><span>{t("discount")}{b.coupon ? ` (${b.coupon})` : ""}</span><span>−{rupees(b.discount)}</span></div>}
          {b.service_charge > 0 && <div className="bill-row"><span>{t("serviceCharge", { n: b.service_charge_pct })}</span><span>{rupees(b.service_charge)}</span></div>}
          {b.tax > 0 && <>
            <div className="bill-row"><span>{t("cgst", { n: b.gst_rate / 2 })}</span><span>{rupees(b.cgst)}</span></div>
            <div className="bill-row"><span>{t("sgst", { n: b.gst_rate / 2 })}</span><span>{rupees(b.sgst)}</span></div>
          </>}
          {b.round_off !== 0 && <div className="bill-row"><span>{t("roundOff")}</span><span>{b.round_off < 0 ? "−" : ""}{rupees(Math.abs(b.round_off))}</span></div>}
          <div className="bill-row bill-total"><span>{t("total")}</span><strong>{rupees(b.total)}</strong></div>
          {b.pending > 0 && <p className="fine">{t("pendingBillNote", { amount: rupees(b.pending) })}</p>}
          {!b.coupon ? (
            <form className="coupon" onSubmit={async (e) => { e.preventDefault(); if (code.trim().length < 3) return; setCouponBusy(true); setCouponMsg(await onCoupon(code.trim())); setCouponBusy(false); }}>
              <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, 20))} placeholder={t("couponHave")} aria-label={t("couponHave")} />
              <button className="btn-outline" disabled={couponBusy || code.trim().length < 3}>{t("couponApply")}</button>
            </form>
          ) : <p className="fine">{t("couponApplied", { code: b.coupon })}</p>}
          {couponMsg && <p className="error" role="alert">{couponMsg}</p>}
        </section>
      )}

      {notice && <p className="error" role="alert">{notice}</p>}
      {billOpen && <p className="notice">{t("billRequestedNotice")}</p>}
      {billOpen && upiLink && (
        <a className="btn-primary btn-link" href={upiLink}>{t("payUpi", { amount: rupees(b.total) })}</a>
      )}
      {billOpen && upiLink && <p className="fine">{t("afterPay")}<br />UPI ID: <strong>{upi}</strong> · Amount: {rupees(b.total)}</p>}

      <div className="actions">
        <button className="btn-outline" disabled={busy || waiterOpen} onClick={() => onService("waiter")}>{waiterOpen ? t("waiterComing") : t("callWaiter")}</button>
        <button className="btn-outline" disabled={busy || billOpen || !canRequestBill} onClick={() => onService("bill")}>{billOpen ? t("billRequested") : t("requestBill")}</button>
      </div>
      {!billOpen && s.orders.length > 0 && !canRequestBill && (
        <p className="fine">{t("billWhenServed")}</p>
      )}
    </div>
  );
}
