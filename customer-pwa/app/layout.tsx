import type { Metadata, Viewport } from "next";
import "@fontsource/hind/400.css";
import "@fontsource/hind/500.css";
import "@fontsource/hind/600.css";
import "@fontsource/hind/700.css";
import "./globals.css";
import RegisterSW from "@/components/RegisterSW";

export const metadata: Metadata = {
  title: "QR Dine | Good food, no waiting",
  description: "Scan the QR code at your table to browse the menu, customize your order, and follow updates.",
  referrer: "no-referrer",
  robots: { index: false, follow: false },
};
export const viewport: Viewport = { width: "device-width", initialScale: 1, themeColor: "#091b15" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        {children}
        <footer className="site-legal-footer">
          <a href="/privacy">Privacy policy</a>
          <a href="/terms">Terms of use</a>
        </footer>
        <RegisterSW />
      </body>
    </html>
  );
}
