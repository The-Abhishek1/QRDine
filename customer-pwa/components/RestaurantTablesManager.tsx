"use client";

import QRCode from "qrcode";
import { useCallback, useEffect, useState } from "react";
import { customerSupabase } from "@/lib/customerSupabase";

type TableRow = { id: string; label: string; status: "closed" | "active" | "bill_requested"; active_session_id: string | null; qr_key_version: number };
type QrLink = { table_id: string; label: string; url: string };

function messageOf(error: unknown): string { return error instanceof Error ? error.message : "The request failed. Please try again."; }

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char] ?? char);
}

export default function RestaurantTablesManager({
  restaurantId,
  restaurantName,
  onRefresh,
  onOpenOrders,
}: {
  restaurantId: string;
  restaurantName: string;
  onRefresh: () => Promise<void>;
  onOpenOrders: () => void;
}) {
  const [tables, setTables] = useState<TableRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [count, setCount] = useState("5");
  const [pinTable, setPinTable] = useState<TableRow | null>(null);
  const [pin, setPin] = useState("");
  const [moveTable, setMoveTable] = useState<TableRow | null>(null);
  const [qrRows, setQrRows] = useState<Array<QrLink & { image: string }>>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const { data, error: loadError } = await customerSupabase().from("dining_tables")
        .select("id,label,status,active_session_id,qr_key_version").eq("restaurant_id", restaurantId).order("label");
      if (loadError) throw loadError;
      setTables((data ?? []) as TableRow[]);
    } catch (loadError) {
      setError(messageOf(loadError));
    } finally {
      setLoading(false);
    }
  }, [restaurantId]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => { void refresh(); }, 10_000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  async function mutate(action: () => Promise<void>, success: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      await refresh();
      await onRefresh();
      setNotice(success);
    } catch (actionError) {
      setError(messageOf(actionError));
    } finally {
      setBusy(false);
    }
  }

  async function addTables(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const amount = Number(count);
    if (!Number.isInteger(amount) || amount < 1 || amount > 50) { setError("Add between 1 and 50 tables at a time."); return; }
    const highest = tables.reduce((max, table) => Math.max(max, Number(/(\d+)$/.exec(table.label)?.[1] ?? 0)), 0);
    await mutate(async () => {
      const rows = Array.from({ length: amount }, (_, index) => ({
        restaurant_id: restaurantId, label: `T${highest + index + 1}`,
      }));
      const { error: insertError } = await customerSupabase().from("dining_tables").insert(rows);
      if (insertError) throw insertError;
    }, `${amount} table${amount === 1 ? "" : "s"} added.`);
  }

  async function openTable(table: TableRow, tablePin?: string) {
    await mutate(async () => {
      const { error: openError } = await customerSupabase().rpc("open_table", {
        p_table_id: table.id, ...(tablePin ? { p_pin: tablePin } : {}),
      });
      if (openError) throw openError;
      setPinTable(null);
      setPin("");
    }, `${table.label} opened.`);
  }

  async function removeTable(table: TableRow) {
    if (!window.confirm(`Remove ${table.label}? Tables with a session or order cannot be removed.`)) return;
    await mutate(async () => {
      const { error: deleteError } = await customerSupabase().from("dining_tables").delete().eq("id", table.id).eq("restaurant_id", restaurantId);
      if (deleteError) throw deleteError;
    }, `${table.label} removed.`);
  }

  async function voidTable(table: TableRow) {
    if (!window.confirm(`Cancel the active session at ${table.label}?`)) return;
    await mutate(async () => {
      const { error: voidError } = await customerSupabase().rpc("void_session", { p_table_id: table.id });
      if (voidError) throw voidError;
    }, `${table.label} session cancelled.`);
  }

  async function moveOrMerge(from: TableRow, to: TableRow) {
    if (!from.active_session_id) return;
    await mutate(async () => {
      const { error: moveError } = to.status === "closed"
        ? await customerSupabase().rpc("move_session", { p_session_id: from.active_session_id, p_to_table_id: to.id })
        : await customerSupabase().rpc("merge_sessions", { p_from: from.active_session_id, p_into: to.active_session_id });
      if (moveError) throw moveError;
      setMoveTable(null);
    }, to.status === "closed" ? `Session moved to ${to.label}.` : `Sessions merged into ${to.label}.`);
  }

  async function replaceQrCodes() {
    if (!window.confirm("Replace QR codes for all tables? Previously printed QR codes will stop working.")) return;
    await mutate(async () => {
      for (const table of tables) {
        const { error: updateError } = await customerSupabase().from("dining_tables")
          .update({ qr_key_version: table.qr_key_version + 1 }).eq("id", table.id).eq("restaurant_id", restaurantId);
        if (updateError) throw updateError;
      }
    }, "QR codes replaced. Generate and print the new sheet.");
  }

  async function createQrSheet() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const { data, error: invokeError } = await customerSupabase().functions.invoke("qr-sheet");
      if (invokeError) throw invokeError;
      const links = data?.tables as QrLink[] | undefined;
      if (!links?.length) throw new Error("No table QR codes were returned. Add tables first.");
      const generated = await Promise.all(links.map(async (table) => ({
        ...table, image: await QRCode.toDataURL(table.url, { width: 220, margin: 1, errorCorrectionLevel: "M" }),
      })));
      setQrRows(generated);
    } catch (qrError) {
      setError(messageOf(qrError));
    } finally {
      setBusy(false);
    }
  }

  function printQrSheet() {
    const popup = window.open("", "_blank", "noopener,noreferrer,width=900,height=700");
    if (!popup) { setError("Allow pop-ups to print table QR codes."); return; }
    const cards = qrRows.map((table) => `<article class="table"><h2>${escapeHtml(table.label)}</h2><img src="${table.image}" alt="QR code for ${escapeHtml(table.label)}"><p>Scan to view the menu and order</p></article>`).join("");
    popup.document.write(`<!doctype html><html><head><title>${escapeHtml(restaurantName)} · Table QR codes</title><style>
      body{font-family:Arial,sans-serif;color:#14231f;margin:24px}h1{text-align:center}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}.table{break-inside:avoid;text-align:center;border:1px solid #d9e1dd;border-radius:12px;padding:14px}.table img{width:190px;height:190px}.table p{font-size:12px;color:#5b6b65}@media print{body{margin:10mm}.grid{gap:10px}}
      </style></head><body><h1>${escapeHtml(restaurantName)} · Table QR codes</h1><div class="grid">${cards}</div><script>window.onload=()=>window.print()</script></body></html>`);
    popup.document.close();
  }

  const openCount = tables.filter((table) => table.status !== "closed").length;
  return <div className="portal-tables-manager">
    {(error || notice) && <p className={`portal-message ${error ? "error" : "success"}`} role={error ? "alert" : "status"}>{error || notice}</p>}
    <div className="portal-metrics">
      <article><span>Total tables</span><strong>{tables.length}</strong><small>Active QR codes</small></article>
      <article><span>Open</span><strong>{openCount}</strong><small>Currently serving guests</small></article>
      <article><span>Bill requested</span><strong>{tables.filter((table) => table.status === "bill_requested").length}</strong><small>Ready for checkout</small></article>
    </div>
    <section className="portal-panel">
      <div className="portal-section-title"><div><h2>Floor controls</h2><p>Open sessions, move guests, and manage table QR codes.</p></div></div>
      <form className="portal-table-create" onSubmit={(event) => void addTables(event)}>
        <label className="portal-field"><span>Number of tables to add</span><input type="number" min="1" max="50" value={count} onChange={(event) => setCount(event.target.value)} /></label>
        <button className="portal-action-primary" disabled={busy}>Add tables</button>
        <button className="portal-secondary" type="button" onClick={() => void createQrSheet()} disabled={busy || !tables.length}>Create QR sheet</button>
        <button className="portal-action-danger" type="button" onClick={() => void replaceQrCodes()} disabled={busy || !tables.length}>Replace all QR codes</button>
      </form>
    </section>
    {loading ? <div className="portal-empty"><span className="portal-spinner" /> Loading tables…</div> : tables.length === 0
      ? <div className="portal-empty"><strong>No tables yet</strong><span>Add tables above to begin taking QR orders.</span></div>
      : <section className="portal-panel">
        <div className="portal-section-title"><div><h2>Your tables</h2><p>Refreshes automatically every 10 seconds.</p></div><button className="portal-secondary" onClick={() => void refresh()}>↻ Refresh</button></div>
        <div className="portal-table-grid">{tables.map((table) => <article className="portal-table-card" key={table.id}>
          <div className="portal-table-card-head"><strong>{table.label}</strong><span className={`portal-order-status ${table.status === "bill_requested" ? "status-pending" : table.status === "active" ? "status-accepted" : ""}`}>{table.status === "bill_requested" ? "Bill requested" : table.status}</span></div>
          <p>{table.status === "closed" ? "No active guest session." : `Session ${table.active_session_id?.slice(0, 8) ?? ""}`}</p>
          <div className="portal-order-actions">
            {table.status === "closed" ? <button className="portal-action-primary" disabled={busy} onClick={() => setPinTable(table)}>Open table</button> : <>
              <button className="portal-secondary" onClick={onOpenOrders}>Orders / bill</button>
              <button className="portal-secondary" disabled={busy} onClick={() => setMoveTable(table)}>Move / merge</button>
              <button className="portal-action-danger" disabled={busy} onClick={() => void voidTable(table)}>Cancel session</button>
            </>}
            {table.status === "closed" && <button className="portal-action-danger" onClick={() => void removeTable(table)} disabled={busy}>Remove</button>}
          </div>
        </article>)}</div>
      </section>}

    {pinTable && <div className="portal-modal-backdrop"><form className="portal-dialog" onSubmit={(event) => { event.preventDefault(); void openTable(pinTable, pin || undefined); }}>
      <h2>Open {pinTable.label}</h2><p>Set an optional four-digit table code to restrict QR ordering to guests who know the code.</p>
      <label className="portal-field"><span>Table code (optional)</span><input type="password" inputMode="numeric" maxLength={4} value={pin} onChange={(event) => setPin(event.target.value.replace(/\D/g, "").slice(0, 4))} /></label>
      {pin && pin.length !== 4 && <p className="portal-form-hint">The table code must be four digits.</p>}
      <footer><button type="button" className="portal-secondary" onClick={() => { setPinTable(null); setPin(""); }}>Cancel</button><button className="portal-action-primary" disabled={busy || (pin.length > 0 && pin.length !== 4)}>Open table</button></footer>
    </form></div>}

    {moveTable && <div className="portal-modal-backdrop"><section className="portal-dialog">
      <h2>Move or merge {moveTable.label}</h2><p>Choose an empty table to move the guest session, or an occupied table to merge bills.</p>
      {tables.filter((table) => table.id !== moveTable.id).map((table) => <button className="portal-table-choice" key={table.id} disabled={busy || table.status === "bill_requested"} onClick={() => void moveOrMerge(moveTable, table)}>
        <strong>{table.label}</strong><span>{table.status === "closed" ? "Empty · move session here" : `Occupied · merge into ${table.label}`}</span>
      </button>)}
      <footer><button className="portal-secondary" onClick={() => setMoveTable(null)}>Cancel</button></footer>
    </section></div>}

    {qrRows.length > 0 && <div className="portal-modal-backdrop"><section className="portal-dialog portal-qr-dialog">
      <header><div><h2>Table QR codes</h2><p>Print and place one QR code at each table.</p></div><button className="portal-secondary" onClick={() => setQrRows([])}>Close</button></header>
      <div className="portal-qr-preview">{qrRows.map((table) => <article key={table.table_id}><img src={table.image} alt={`QR code for ${table.label}`} /><strong>{table.label}</strong></article>)}</div>
      <footer><button className="portal-secondary" onClick={() => void createQrSheet()} disabled={busy}>Refresh codes</button><button className="portal-action-primary" onClick={printQrSheet}>Print QR sheet</button></footer>
    </section></div>}
  </div>;
}
