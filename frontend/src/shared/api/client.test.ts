import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, getCsrf, resetCsrf, sessionEvents } from "./client";
import { ApiError } from "./error";
const json = (value: unknown, status = 200, headers?: HeadersInit) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
beforeEach(() => resetCsrf());
describe("session HTTP client", () => {
  it("deduplicates CSRF loading and includes credentials on all requests", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(json({ headerName: "X-CSRF-TOKEN", token: "csrf" }));
    vi.stubGlobal("fetch", fetcher);
    await Promise.all([getCsrf(), getCsrf()]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      credentials: "include",
      cache: "no-store",
    });
  });
  it("sends JSON/CSRF and handles no-content without parsing JSON", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json({ headerName: "X-CSRF-TOKEN", token: "csrf" }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(
      api.mutate<void>("/api/tasks/1", "DELETE"),
    ).resolves.toBeUndefined();
    const init = fetcher.mock.calls[1]?.[1] as RequestInit;
    expect(new Headers(init.headers).get("X-CSRF-TOKEN")).toBe("csrf");
    expect(init.credentials).toBe("include");
  });
  it("refreshes invalid CSRF but never replays a mutation", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json({ headerName: "X-CSRF-TOKEN", token: "old" }))
      .mockResolvedValueOnce(json({ code: "csrf_invalid" }, 403))
      .mockResolvedValueOnce(
        json({ headerName: "X-CSRF-TOKEN", token: "new" }),
      );
    vi.stubGlobal("fetch", fetcher);
    await expect(
      api.mutate("/api/tasks", "POST", { title: "x" }),
    ).rejects.toMatchObject({ code: "csrf_invalid" });
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(1);
    expect((await getCsrf()).token).toBe("new");
  });
  it("invalidates CSRF after successful session rotation", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json({ headerName: "X-CSRF-TOKEN", token: "old" }))
      .mockResolvedValueOnce(json({ id: "user" }))
      .mockResolvedValueOnce(
        json({ headerName: "X-CSRF-TOKEN", token: "new" }),
      );
    vi.stubGlobal("fetch", fetcher);
    await api.mutate("/api/auth/login", "POST", {}, true);
    expect((await getCsrf()).token).toBe("new");
  });
  it("notifies expired sessions but not invalid login credentials", async () => {
    const listener = vi.fn();
    const unsubscribe = sessionEvents.subscribe(listener);
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json({ code: "invalid_credentials" }, 401))
      .mockResolvedValueOnce(json({ code: "unauthenticated" }, 401));
    vi.stubGlobal("fetch", fetcher);
    await expect(api.get("/first")).rejects.toBeInstanceOf(ApiError);
    expect(listener).not.toHaveBeenCalled();
    await expect(api.get("/second")).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledOnce();
    unsubscribe();
  });
  it("exposes retry deadline and converts network errors safely", async () => {
    const error = await ApiError.fromResponse(
      json({ code: "rate_limited" }, 429, { "Retry-After": "30" }),
    );
    expect(error.retryAt).toBeGreaterThan(Date.now() + 29000);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("private network details")),
    );
    await expect(api.get("/api/tasks")).rejects.toMatchObject({
      code: "network_error",
    });
  });
  it("serializes concurrent writes and recovers its queue after a failure", async () => {
    const order: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) => {
        order.push(path);
        if (path.endsWith("csrf"))
          return json({ headerName: "X-CSRF-TOKEN", token: "csrf" });
        if (path === "/first") {
          await new Promise((resolve) => setTimeout(resolve, 10));
          return json({ code: "validation_failed" }, 400);
        }
        return new Response(null, { status: 204 });
      }),
    );
    const results = await Promise.allSettled([
      api.mutate("/first", "POST"),
      api.mutate("/second", "POST"),
    ]);
    expect(results.map((r) => r.status)).toEqual(["rejected", "fulfilled"]);
    expect(order).toEqual(["/api/auth/csrf", "/first", "/second"]);
  });
});
