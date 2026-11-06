/** Server-side transport shared by the Worker entrypoint and OAuth navigation. */
export interface BackendBinding {
  fetch(request: Request): Promise<Response>;
}
export interface BackendEnvironment {
  BACKEND?: BackendBinding;
  EDGE_PROXY_SECRET?: string;
}
export const proxyHeaders = {
  secret: "X-My-Notes-Proxy-Secret",
  client: "X-My-Notes-Client-IP",
} as const;
export function edgeHeaders(request: Request, secret: string) {
  if (secret.length < 32) throw new Error("Edge transport is not configured");
  const client = request.headers.get("CF-Connecting-IP");
  if (!client || client.length > 45 || !/^[0-9a-fA-F:.]+$/.test(client))
    throw new Error("Edge client address is unavailable");
  const headers = new Headers(request.headers);
  for (const name of [
    "Forwarded",
    "X-Forwarded-For",
    "X-Forwarded-Host",
    "X-Forwarded-Proto",
    "Host",
    "CF-Connecting-IP",
    "CF-Connecting-IPv6",
    "True-Client-IP",
  ])
    headers.delete(name);
  headers.set(proxyHeaders.secret, secret);
  headers.set(proxyHeaders.client, client);
  return headers;
}
export function upstreamRequest(request: Request, secret: string) {
  const source = new URL(request.url),
    target = new URL("http://backend.internal");
  target.pathname = source.pathname;
  target.search = source.search;
  const init: RequestInit & { duplex: "half" } = {
    method: request.method,
    headers: edgeHeaders(request, secret),
    body: ["GET", "HEAD"].includes(request.method) ? undefined : request.body,
    duplex: "half",
    redirect: "manual",
    signal: AbortSignal.any([request.signal, AbortSignal.timeout(15_000)]),
  };
  return new Request(target, init);
}
export function gatewayProblem(path: string) {
  return Response.json(
    {
      type: "about:blank",
      title: "Bad Gateway",
      status: 502,
      code: "internal_error",
      instance: path,
    },
    {
      status: 502,
      headers: {
        "Content-Type": "application/problem+json",
        "Cache-Control": "no-store",
      },
    },
  );
}
export async function proxyApi(request: Request, env: BackendEnvironment) {
  const path = new URL(request.url).pathname;
  if (
    path !== "/api" &&
    !path.startsWith("/api/") &&
    path !== "/actuator/health"
  )
    throw new Error("Only fixed API and health routes can be proxied");
  try {
    if (!env.BACKEND || !env.EDGE_PROXY_SECRET)
      throw new Error("Missing backend binding");
    const response = await env.BACKEND.fetch(
      upstreamRequest(request, env.EDGE_PROXY_SECRET),
    );
    if (
      response.status >= 500 &&
      !response.headers
        .get("Content-Type")
        ?.startsWith("application/problem+json")
    ) {
      await response.body?.cancel();
      return gatewayProblem(path);
    }
    const headers = new Headers(response.headers);
    headers.set("Cache-Control", "no-store");
    headers.delete("Set-Cookie");
    for (const cookie of response.headers.getSetCookie())
      headers.append("Set-Cookie", cookie);
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  } catch {
    return gatewayProblem(path);
  }
}
