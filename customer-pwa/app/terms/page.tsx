import type { Metadata } from "next";
import LegalPage from "@/components/LegalPage";

export const metadata: Metadata = {
  title: "Terms of Use | QR Dine",
  description: "Terms for using QR Dine guest ordering and restaurant services.",
  robots: { index: true, follow: true },
};

export default function TermsPage() {
  return (
    <LegalPage title="Terms of Use" updated="26 October 2026">
      <p>
        These terms apply to your use of QR Dine guest ordering pages and the QR Dine administration service.
        QR Dine is operated under the name <strong>QR Dine</strong> in Bengaluru, Karnataka, India. By using
        the service, you agree to these terms. If you do not agree, do not use the service.
      </p>

      <h2>Guest ordering</h2>
      <p>
        QR Dine provides software that lets participating restaurants display menus and receive orders. The
        restaurant, not QR Dine, prepares and supplies food, sets menu availability and prices, accepts or rejects
        orders, and handles questions, cancellations, refunds, food quality, allergens, and fulfilment. Review the
        order details and total shown before submitting. An order is subject to the restaurant&apos;s acceptance.
      </p>
      <p>
        Any UPI payment is made using a payment app or provider selected by you and is subject to that provider&apos;s
        terms. QR Dine does not collect payment-card credentials through the guest ordering page. Contact the
        restaurant directly for payment or refund issues.
      </p>

      <h2>Restaurant accounts</h2>
      <p>
        Restaurant users are responsible for keeping account credentials confidential, maintaining accurate menu,
        tax, pricing, and restaurant information, and ensuring that staff access is appropriate. You must promptly
        notify QR Dine if you suspect unauthorized access. You may use the service only for lawful restaurant
        operations and must not interfere with the service, misuse another person&apos;s account, or submit content
        you do not have the right to provide.
      </p>

      <h2>Availability and content</h2>
      <p>
        We work to keep QR Dine available, but maintenance, network failures, or events outside our control may
        interrupt access. We may make changes to or suspend parts of the service to maintain security, comply with
        law, or address misuse. Restaurant menu descriptions, images, prices, offers, and availability are provided
        by the restaurant; the restaurant is responsible for their accuracy and lawful use.
      </p>

      <h2>Responsibility</h2>
      <p>
        To the extent permitted by applicable law, QR Dine is provided on an &quot;as available&quot; basis, and QR Dine
        is not responsible for a restaurant&apos;s food, service, order acceptance, or payment handling. Nothing in
        these terms excludes a right or liability that cannot legally be excluded. You remain responsible for
        losses caused by your unlawful use or breach of these terms, subject to applicable law.
      </p>

      <h2>Privacy, contact, and governing law</h2>
      <p>
        Our <a href="/privacy">Privacy Policy</a> explains how information is handled. For questions or complaints,
        contact <a href="mailto:idiot63666@gmail.com">idiot63666@gmail.com</a>. These terms are governed by the
        laws of India. Subject to any mandatory consumer-protection rules, courts in Bengaluru, Karnataka have
        jurisdiction over disputes relating to these terms.
      </p>
      <p>
        We may update these terms when the service or applicable requirements change. Continued use after an
        updated version is published means you accept the revised terms, to the extent permitted by law.
      </p>
    </LegalPage>
  );
}
