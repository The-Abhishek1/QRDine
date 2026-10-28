function QrIllustration() {
  return (
    <div className="welcome-visual" aria-hidden="true">
      <div className="welcome-orbit welcome-orbit-one" />
      <div className="welcome-orbit welcome-orbit-two" />
      <div className="welcome-plate">
        <span className="plate-garnish" />
        <span className="plate-food plate-food-one" />
        <span className="plate-food plate-food-two" />
        <span className="plate-food plate-food-three" />
      </div>
      <div className="scan-card">
        <div className="qr-pattern">
          <span className="qr-corner qr-corner-tl" />
          <span className="qr-corner qr-corner-tr" />
          <span className="qr-corner qr-corner-bl" />
          <span className="qr-modules" />
          <span className="qr-center">Q</span>
        </div>
        <span className="scan-caption">SCAN · ORDER · ENJOY</span>
      </div>
      <span className="welcome-spark welcome-spark-one">✦</span>
      <span className="welcome-spark welcome-spark-two">✧</span>
      <div className="welcome-float-card">
        <span className="welcome-float-icon">✓</span>
        <span><strong>Made simple</strong><small>From menu to table</small></span>
      </div>
    </div>
  );
}

export default function Home() {
  return (
    <main className="welcome-page">
      <header className="welcome-nav">
        <a className="welcome-brand" href="/" aria-label="QR Dine home">
          <span className="welcome-brand-mark">Q</span>
          <span>QR<span>Dine</span></span>
        </a>
        <span className="welcome-nav-note"><span /> Your table, your menu</span>
      </header>

      <section className="welcome-hero">
        <div className="welcome-copy">
          <span className="welcome-eyebrow"><span>✦</span> THE TABLE IS YOURS</span>
          <h1>Good food.<br /><em>No waiting.</em></h1>
          <p>
            Scan the QR code at your table to explore the menu, make it yours with extras, and send your order
            straight to the kitchen.
          </p>
          <a className="welcome-cta" href="/explore">Order online from restaurants <span aria-hidden="true">→</span></a>
          <a className="welcome-cta" href="#how-it-works">
            See how it works <span aria-hidden="true">↓</span>
          </a>
          <div className="welcome-promise">
            <span className="promise-check">✓</span>
            No app download. No account. Just scan and enjoy.
          </div>
        </div>
        <QrIllustration />
      </section>

      <section className="welcome-how" id="how-it-works">
        <div className="welcome-section-heading">
          <span className="welcome-eyebrow">A LITTLE MORE CONVENIENT</span>
          <h2>Your meal, in three easy steps.</h2>
        </div>
        <div className="welcome-steps">
          <article className="welcome-step">
            <span className="step-number">01</span>
            <span className="step-icon" aria-hidden="true">⌗</span>
            <h3>Scan your table</h3>
            <p>Open your phone camera and tap the restaurant&apos;s QR link.</p>
          </article>
          <article className="welcome-step">
            <span className="step-number">02</span>
            <span className="step-icon" aria-hidden="true">＋</span>
            <h3>Pick your favorites</h3>
            <p>Browse dishes, choose options, and add any extras you like.</p>
          </article>
          <article className="welcome-step">
            <span className="step-number">03</span>
            <span className="step-icon" aria-hidden="true">↗</span>
            <h3>Order at your pace</h3>
            <p>Send your order and follow updates right here on your phone.</p>
          </article>
        </div>
        <p className="welcome-help">Already scanned? Your restaurant menu opens automatically.</p>
      </section>
    </main>
  );
}
