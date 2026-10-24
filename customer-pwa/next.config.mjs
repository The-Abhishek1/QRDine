/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  async headers() {
    return [
      {
        // The QR token is in the URL path: never leak it through Referer to image hosts, never cache the page.
        source: "/t/:token*",
        headers: [
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Cache-Control", value: "no-store" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
        ],
      },
    ];
  },
};
export default nextConfig;
