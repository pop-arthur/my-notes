// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { handleOAuthNavigation } from "./oauth-navigation";

const request = (path = "/oauth2/authorization/google") =>
  new Request(`http://localhost:3000${path}`, {
    headers: { Cookie: "JSESSIONID=original-session" },
  });
const jsonError = (status: number, code: string) =>
  new Response(
    JSON.stringify({
      status,
      code,
      detail: "INTERNAL ERROR MUST NOT REACH THE PAGE",
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );

beforeEach(() => vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080"));
afterEach(() => vi.unstubAllEnvs());

describe("OAuth browser navigation", () => {
  it("turns disabled Google JSON into a safe login redirect", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(jsonError(503, "google_unavailable"));
    vi.stubGlobal("fetch", fetcher);
    const response = await handleOAuthNavigation(request(), "start");
    expect(response.status).toBe(302);
    expect(response.headers.get("Location")).toBe(
      "/login?error=google_unavailable",
    );
    expect(await response.text()).toBe("");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("starts Google exactly once and preserves state cookies and redirect parameters", async () => {
    const location =
      "https://accounts.google.com/o/oauth2/v2/auth?state=opaque-state&nonce=opaque-nonce";
    const headers = new Headers({ Location: location });
    headers.append(
      "Set-Cookie",
      "JSESSIONID=oauth-state; Path=/; HttpOnly; SameSite=Lax",
    );
    headers.append("Set-Cookie", "another=value; Path=/; Secure");
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 302, headers }));
    vi.stubGlobal("fetch", fetcher);
    const response = await handleOAuthNavigation(request(), "start");
    expect(response.headers.get("Location")).toBe(location);
    expect(response.headers.getSetCookie()).toEqual(headers.getSetCookie());
    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0]!;
    expect(String(url)).toBe(
      "http://127.0.0.1:8080/oauth2/authorization/google",
    );
    expect(init).toMatchObject({
      method: "GET",
      redirect: "manual",
      cache: "no-store",
    });
    expect(new Headers(init.headers).get("Cookie")).toBe(
      "JSESSIONID=original-session",
    );
  });

  it("passes callback code/state to Spring and forwards its rotated session", async () => {
    const headers = new Headers({
      Location: "/",
      "Set-Cookie": "JSESSIONID=authenticated; HttpOnly; Path=/",
    });
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 302, headers }));
    vi.stubGlobal("fetch", fetcher);
    const response = await handleOAuthNavigation(
      request(
        "/login/oauth2/code/google?code=secret-code&state=secret-state&error_description=private-text&returnUrl=https://evil.example",
      ),
      "callback",
    );
    expect(response.headers.get("Location")).toBe("/");
    expect(response.headers.getSetCookie()).toEqual(headers.getSetCookie());
    expect(String(fetcher.mock.calls[0]?.[0])).toBe(
      "http://127.0.0.1:8080/login/oauth2/code/google?code=secret-code&state=secret-state",
    );
    expect(await response.text()).toBe("");
  });

  it("preserves logout cookies on a failed callback without reflecting secrets", async () => {
    const headers = new Headers({
      Location: "/login?error=oauth_failed",
      "Set-Cookie": "JSESSIONID=; Max-Age=0; Path=/; HttpOnly",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 302, headers })),
    );
    const response = await handleOAuthNavigation(
      request("/login/oauth2/code/google?error=access_denied&state=secret"),
      "callback",
    );
    expect(response.headers.get("Location")).toBe("/login?error=oauth_failed");
    expect(response.headers.getSetCookie()).toEqual(headers.getSetCookie());
  });

  it("handles rate limiting before OAuth redirect", async () => {
    const upstream = jsonError(429, "rate_limited");
    upstream.headers.set("Retry-After", "30");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(upstream));
    const response = await handleOAuthNavigation(request(), "start");
    const location = new URL(
      response.headers.get("Location")!,
      "http://localhost:3000",
    );
    expect(location.searchParams.get("error")).toBe("rate_limited");
    expect(Number(location.searchParams.get("retryAt"))).toBeGreaterThan(
      Date.now() + 29_000,
    );
  });

  it.each(["start", "callback"] as const)(
    "does not expose HTML proxy errors during %s",
    async (step) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response("<h1>Upstream stack trace</h1>", {
            status: 502,
            headers: { "Content-Type": "text/html" },
          }),
        ),
      );
      const response = await handleOAuthNavigation(request(), step);
      expect(response.status).toBe(302);
      expect(response.headers.get("Location")).toBe(
        `/login?error=${step === "start" ? "oauth_unavailable" : "oauth_failed"}`,
      );
      expect(await response.text()).toBe("");
    },
  );

  it.each(["start", "callback"] as const)(
    "handles unreachable backend and timeouts during %s",
    async (step) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockRejectedValue(
            new DOMException("private upstream address", "TimeoutError"),
          ),
      );
      const response = await handleOAuthNavigation(request(), step);
      expect(response.status).toBe(302);
      expect(response.headers.get("Location")).toBe(
        `/login?error=${step === "start" ? "oauth_unavailable" : "oauth_failed"}`,
      );
    },
  );

  it.each([
    "https://evil.example/",
    "//evil.example/",
    "https://accounts.google.com.evil.example/",
    "javascript:alert(1)",
  ])("rejects an unexpected upstream destination %s", async (location) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(null, { status: 302, headers: { Location: location } }),
        ),
    );
    const response = await handleOAuthNavigation(request(), "start");
    expect(response.headers.get("Location")).toBe("/login?error=oauth_failed");
  });

  it("rejects callback redirects outside the two documented destinations", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: 302,
          headers: { Location: "https://evil.example/" },
        }),
      ),
    );
    const response = await handleOAuthNavigation(request(), "callback");
    expect(response.headers.get("Location")).toBe("/login?error=oauth_failed");
  });

  it("fails safely if the production backend origin is missing", async () => {
    vi.stubEnv("NODE_ENV", "production");
    vi.stubEnv("BACKEND_ORIGIN", "");
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    const response = await handleOAuthNavigation(request(), "start");
    expect(response.headers.get("Location")).toBe(
      "/login?error=oauth_unavailable",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
});
