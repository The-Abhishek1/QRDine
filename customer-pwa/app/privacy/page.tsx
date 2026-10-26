import type { Metadata } from "next";
import LegalPage from "@/components/LegalPage";

export const metadata: Metadata = {
  title: "Privacy Policy | QR Dine",
  description: "How QR Dine handles information when guests order or restaurants use QR Dine.",
  robots: { index: true, follow: true },
};

export default function PrivacyPage() {
  return (
    <LegalPage title="Privacy Policy" updated="26 October 2026">
      <p>
        This policy explains how QR Dine handles information when guests use a restaurant&apos;s QR ordering page
        or restaurant teams use the QR Dine administration app. The service is operated as <strong>QR Dine</strong>
        in Bengaluru, Karnataka, India. Questions or privacy requests can be sent to{" "}
        <a href="mailto:idiot63666@gmail.com">idiot63666@gmail.com</a>.
      </p>

      <h2>Information handled</h2>
      <ul>
        <li>
          <strong>Guest orders:</strong> the table or session, selected items and options, quantities, notes,
          order and bill status, and any review rating or comment you choose to submit. The guest ordering flow
          does not ask you to create an account or provide your name, email address, or phone number.
        </li>
        <li>
          <strong>Restaurant accounts:</strong> account email and authentication data, restaurant and staff
          details entered by the restaurant, menu and order records, and device identifiers used for order
          notifications. Passwords are handled by the authentication provider and are not available to QR Dine
          staff in readable form.
        </li>
        <li>
          <strong>Device and browser storage:</strong> the guest page stores the active table session, cart,
          an order de-duplication key, and language preference in browser storage so ordering can continue after
          a refresh. The Android app may store its authentication session and notification settings on the device.
        </li>
        <li>
          <strong>Technical information:</strong> the hosting, database, and notification providers may process
          network and diagnostic information necessary to deliver, secure, and troubleshoot the service.
        </li>
      </ul>

      <h2>How information is used and shared</h2>
      <p>
        Information is used to display restaurant menus, route orders to restaurant staff, provide order and bill
        status, enable reviews, manage restaurant accounts, send operational notifications, and protect the service.
        Guest order and review information is shared with the restaurant that serves the table. QR Dine uses
        infrastructure providers, including Supabase, to host application data and provide authentication and
        backend services; providers process data on QR Dine&apos;s behalf under their own terms and privacy notices.
      </p>
      <p>
        QR Dine does not sell personal information or use it for targeted advertising. Payment details are not
        entered into the guest ordering page. If you pay using a restaurant&apos;s UPI option, the payment is
        handled by the payment app or provider you choose and is subject to its privacy terms.
      </p>

      <h2>Retention and your choices</h2>
      <p>
        Restaurant order, bill, and review records may be retained by the restaurant and QR Dine for service,
        accounting, security, and legal requirements. Guest browser data can be cleared using the browser&apos;s
        site-data controls; clearing it may end or interrupt an active table session. Restaurant account holders
        can contact us to request access, correction, or deletion of account information, subject to records that
        must be retained for legitimate operational or legal reasons. Guests may contact the restaurant or QR Dine
        about an order or review.
      </p>

      <h2>Security and changes</h2>
      <p>
        We use access controls and signed session links to help protect information, but no internet service can
        guarantee absolute security. We may update this policy as the service changes. The updated date above
        identifies the latest published version.
      </p>
    </LegalPage>
  );
}
