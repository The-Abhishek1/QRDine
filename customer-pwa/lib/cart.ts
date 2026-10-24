import type { Dish } from "./types";

export type CartLine = { key: string; dishId: string; qty: number; variantId: string | null; addonIds: string[]; instructions: string };

export const lineKey = (dishId: string, variantId: string | null, addonIds: string[], instructions: string) =>
  [dishId, variantId ?? "", [...addonIds].sort().join(","), instructions.trim()].join("|");

export function unitPrice(d: Dish, variantId: string | null, addonIds: string[]): number {
  const base = variantId ? (d.variants.find((v) => v.id === variantId)?.price ?? d.base_price) : d.base_price;
  const extra = d.addon_groups.flatMap((g) => g.addons).filter((a) => addonIds.includes(a.id)).reduce((n, a) => n + a.price, 0);
  return base + extra;
}

export const isSimple = (d: Dish) => d.variants.length === 0 && d.addon_groups.length === 0;

export function parseCart(raw: string | null): CartLine[] {
  try {
    const v = raw ? JSON.parse(raw) : [];
    if (!Array.isArray(v)) return [];
    return v.filter((l) => l && typeof l.key === "string" && typeof l.dishId === "string" && Number.isInteger(l.qty) && l.qty > 0)
      .map((l) => ({ key: l.key, dishId: l.dishId, qty: l.qty, variantId: l.variantId ?? null, addonIds: Array.isArray(l.addonIds) ? l.addonIds : [], instructions: String(l.instructions ?? "") }));
  } catch { return []; }
}
