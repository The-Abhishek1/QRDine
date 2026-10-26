import Link from "next/link";

export default function LegalPage({
  title,
  updated,
  children,
}: {
  title: string;
  updated: string;
  children: React.ReactNode;
}) {
  return (
    <main className="legal-page">
      <Link className="legal-back" href="/">QR Dine</Link>
      <h1>{title}</h1>
      <p className="legal-updated">Last updated: {updated}</p>
      <div className="legal-content">{children}</div>
      <nav className="legal-nav" aria-label="Legal pages">
        <Link href="/privacy">Privacy policy</Link>
        <Link href="/terms">Terms of use</Link>
      </nav>
    </main>
  );
}
