import type { Offer } from "./types";

type OfferRow = { line: { key: string; qty: number }; dish: { id: string }; unit: number };

export function freeOfferQuantities(rows: OfferRow[], offers: Offer[]): Map<string, number> {
  const free = new Map<string, number>();

  for (const offer of offers) {
    const qualifyingQty = rows
      .filter((row) => row.dish.id === offer.buy_dish_id)
      .reduce((sum, row) => sum + row.line.qty - (free.get(row.line.key) ?? 0), 0);
    const bundleSize = offer.buy_dish_id === offer.reward_dish_id
      ? offer.buy_qty + offer.get_qty
      : offer.buy_qty;
    const offeredQty = Math.floor(qualifyingQty / bundleSize) * offer.get_qty;
    let remaining = Math.min(
      offeredQty,
      rows.filter((row) => row.dish.id === offer.reward_dish_id)
        .reduce((sum, row) => sum + row.line.qty - (free.get(row.line.key) ?? 0), 0),
    );

    const rewards = rows
      .filter((row) => row.dish.id === offer.reward_dish_id)
      .sort((a, b) => a.unit - b.unit || a.line.key.localeCompare(b.line.key));
    for (const row of rewards) {
      if (remaining === 0) break;
      const alreadyFree = free.get(row.line.key) ?? 0;
      const qty = Math.min(remaining, row.line.qty - alreadyFree);
      if (qty > 0) free.set(row.line.key, alreadyFree + qty);
      remaining -= qty;
    }
  }

  return free;
}
