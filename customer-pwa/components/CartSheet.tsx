"use client";
import type { Dish } from "@/lib/types";
import { rupees } from "@/lib/money";
import { useI18n } from "@/lib/i18n";
import type { CartLine } from "@/lib/cart";

export type CartRow = { line: CartLine; dish: Dish; unit: number; label: string; problem: string | null; freeQty: number; canIncrease: boolean };

export default function CartSheet({
  rows, suggestions, orderType, setOrderType, note, setNote, error, placing, onInc, onDec, onSuggest, onPlace, onClose,
}: {
  rows: CartRow[]; suggestions: Dish[]; orderType: "dine_in" | "parcel"; setOrderType: (t: "dine_in" | "parcel") => void;
  note: string; setNote: (s: string) => void; error: string | null; placing: boolean;
  onInc: (key: string) => void; onDec: (key: string) => void; onSuggest: (d: Dish) => void; onPlace: () => void; onClose: () => void;
}) {
  const { t, pick } = useI18n();
  const total = rows.reduce((n, r) => n + r.unit * (r.line.qty - r.freeQty), 0);
  const blocked = rows.some((r) => r.problem);
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={t("yourOrder")} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head"><h2>{t("yourOrder")}</h2><button className="link" onClick={onClose}>{t("close")}</button></div>

        <div className="seg" role="radiogroup" aria-label={t("orderType")}>
          <button role="radio" aria-checked={orderType === "dine_in"} className={orderType === "dine_in" ? "on" : ""} onClick={() => setOrderType("dine_in")}>{t("eatHere")}</button>
          <button role="radio" aria-checked={orderType === "parcel"} className={orderType === "parcel" ? "on" : ""} onClick={() => setOrderType("parcel")}>{t("parcel")}</button>
        </div>

        <ul className="cart-lines">
          {rows.map(({ line, label, unit, problem, freeQty, canIncrease }) => (
            <li key={line.key}>
              <div>
                <div className="cart-name">{label}</div>
                {freeQty > 0 && <div className="cart-note">{t("freeOfferItem", { n: freeQty })}</div>}
                {line.instructions && <div className="cart-note">“{line.instructions}”</div>}
                {problem && <div className="warn">{problem}</div>}
              </div>
              <div className="cart-right">
                <div className="stepper" role="group" aria-label={label}>
                  <button onClick={() => onDec(line.key)} aria-label={`${t("remove")} ${label}`}>−</button>
                  <span>{line.qty}</span>
                  <button onClick={() => onInc(line.key)} disabled={!canIncrease} aria-label={`${t("add")} ${label}`}>+</button>
                </div>
                <div className="cart-price">{rupees(unit * (line.qty - freeQty))}</div>
              </div>
            </li>
          ))}
        </ul>

        {suggestions.length > 0 && (
          <div className="suggest">
            <h3>{t("alsoOrder")}</h3>
            <div className="suggest-row">
              {suggestions.map((d) => (
                <button key={d.id} className="suggest-chip" onClick={() => onSuggest(d)}>
                  <span>{pick(d.name, d.name_i18n)}</span><b>+ {rupees(d.variants.length ? Math.min(...d.variants.map((v) => v.price)) : d.base_price)}</b>
                </button>
              ))}
            </div>
          </div>
        )}

        <label className="note">
          <span>{t("kitchenNote")}</span>
          <textarea rows={2} maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} />
        </label>

        <div className="total-row"><span>{t("subtotal")}</span><strong>{rupees(total)}</strong></div>
        <p className="fine">{t("taxesNote")}</p>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="btn-primary" onClick={onPlace} disabled={placing || blocked || rows.length === 0}>
          {placing ? t("sending") : `${t("placeOrder")} · ${rupees(total)}`}
        </button>
      </div>
    </div>
  );
}
