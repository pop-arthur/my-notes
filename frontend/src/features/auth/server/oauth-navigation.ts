import {
  upstreamRequest,
  type BackendEnvironment,
} from "@/shared/api/backend-transport";
import { ApiError } from "@/shared/api/error";

type OAuthStep = "start" | "callback";
const GOOGLE_AUTHORIZATION_ORIGIN = "https://accounts.google.com";
const OAUTH_TIMEOUT_MS = 8_000;
const paths: Record<OAuthStep, string> = {
  start: "/oauth2/authorization/google",
  callback: "/login/oauth2/code/google",
};

function browserRedirect(location: string, upstream?: Response) {
  const headers = new Headers({
    Location: location,
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
  });
  // Keep Spring's OAuth-state/session cookies, including deletion and rotation.
  for (const cookie of upstream?.headers.getSetCookie() ?? []) {
    headers.append("Set-Cookie", cookie);
  }
  return new Response(null, { status: 302, headers });
}

function errorRedirect(code: string, upstream?: Response, retryAt?: number) {
  const params = new URLSearchParams({ error: code });
  if (retryAt !== undefined) params.set("retryAt", String(retryAt));
  return browserRedirect(`/login?${params}`, upstream);
}

function backendUrl(step: OAuthStep, request: Request, edge = false) {
  const configuredOrigin = edge
    ? "http://backend.internal"
    : process.env.BACKEND_ORIGIN;
  if (!configuredOrigin && process.env.NODE_ENV === "production") {
    throw new Error("BACKEND_ORIGIN is required for OAuth navigation");
  }
  const origin = new URL(configuredOrigin ?? "http://127.0.0.1:8080");
  if (
    !["http:", "https:"].includes(origin.protocol) ||
    origin.username ||
    origin.password ||
    origin.search ||
    origin.hash ||
    origin.pathname !== "/"
  ) {
    throw new Error("BACKEND_ORIGIN must be an HTTP(S) origin");
  }
  const target = new URL(paths[step], origin);
  if (step === "callback") {
    const params = new URL(request.url).searchParams;
    for (const name of ["code", "state", "error"]) {
      for (const value of params.getAll(name))
        target.searchParams.append(name, value);
    }
  }
  return target;
}

function safeDestination(location: string | null, step: OAuthStep) {
  if (!location) return null;
  if (step === "callback") {
    return location === "/" || location === "/login?error=oauth_failed"
      ? location
      : null;
  }
  try {
    const target = new URL(location);
    if (
      target.origin === GOOGLE_AUTHORIZATION_ORIGIN &&
      !target.username &&
      !target.password
    ) {
      return target.href;
    }
  } catch {
    /* An invalid upstream redirect becomes a safe login error. */
  }
  return null;
}

/** Browser navigation only. Spring still owns state, ID-token checks and authentication. */
export async function handleOAuthNavigation(
  request: Request,
  step: OAuthStep,
  edge?: BackendEnvironment,
) {
  try {
    const headers = new Headers({
      Accept: "application/json, application/problem+json",
    });
    const cookie = request.headers.get("Cookie");
    if (cookie) headers.set("Cookie", cookie);
    const options: RequestInit = {
      method: "GET",
      headers,
      redirect: "manual",
      cache: "no-store",
      signal: AbortSignal.timeout(OAUTH_TIMEOUT_MS),
    };
    let upstream: Response;
    if (edge) {
      if (!edge.BACKEND || !edge.EDGE_PROXY_SECRET)
        throw new Error("Missing VPC transport");
      headers.set(
        "CF-Connecting-IP",
        request.headers.get("CF-Connecting-IP") ?? "",
      );
      const navigation = new Request(backendUrl(step, request, true), options);
      upstream = await edge.BACKEND.fetch(
        upstreamRequest(navigation, edge.EDGE_PROXY_SECRET),
      );
    } else upstream = await fetch(backendUrl(step, request), options);
    if ([301, 302, 303, 307, 308].includes(upstream.status)) {
      const destination = safeDestination(
        upstream.headers.get("Location"),
        step,
      );
      return destination
        ? browserRedirect(destination, upstream)
        : errorRedirect("oauth_failed", upstream);
    }
    const error = await ApiError.fromResponse(upstream);
    if (upstream.status === 429)
      return errorRedirect("rate_limited", upstream, error.retryAt);
    if (
      step === "start" &&
      upstream.status === 503 &&
      error.code === "google_unavailable"
    ) {
      return errorRedirect("google_unavailable", upstream);
    }
    return errorRedirect(
      step === "callback" ? "oauth_failed" : "oauth_unavailable",
      upstream,
    );
  } catch {
    // Never render upstream JSON/HTML or reflect callback parameters into an error page.
    return errorRedirect(
      step === "callback" ? "oauth_failed" : "oauth_unavailable",
    );
  }
}
