"use client";
import { useState } from "react";
import type { Dish } from "@/lib/types";
import { type CartLine, lineKey, unitPrice } from "@/lib/cart";
import { rupees } from "@/lib/money";
import { useI18n } from "@/lib/i18n";

export default function DishSheet({ dish, onAdd, onClose }: { dish: Dish; onAdd: (line: CartLine) => void; onClose: () => void }) {
  const { t, pick } = useI18n();
  const [variantId, setVariantId] = useState<string | null>(dish.variants[0]?.id ?? null);
  const [sel, setSel] = useState<Record<string, string[]>>({});
  const [qty, setQty] = useState(1);
  const [note, setNote] = useState("");

  const addonIds = Object.values(sel).flat();
  const invalidGroup = dish.addon_groups.find((g) => {
    const n = (sel[g.id] ?? []).length;
    return n < g.min_select || n > g.max_select;
  });
  const impossibleGroup = dish.addon_groups.find((g) => g.min_select > g.addons.filter((a) => a.is_available).length);
  const valid = !invalidGroup;
  const price = unitPrice(dish, variantId, addonIds) * qty;

  function toggle(gid: string, aid: string, max: number) {
    setSel((s) => {
      const cur = s[gid] ?? [];
      if (cur.includes(aid)) return { ...s, [gid]: cur.filter((x) => x !== aid) };
      if (max === 1) return { ...s, [gid]: [aid] };
      if (cur.length >= max) return s;
      return { ...s, [gid]: [...cur, aid] };
    });
  }

  return (
    <div className="sheet-backdrop sheet-top" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={dish.name} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-head"><h2>{pick(dish.name, dish.name_i18n)}</h2><button className="link" onClick={onClose}>{t("close")}</button></div>

        {dish.variants.length > 0 && (
          <fieldset className="opt-group">
            <legend>{t("chooseOne")} <small>· {t("required")}</small></legend>
            {dish.variants.map((v) => (
              <label key={v.id} className="opt">
                <input type="radio" name="variant" checked={variantId === v.id} onChange={() => setVariantId(v.id)} />
                <span>{v.name}</span><b className="price-pair"><span>{rupees(v.price)}</span>{v.original_price != null && v.original_price > v.price && <del>{rupees(v.original_price)}</del>}</b>
              </label>
            ))}
          </fieldset>
        )}

        {dish.addon_groups.map((g) => (
          <fieldset key={g.id} className="opt-group">
            <legend>{g.name} <small>· {g.min_select === 0 ? `${t("optional")}, ` : `${t("chooseAtLeast", { n: g.min_select })}, `}{g.max_select === 1 ? t("chooseOne") : t("chooseUpTo", { n: g.max_select })}</small></legend>
            {g.addons.map((a) => (
              <label key={a.id} className={`opt ${a.is_available ? "" : "opt-off"}`}>
                <input type={g.max_select === 1 ? "radio" : "checkbox"} name={`g-${g.id}`} disabled={!a.is_available}
                  checked={(sel[g.id] ?? []).includes(a.id)} onChange={() => toggle(g.id, a.id, g.max_select)} />
                <span>{a.name}{!a.is_available ? ` (${t("soldOut")})` : ""}</span><b>{a.price > 0 ? `+${rupees(a.price)}` : ""}</b>
              </label>
            ))}
          </fieldset>
        ))}
        {impossibleGroup ? (
          <p className="warn" role="status">{t("notEnoughAvailableOptions", { name: impossibleGroup.name })}</p>
        ) : invalidGroup && invalidGroup.min_select > (sel[invalidGroup.id] ?? []).length ? (
          <p className="warn" role="status">{t("selectAtLeastOptions", { n: invalidGroup.min_select })}</p>
        ) : null}

        <label className="note">
          <span>{t("instructions")}</span>
          <textarea rows={2} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
        </label>

        <div className="sheet-actions">
          <div className="stepper" role="group" aria-label={t("quantity")}>
            <button onClick={() => setQty((q) => Math.max(1, q - 1))} aria-label={t("remove")}>−</button><span>{qty}</span><button onClick={() => setQty((q) => Math.min(50, q + 1))} aria-label={t("add")}>+</button>
          </div>
          <button className="btn-primary" disabled={!valid} onClick={() => { onAdd({ key: lineKey(dish.id, variantId, addonIds, note), dishId: dish.id, qty, variantId, addonIds, instructions: note.trim() }); onClose(); }}>
            {t("addToOrder")} · {rupees(price)}
          </button>
        </div>
      </div>
    </div>
  );
}
