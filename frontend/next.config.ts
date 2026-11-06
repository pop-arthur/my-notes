import type { NextConfig } from "next";
const nextConfig: NextConfig = {
  distDir: process.env.NEXT_DIST_DIR ?? ".next",
  poweredByHeader: false,
  devIndicators: false,
  logging: {
    fetches: { fullUrl: false },
    incomingRequests: {
      ignore: [
        /^\/login\/oauth2\//,
        /^\/verify-email(?:\?|$)/,
        /^\/reset-password(?:\?|$)/,
      ],
    },
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
        ],
      },
    ];
  },
  async rewrites() {
    // Backend disables CORS. Local development needs the same origin for cookies/OAuth.
    // Production uses ingress routing directly to Spring; no application BFF.
    if (process.env.NODE_ENV !== "development") return [];
    const origin = process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8080";
    return ["/api/:path*"].map((source) => ({
      source,
      destination: `${origin}${source}`,
    }));
  },
};
export default nextConfig;
