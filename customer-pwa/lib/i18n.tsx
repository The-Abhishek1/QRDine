"use client";
import { createContext, useContext } from "react";
import type { I18n } from "./types";

export const LANGS: Record<string, string> = { en: "English", hi: "हिन्दी", kn: "ಕನ್ನಡ", mr: "मराठी", ta: "தமிழ்", te: "తెలుగు" };

type Row = { en: string; hi?: string; kn?: string };
// Buttons and labels. Dish and category names can be in any language the hotel types in. Marathi, Tamil and Telugu fall back to English labels.
const S: Record<string, Row> = {
  menu: { en: "Menu", hi: "मेन्यू", kn: "ಮೆನು" },
  myOrders: { en: "My orders", hi: "मेरे ऑर्डर", kn: "ನನ್ನ ಆರ್ಡರ್‌ಗಳು" },
  search: { en: "Search the menu", hi: "मेन्यू खोजें", kn: "ಮೆನು ಹುಡುಕಿ" },
  vegOnly: { en: "Veg only", hi: "सिर्फ़ शाकाहारी", kn: "ಕೇವಲ ಶಾಕಾಹಾರ" },
  add: { en: "Add", hi: "जोड़ें", kn: "ಸೇರಿಸಿ" },
  soldOut: { en: "Sold out", hi: "खत्म", kn: "ಖಾಲಿಯಾಗಿದೆ" },
  notNow: { en: "Not available now", hi: "अभी उपलब्ध नहीं", kn: "ಈಗ ಲಭ್ಯವಿಲ್ಲ" },
  available: { en: "Available", hi: "उपलब्ध", kn: "ಲಭ್ಯ" },
  bestseller: { en: "Bestseller", hi: "सबसे ज़्यादा बिकने वाला", kn: "ಹೆಚ್ಚು ಮಾರಾಟವಾಗುವುದು" },
  onlyLeft: { en: "Only {n} left", hi: "सिर्फ़ {n} बचे", kn: "ಕೇವಲ {n} ಉಳಿದಿದೆ" },
  inCart: { en: "{n} in order", hi: "ऑर्डर में {n}", kn: "ಆರ್ಡರ್‌ನಲ್ಲಿ {n}" },
  items: { en: "items", hi: "आइटम", kn: "ಐಟಂಗಳು" },
  item: { en: "item", hi: "आइटम", kn: "ಐಟಂ" },
  viewOrder: { en: "View order", hi: "ऑर्डर देखें", kn: "ಆರ್ಡರ್ ನೋಡಿ" },
  yourOrder: { en: "Your order", hi: "आपका ऑर्डर", kn: "ನಿಮ್ಮ ಆರ್ಡರ್" },
  close: { en: "Close", hi: "बंद करें", kn: "ಮುಚ್ಚಿ" },
  eatHere: { en: "Eat here", hi: "यहीं खाना", kn: "ಇಲ್ಲಿ ತಿನ್ನುವುದು" },
  parcel: { en: "Parcel", hi: "पार्सल", kn: "ಪಾರ್ಸೆಲ್" },
  kitchenNote: { en: "Note for the kitchen (optional)", hi: "रसोई के लिए नोट (वैकल्पिक)", kn: "ಅಡುಗೆಮನೆಗೆ ಟಿಪ್ಪಣಿ (ಐಚ್ಛಿಕ)" },
  subtotal: { en: "Subtotal", hi: "उप-कुल", kn: "ಉಪಮೊತ್ತ" },
  taxesNote: { en: "Taxes and any coupon are added on your final bill.", hi: "टैक्स और कूपन अंतिम बिल में जुड़ेंगे।", kn: "ತೆರಿಗೆ ಮತ್ತು ಕೂಪನ್ ಅಂತಿಮ ಬಿಲ್‌ನಲ್ಲಿ ಸೇರುತ್ತದೆ." },
  placeOrder: { en: "Place order", hi: "ऑर्डर करें", kn: "ಆರ್ಡರ್ ಮಾಡಿ" },
  sending: { en: "Sending…", hi: "भेज रहे हैं…", kn: "ಕಳುಹಿಸಲಾಗುತ್ತಿದೆ…" },
  callWaiter: { en: "Call waiter", hi: "वेटर बुलाएँ", kn: "ವೇಟರ್ ಕರೆಯಿರಿ" },
  waiterComing: { en: "Waiter is coming", hi: "वेटर आ रहे हैं", kn: "ವೇಟರ್ ಬರುತ್ತಿದ್ದಾರೆ" },
  requestBill: { en: "Request bill", hi: "बिल माँगें", kn: "ಬಿಲ್ ಕೇಳಿ" },
  billRequested: { en: "Bill requested", hi: "बिल माँगा गया", kn: "ಬಿಲ್ ಕೇಳಲಾಗಿದೆ" },
  billWhenServed: { en: "You can request the bill once all your dishes are served.", hi: "सभी व्यंजन परोसे जाने के बाद बिल माँग सकते हैं।", kn: "ಎಲ್ಲಾ ಖಾದ್ಯಗಳನ್ನು ಬಡಿಸಿದ ನಂತರ ಬಿಲ್ ಕೇಳಬಹುದು." },
  yourBill: { en: "Your bill", hi: "आपका बिल", kn: "ನಿಮ್ಮ ಬಿಲ್" },
  total: { en: "Total", hi: "कुल", kn: "ಒಟ್ಟು" },
  payUpi: { en: "Pay {amount} with UPI", hi: "UPI से {amount} चुकाएँ", kn: "UPI ಮೂಲಕ {amount} ಪಾವತಿಸಿ" },
  couponHave: { en: "Have a coupon?", hi: "कूपन है?", kn: "ಕೂಪನ್ ಇದೆಯೇ?" },
  couponApply: { en: "Apply", hi: "लगाएँ", kn: "ಅನ್ವಯಿಸಿ" },
  couponApplied: { en: "Coupon {code} applied", hi: "कूपन {code} लगा", kn: "ಕೂಪನ್ {code} ಅನ್ವಯಿಸಲಾಗಿದೆ" },
  couponBad: { en: "That coupon does not apply to this bill.", hi: "यह कूपन इस बिल पर लागू नहीं है।", kn: "ಈ ಕೂಪನ್ ಈ ಬಿಲ್‌ಗೆ ಅನ್ವಯಿಸುವುದಿಲ್ಲ." },
  orderN: { en: "Order {n}", hi: "ऑर्डर {n}", kn: "ಆರ್ಡರ್ {n}" },
  waitingAccept: { en: "Waiting for the hotel to accept", hi: "होटल की मंज़ूरी का इंतज़ार", kn: "ಹೋಟೆಲ್ ಒಪ್ಪಿಗೆಗಾಗಿ ಕಾಯುತ್ತಿದೆ" },
  beingPrepared: { en: "Being prepared", hi: "तैयार हो रहा है", kn: "ತಯಾರಾಗುತ್ತಿದೆ" },
  ready: { en: "Ready! It is on its way to your table", hi: "तैयार है! आपकी टेबल पर आ रहा है", kn: "ಸಿದ್ಧವಾಗಿದೆ! ನಿಮ್ಮ ಟೇಬಲ್‌ಗೆ ಬರುತ್ತಿದೆ" },
  served: { en: "Served. Enjoy your meal", hi: "परोस दिया गया। आनंद लें", kn: "ಬಡಿಸಲಾಗಿದೆ. ಆನಂದಿಸಿ" },
  notAccepted: { en: "Not accepted", hi: "स्वीकार नहीं हुआ", kn: "ಒಪ್ಪಿಕೊಂಡಿಲ್ಲ" },
  cancelled: { en: "Cancelled", hi: "रद्द", kn: "ರದ್ದಾಗಿದೆ" },
  chooseOne: { en: "Choose one", hi: "एक चुनें", kn: "ಒಂದನ್ನು ಆರಿಸಿ" },
  chooseUpTo: { en: "Choose up to {n}", hi: "अधिकतम {n} चुनें", kn: "ಗರಿಷ್ಠ {n} ಆರಿಸಿ" },
  required: { en: "Required", hi: "ज़रूरी", kn: "ಕಡ್ಡಾಯ" },
  instructions: { en: "Special instructions (optional)", hi: "विशेष निर्देश (वैकल्पिक)", kn: "ವಿಶೇಷ ಸೂಚನೆಗಳು (ಐಚ್ಛಿಕ)" },
  addToOrder: { en: "Add to order", hi: "ऑर्डर में जोड़ें", kn: "ಆರ್ಡರ್‌ಗೆ ಸೇರಿಸಿ" },
  alsoOrder: { en: "People also order", hi: "लोग ये भी मँगाते हैं", kn: "ಜನರು ಇದನ್ನೂ ಆರ್ಡರ್ ಮಾಡುತ್ತಾರೆ" },
  thanks: { en: "Thank you!", hi: "धन्यवाद!", kn: "ಧನ್ಯವಾದಗಳು!" },
  rateVisit: { en: "How was your visit?", hi: "आपका अनुभव कैसा रहा?", kn: "ನಿಮ್ಮ ಭೇಟಿ ಹೇಗಿತ್ತು?" },
  food: { en: "Food", hi: "खाना", kn: "ಆಹಾರ" },
  service: { en: "Service", hi: "सेवा", kn: "ಸೇವೆ" },
  sendFeedback: { en: "Send feedback", hi: "फ़ीडबैक भेजें", kn: "ಪ್ರತಿಕ್ರಿಯೆ ಕಳುಹಿಸಿ" },
};

export function format(tpl: string, vars?: Record<string, string | number>) {
  return vars ? tpl.replace(/\{(\w+)\}/g, (_, k) => String(vars[k] ?? "")) : tpl;
}

export type I18nCtx = { lang: string; t: (key: keyof typeof S | string, vars?: Record<string, string | number>) => string; pick: (base: string, tr: I18n) => string };

export function makeI18n(lang: string): I18nCtx {
  return {
    lang,
    t: (key, vars) => { const r = S[key]; return format((r && ((r as Record<string, string | undefined>)[lang] ?? r.en)) || String(key), vars); },
    pick: (base, tr) => (tr && lang !== "en" && tr[lang]?.trim()) || base,
  };
}

export const I18nContext = createContext<I18nCtx>(makeI18n("en"));
export const useI18n = () => useContext(I18nContext);
