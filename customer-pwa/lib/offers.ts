import type { CartLine } from "./cart";
import { lineKey, unitPrice } from "./cart";
import type { Category, Dish, Offer } from "./types";

type OfferRow = { line: { key: string; qty: number }; dish: { id: string }; unit: number };

export function autoOfferRewardLines(
  cart: CartLine[],
  dishes: Dish[],
  offers: Offer[],
  categories: Category[] = [],
  onlineMode = false,
): CartLine[] {
  const dishById = new Map(dishes.map((dish) => [dish.id, dish]));
  const rows = cart.flatMap((line) => {
    const dish = dishById.get(line.dishId);
    return dish ? [{ line, dish: { id: dish.id }, unit: unitPrice(dish, line.variantId, line.addonIds) }] : [];
  });
  const free = new Map<string, number>();
  const generated = new Map<string, CartLine>();

  for (const offer of offers) {
    const qualifyingQty = rows
      .filter((row) => row.dish.id === offer.buy_dish_id)
      .reduce((sum, row) => sum + row.line.qty - (free.get(row.line.key) ?? 0), 0);
    let remaining = Math.floor(qualifyingQty / offer.buy_qty) * offer.get_qty;
    if (remaining < 1) continue;

    const rewards = [
      ...rows.filter((row) => row.dish.id === offer.reward_dish_id),
      ...[...generated.values()]
        .filter((line) => line.dishId === offer.reward_dish_id)
        .map((line) => {
          const dish = dishById.get(line.dishId)!;
          return { line, dish: { id: dish.id }, unit: unitPrice(dish, line.variantId, line.addonIds) };
        }),
    ].sort((a, b) => a.unit - b.unit || a.line.key.localeCompare(b.line.key));

    for (const row of rewards) {
      if (remaining < 1) break;
      const alreadyFree = free.get(row.line.key) ?? 0;
      const quantity = Math.min(remaining, row.line.qty - alreadyFree);
      if (quantity > 0) free.set(row.line.key, alreadyFree + quantity);
      remaining -= quantity;
    }

    if (remaining < 1) continue;
    const reward = dishById.get(offer.reward_dish_id);
    if (!reward) continue;
    const categoryIsOpen = categories.find((item) => item.id === reward.category_id)?.open_now ?? true;
    if (!reward.is_available || (onlineMode && reward.online_available === false)
      || !categoryIsOpen || reward.addon_groups.some((group) => group.min_select > 0)) continue;

    const variant = [...reward.variants].sort((a, b) => a.price - b.price || a.id.localeCompare(b.id))[0];
    const key = `offer-reward:${lineKey(reward.id, variant?.id ?? null, [], "")}`;
    const existing = generated.get(key);
    const quantity = Math.min(remaining, 50 - (existing?.qty ?? 0));
    const alreadyInCart = cart.filter((line) => line.dishId === reward.id).reduce((sum, line) => sum + line.qty, 0)
      + [...generated.values()].filter((line) => line.dishId === reward.id).reduce((sum, line) => sum + line.qty, 0);
    const stockLeft = reward.stock_left === null ? Number.MAX_SAFE_INTEGER : Math.max(0, reward.stock_left - alreadyInCart);
    const added = Math.min(quantity, stockLeft);
    if (added < 1) continue;
    const line: CartLine = {
      key, dishId: reward.id, qty: (existing?.qty ?? 0) + added,
      variantId: variant?.id ?? null, addonIds: [], instructions: "",
    };
    generated.set(key, line);
    const generatedRow = rows.find((row) => row.line.key === key);
    if (generatedRow) generatedRow.line.qty = line.qty;
    else rows.push({ line, dish: { id: reward.id }, unit: unitPrice(reward, line.variantId, line.addonIds) });
    free.set(key, (free.get(key) ?? 0) + added);
  }

  return [...generated.values()];
}

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
