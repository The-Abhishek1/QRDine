export type I18n = Record<string, string> | null;
export type Category = {
  id: string; name: string; kind: string; sort: number; name_i18n: I18n;
  avail_from: string | null; avail_to: string | null; open_now: boolean;
};
export type Variant = { id: string; name: string; price: number; original_price: number | null };
export type Addon = { id: string; name: string; price: number; is_available: boolean };
export type AddonGroup = { id: string; name: string; min_select: number; max_select: number; addons: Addon[] };
export type Dish = {
  id: string; category_id: string; name: string; description: string | null; image_url: string | null;
  base_price: number; original_price: number | null; is_veg: boolean; is_available: boolean; sort: number;
  name_i18n: I18n; description_i18n: I18n; stock_left: number | null;
  variants: Variant[]; addon_groups: AddonGroup[];
};
export type Menu = {
  categories: Category[]; dishes: Dish[]; announcement: string | null;
  languages: string[]; bestsellers: string[]; pairs: Record<string, string[]>;
  offers: Offer[];
};
export type Offer = {
  id: string; name: string; buy_qty: number; get_qty: number;
  buy_dish_id: string; reward_dish_id: string; created_at: string;
};

export type Restaurant = { name: string; upi_id?: string | null; logo_url?: string | null };
export type BillView = {
  subtotal: number; discount: number; coupon: string | null; service_charge: number; service_charge_pct: number;
  tax: number; cgst: number; sgst: number; gst_rate: number; round_off: number; total: number; pending: number;
};

export type OrderStatus = "pending" | "accepted" | "rejected" | "cancelled" | "completed";
export type OrderView = {
  id: string; order_type: "dine_in" | "parcel"; status: OrderStatus; ready: boolean;
  eta_minutes: number | null; accepted_at: string | null; reject_reason: string | null;
  note: string | null; created_at: string; total: number;
  items: { name: string; qty: number; price: number; cancelled: boolean; reason: string | null }[];
};

export type JoinResult = {
  state: "ready" | "table_closed" | "shop_closed" | "unavailable" | "pin_required" | "pin_locked";
  wrong?: boolean;
  guest_token?: string;
  restaurant: Restaurant;
  table: { label: string };
  menu?: Menu;
};

export type ActiveState = {
  state: "active";
  restaurant: Restaurant;
  table: { label: string };
  session_status: "active" | "bill_requested";
  orders: OrderView[];
  bill: BillView;
  open_requests: ("waiter" | "bill")[];
  menu?: Menu;
};
export type EndedState = {
  state: "ended"; session_status: "paid" | "void"; restaurant: Restaurant;
  paid: { total: number; method: string } | null; reviewed: boolean;
};
export type SessionState = ActiveState | EndedState;
