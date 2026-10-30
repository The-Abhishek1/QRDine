"use client";
import { useMemo, useState } from "react";
import type { Dish, Menu } from "@/lib/types";
import { rupees } from "@/lib/money";
import { type CartLine, isSimple, lineKey } from "@/lib/cart";
import { useI18n } from "@/lib/i18n";

function VegMark({ veg }: { veg: boolean }) {
  const { t } = useI18n();
  return <span className={`veg ${veg ? "veg-yes" : "veg-no"}`} role="img" aria-label={t(veg ? "vegetarian" : "nonVegetarian")}><i /></span>;
}

function Stepper({ qty, onAdd, onRemove, name, canAddMore }: { qty: number; onAdd: () => void; onRemove: () => void; name: string; canAddMore: boolean }) {
  const { t } = useI18n();
  if (qty === 0) return <button className="btn-add" onClick={onAdd} aria-label={`${t("add")} ${name}`}>{t("add")}</button>;
  return (
    <div className="stepper" role="group" aria-label={`${name}`}>
      <button onClick={onRemove} aria-label={`${t("remove")} ${name}`}>−</button>
      <span aria-live="polite">{qty}</span>
      <button onClick={onAdd} disabled={!canAddMore} aria-label={`${t("add")} ${name}`}>+</button>
    </div>
  );
}

const hm = (v: string | null) => (v ?? "").slice(0, 5);

export default function MenuView({ menu, lines, onQuickAdd, onQuickRemove, onConfigure, onRefresh, onlineMode = false }: {
  menu: Menu; lines: CartLine[]; onQuickAdd: (id: string) => void; onQuickRemove: (id: string) => void; onConfigure: (d: Dish) => void; onRefresh?: () => Promise<void>; onlineMode?: boolean;
}) {
  const { t, pick } = useI18n();
  const [vegOnly, setVegOnly] = useState(false);
  const [q, setQ] = useState("");
  const [refreshing, setRefreshing] = useState(false);

  const groups = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return menu.categories
      .map((c) => ({
        c,
        dishes: menu.dishes.filter((d) => {
          if (d.category_id !== c.id || (vegOnly && !d.is_veg)) return false;
          if (!needle) return true;
          const hay = [d.name, d.description ?? "", ...Object.values(d.name_i18n ?? {})].join(" ").toLowerCase();
          return hay.includes(needle);
        }),
      }))
      .filter((g) => g.dishes.length > 0);
  }, [menu, vegOnly, q]);

  async function refreshMenu() {
    if (!onRefresh || refreshing) return;
    setRefreshing(true);
    try {
      await onRefresh();
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <div>
      <div className="tools">
        <input type="search" placeholder={t("search")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("search")} />
        <label className="chip-toggle">
          <input type="checkbox" checked={vegOnly} onChange={(e) => setVegOnly(e.target.checked)} />
          <span><VegMark veg /> {t("vegOnly")}</span>
        </label>
        {onRefresh && (
          <button className="menu-refresh" type="button" onClick={() => void refreshMenu()} disabled={refreshing} aria-label="Refresh menu">
            <span aria-hidden="true">{refreshing ? "…" : "↻"}</span>
            {refreshing ? "Refreshing…" : "Refresh"}
          </button>
        )}
      </div>
      {!!menu.offers?.length && (
        <section className="offers" aria-label={t("offers")}>
          <h2>{t("offers")}</h2>
          {menu.offers.map((offer) => {
            const item = menu.dishes.find((d) => d.id === offer.buy_dish_id);
            const reward = menu.dishes.find((d) => d.id === offer.reward_dish_id);
            if (!item || !reward) return null;
            return (
              <div className="offer-card" key={offer.id}>
                <strong>{offer.name}</strong>
                <span>{t("buyGetOffer", { buy: offer.buy_qty, item: pick(item.name, item.name_i18n), get: offer.get_qty, reward: pick(reward.name, reward.name_i18n) })}</span>
                <small>{t("addRewardToQualify")}</small>
              </div>
            );
          })}
        </section>
      )}
      {groups.length > 1 && (
        <nav className="cats" aria-label={t("menuSections")}>
          {groups.map(({ c }) => (
            <a key={c.id} href={`#cat-${c.id}`} onClick={(e) => { e.preventDefault(); document.getElementById(`cat-${c.id}`)?.scrollIntoView({ behavior: "smooth", block: "start" }); }}>{pick(c.name, c.name_i18n)}</a>
          ))}
        </nav>
      )}
      {groups.length === 0 && <p className="empty">—</p>}
      {groups.map(({ c, dishes }) => (
        <section key={c.id} id={`cat-${c.id}`} className="cat">
          <h2>{pick(c.name, c.name_i18n)}</h2>
          {c.avail_from && c.avail_to && (
            <p className={`cat-window ${c.open_now ? "" : "cat-closed"}`}>{c.open_now ? t("available") : t("notNow")}: {hm(c.avail_from)} – {hm(c.avail_to)}</p>
          )}
          <ul>
            {dishes.map((d) => {
              const simple = isSimple(d);
              const orderable = d.is_available && (!onlineMode || d.online_available !== false) && c.open_now && (d.stock_left === null || d.stock_left > 0);
              const qty = simple
                ? (lines.find((l) => l.key === lineKey(d.id, null, [], ""))?.qty ?? 0)
                : lines.filter((l) => l.dishId === d.id).reduce((n, l) => n + l.qty, 0);
              const fromVariant = [...d.variants].sort((a, b) => a.price - b.price)[0];
              const from = fromVariant?.price ?? d.base_price;
              const original = fromVariant ? fromVariant.original_price : d.original_price;
              const desc = pick(d.description ?? "", d.description_i18n);
              return (
                <li key={d.id} className={`dish ${orderable ? "" : "dish-out"}`}>
                  <div className="dish-text">
                    <div className="dish-name"><VegMark veg={d.is_veg} /><span>{pick(d.name, d.name_i18n)}</span></div>
                    <div className="dish-price"><span>{rupees(from)}{d.variants.length > 0 ? "+" : ""}</span>{original != null && original > from && <del>{rupees(original)}</del>}</div>
                    <div className="dish-tags">
                      {menu.bestsellers.includes(d.id) && <span className="tag tag-best">★ {t("bestseller")}</span>}
                      {d.stock_left !== null && d.stock_left > 0 && d.stock_left <= 5 && <span className="tag tag-low">{t("onlyLeft", { n: d.stock_left })}</span>}
                    </div>
                    {desc && <p className="dish-desc">{desc}</p>}
                  </div>
                  <div className="dish-side">
                    {d.image_url ? <img src={d.image_url} alt="" loading="lazy" width={96} height={96} referrerPolicy="no-referrer" /> : <div className="dish-noimg" />}
                    {!orderable ? <span className="out-tag">{d.stock_left === 0 || !d.is_available ? t("soldOut") : onlineMode && d.online_available === false ? "Not available online" : t("notNow")}</span>
                      : simple ? <Stepper qty={qty} onAdd={() => onQuickAdd(d.id)} onRemove={() => onQuickRemove(d.id)} name={pick(d.name, d.name_i18n)} canAddMore={qty < 50 && (d.stock_left === null || qty < d.stock_left)} />
                      : <>
                          <button className="btn-add" disabled={d.stock_left !== null && qty >= d.stock_left} onClick={() => onConfigure(d)}>{t("add")}</button>
                          {qty > 0 && <span className="in-cart">{t("inCart", { n: qty })}</span>}
                        </>}
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
