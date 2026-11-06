/** Test-only local binding, never included in the production Wrangler config. */
const backend = {
  async fetch(request: Request) {
    const url = new URL(request.url);
    if (url.pathname === "/actuator/health")
      return Response.json({ status: "UP" });
    if (url.pathname === "/api/fail")
      throw new Error("Test-only unavailable upstream");
    const headers = new Headers({ "Cache-Control": "public, max-age=3600" });
    headers.append(
      "Set-Cookie",
      "JSESSIONID=worker-test; HttpOnly; Secure; SameSite=Lax; Path=/",
    );
    headers.append("Set-Cookie", "old=; Max-Age=0; Path=/");
    if (url.pathname === "/oauth2/authorization/google") {
      headers.set(
        "Location",
        "https://accounts.google.com/o/oauth2/v2/auth?state=test-state",
      );
      return new Response(null, { status: 302, headers });
    }
    if (url.pathname === "/login/oauth2/code/google") {
      headers.set("Location", "/");
      return new Response(null, { status: 302, headers });
    }
    if (url.pathname === "/api/empty")
      return new Response(null, { status: 204, headers });
    return Response.json(
      {
        method: request.method,
        cookie: request.headers.get("Cookie"),
        csrf: request.headers.get("X-CSRF-TOKEN"),
        client: request.headers.get("X-My-Notes-Client-IP"),
        forwarded: request.headers.get("X-Forwarded-For"),
        proxyAuthenticated:
          request.headers.get("X-My-Notes-Proxy-Secret") ===
          "runtime-test-secret-with-at-least-32-characters",
        body: request.method === "GET" ? null : await request.text(),
      },
      { headers },
    );
  },
};
export default backend;
