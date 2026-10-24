import type { JoinResult, SessionState } from "./types";

const BASE = (process.env.NEXT_PUBLIC_API_BASE ?? "").replace(/\/+$/, "");

export class ApiError extends Error {
  constructor(public status: number, public code: string, public detail?: string) { super(code); }
}

async function call<T>(fn: string, body: unknown, guest?: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE}/${fn}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(guest ? { "x-guest-token": guest } : {}) },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, "network");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, data.error ?? "server_error", data.detail);
  return data as T;
}

export type OrderItemPayload = { dish_id: string; qty: number; variant_id: string | null; addon_ids: string[]; instructions: string };

export const api = {
  join: (token: string, pin?: string) => call<JoinResult>("session-join", { token, pin }),
  state: (guest: string, includeMenu: boolean) => call<SessionState>("session-state", { include_menu: includeMenu }, guest),
  order: (guest: string, payload: { order_type: string; client_key: string; note?: string; items: OrderItemPayload[] }) =>
    call<{ order_id: string; duplicate: boolean }>("order-place", payload, guest),
  service: (guest: string, type: "waiter" | "bill") => call<unknown>("service-request", { type }, guest),
  coupon: (guest: string, code: string) => call<{ discount: number; coupon: string }>("session-coupon", { code }, guest),
  review: (guest: string, payload: { food?: number; service?: number; comment?: string }) => call<{ ok: true }>("review-submit", payload, guest),
};
