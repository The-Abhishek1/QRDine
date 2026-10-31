"use client";

import { useCallback, useEffect, useState } from "react";
import { customerSupabase } from "@/lib/customerSupabase";
import { rupees } from "@/lib/money";

type Category = { id: string; name: string; kind: "food" | "beverage"; sort: number; avail_from: string | null; avail_to: string | null; name_i18n?: Record<string, string> | null };
type Dish = {
  id: string; category_id: string; name: string; description: string | null; image_url: string | null;
  base_price: number; original_price: number | null; is_veg: boolean; is_available: boolean;
  online_available: boolean; stock_qty: number | null; name_i18n?: Record<string, string> | null;
  description_i18n?: Record<string, string> | null;
};
type Variant = { id: string; dish_id: string; name: string; price: number; original_price: number | null };
type AddonGroup = { id: string; name: string; min_select: number; max_select: number };
type Addon = { id: string; group_id: string; name: string; price: number; is_available: boolean };
type DishGroupLink = { dish_id: string; group_id: string };
type Offer = { id: string; name: string; buy_qty: number; get_qty: number; buy_dish_id: string; reward_dish_id: string; active: boolean };

type MenuState = {
  categories: Category[];
  dishes: Dish[];
  variants: Variant[];
  groups: AddonGroup[];
  addons: Addon[];
  links: DishGroupLink[];
  offers: Offer[];
};

const EMPTY: MenuState = { categories: [], dishes: [], variants: [], groups: [], addons: [], links: [], offers: [] };
const LANGUAGES: Record<string, string> = { hi: "Hindi", kn: "Kannada", ta: "Tamil", te: "Telugu", bn: "Bengali" };

function messageOf(error: unknown): string { return error instanceof Error ? error.message : "The request failed. Please try again."; }

function toPaise(value: string): number | null {
  if (!/^\d{1,8}(?:\.\d{1,2})?$/.test(value.trim())) return null;
  const amount = Number(value);
  return Number.isFinite(amount) ? Math.round(amount * 100) : null;
}

function Field({
  label, value, onChange, type = "text", required = false,
}: {
  label: string; value: string; onChange: (value: string) => void; type?: string; required?: boolean;
}) {
  return <label className="portal-field"><span>{label}{required ? " *" : ""}</span><input type={type} value={value} onChange={(event) => onChange(event.target.value)} required={required} /></label>;
}

function downloadCsv(filename: string, value: string) {
  const url = URL.createObjectURL(new Blob([value], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function csvCell(value: string): string { return `"${value.replace(/"/g, '""')}"`; }

type ParsedCsvDish = {
  category: string; kind: "food" | "beverage" | null; categoryI18n: Record<string, string>;
  name: string; nameI18n: Record<string, string>; price: number; originalPrice: number | null;
  veg: boolean; description: string; descriptionI18n: Record<string, string>;
  variantName: string | null; variantPrice: number | null; variantOriginalPrice: number | null;
};

function readCsvRecords(text: string): string[][] {
  const records: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  let closedQuote = false;
  const endCell = () => { row.push(field); field = ""; closedQuote = false; };
  const endRow = () => {
    endCell();
    if (row.some((cell) => cell.trim())) records.push(row);
    row = [];
  };
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') { field += '"'; index += 1; }
      else if (character === '"') { quoted = false; closedQuote = true; }
      else field += character;
    } else if (character === '"' && !field && !closedQuote) quoted = true;
    else if (character === ",") endCell();
    else if (character === "\n") endRow();
    else if (character === "\r") {
      endRow();
      if (text[index + 1] === "\n") index += 1;
    } else if (closedQuote && !/\s/.test(character)) {
      throw new Error("CSV has an invalid character after a quoted value.");
    } else if (character === '"') throw new Error("A quote inside a CSV value must be doubled.");
    else field += character;
  }
  if (quoted) throw new Error("A quoted CSV value was not closed.");
  if (row.length || field || closedQuote) endRow();
  return records;
}

function normalizedCsvHeader(raw: string, languages: string[]): string {
  const lower = raw.trim().replace(/^\uFEFF/, "").toLowerCase();
  const normalized = lower.replace(/[^a-z0-9]+/g, " ").trim().replace(/\s+/g, " ");
  const simple = lower.replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
  const supported = new Set([
    "category", "category_kind", "name", "price", "original_price", "veg", "description",
    "variant_name", "variant_price", "variant_original_price",
    ...languages.flatMap((language) => [`category_${language}`, `name_${language}`, `description_${language}`]),
  ]);
  if (supported.has(simple)) return simple;
  const aliases: Record<string, string> = {
    "food item": "name", "price inr": "price", "category name": "category",
    "category type food or beverage": "category_kind", "dish name": "name",
    "selling price": "price", "selling price inr": "price",
    "original list price": "original_price", "original list price inr": "original_price",
    "vegetarian yes or no": "veg", "dish description": "description",
    "size variant name": "variant_name", "size variant selling price": "variant_price",
    "size variant selling price inr": "variant_price",
    "size variant original list price": "variant_original_price",
    "size variant original list price inr": "variant_original_price",
  };
  if (aliases[normalized]) return aliases[normalized];
  for (const [code, label] of Object.entries(LANGUAGES)) {
    if (!languages.includes(code)) continue;
    for (const [prefix, column] of [["category name", "category"], ["dish name", "name"], ["dish description", "description"]]) {
      if (normalized === `${prefix} ${label.toLowerCase()}`) return `${column}_${code}`;
    }
  }
  return simple;
}

export default function RestaurantMenuManager({
  restaurantId,
  languages,
  onRefresh,
}: {
  restaurantId: string;
  languages: string[];
  onRefresh: () => Promise<void>;
}) {
  const [data, setData] = useState<MenuState>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [categoryName, setCategoryName] = useState("");
  const [categoryKind, setCategoryKind] = useState<"food" | "beverage">("food");
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const [dishFormOpen, setDishFormOpen] = useState(false);
  const [editingDish, setEditingDish] = useState<Dish | null>(null);
  const [dishName, setDishName] = useState("");
  const [dishCategory, setDishCategory] = useState("");
  const [dishDescription, setDishDescription] = useState("");
  const [dishPrice, setDishPrice] = useState("");
  const [dishOriginalPrice, setDishOriginalPrice] = useState("");
  const [dishVeg, setDishVeg] = useState(true);
  const [dishStock, setDishStock] = useState("");
  const [dishImage, setDishImage] = useState<File | null>(null);
  const [dishVariants, setDishVariants] = useState<Array<{ name: string; price: string; originalPrice: string }>>([]);
  const [dishGroupIds, setDishGroupIds] = useState<string[]>([]);
  const [translations, setTranslations] = useState<Record<string, string>>({});
  const [groupName, setGroupName] = useState("");
  const [groupMin, setGroupMin] = useState("0");
  const [groupMax, setGroupMax] = useState("1");
  const [addonDrafts, setAddonDrafts] = useState<Record<string, { name: string; price: string }>>({});
  const [offerName, setOfferName] = useState("");
  const [offerBuyDish, setOfferBuyDish] = useState("");
  const [offerRewardDish, setOfferRewardDish] = useState("");
  const [offerBuyQty, setOfferBuyQty] = useState("2");
  const [offerGetQty, setOfferGetQty] = useState("1");
  const [categoryTranslations, setCategoryTranslations] = useState<Record<string, string>>({});
  const [categoryFrom, setCategoryFrom] = useState("");
  const [categoryTo, setCategoryTo] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const supabase = customerSupabase();
    const [categories, dishes, variants, groups, addons, links, offers] = await Promise.all([
      supabase.from("categories").select("id,name,kind,sort,avail_from,avail_to,name_i18n").eq("restaurant_id", restaurantId).is("deleted_at", null).order("sort"),
      supabase.from("dishes").select("id,category_id,name,description,image_url,base_price,original_price,is_veg,is_available,online_available,stock_qty,name_i18n,description_i18n").eq("restaurant_id", restaurantId).is("deleted_at", null).order("sort"),
      supabase.from("dish_variants").select("id,dish_id,name,price,original_price").eq("restaurant_id", restaurantId).order("price"),
      supabase.from("addon_groups").select("id,name,min_select,max_select").eq("restaurant_id", restaurantId).order("name"),
      supabase.from("addons").select("id,group_id,name,price,is_available").eq("restaurant_id", restaurantId).order("name"),
      supabase.from("dish_addon_groups").select("dish_id,group_id").eq("restaurant_id", restaurantId),
      supabase.from("offers").select("id,name,buy_qty,get_qty,buy_dish_id,reward_dish_id,active").eq("restaurant_id", restaurantId).order("created_at"),
    ]);
    const failure = categories.error || dishes.error || variants.error || groups.error || addons.error || links.error || offers.error;
    if (failure) {
      setError(messageOf(failure));
    } else {
      setData({
        categories: categories.data as Category[],
        dishes: dishes.data as Dish[],
        variants: variants.data as Variant[],
        groups: groups.data as AddonGroup[],
        addons: addons.data as Addon[],
        links: links.data as DishGroupLink[],
        offers: offers.data as Offer[],
      });
    }
    setLoading(false);
  }, [restaurantId]);

  useEffect(() => { void load(); }, [load]);

  async function mutate(action: () => Promise<void>, success: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      await load();
      await onRefresh();
      setNotice(success);
    } catch (actionError) {
      setError(messageOf(actionError));
    } finally {
      setBusy(false);
    }
  }

  async function saveCategory(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!categoryName.trim()) return;
    await mutate(async () => {
      const supabase = customerSupabase();
      if (editingCategory) {
        const { error: saveError } = await supabase.from("categories").update({
          name: categoryName.trim(),
          name_i18n: Object.fromEntries(Object.entries(categoryTranslations).filter(([, value]) => value.trim())),
          avail_from: categoryFrom || null,
          avail_to: categoryTo || null,
        }).eq("id", editingCategory.id).eq("restaurant_id", restaurantId);
        if (saveError) throw saveError;
      } else {
        const { error: insertError } = await supabase.from("categories").insert({
          restaurant_id: restaurantId, name: categoryName.trim(), kind: categoryKind, sort: data.categories.length,
        });
        if (insertError) throw insertError;
      }
      setCategoryName("");
      setEditingCategory(null);
      setCategoryTranslations({});
      setCategoryFrom("");
      setCategoryTo("");
    }, editingCategory ? "Category updated." : "Category added.");
  }

  function beginEditCategory(category: Category) {
    setEditingCategory(category);
    setCategoryName(category.name);
    setCategoryKind(category.kind);
    setCategoryTranslations(category.name_i18n ?? {});
    setCategoryFrom(category.avail_from?.slice(0, 5) ?? "");
    setCategoryTo(category.avail_to?.slice(0, 5) ?? "");
  }

  async function deleteCategory(category: Category) {
    if (!window.confirm(`Delete category "${category.name}"? It must not contain dishes.`)) return;
    await mutate(async () => {
      const { error: deleteError } = await customerSupabase().rpc("delete_category", { p_id: category.id });
      if (deleteError) throw deleteError;
    }, "Category deleted.");
  }

  async function moveCategory(category: Category, offset: -1 | 1) {
    const index = data.categories.findIndex((item) => item.id === category.id);
    const target = index + offset;
    if (target < 0 || target >= data.categories.length) return;
    const reordered = [...data.categories];
    [reordered[index], reordered[target]] = [reordered[target], reordered[index]];
    await mutate(async () => {
      for (const [sort, item] of reordered.entries()) {
        const { error: moveError } = await customerSupabase().from("categories").update({ sort })
          .eq("id", item.id).eq("restaurant_id", restaurantId);
        if (moveError) throw moveError;
      }
    }, "Category order updated.");
  }

  async function moveDish(dish: Dish, offset: -1 | 1) {
    const rows = data.dishes.filter((item) => item.category_id === dish.category_id);
    const index = rows.findIndex((item) => item.id === dish.id);
    const target = index + offset;
    if (target < 0 || target >= rows.length) return;
    [rows[index], rows[target]] = [rows[target], rows[index]];
    await mutate(async () => {
      for (const [sort, item] of rows.entries()) {
        const { error: moveError } = await customerSupabase().from("dishes").update({ sort })
          .eq("id", item.id).eq("restaurant_id", restaurantId);
        if (moveError) throw moveError;
      }
    }, "Dish order updated.");
  }

  function resetDishEditor(dish: Dish | null) {
    setEditingDish(dish);
    setDishName(dish?.name ?? "");
    setDishCategory(dish?.category_id ?? data.categories[0]?.id ?? "");
    setDishDescription(dish?.description ?? "");
    setDishPrice(dish ? String(dish.base_price / 100) : "");
    setDishOriginalPrice(dish?.original_price ? String(dish.original_price / 100) : "");
    setDishVeg(dish?.is_veg ?? true);
    setDishStock(dish?.stock_qty?.toString() ?? "");
    setDishImage(null);
    setDishVariants(dish ? data.variants.filter((variant) => variant.dish_id === dish.id).map((variant) => ({
      name: variant.name, price: String(variant.price / 100), originalPrice: variant.original_price ? String(variant.original_price / 100) : "",
    })) : []);
    setDishGroupIds(dish ? data.links.filter((link) => link.dish_id === dish.id).map((link) => link.group_id) : []);
    setTranslations({ ...(dish?.name_i18n ?? {}), ...Object.fromEntries(Object.entries(dish?.description_i18n ?? {}).map(([key, value]) => [`description_${key}`, value])) });
    setDishFormOpen(true);
  }

  async function saveDish(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const price = toPaise(dishPrice);
    const originalPrice = dishOriginalPrice ? toPaise(dishOriginalPrice) : null;
    const stock = dishStock.trim() ? Number(dishStock) : null;
    const variants = dishVariants.map((variant) => ({
      name: variant.name.trim(), price: toPaise(variant.price), original: variant.originalPrice ? toPaise(variant.originalPrice) : null,
    }));
    if (!dishName.trim() || !dishCategory || price === null || (dishOriginalPrice && (originalPrice === null || originalPrice <= price)) ||
      (stock !== null && (!Number.isInteger(stock) || stock < 0)) ||
      variants.some((variant) => !variant.name || variant.price === null || variant.price <= 0 ||
        (variant.original !== null && variant.original <= variant.price)) ||
      dishVariants.some((variant, index) => variant.originalPrice.trim() && variants[index].original === null) ||
      new Set(variants.map((variant) => variant.name.toLowerCase())).size !== variants.length) {
      setError("Check the dish name, category, price, stock, and unique size prices.");
      return;
    }
    await mutate(async () => {
      const supabase = customerSupabase();
      let imageUrl = editingDish?.image_url ?? null;
      if (dishImage) {
        if (!dishImage.type.startsWith("image/") || dishImage.size > 5 * 1024 * 1024) throw new Error("Choose an image smaller than 5 MB.");
        const extension = dishImage.name.split(".").pop()?.toLowerCase().replace(/[^a-z0-9]/g, "") || "jpg";
        const imagePath = `${restaurantId}/${crypto.randomUUID()}.${extension}`;
        const { error: uploadError } = await supabase.storage.from("dish-images").upload(imagePath, dishImage, { contentType: dishImage.type });
        if (uploadError) throw uploadError;
        imageUrl = supabase.storage.from("dish-images").getPublicUrl(imagePath).data.publicUrl;
      }
      const nameI18n = Object.fromEntries(Object.entries(translations).filter(([key, value]) => !key.startsWith("description_") && value.trim()));
      const descriptionI18n = Object.fromEntries(Object.entries(translations).filter(([key, value]) => key.startsWith("description_") && value.trim()).map(([key, value]) => [key.slice("description_".length), value]));
      let dishId = editingDish?.id;
      const dishValues = {
        category_id: dishCategory, name: dishName.trim(), description: dishDescription.trim() || null,
        image_url: imageUrl, base_price: price, original_price: originalPrice, is_veg: dishVeg,
        stock_qty: stock, name_i18n: nameI18n, description_i18n: descriptionI18n,
      };
      if (dishId) {
        const { error: updateError } = await supabase.from("dishes").update(dishValues).eq("id", dishId).eq("restaurant_id", restaurantId);
        if (updateError) throw updateError;
      } else {
        const { data: created, error: insertError } = await supabase.from("dishes").insert({
          ...dishValues, restaurant_id: restaurantId, sort: data.dishes.length,
        }).select("id").single();
        if (insertError) throw insertError;
        dishId = created.id;
      }
      const { error: variantDeleteError } = await supabase.from("dish_variants").delete().eq("dish_id", dishId).eq("restaurant_id", restaurantId);
      if (variantDeleteError) throw variantDeleteError;
      if (variants.length) {
        const { error: variantInsertError } = await supabase.from("dish_variants").insert(variants.map((variant) => ({
          restaurant_id: restaurantId, dish_id: dishId, name: variant.name, price: variant.price, original_price: variant.original,
        })));
        if (variantInsertError) throw variantInsertError;
      }
      const { error: linkDeleteError } = await supabase.from("dish_addon_groups").delete().eq("dish_id", dishId).eq("restaurant_id", restaurantId);
      if (linkDeleteError) throw linkDeleteError;
      if (dishGroupIds.length) {
        const { error: linkInsertError } = await supabase.from("dish_addon_groups").insert(dishGroupIds.map((groupId) => ({
          restaurant_id: restaurantId, dish_id: dishId, group_id: groupId,
        })));
        if (linkInsertError) throw linkInsertError;
      }
      setDishFormOpen(false);
    }, editingDish ? "Dish updated." : "Dish added.");
  }

  async function setDishFlag(dish: Dish, key: "is_available" | "online_available", value: boolean) {
    await mutate(async () => {
      if (key === "online_available") {
        const { error: updateError } = await customerSupabase().rpc("set_online_order_dish_availability", { p_dish_id: dish.id, p_available: value });
        if (updateError) throw updateError;
      } else {
        const { error: updateError } = await customerSupabase().from("dishes").update({ is_available: value }).eq("id", dish.id).eq("restaurant_id", restaurantId);
        if (updateError) throw updateError;
      }
    }, "Dish availability updated.");
  }

  async function deleteDish(dish: Dish) {
    if (!window.confirm(`Remove "${dish.name}" from the menu? Past bills will be retained.`)) return;
    await mutate(async () => {
      const { error: deleteError } = await customerSupabase().from("dishes").update({ deleted_at: new Date().toISOString() }).eq("id", dish.id).eq("restaurant_id", restaurantId);
      if (deleteError) throw deleteError;
    }, "Dish removed from the menu.");
  }

  async function createGroup(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const min = Number(groupMin);
    const max = Number(groupMax);
    if (!groupName.trim() || !Number.isInteger(min) || !Number.isInteger(max) || min < 0 || max < min || max > 20) {
      setError("Enter a group name, minimum of 0 or more, and maximum of at least the minimum.");
      return;
    }
    await mutate(async () => {
      const { error: insertError } = await customerSupabase().from("addon_groups").insert({
        restaurant_id: restaurantId, name: groupName.trim(), min_select: min, max_select: max,
      });
      if (insertError) throw insertError;
      setGroupName("");
    }, "Extra group created.");
  }

  async function addAddon(groupId: string) {
    const addon = addonDrafts[groupId];
    const price = addon ? toPaise(addon.price || "0") : null;
    if (!addon?.name.trim() || price === null) { setError("Enter an add-on name and a valid price."); return; }
    await mutate(async () => {
      const { error: insertError } = await customerSupabase().from("addons").insert({
        restaurant_id: restaurantId, group_id: groupId, name: addon.name.trim(), price,
      });
      if (insertError) throw insertError;
      setAddonDrafts((current) => ({ ...current, [groupId]: { name: "", price: "" } }));
    }, "Extra added.");
  }

  async function setAddonActive(addon: Addon, active: boolean) {
    await mutate(async () => {
      const { error: updateError } = await customerSupabase().from("addons").update({ is_available: active }).eq("id", addon.id).eq("restaurant_id", restaurantId);
      if (updateError) throw updateError;
    }, "Extra availability updated.");
  }

  async function createOffer(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const buyQty = Number(offerBuyQty);
    const getQty = Number(offerGetQty);
    if (!offerName.trim() || !offerBuyDish || !offerRewardDish || !Number.isInteger(buyQty) || buyQty < 1 || buyQty > 50 ||
      !Number.isInteger(getQty) || getQty < 1 || getQty > 50) {
      setError("Choose both dishes and enter buy/get quantities from 1 to 50.");
      return;
    }
    await mutate(async () => {
      const { error: insertError } = await customerSupabase().from("offers").insert({
        restaurant_id: restaurantId, name: offerName.trim(), buy_qty: buyQty, get_qty: getQty,
        buy_dish_id: offerBuyDish, reward_dish_id: offerRewardDish,
      });
      if (insertError) throw insertError;
      setOfferName("");
      setOfferBuyDish("");
      setOfferRewardDish("");
    }, "Offer created.");
  }

  async function setOfferActive(offer: Offer, active: boolean) {
    await mutate(async () => {
      const { error: updateError } = await customerSupabase().from("offers").update({ active }).eq("id", offer.id).eq("restaurant_id", restaurantId);
      if (updateError) throw updateError;
    }, "Offer status updated.");
  }

  async function removeOffer(offer: Offer) {
    if (!window.confirm(`Delete offer "${offer.name}"?`)) return;
    await mutate(async () => {
      const { error: deleteError } = await customerSupabase().from("offers").delete().eq("id", offer.id).eq("restaurant_id", restaurantId);
      if (deleteError) throw deleteError;
    }, "Offer deleted.");
  }

  function exportMenu(withExamples: boolean) {
    const headers = [
      "Category name", "Category type (food or beverage)", "Dish name", "Selling price (INR)", "Original list price (INR)",
      "Vegetarian (yes or no)", "Dish description", "Size variant name", "Size variant selling price (INR)", "Size variant original list price (INR)",
      ...languages.flatMap((language) => [`Category name ${LANGUAGES[language] ?? language}`, `Dish name ${LANGUAGES[language] ?? language}`, `Dish description ${LANGUAGES[language] ?? language}`]),
    ];
    const rows: string[][] = [];
    if (withExamples) {
      rows.push(["Starters", "food", "Paneer Tikka", "220", "", "yes", "Marinated cottage cheese grilled in a tandoor.", "Half", "220", "260"]);
      rows.push(["Starters", "food", "Paneer Tikka", "220", "", "yes", "Marinated cottage cheese grilled in a tandoor.", "Full", "390", "450"]);
      rows.push(["Main Course", "food", "Chicken Biryani", "280", "", "no", "Fragrant basmati rice layered with spiced chicken.", "", "", ""]);
      rows.push(["Beverages", "beverage", "Fresh Lime Soda", "90", "", "yes", "Fresh lime with chilled soda.", "", "", ""]);
    } else {
      for (const dish of data.dishes) {
        const category = data.categories.find((item) => item.id === dish.category_id);
        if (!category) continue;
        const variants = data.variants.filter((variant) => variant.dish_id === dish.id);
        const shared = [category.name, category.kind, dish.name, (dish.base_price / 100).toFixed(2), dish.original_price ? (dish.original_price / 100).toFixed(2) : "", dish.is_veg ? "yes" : "no", dish.description ?? ""];
        const locale = languages.flatMap((language) => [category.name_i18n?.[language] ?? "", dish.name_i18n?.[language] ?? "", dish.description_i18n?.[language] ?? ""]);
        if (variants.length) variants.forEach((variant, index) => rows.push([
          ...shared, variant.name, (variant.price / 100).toFixed(2), variant.original_price ? (variant.original_price / 100).toFixed(2) : "", ...(index === 0 ? locale : locale.map(() => "")),
        ]));
        else rows.push([...shared, "", "", "", ...locale]);
      }
    }
    downloadCsv(withExamples ? "qrdine-menu-template.csv" : "qrdine-menu-export.csv", [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\r\n"));
  }

  async function importCsv(file: File | undefined) {
    if (!file) return;
    setError(null);
    let parsed: ParsedCsvDish[];
    try {
      const records = readCsvRecords(await file.text());
      if (!records.length) throw new Error("The CSV file is empty.");
      const first = records[0].map((header) => normalizedCsvHeader(header, languages));
      const hasHeader = ["category", "name", "price"].every((key) => first.includes(key));
      const headers = hasHeader ? new Map(first.map((key, index) => [key, index])) : new Map<string, number>();
      if (hasHeader && headers.size !== first.length) throw new Error("CSV column names must be unique.");
      if (hasHeader) {
        const supported = new Set([
          "category", "category_kind", "name", "price", "original_price", "veg", "description",
          "variant_name", "variant_price", "variant_original_price",
          ...languages.flatMap((language) => [`category_${language}`, `name_${language}`, `description_${language}`]),
        ]);
        const unknown = [...headers.keys()].filter((header) => !supported.has(header));
        if (unknown.length) throw new Error(`Unsupported CSV columns: ${unknown.join(", ")}.`);
      } else if (records.some((row) => row.length > 5)) {
        throw new Error("Headerless CSV supports only category, name, price, vegetarian, and description. Use the downloaded template for variants and translations.");
      }
      const rows = hasHeader ? records.slice(1) : records;
      const value = (row: string[], key: string, legacyIndex?: number) =>
        (hasHeader ? row[headers.get(key) ?? -1] : legacyIndex === undefined ? "" : row[legacyIndex])?.trim().replace(/^\uFEFF/, "") ?? "";
      parsed = rows.map((row, index) => {
        if (hasHeader && row.length > first.length) throw new Error(`CSV row ${index + 2} has more cells than the header.`);
        const rowNumber = index + (hasHeader ? 2 : 1);
        const category = value(row, "category", 0);
        const name = value(row, "name", 1);
        const rawPrice = value(row, "price", 2).replace(/[₹,]/g, "");
        const price = toPaise(rawPrice);
        if (!category || !name || price === null || price > 10_000_000) {
          throw new Error(`CSV row ${rowNumber} needs a category, dish name, and valid selling price.`);
        }
        const rawKind = value(row, "category_kind").toLowerCase();
        if (rawKind && rawKind !== "food" && rawKind !== "beverage") throw new Error(`CSV row ${rowNumber} category type must be food or beverage.`);
        const parseOptionalPrice = (key: string) => {
          const raw = value(row, key).replace(/[₹,]/g, "");
          if (!raw) return null;
          const amount = toPaise(raw);
          if (amount === null || amount > 10_000_000) throw new Error(`CSV row ${rowNumber} has an invalid ${key.replaceAll("_", " ")}.`);
          return amount;
        };
        const originalPrice = parseOptionalPrice("original_price");
        if (originalPrice !== null && originalPrice <= price) throw new Error(`CSV row ${rowNumber}: original/list price must exceed selling price.`);
        const vegRaw = value(row, "veg", 3).toLowerCase();
        const vegMap: Record<string, boolean> = { y: true, yes: true, veg: true, true: true, "1": true, n: false, no: false, "non-veg": false, nonveg: false, "non veg": false, false: false, "0": false };
        if (vegRaw && vegMap[vegRaw] === undefined) throw new Error(`CSV row ${rowNumber} has an invalid vegetarian value; use yes or no.`);
        const variantName = value(row, "variant_name") || null;
        const variantPriceRaw = value(row, "variant_price");
        if (Boolean(variantName) !== Boolean(variantPriceRaw)) throw new Error(`CSV row ${rowNumber} needs both a variant name and variant selling price.`);
        const variantPrice = parseOptionalPrice("variant_price");
        const variantOriginalPrice = parseOptionalPrice("variant_original_price");
        if (variantOriginalPrice !== null && (variantPrice === null || variantOriginalPrice <= variantPrice)) {
          throw new Error(`CSV row ${rowNumber}: variant original/list price must exceed its selling price.`);
        }
        const localized = (prefix: "category" | "name" | "description") => Object.fromEntries(
          languages.flatMap((language) => {
            const translated = value(row, `${prefix}_${language}`);
            return translated ? [[language, translated]] : [];
          }),
        );
        return {
          category, kind: rawKind ? rawKind as "food" | "beverage" : null,
          categoryI18n: localized("category"), name, nameI18n: localized("name"), price, originalPrice,
          veg: vegRaw ? vegMap[vegRaw] : !/non[\s-]?veg/i.test(category),
          description: value(row, "description", 4), descriptionI18n: localized("description"),
          variantName, variantPrice, variantOriginalPrice,
        };
      });
      if (!parsed.length) throw new Error("The CSV has headers but no dish rows.");
    } catch (parseError) {
      setError(messageOf(parseError));
      return;
    }

    await mutate(async () => {
      const supabase = customerSupabase();
      const categoryGroups = new Map<string, ParsedCsvDish[]>();
      parsed.forEach((row) => {
        const key = row.category.trim().toLowerCase();
        categoryGroups.set(key, [...(categoryGroups.get(key) ?? []), row]);
      });
      const existing = new Map(data.categories.map((category) => [category.name.trim().toLowerCase(), category]));
      for (const [key, rows] of categoryGroups) {
        if (new Set(rows.map((row) => row.category.trim())).size > 1) throw new Error("Category names must match exactly, including capitalization.");
        const kinds = [...new Set(rows.map((row) => row.kind).filter((kind): kind is "food" | "beverage" => kind !== null))];
        const translations = new Map<string, string>();
        rows.forEach((row) => Object.entries(row.categoryI18n).forEach(([language, translation]) => {
          const previous = translations.get(language);
          if (previous && previous !== translation) throw new Error(`Category rows contain conflicting ${language} translations.`);
          translations.set(language, translation);
        }));
        if (kinds.length > 1) throw new Error("Category rows contain conflicting food/beverage types.");
        let category = existing.get(key);
        if (!category) {
          const { data: created, error: insertError } = await supabase.from("categories").insert({
            restaurant_id: restaurantId, name: rows[0].category.trim(), kind: kinds[0] ?? "food",
            name_i18n: Object.fromEntries(translations), sort: data.categories.length + existing.size,
          }).select("id,name,kind,sort,avail_from,avail_to,name_i18n").single();
          if (insertError) throw insertError;
          category = created as Category;
          existing.set(key, category);
        } else if (kinds.length || translations.size) {
          const { error: updateError } = await supabase.from("categories").update({
            ...(kinds.length ? { kind: kinds[0] } : {}),
            ...(translations.size ? { name_i18n: { ...category.name_i18n, ...Object.fromEntries(translations) } } : {}),
          }).eq("id", category.id).eq("restaurant_id", restaurantId);
          if (updateError) throw updateError;
        }
      }

      const variantGroups = new Map<string, ParsedCsvDish[]>();
      const plainRows: ParsedCsvDish[] = [];
      parsed.forEach((row) => {
        const key = `${row.category.trim().toLowerCase()}\u001f${row.name.trim().toLowerCase()}`;
        if (row.variantName) variantGroups.set(key, [...(variantGroups.get(key) ?? []), row]);
        else plainRows.push(row);
      });
      const variantKeys = new Set(variantGroups.keys());
      if (plainRows.some((row) => variantKeys.has(`${row.category.trim().toLowerCase()}\u001f${row.name.trim().toLowerCase()}`))) {
        throw new Error("A dish cannot mix a plain row with size/variant rows.");
      }
      const dishGroups = [...plainRows.map((row) => [row]), ...variantGroups.values()];
      let dishSort = data.dishes.length;
      for (const rows of dishGroups) {
        const base = rows[0];
        const consistency = (row: ParsedCsvDish) => JSON.stringify([
          row.category.trim(), row.name.trim(), row.price, row.originalPrice, row.veg, row.description,
          row.categoryI18n, row.nameI18n, row.descriptionI18n, row.kind,
        ]);
        if (rows.some((row) => consistency(row) !== consistency(base))) {
          throw new Error(`Repeated rows for "${base.name}" must repeat the same dish details and prices.`);
        }
        const variants = rows.filter((row) => row.variantName);
        if (new Set(variants.map((row) => row.variantName!.trim().toLowerCase())).size !== variants.length) {
          throw new Error(`Variant names for "${base.name}" must be unique.`);
        }
        const category = existing.get(base.category.trim().toLowerCase());
        if (!category) throw new Error(`Could not find category "${base.category}".`);
        const { data: created, error: dishError } = await supabase.from("dishes").insert({
          restaurant_id: restaurantId, category_id: category.id, name: base.name.trim(), base_price: base.price,
          original_price: base.originalPrice, description: base.description || null, is_veg: base.veg,
          name_i18n: Object.keys(base.nameI18n).length ? base.nameI18n : null,
          description_i18n: Object.keys(base.descriptionI18n).length ? base.descriptionI18n : null,
          sort: dishSort++,
        }).select("id").single();
        if (dishError) throw dishError;
        if (variants.length) {
          const { error: variantError } = await supabase.from("dish_variants").insert(variants.map((row) => ({
            restaurant_id: restaurantId, dish_id: created.id, name: row.variantName!.trim(),
            price: row.variantPrice!, original_price: row.variantOriginalPrice,
          })));
          if (variantError) throw variantError;
        }
      }
    }, `${parsed.length} menu row${parsed.length === 1 ? "" : "s"} imported.`);
  }

  if (loading) return <div className="portal-empty"><span className="portal-spinner" /> Loading restaurant menu…</div>;

  return <div className="portal-menu-manager">
    {(error || notice) && <p className={`portal-message ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error || notice}</p>}
    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Menu tools</h2><p>Import a CSV or download a full template with column names and examples.</p></div>
        <div className="portal-menu-tools">
          <label className="portal-secondary">Import CSV<input type="file" accept=".csv,text/csv" onChange={(event) => void importCsv(event.target.files?.[0])} hidden disabled={busy} /></label>
          <button className="portal-secondary" onClick={() => exportMenu(true)}>Download template</button>
          <button className="portal-secondary" onClick={() => exportMenu(false)}>Export menu CSV</button>
          <button className="portal-action-primary" onClick={() => resetDishEditor(null)} disabled={!data.categories.length || busy}>Add dish</button>
        </div>
      </div>
      {!data.categories.length && <p className="portal-form-hint">Create a category before adding menu dishes.</p>}
    </section>

    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Categories</h2><p>Categories may be limited to service hours and translated for guests.</p></div></div>
      <form className="portal-coupon-form" onSubmit={(event) => void saveCategory(event)}>
        <Field label="Category name" value={categoryName} onChange={setCategoryName} required />
        {!editingCategory && <label className="portal-field"><span>Category type</span><select value={categoryKind} onChange={(event) => setCategoryKind(event.target.value as "food" | "beverage")}><option value="food">Food</option><option value="beverage">Beverage</option></select></label>}
        {editingCategory && <>
          <Field label="Available from (optional)" value={categoryFrom} onChange={setCategoryFrom} type="time" />
          <Field label="Available until (optional)" value={categoryTo} onChange={setCategoryTo} type="time" />
          {languages.map((language) => <Field key={language} label={`Category in ${LANGUAGES[language] ?? language}`} value={categoryTranslations[language] ?? ""} onChange={(value) => setCategoryTranslations((current) => ({ ...current, [language]: value }))} />)}
        </>}
        <button className="portal-action-primary" disabled={busy}>{editingCategory ? "Save category" : "Add category"}</button>
        {editingCategory && <button className="portal-secondary" type="button" onClick={() => { setEditingCategory(null); setCategoryName(""); setCategoryTranslations({}); setCategoryFrom(""); setCategoryTo(""); }}>Cancel edit</button>}
      </form>
      <div className="portal-settings-list">{data.categories.map((category, index) => <article className="portal-setting-item" key={category.id}>
        <div><strong>{category.name}</strong><span>{category.kind}{category.avail_from && category.avail_to ? ` · ${category.avail_from.slice(0, 5)}–${category.avail_to.slice(0, 5)}` : ""} · {data.dishes.filter((dish) => dish.category_id === category.id).length} dishes</span></div>
        <button className="portal-secondary" aria-label={`Move ${category.name} up`} title="Move up" onClick={() => void moveCategory(category, -1)} disabled={busy || index === 0}>↑</button>
        <button className="portal-secondary" aria-label={`Move ${category.name} down`} title="Move down" onClick={() => void moveCategory(category, 1)} disabled={busy || index === data.categories.length - 1}>↓</button>
        <button className="portal-secondary" onClick={() => beginEditCategory(category)} disabled={busy}>Edit</button>
        <button className="portal-action-danger" onClick={() => void deleteCategory(category)} disabled={busy}>Delete</button>
      </article>)}</div>
    </section>

    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Dishes</h2><p>Menu availability is separate from online-order availability.</p></div></div>
      {data.categories.map((category) => {
        const rows = data.dishes.filter((dish) => dish.category_id === category.id);
        return <section className="portal-menu-category" key={category.id}>
          <h3>{category.name}</h3>
          {rows.length ? rows.map((dish, index) => {
            const variants = data.variants.filter((variant) => variant.dish_id === dish.id);
            return <article className="portal-dish-row portal-managed-dish" key={dish.id}>
              {dish.image_url && <img src={dish.image_url} alt="" />}
              <div><strong>{dish.name}</strong><span>{rupees(dish.base_price)}{dish.original_price ? ` · list ${rupees(dish.original_price)}` : ""} · {dish.is_veg ? "Veg" : "Non-veg"}{dish.stock_qty !== null ? ` · stock ${dish.stock_qty}` : ""}</span>
                {variants.length > 0 && <small>{variants.map((variant) => `${variant.name}: ${rupees(variant.price)}`).join(" · ")}</small>}
              </div>
              <label className="portal-switch"><input type="checkbox" checked={dish.is_available} onChange={(event) => void setDishFlag(dish, "is_available", event.target.checked)} disabled={busy} />Menu</label>
              <label className="portal-switch"><input type="checkbox" checked={dish.online_available} onChange={(event) => void setDishFlag(dish, "online_available", event.target.checked)} disabled={busy || !dish.is_available} />Online</label>
              <button className="portal-secondary" aria-label={`Move ${dish.name} up`} title="Move up" onClick={() => void moveDish(dish, -1)} disabled={busy || index === 0}>↑</button>
              <button className="portal-secondary" aria-label={`Move ${dish.name} down`} title="Move down" onClick={() => void moveDish(dish, 1)} disabled={busy || index === rows.length - 1}>↓</button>
              <button className="portal-secondary" onClick={() => resetDishEditor(dish)} disabled={busy}>Edit</button>
              <button className="portal-action-danger" onClick={() => void deleteDish(dish)} disabled={busy}>Remove</button>
            </article>;
          }) : <p className="portal-form-hint">No dishes in this category yet.</p>}
        </section>;
      })}
    </section>

    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Optional extras</h2><p>Minimum 0 keeps an add-on group optional for customers.</p></div></div>
      <form className="portal-coupon-form" onSubmit={(event) => void createGroup(event)}>
        <Field label="Group name" value={groupName} onChange={setGroupName} required />
        <Field label="Minimum selections" value={groupMin} onChange={setGroupMin} type="number" />
        <Field label="Maximum selections" value={groupMax} onChange={setGroupMax} type="number" />
        <button className="portal-action-primary" disabled={busy}>Add extra group</button>
      </form>
      {data.groups.map((group) => <section className="portal-addon-group" key={group.id}>
        <h3>{group.name} <small>{group.min_select} to {group.max_select} selections</small></h3>
        {data.addons.filter((addon) => addon.group_id === group.id).map((addon) => <div className="portal-setting-item" key={addon.id}>
          <div><strong>{addon.name}</strong><span>{rupees(addon.price)}</span></div>
          <label className="portal-switch"><input type="checkbox" checked={addon.is_available} onChange={(event) => void setAddonActive(addon, event.target.checked)} disabled={busy} />Available</label>
          <button className="portal-action-danger" onClick={() => {
            if (window.confirm(`Delete ${addon.name}?`)) void mutate(async () => {
              const { error: deleteError } = await customerSupabase().from("addons").delete().eq("id", addon.id).eq("restaurant_id", restaurantId);
              if (deleteError) throw deleteError;
            }, "Extra deleted.");
          }} disabled={busy}>Delete</button>
        </div>)}
        <div className="portal-addon-create">
          <Field label="Extra name" value={addonDrafts[group.id]?.name ?? ""} onChange={(name) => setAddonDrafts((current) => ({ ...current, [group.id]: { name, price: current[group.id]?.price ?? "" } }))} />
          <Field label="Price (₹)" value={addonDrafts[group.id]?.price ?? ""} onChange={(price) => setAddonDrafts((current) => ({ ...current, [group.id]: { name: current[group.id]?.name ?? "", price } }))} type="number" />
          <button className="portal-secondary" onClick={() => void addAddon(group.id)} disabled={busy}>Add extra</button>
        </div>
      </section>)}
    </section>

    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Buy N, get M offers</h2><p>Offers are applied automatically to eligible QR/table orders.</p></div></div>
      <form className="portal-coupon-form" onSubmit={(event) => void createOffer(event)}>
        <Field label="Offer name" value={offerName} onChange={setOfferName} required />
        <label className="portal-field"><span>Buy dish</span><select value={offerBuyDish} onChange={(event) => setOfferBuyDish(event.target.value)} required><option value="">Select dish</option>{data.dishes.map((dish) => <option key={dish.id} value={dish.id}>{dish.name}</option>)}</select></label>
        <Field label="Buy quantity" value={offerBuyQty} onChange={setOfferBuyQty} type="number" />
        <label className="portal-field"><span>Free dish</span><select value={offerRewardDish} onChange={(event) => setOfferRewardDish(event.target.value)} required><option value="">Select dish</option>{data.dishes.map((dish) => <option key={dish.id} value={dish.id}>{dish.name}</option>)}</select></label>
        <Field label="Free quantity" value={offerGetQty} onChange={setOfferGetQty} type="number" />
        <button className="portal-action-primary" disabled={busy}>Create offer</button>
      </form>
      {data.offers.map((offer) => <article className="portal-setting-item" key={offer.id}>
        <div><strong>{offer.name}</strong><span>Buy {offer.buy_qty} × {data.dishes.find((dish) => dish.id === offer.buy_dish_id)?.name ?? "dish"}, get {offer.get_qty} × {data.dishes.find((dish) => dish.id === offer.reward_dish_id)?.name ?? "dish"} free</span></div>
        <label className="portal-switch"><input type="checkbox" checked={offer.active} onChange={(event) => void setOfferActive(offer, event.target.checked)} disabled={busy} />Active</label>
        <button className="portal-action-danger" onClick={() => void removeOffer(offer)} disabled={busy}>Delete</button>
      </article>)}
    </section>

    {dishFormOpen && <div className="portal-modal-backdrop" role="presentation">
      <form className="portal-dish-editor" onSubmit={(event) => void saveDish(event)}>
        <header><div><h2>{editingDish ? "Edit dish" : "Add dish"}</h2><p>Set prices, variants, optional extras, and translations.</p></div><button type="button" className="portal-secondary" onClick={() => setDishFormOpen(false)}>Close</button></header>
        {editingDish?.image_url && <img src={editingDish.image_url} alt="" className="portal-dish-editor-image" />}
        <label className="portal-field"><span>Dish photo (optional)</span><input type="file" accept="image/*" onChange={(event) => setDishImage(event.target.files?.[0] ?? null)} /></label>
        <Field label="Dish name" value={dishName} onChange={setDishName} required />
        <label className="portal-field"><span>Category</span><select value={dishCategory} onChange={(event) => setDishCategory(event.target.value)} required>{data.categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
        <label className="portal-field"><span>Description</span><textarea rows={3} maxLength={500} value={dishDescription} onChange={(event) => setDishDescription(event.target.value)} /></label>
        <div className="portal-coupon-form">
          <Field label="Base price (₹)" value={dishPrice} onChange={setDishPrice} type="number" required />
          <Field label="Original price (₹, optional)" value={dishOriginalPrice} onChange={setDishOriginalPrice} type="number" />
          <Field label="Stock quantity (blank = unlimited)" value={dishStock} onChange={setDishStock} type="number" />
        </div>
        <label className="portal-settings-checkbox"><input type="checkbox" checked={dishVeg} onChange={(event) => setDishVeg(event.target.checked)} /> Vegetarian</label>
        <div className="portal-dish-variants">
          <div className="portal-section-title"><div><h3>Size variants</h3><p>When variants exist, customers must choose one.</p></div><button type="button" className="portal-secondary" onClick={() => setDishVariants((current) => [...current, { name: "", price: "", originalPrice: "" }])}>Add size</button></div>
          {dishVariants.map((variant, index) => <div className="portal-variant-row" key={`variant-${index}`}>
            <Field label="Size" value={variant.name} onChange={(name) => setDishVariants((current) => current.map((item, row) => row === index ? { ...item, name } : item))} />
            <Field label="Price (₹)" value={variant.price} onChange={(price) => setDishVariants((current) => current.map((item, row) => row === index ? { ...item, price } : item))} type="number" />
            <Field label="Original price (₹)" value={variant.originalPrice} onChange={(originalPrice) => setDishVariants((current) => current.map((item, row) => row === index ? { ...item, originalPrice } : item))} type="number" />
            <button type="button" className="portal-action-danger" onClick={() => setDishVariants((current) => current.filter((_, row) => row !== index))}>Remove</button>
          </div>)}
        </div>
        <fieldset className="portal-options"><legend>Extras shown on this dish</legend>{data.groups.length ? data.groups.map((group) => <label key={group.id}>
          <input type="checkbox" checked={dishGroupIds.includes(group.id)} onChange={(event) => setDishGroupIds((current) => event.target.checked ? [...current, group.id] : current.filter((id) => id !== group.id))} />
          {group.name} ({group.min_select} to {group.max_select}; 0 minimum is optional)
        </label>) : <span className="portal-form-hint">Create extra groups below to make them available here.</span>}</fieldset>
        {languages.map((language) => <div className="portal-coupon-form" key={language}>
          <Field label={`Name in ${LANGUAGES[language] ?? language}`} value={translations[language] ?? ""} onChange={(value) => setTranslations((current) => ({ ...current, [language]: value }))} />
          <Field label={`Description in ${LANGUAGES[language] ?? language}`} value={translations[`description_${language}`] ?? ""} onChange={(value) => setTranslations((current) => ({ ...current, [`description_${language}`]: value }))} />
        </div>)}
        <footer><button type="button" className="portal-secondary" onClick={() => setDishFormOpen(false)}>Cancel</button><button className="portal-action-primary" disabled={busy}>{busy ? "Saving…" : "Save dish"}</button></footer>
      </form>
    </div>}
  </div>;
}
