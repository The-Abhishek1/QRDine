export function rupees(paise: number): string {
  const r = paise / 100;
  return "₹" + (Number.isInteger(r) ? r.toString() : r.toFixed(2));
}
