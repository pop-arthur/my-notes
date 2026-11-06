// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { edgeHeaders, proxyApi, upstreamRequest } from "./backend-transport";
const secret = "runtime-test-secret-with-at-least-32-characters";
const request = (path = "/api/tasks", init?: RequestInit) =>
  new Request(`https://site.workers.dev${path}`, {
    ...init,
    headers: {
      "CF-Connecting-IP": "203.0.113.1",
      Cookie: "JSESSIONID=session",
      "X-CSRF-TOKEN": "csrf",
      "X-My-Notes-Client-IP": "spoof",
      "X-My-Notes-Proxy-Secret": "spoof",
      "X-Forwarded-For": "spoof",
      Forwarded: "for=spoof",
    },
  });
describe("VPC backend transport", () => {
  it("replaces spoofed headers and preserves cookie/CSRF", () => {
    const h = edgeHeaders(request(), secret);
    expect(h.get("X-My-Notes-Client-IP")).toBe("203.0.113.1");
    expect(h.get("X-My-Notes-Proxy-Secret")).toBe(secret);
    expect(h.has("X-Forwarded-For")).toBe(false);
    expect(h.has("Forwarded")).toBe(false);
    expect(h.get("Cookie")).toBe("JSESSIONID=session");
    expect(h.get("X-CSRF-TOKEN")).toBe("csrf");
  });
  it("fails closed without client identity or a strong secret", () => {
    expect(() =>
      edgeHeaders(new Request("https://site.workers.dev/api"), secret),
    ).toThrow();
    expect(() => edgeHeaders(request(), "short")).toThrow();
  });
  it("fixes destination, preserves body and does not follow redirects", async () => {
    const r = upstreamRequest(
      request("/api/tasks?url=https://evil.test", {
        method: "PATCH",
        body: '{"completed":true}',
      }),
      secret,
    );
    expect(new URL(r.url).origin).toBe("http://backend.internal");
    expect(r.redirect).toBe("manual");
    expect(await r.json()).toEqual({ completed: true });
  });
  it("preserves status and separate cookies while preventing caching", async () => {
    const h = new Headers({ "Cache-Control": "public" });
    h.append("Set-Cookie", "JSESSIONID=rotated; HttpOnly; Secure; Path=/");
    h.append("Set-Cookie", "old=; Max-Age=0; Path=/");
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204, headers: h }));
    const r = await proxyApi(request(), {
      BACKEND: { fetch: fetcher },
      EDGE_PROXY_SECRET: secret,
    });
    expect(r.status).toBe(204);
    expect(r.headers.getSetCookie()).toHaveLength(2);
    expect(r.headers.get("Cache-Control")).toBe("no-store");
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("does not replay failed writes or disclose transport exceptions", async () => {
    const f = vi.fn().mockRejectedValue(new Error(secret));
    const r = await proxyApi(
      request("/api/tasks", { method: "POST", body: "{}" }),
      { BACKEND: { fetch: f }, EDGE_PROXY_SECRET: secret },
    );
    expect(r.status).toBe(502);
    expect(await r.text()).not.toContain(secret);
    expect(f).toHaveBeenCalledOnce();
  });
  it("preserves Problem Details but hides non-JSON upstream failures", async () => {
    const f = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json(
          { code: "internal_error" },
          {
            status: 500,
            headers: { "Content-Type": "application/problem+json" },
          },
        ),
      )
      .mockResolvedValueOnce(new Response("PRIVATE", { status: 500 }));
    const env = { BACKEND: { fetch: f }, EDGE_PROXY_SECRET: secret };
    expect((await proxyApi(request(), env)).status).toBe(500);
    const r = await proxyApi(request(), env);
    expect(r.status).toBe(502);
    expect(await r.text()).not.toContain("PRIVATE");
  });
  it("rejects arbitrary proxy targets and missing bindings", async () => {
    expect((await proxyApi(request(), {})).status).toBe(502);
    await expect(proxyApi(request("/admin"), {})).rejects.toThrow();
  });
});
