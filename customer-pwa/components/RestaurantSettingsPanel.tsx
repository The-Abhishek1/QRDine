"use client";

import { useCallback, useEffect, useState } from "react";
import { customerSupabase } from "@/lib/customerSupabase";
import { rupees } from "@/lib/money";
import { playOwnerAlertSound } from "@/lib/ownerAlerts";
import { isWebPushConfigured, registerWebPush, unregisterWebPush } from "@/lib/webPush";

type SettingsRestaurant = {
  id: string;
  name: string;
  phone: string | null;
  address: string | null;
  fssai: string | null;
  logo_url: string | null;
  gst_mode: "none" | "regular" | "composition";
  gst_rate: number;
  gstin: string | null;
  service_charge_pct: number;
  upi_id: string | null;
  mode: "staff_activated" | "open";
  is_open: boolean;
  languages: string[];
  online_orders_enabled: boolean;
};

type Coupon = {
  id: string;
  code: string;
  type: "percent" | "flat";
  value: number;
  min_bill: number;
  max_uses: number | null;
  used_count: number;
  active: boolean;
};

type StaffMember = {
  user_id: string;
  email: string | null;
  name: string | null;
  role: "waiter" | "kitchen";
  active: boolean;
};

type Announcement = { id: string; text: string };

const LANGUAGES: Record<string, string> = {
  hi: "Hindi",
  kn: "Kannada",
  ta: "Tamil",
  te: "Telugu",
  bn: "Bengali",
};

function localDate(): string {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function amountToPaise(value: string): number | null {
  const match = /^(?:\d{1,8})(?:\.\d{1,2})?$/.test(value.trim());
  if (!match) return null;
  const amount = Number(value);
  return Number.isFinite(amount) ? Math.round(amount * 100) : null;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "The request failed. Please try again.";
}

function SettingField({
  label, value, onChange, type = "text", hint, required = false,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  hint?: string;
  required?: boolean;
}) {
  return <label className="portal-field">
    <span>{label}{required ? " *" : ""}</span>
    <input type={type} value={value} onChange={(event) => onChange(event.target.value)} required={required} />
    {hint && <small className="portal-setting-hint">{hint}</small>}
  </label>;
}

function Section({
  title, detail, children,
}: {
  title: string;
  detail?: string;
  children: React.ReactNode;
}) {
  return <section className="portal-panel">
    <div className="portal-section-title"><div><h2>{title}</h2>{detail && <p>{detail}</p>}</div></div>
    {children}
  </section>;
}

export default function RestaurantSettingsPanel({
  restaurant,
  email,
  busy,
  codAvailable,
  onRefresh,
}: {
  restaurant: SettingsRestaurant;
  email: string;
  busy: boolean;
  codAvailable: boolean;
  onRefresh: () => Promise<void>;
}) {
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState(restaurant.name);
  const [phone, setPhone] = useState(restaurant.phone ?? "");
  const [address, setAddress] = useState(restaurant.address ?? "");
  const [fssai, setFssai] = useState(restaurant.fssai ?? "");
  const [gstMode, setGstMode] = useState(restaurant.gst_mode);
  const [gstRate, setGstRate] = useState(String(restaurant.gst_rate));
  const [gstin, setGstin] = useState(restaurant.gstin ?? "");
  const [serviceCharge, setServiceCharge] = useState(String(restaurant.service_charge_pct));
  const [upi, setUpi] = useState(restaurant.upi_id ?? "");
  const [orderingMode, setOrderingMode] = useState(restaurant.mode);
  const [languages, setLanguages] = useState<string[]>(restaurant.languages ?? []);
  const [announcement, setAnnouncement] = useState("");
  const [announcementId, setAnnouncementId] = useState<string | null>(null);
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [staff, setStaff] = useState<StaffMember[]>([]);
  const [pinExists, setPinExists] = useState<boolean | null>(null);
  const [currentPin, setCurrentPin] = useState("");
  const [newPin, setNewPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [staffName, setStaffName] = useState("");
  const [staffEmail, setStaffEmail] = useState("");
  const [staffPassword, setStaffPassword] = useState("");
  const [staffRole, setStaffRole] = useState<"waiter" | "kitchen">("waiter");
  const [couponCode, setCouponCode] = useState("");
  const [couponType, setCouponType] = useState<"percent" | "flat">("percent");
  const [couponValue, setCouponValue] = useState("");
  const [couponMinimum, setCouponMinimum] = useState("");
  const [couponMaxUses, setCouponMaxUses] = useState("");
  const [fileInputKey, setFileInputKey] = useState(0);
  const [alertSoundEnabled, setAlertSoundEnabled] = useState(false);
  const [webPushEnabled, setWebPushEnabled] = useState(false);
  const [printerWidth, setPrinterWidth] = useState<"58" | "80">("58");

  const run = useCallback(async (action: () => Promise<void>, success?: string) => {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      await action();
      await onRefresh();
      if (success) setMessage(success);
    } catch (actionError) {
      setError(messageOf(actionError));
    } finally {
      setSaving(false);
    }
  }, [onRefresh]);

  const loadExtras = useCallback(async () => {
    const supabase = customerSupabase();
    const [{ data: announcementRows, error: announcementError }, { data: couponRows, error: couponError },
      { data: staffRows, error: staffError }, { data: pinData, error: pinError }] = await Promise.all([
      supabase.from("announcements").select("id,text").eq("restaurant_id", restaurant.id).eq("show_date", localDate()).limit(1),
      supabase.from("coupons").select("id,code,type,value,min_bill,max_uses,used_count,active").eq("restaurant_id", restaurant.id).order("code"),
      supabase.rpc("owner_staff"),
      supabase.rpc("has_manager_pin"),
    ]);
    const failure = announcementError || couponError || staffError || pinError;
    if (failure) throw failure;
    const currentAnnouncement = (announcementRows ?? [])[0] as Announcement | undefined;
    setAnnouncement(currentAnnouncement?.text ?? "");
    setAnnouncementId(currentAnnouncement?.id ?? null);
    setCoupons((couponRows ?? []) as Coupon[]);
    setStaff((staffRows ?? []) as StaffMember[]);
    setPinExists(Boolean(pinData));
  }, [restaurant.id]);

  useEffect(() => {
    void loadExtras().catch((loadError) => setError(messageOf(loadError)));
  }, [loadExtras]);

  useEffect(() => {
    setAlertSoundEnabled(window.localStorage.getItem("qrdine:restaurant-alert-sound") === "on");
    setWebPushEnabled(window.localStorage.getItem("qrdine:web-push-enabled") === "on");
    setPrinterWidth(window.localStorage.getItem("qrdine:printer-width") === "80" ? "80" : "58");
  }, []);

  const gstRateNumber = Number(gstRate);
  const serviceChargeNumber = Number(serviceCharge);
  const billingError = gstMode === "regular" && (!Number.isFinite(gstRateNumber) || gstRateNumber < 0 || gstRateNumber > 28)
    ? "GST rate must be between 0 and 28%."
    : !Number.isFinite(serviceChargeNumber) || serviceChargeNumber < 0 || serviceChargeNumber > 20
      ? "Service charge must be between 0 and 20%."
      : gstin.trim() && gstin.trim().length !== 15
        ? "GSTIN must have exactly 15 characters."
        : upi.trim() && !/^[A-Za-z0-9._-]{2,64}@[A-Za-z0-9.-]{2,64}$/.test(upi.trim())
          ? "Enter a valid UPI ID, such as restaurant@bank."
          : null;

  async function saveProfile(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await run(async () => {
      const { error: saveError } = await customerSupabase().from("restaurants").update({
        name: name.trim(), phone: phone.trim() || null, address: address.trim() || null, fssai: fssai.trim() || null,
      }).eq("id", restaurant.id);
      if (saveError) throw saveError;
    }, "Restaurant details saved.");
  }

  async function saveBilling(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (billingError) { setError(billingError); return; }
    await run(async () => {
      const { error: saveError } = await customerSupabase().from("restaurants").update({
        gst_mode: gstMode,
        gst_rate: gstMode === "regular" ? gstRateNumber : 0,
        gstin: gstin.trim().toUpperCase() || null,
        service_charge_pct: serviceChargeNumber,
        upi_id: upi.trim() || null,
      }).eq("id", restaurant.id);
      if (saveError) throw saveError;
    }, "Billing, tax, and UPI settings saved.");
  }

  async function setRestaurantOpen(open: boolean) {
    await run(async () => {
      const { error: updateError } = await customerSupabase().from("restaurants").update({ is_open: open }).eq("id", restaurant.id);
      if (updateError) throw updateError;
    }, open ? "Restaurant is open for table orders." : "Restaurant is closed for new table orders.");
  }

  async function setQrMode(mode: "open" | "staff_activated") {
    await run(async () => {
      const { error: updateError } = await customerSupabase().from("restaurants").update({ mode }).eq("id", restaurant.id);
      if (updateError) throw updateError;
      setOrderingMode(mode);
    }, "Table ordering mode saved.");
  }

  async function saveLanguages() {
    await run(async () => {
      const { error: updateError } = await customerSupabase().from("restaurants").update({ languages }).eq("id", restaurant.id);
      if (updateError) throw updateError;
    }, "Guest languages saved.");
  }

  async function saveAnnouncement() {
    await run(async () => {
      const supabase = customerSupabase();
      if (announcementId) {
        if (announcement.trim()) {
          const { error: updateError } = await supabase.from("announcements").update({ text: announcement.trim() }).eq("id", announcementId);
          if (updateError) throw updateError;
        } else {
          const { error: deleteError } = await supabase.from("announcements").delete().eq("id", announcementId);
          if (deleteError) throw deleteError;
        }
      } else if (announcement.trim()) {
        const { error: insertError } = await supabase.from("announcements").insert({
          restaurant_id: restaurant.id, text: announcement.trim(), show_date: localDate(),
        });
        if (insertError) throw insertError;
      }
      await loadExtras();
    }, announcement.trim() ? "Today's guest announcement saved." : "Today's announcement removed.");
  }

  async function addCoupon(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const amount = Number(couponValue);
    const minimumPaise = amountToPaise(couponMinimum || "0");
    const value = couponType === "percent" ? amount : amountToPaise(couponValue);
    if (!couponCode.trim() || !Number.isInteger(amount) || amount <= 0 ||
      (couponType === "percent" && amount > 100) || minimumPaise === null || value === null ||
      (couponMaxUses && (!Number.isInteger(Number(couponMaxUses)) || Number(couponMaxUses) <= 0))) {
      setError("Enter a valid coupon code, discount, minimum bill, and use limit.");
      return;
    }
    await run(async () => {
      const { error: insertError } = await customerSupabase().from("coupons").insert({
        restaurant_id: restaurant.id,
        code: couponCode.trim().toUpperCase(),
        type: couponType,
        value,
        min_bill: minimumPaise,
        max_uses: couponMaxUses ? Number(couponMaxUses) : null,
      });
      if (insertError) throw insertError;
      setCouponCode("");
      setCouponValue("");
      setCouponMinimum("");
      setCouponMaxUses("");
      await loadExtras();
    }, "Coupon created.");
  }

  async function updateCoupon(coupon: Coupon, changes: Partial<Coupon>) {
    await run(async () => {
      const { error: updateError } = await customerSupabase().from("coupons").update(changes)
        .eq("id", coupon.id).eq("restaurant_id", restaurant.id);
      if (updateError) throw updateError;
      await loadExtras();
    }, "Coupon updated.");
  }

  async function removeCoupon(coupon: Coupon) {
    const operation = coupon.used_count > 0
      ? customerSupabase().from("coupons").update({ active: false }).eq("id", coupon.id).eq("restaurant_id", restaurant.id)
      : customerSupabase().from("coupons").delete().eq("id", coupon.id).eq("restaurant_id", restaurant.id);
    await run(async () => {
      const { error: couponError } = await operation;
      if (couponError) throw couponError;
      await loadExtras();
    }, coupon.used_count > 0 ? "Used coupon deactivated and retained for bill history." : "Coupon deleted.");
  }

  async function submitStaffAction(body: Record<string, string | boolean>) {
    await run(async () => {
      const { data, error: invokeError } = await customerSupabase().functions.invoke("staff-admin", { body });
      if (invokeError) throw invokeError;
      if (!data?.ok) throw new Error(data?.error || "Staff account action failed.");
      setStaffName("");
      setStaffEmail("");
      setStaffPassword("");
      await loadExtras();
    }, "Staff account updated.");
  }

  async function saveManagerPin(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!/^\d{4,6}$/.test(newPin) || newPin !== confirmPin || (pinExists && !/^\d{4,6}$/.test(currentPin))) {
      setError(pinExists ? "Enter your current PIN and matching new PIN (4-6 digits)." : "Choose matching new PINs with 4-6 digits.");
      return;
    }
    await run(async () => {
      const { data, error: pinError } = await customerSupabase().rpc("set_manager_pin", {
        p_pin: newPin, p_old_pin: pinExists ? currentPin : null,
      });
      if (pinError) throw pinError;
      if (!data?.ok) throw new Error(data?.error || "Could not save the manager PIN.");
      setPinExists(true);
      setCurrentPin("");
      setNewPin("");
      setConfirmPin("");
    }, "Manager PIN saved.");
  }

  async function changePassword(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (password.length < 8 || password !== confirmPassword) {
      setError("Use a password of at least 8 characters and make sure both entries match.");
      return;
    }
    await run(async () => {
      const { error: passwordError } = await customerSupabase().auth.updateUser({ password });
      if (passwordError) throw passwordError;
      setPassword("");
      setConfirmPassword("");
    }, "Password changed.");
  }

  async function uploadLogo(file: File | undefined) {
    if (!file) return;
    if (!file.type.startsWith("image/") || file.size > 5 * 1024 * 1024) {
      setError("Choose an image file smaller than 5 MB.");
      setFileInputKey((key) => key + 1);
      return;
    }
    await run(async () => {
      const extension = file.name.split(".").pop()?.toLowerCase().replace(/[^a-z0-9]/g, "") || "jpg";
      const path = `${restaurant.id}/${crypto.randomUUID()}.${extension}`;
      const supabase = customerSupabase();
      const { error: uploadError } = await supabase.storage.from("dish-images").upload(path, file, { contentType: file.type, upsert: false });
      if (uploadError) throw uploadError;
      const { error: updateError } = await supabase.from("restaurants").update({ logo_url: supabase.storage.from("dish-images").getPublicUrl(path).data.publicUrl }).eq("id", restaurant.id);
      if (updateError) throw updateError;
      await onRefresh();
    }, "Restaurant photo updated.");
    setFileInputKey((key) => key + 1);
  }

  async function testBrowserAlert() {
    setError(null);
    setMessage(null);
    try {
      if (!("Notification" in window)) throw new Error("This browser does not support desktop notifications.");
      const permission = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
      if (permission !== "granted") throw new Error("Notification permission was not granted. Change it in your browser's site settings.");
      new Notification("QR Dine test alert", { body: "Browser notifications are enabled for this device." });
      setMessage("Test notification sent.");
    } catch (alertError) {
      setError(messageOf(alertError));
    }
  }

  function updateAlertSound(enabled: boolean) {
    setAlertSoundEnabled(enabled);
    window.localStorage.setItem("qrdine:restaurant-alert-sound", enabled ? "on" : "off");
    if (enabled) playOwnerAlertSound();
  }

  function printTestTicket() {
    const popup = window.open("", "_blank");
    if (!popup) {
      setError("Your browser blocked the printer test window. Allow pop-ups and try again.");
      return;
    }
    popup.onload = () => { popup.focus(); popup.print(); };
    popup.document.write(`<!doctype html><html><head><title>QR Dine printer test</title><style>@page{size:${printerWidth}mm auto;margin:3mm}body{font:14px Arial,sans-serif;color:#000;width:100%;margin:0;text-align:center}h1{font-size:20px}hr{border:0;border-top:1px dashed #000}</style></head><body><h1>QR Dine</h1><p>Printer test · ${printerWidth} mm</p><hr><p>Rs 1,234.00 · OK</p><p>If you can read this, printing works.</p><hr></body></html>`);
    popup.document.close();
  }

  async function toggleBackgroundAlerts() {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const supabase = customerSupabase();
      if (webPushEnabled) {
        const token = window.localStorage.getItem("qrdine:web-push-token");
        if (token) {
          const { error: deleteError } = await supabase.from("devices").delete()
            .eq("restaurant_id", restaurant.id).eq("fcm_token", token);
          if (deleteError) throw deleteError;
        }
        await unregisterWebPush();
        window.localStorage.removeItem("qrdine:web-push-token");
        window.localStorage.setItem("qrdine:web-push-enabled", "off");
        setWebPushEnabled(false);
        setMessage("Background order alerts are disabled on this browser.");
      } else {
        const { data: userData, error: userError } = await supabase.auth.getUser();
        if (userError) throw userError;
        if (!userData.user) throw new Error("Sign in again before enabling order notifications.");
        const token = await registerWebPush();
        const { error: saveError } = await supabase.from("devices").upsert({
          restaurant_id: restaurant.id, user_id: userData.user.id, fcm_token: token,
        }, { onConflict: "restaurant_id,fcm_token" });
        if (saveError) {
          await unregisterWebPush();
          throw saveError;
        }
        window.localStorage.setItem("qrdine:web-push-token", token);
        window.localStorage.setItem("qrdine:web-push-enabled", "on");
        setWebPushEnabled(true);
        setMessage("Background order alerts are enabled on this browser.");
      }
    } catch (pushError) {
      setError(messageOf(pushError));
    } finally {
      setSaving(false);
    }
  }

  return <div className="portal-settings-layout portal-settings-full">
    {(error || message) && <div className={`portal-settings-notice portal-message ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error || message}</div>}
    <Section title="Restaurant profile" detail="These details appear on your customer menu and printed bills.">
      <form className="portal-form-grid" onSubmit={(event) => void saveProfile(event)}>
        {restaurant.logo_url && <img className="portal-logo-preview" src={restaurant.logo_url} alt={`${restaurant.name} logo`} />}
        <label className="portal-field"><span>Restaurant photo / logo</span><input key={fileInputKey} type="file" accept="image/*" onChange={(event) => void uploadLogo(event.target.files?.[0])} /></label>
        <SettingField label="Restaurant name" value={name} onChange={setName} required />
        <SettingField label="Restaurant phone" value={phone} onChange={setPhone} type="tel" />
        <SettingField label="Restaurant address" value={address} onChange={setAddress} />
        <SettingField label="FSSAI licence number" value={fssai} onChange={setFssai} />
        <button className="portal-action-primary" disabled={saving || busy}>Save profile</button>
      </form>
    </Section>

    <Section title="Billing, tax, and UPI" detail="Applied to table bills; UPI is also used for customer online orders.">
      <form className="portal-form-grid" onSubmit={(event) => void saveBilling(event)}>
        <fieldset className="portal-options">
          <legend>GST mode</legend>
          {(["none", "regular", "composition"] as const).map((value) => <label key={value}>
            <input type="radio" name="gst-mode" checked={gstMode === value} onChange={() => setGstMode(value)} />
            {value === "none" ? "No GST" : value === "regular" ? "Regular GST" : "Composition"}
          </label>)}
        </fieldset>
        {gstMode === "regular" && <SettingField label="GST rate (%)" value={gstRate} onChange={setGstRate} type="number" />}
        <SettingField label="GSTIN (optional)" value={gstin} onChange={(value) => setGstin(value.toUpperCase().slice(0, 15))} />
        <SettingField label="Service charge (%)" value={serviceCharge} onChange={setServiceCharge} type="number" hint="Guests may ask you to remove a voluntary service charge." />
        <SettingField label="UPI ID" value={upi} onChange={setUpi} hint="Verify payments in your UPI app; entering an ID does not verify payment." />
        {billingError && <p className="portal-message error">{billingError}</p>}
        <button className="portal-action-primary" disabled={saving || busy || Boolean(billingError)}>Save billing settings</button>
      </form>
    </Section>

    <Section title="Table ordering" detail="Control how guest QR codes open table sessions.">
      <div className="portal-settings-toggle">
        <div><strong>{restaurant.is_open ? "Restaurant is open" : "Restaurant is closed"}</strong><span>Open / close QR table ordering for guests.</span></div>
        <button className={restaurant.is_open ? "portal-action-danger" : "portal-action-primary"} onClick={() => void setRestaurantOpen(!restaurant.is_open)} disabled={saving || busy}>{restaurant.is_open ? "Close restaurant" : "Open restaurant"}</button>
      </div>
      <fieldset className="portal-options">
        <legend>How QR tables open</legend>
        <label><input type="radio" name="qr-mode" checked={orderingMode === "staff_activated"} onChange={() => void setQrMode("staff_activated")} disabled={saving || busy} /> Staff opens each table (recommended)</label>
        <label><input type="radio" name="qr-mode" checked={orderingMode === "open"} onChange={() => void setQrMode("open")} disabled={saving || busy} /> Guest QR scan opens the table automatically</label>
      </fieldset>
      <p className="portal-form-hint">Open ordering is less secure if QR codes are copied or photographed. Orders still require restaurant approval.</p>
    </Section>

    <Section title="Guest languages" detail="Choose the menu languages customers can switch to. Add translations to dishes and categories in Menu.">
      <div className="portal-settings-options">
        {Object.entries(LANGUAGES).map(([code, label]) => <label key={code}><input type="checkbox" checked={languages.includes(code)} onChange={(event) => setLanguages((current) => event.target.checked ? [...current, code] : current.filter((value) => value !== code))} />{label}</label>)}
      </div>
      <button className="portal-action-primary" onClick={() => void saveLanguages()} disabled={saving || busy}>Save languages</button>
    </Section>

    <Section title="Announcement for guests" detail="A message displayed at the top of today's guest menu.">
      <label className="portal-field"><span>Today's announcement</span><textarea rows={3} maxLength={300} value={announcement} onChange={(event) => setAnnouncement(event.target.value)} placeholder="For example: Kitchen closes at 10 pm." /></label>
      <div className="portal-settings-row"><small>{announcement.length}/300 characters</small><button className="portal-action-primary" onClick={() => void saveAnnouncement()} disabled={saving || busy}>Save announcement</button></div>
    </Section>

    <Section title="Coupons" detail="Customers provide coupon codes; apply them when settling table bills.">
      <form className="portal-coupon-form" onSubmit={(event) => void addCoupon(event)}>
        <SettingField label="Coupon code" value={couponCode} onChange={(value) => setCouponCode(value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 20))} required />
        <label className="portal-field"><span>Discount type</span><select value={couponType} onChange={(event) => setCouponType(event.target.value as "percent" | "flat")}><option value="percent">Percent off</option><option value="flat">Amount off</option></select></label>
        <SettingField label={couponType === "percent" ? "Percent off" : "Amount off (₹)"} value={couponValue} onChange={setCouponValue} type="number" required />
        <SettingField label="Minimum bill (₹, optional)" value={couponMinimum} onChange={setCouponMinimum} type="number" />
        <SettingField label="Maximum uses (optional)" value={couponMaxUses} onChange={setCouponMaxUses} type="number" />
        <button className="portal-action-primary" disabled={saving || busy}>Add coupon</button>
      </form>
      <div className="portal-settings-list">{coupons.map((coupon) => <article key={coupon.id} className="portal-setting-item">
        <div><strong>{coupon.code}</strong><span>{coupon.type === "percent" ? `${coupon.value}% off` : `${rupees(coupon.value)} off`} · used {coupon.used_count}{coupon.max_uses ? ` of ${coupon.max_uses}` : ""}{coupon.min_bill ? ` · min ${rupees(coupon.min_bill)}` : ""}</span></div>
        <label className="portal-switch"><input type="checkbox" checked={coupon.active} onChange={(event) => void updateCoupon(coupon, { active: event.target.checked })} disabled={saving || busy} />Active</label>
        <button className="portal-action-danger" onClick={() => void removeCoupon(coupon)} disabled={saving || busy}>{coupon.used_count ? "Deactivate" : "Delete"}</button>
      </article>)}</div>
    </Section>

    <Section title="Staff logins" detail="Waiters can manage service and payments. Kitchen accounts can process orders. Only owners can change settings and view insights.">
      <div className="portal-settings-list">{staff.map((member) => <article key={member.user_id} className="portal-setting-item">
        <div><strong>{member.name || member.email || "Staff account"}</strong><span>{member.role} · {member.email}</span></div>
        <label className="portal-switch"><input type="checkbox" checked={member.active} disabled={saving || busy} onChange={(event) => void submitStaffAction({ action: "set_active", user_id: member.user_id, active: event.target.checked })} />Active</label>
        <button className="portal-secondary" disabled={saving || busy} onClick={() => {
          const nextPassword = window.prompt(`New password for ${member.name || member.email} (8+ characters)`);
          if (nextPassword && nextPassword.length >= 8) void submitStaffAction({ action: "reset_password", user_id: member.user_id, new_password: nextPassword });
          else if (nextPassword) setError("Staff passwords must be at least 8 characters.");
        }}>Reset password</button>
        <button className="portal-action-danger" disabled={saving || busy} onClick={() => {
          if (window.confirm(`Remove ${member.name || member.email}'s staff login?`)) void submitStaffAction({ action: "delete", user_id: member.user_id });
        }}>Remove</button>
      </article>)}</div>
      <form className="portal-coupon-form" onSubmit={(event) => {
        event.preventDefault();
        if (staffPassword.length < 8) { setError("Staff passwords must be at least 8 characters."); return; }
        void submitStaffAction({ action: "create", name: staffName.trim(), email: staffEmail.trim(), password: staffPassword, role: staffRole });
      }}>
        <SettingField label="Staff name" value={staffName} onChange={setStaffName} required />
        <SettingField label="Staff email" value={staffEmail} onChange={setStaffEmail} type="email" required />
        <SettingField label="Temporary password (8+ characters)" value={staffPassword} onChange={setStaffPassword} type="password" required />
        <label className="portal-field"><span>Access role</span><select value={staffRole} onChange={(event) => setStaffRole(event.target.value as "waiter" | "kitchen")}><option value="waiter">Waiter</option><option value="kitchen">Kitchen</option></select></label>
        <button className="portal-action-primary" disabled={saving || busy}>Create staff login</button>
      </form>
    </Section>

    <Section title="Manager PIN" detail="A manager PIN is required to apply a manual discount to a table bill.">
      <form className="portal-form-grid" onSubmit={(event) => void saveManagerPin(event)}>
        {pinExists && <SettingField label="Current PIN" value={currentPin} onChange={setCurrentPin} type="password" required />}
        <SettingField label={pinExists ? "New PIN (4-6 digits)" : "PIN (4-6 digits)"} value={newPin} onChange={(value) => setNewPin(value.replace(/\D/g, "").slice(0, 6))} type="password" required />
        <SettingField label="Repeat PIN" value={confirmPin} onChange={(value) => setConfirmPin(value.replace(/\D/g, "").slice(0, 6))} type="password" required />
        <button className="portal-action-primary" disabled={saving || busy}>Save manager PIN</button>
      </form>
    </Section>

    <Section title="Browser alerts and receipts" detail="Browser permissions and print preferences are managed per browser/device.">
      <div className="portal-settings-toggle"><div><strong>Order notifications</strong><span>{typeof window !== "undefined" && "Notification" in window ? `Permission: ${Notification.permission}` : "Notifications are not supported in this browser."}</span></div><button className="portal-secondary" onClick={() => void testBrowserAlert()} disabled={saving || busy}>Send test notification</button></div>
      <div className="portal-settings-toggle"><div><strong>Alerts when this dashboard is closed</strong><span>{isWebPushConfigured() ? "Register this browser to receive new-order push alerts." : "Firebase web messaging is not configured for this deployment."}</span></div><button className={webPushEnabled ? "portal-action-danger" : "portal-secondary"} onClick={() => void toggleBackgroundAlerts()} disabled={saving || busy || (!webPushEnabled && !isWebPushConfigured())}>{webPushEnabled ? "Disable background alerts" : "Enable background alerts"}</button></div>
      <div className="portal-settings-toggle"><div><strong>Alert readiness</strong><span>
        {typeof window === "undefined" ? "Checking browser support…" : [
          window.isSecureContext ? "Secure connection: ready" : "Secure connection: required for notifications",
          "Notification" in window ? `Browser notifications: ${Notification.permission}` : "Browser notifications: unsupported",
          isWebPushConfigured() ? (window.localStorage.getItem("qrdine:web-push-token") ? "Background push: registered on this browser" : "Background push: configured but not registered") : "Background push: deployment configuration missing",
        ].join(" · ")}
      </span></div></div>
      <fieldset className="portal-options portal-settings-options">
        <legend>Play a sound for new orders</legend>
        <label><input type="radio" name="owner-alert-sound" checked={alertSoundEnabled} onChange={() => updateAlertSound(true)} /> On</label>
        <label><input type="radio" name="owner-alert-sound" checked={!alertSoundEnabled} onChange={() => updateAlertSound(false)} /> Off</label>
      </fieldset>
      <p className="portal-form-hint">Sound alerts work while this dashboard is open. Browser notification permission is controlled separately above.</p>
      <div className="portal-settings-toggle"><div><strong>Receipt and kitchen ticket paper width</strong><span>Used by the printable invoice and kitchen-ticket layouts; choose your printer destination in the system dialog.</span></div>
        <fieldset className="portal-options portal-settings-options">
          <legend>Paper width</legend>
          {(["58", "80"] as const).map((width) => <label key={width}><input type="radio" name="printer-width" checked={printerWidth === width} onChange={() => { setPrinterWidth(width); window.localStorage.setItem("qrdine:printer-width", width); }} />{width} mm</label>)}
        </fieldset>
      </div>
      <div className="portal-settings-toggle"><div><strong>Test receipt printer</strong><span>Choose a browser-connected printer in the print dialog, then check alignment and paper size.</span></div><button className="portal-secondary" onClick={printTestTicket}>Print test ticket</button></div>
    </Section>

    <Section title="Account" detail="Owner credentials and password.">
      <p className="portal-account-caption">Signed in as <strong>{email}</strong></p>
      <form className="portal-form-grid" onSubmit={(event) => void changePassword(event)}>
        <SettingField label="New password (at least 8 characters)" value={password} onChange={setPassword} type="password" required />
        <SettingField label="Confirm new password" value={confirmPassword} onChange={setConfirmPassword} type="password" required />
        <button className="portal-action-primary" disabled={saving || busy || password.length < 8 || password !== confirmPassword}>Change password</button>
      </form>
      <button className="portal-secondary" onClick={() => void onRefresh()} disabled={saving || busy}>Refresh restaurant settings</button>
    </Section>
  </div>;
}
