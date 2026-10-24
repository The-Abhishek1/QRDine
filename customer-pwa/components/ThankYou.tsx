"use client";
import { useState } from "react";
import { rupees } from "@/lib/money";
import { useI18n } from "@/lib/i18n";

function Stars({ label, value, onChange }: { label: string; value: number; onChange: (n: number) => void }) {
  return (
    <div className="stars-row">
      <span>{label}</span>
      <div className="stars" role="radiogroup" aria-label={label}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} role="radio" aria-checked={value === n} aria-label={`${n} of 5`} className={n <= value ? "on" : ""} onClick={() => onChange(n)}>★</button>
        ))}
      </div>
    </div>
  );
}

const METHOD: Record<string, string> = { cash: "cash", upi: "UPI", split: "cash and UPI" };

export default function ThankYou({ name, paid, canReview, reviewed, onSubmit }: {
  name: string; paid: { total: number; method: string } | null; canReview: boolean; reviewed: boolean;
  onSubmit: (r: { food?: number; service?: number; comment?: string }) => Promise<void>;
}) {
  const { t } = useI18n();
  const [food, setFood] = useState(0);
  const [service, setService] = useState(0);
  const [comment, setComment] = useState("");
  const [state, setState] = useState<"idle" | "sending" | "done" | "error">(reviewed ? "done" : "idle");

  async function send() {
    setState("sending");
    try {
      await onSubmit({ food: food || undefined, service: service || undefined, comment: comment.trim() || undefined });
      setState("done");
    } catch { setState("error"); }
  }

  return (
    <main className="screen thanks">
      <h1 className="screen-title">{paid ? `${t("thanks")}` : "Thanks for dining with us"}</h1>
      <p className="screen-body">
        {paid
          ? `${rupees(paid.total)} paid by ${METHOD[paid.method] ?? paid.method} at ${name}. We hope you enjoyed your meal.`
          : `This table's session at ${name} is closed.`}
      </p>

      {paid && canReview && state !== "done" && (
        <section className="review-card" aria-label="Rate your visit">
          <h2>{t("rateVisit")}</h2>
          <Stars label={t("food")} value={food} onChange={setFood} />
          <Stars label={t("service")} value={service} onChange={setService} />
          <textarea rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Anything we should know? (optional)" />
          {state === "error" && <p className="error" role="alert">Could not send. Please try again.</p>}
          <button className="btn-primary" onClick={send} disabled={state === "sending" || (!food && !service && !comment.trim())}>
            {state === "sending" ? t("sending") : t("sendFeedback")}
          </button>
        </section>
      )}
      {state === "done" && <p className="notice">Thank you for your feedback.</p>}
      <p className="fine">To order again, scan the QR code on the table.</p>
    </main>
  );
}
