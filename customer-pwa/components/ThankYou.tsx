"use client";
import { useState } from "react";
import { rupees } from "@/lib/money";
import { useI18n } from "@/lib/i18n";

function Stars({ label, value, onChange }: { label: string; value: number; onChange: (n: number) => void }) {
  const { t } = useI18n();
  return (
    <div className="stars-row">
      <span>{label}</span>
      <div className="stars" role="radiogroup" aria-label={label}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} role="radio" aria-checked={value === n} aria-label={t("starsOf", { n })} className={n <= value ? "on" : ""} onClick={() => onChange(n)}>★</button>
        ))}
      </div>
    </div>
  );
}

export default function ThankYou({ name, paid, canReview, reviewed, onSubmit }: {
  name: string; paid: { total: number; method: string } | null; canReview: boolean; reviewed: boolean;
  onSubmit: (r: { food?: number; service?: number; comment?: string }) => Promise<void>;
}) {
  const { t } = useI18n();
  const [food, setFood] = useState(0);
  const [service, setService] = useState(0);
  const [comment, setComment] = useState("");
  const [state, setState] = useState<"idle" | "sending" | "done" | "error">(reviewed ? "done" : "idle");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function send() {
    setState("sending");
    setErrorMessage(null);
    try {
      await onSubmit({ food: food || undefined, service: service || undefined, comment: comment.trim() || undefined });
      setState("done");
    } catch (e) {
      setErrorMessage(e instanceof Error ? e.message : t("genericError"));
      setState("error");
    }
  }

  return (
    <main className="screen thanks">
      <h1 className="screen-title">{paid ? t("thanks") : t("thanksDining")}</h1>
      <p className="screen-body">
        {paid
          ? t("paidByAt", { amount: rupees(paid.total), method: t(paid.method === "cash" ? "methodCash" : paid.method === "upi" ? "methodUpi" : paid.method === "split" ? "methodSplit" : "paymentMethod", { method: paid.method }), name })
          : t("sessionClosedAt", { name })}
      </p>

      {paid && canReview && state !== "done" && (
        <section className="review-card" aria-label={t("rateYourVisit")}>
          <h2>{t("rateVisit")}</h2>
          <Stars label={t("food")} value={food} onChange={setFood} />
          <Stars label={t("service")} value={service} onChange={setService} />
          <textarea rows={3} maxLength={500} value={comment} onChange={(e) => setComment(e.target.value)} placeholder={t("feedbackPrompt")} />
          {state === "error" && <p className="error" role="alert">{errorMessage}</p>}
          <button className="btn-primary" onClick={send} disabled={state === "sending" || (!food && !service && !comment.trim())}>
            {state === "sending" ? t("sending") : t("sendFeedback")}
          </button>
        </section>
      )}
      {state === "done" && <p className="notice">{t("feedbackThanks")}</p>}
      <p className="fine">{t("orderAgain")}</p>
    </main>
  );
}
