import type { components } from "./schema";
import { ApiError } from "./error";
type Csrf = components["schemas"]["Csrf"];
type Method = "GET" | "POST" | "PATCH" | "DELETE";
let csrf: Csrf | undefined;
let csrfRequest: Promise<Csrf> | undefined;
let writes: Promise<unknown> = Promise.resolve();
const expiredListeners = new Set<() => void>();
export const sessionEvents = {
  subscribe(listener: () => void) {
    expiredListeners.add(listener);
    return () => {
      expiredListeners.delete(listener);
    };
  },
};
export function resetCsrf() {
  csrf = undefined;
  csrfRequest = undefined;
}
async function fetchResponse(path: string, init: RequestInit) {
  try {
    return await fetch(path, {
      ...init,
      credentials: "include",
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(0, "network_error");
  }
}
export async function getCsrf(): Promise<Csrf> {
  if (csrf) return csrf;
  if (!csrfRequest) {
    csrfRequest = (async () => {
      const res = await fetchResponse("/api/auth/csrf", { method: "GET" });
      if (!res.ok) throw await ApiError.fromResponse(res);
      const value: unknown = await res.json();
      if (
        !value ||
        typeof value !== "object" ||
        !("headerName" in value) ||
        value.headerName !== "X-CSRF-TOKEN" ||
        !("token" in value) ||
        typeof value.token !== "string" ||
        !value.token
      )
        throw new ApiError(502, "invalid_response");
      csrf = { headerName: value.headerName, token: value.token };
      return csrf;
    })().finally(() => {
      csrfRequest = undefined;
    });
  }
  return csrfRequest;
}
async function execute<T>(
  path: string,
  method: Method,
  body?: unknown,
  signal?: AbortSignal,
  rotatesSession = false,
): Promise<T> {
  const headers = new Headers({
    Accept: "application/json, application/problem+json",
  });
  if (body !== undefined) headers.set("Content-Type", "application/json");
  if (method !== "GET") {
    const token = await getCsrf();
    headers.set(token.headerName, token.token);
  }
  const response = await fetchResponse(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  if (!response.ok) {
    const error = await ApiError.fromResponse(response);
    if (error.code === "csrf_invalid") {
      resetCsrf();
      await getCsrf().catch(() => undefined);
    }
    if (error.code === "unauthenticated") {
      resetCsrf();
      expiredListeners.forEach((listener) => listener());
    }
    throw error; // Mutations are never retried automatically, including after CSRF refresh.
  }
  if (rotatesSession) resetCsrf();
  if (response.status === 204) return undefined as T;
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError(502, "invalid_response");
  }
}
export const api = {
  get<T>(path: string, signal?: AbortSignal) {
    return execute<T>(path, "GET", undefined, signal);
  },
  mutate<T>(
    path: string,
    method: Exclude<Method, "GET">,
    body?: unknown,
    rotatesSession = false,
  ) {
    // Serialize writes so session changes and task mutations cannot race within this tab.
    const next = writes.then(() =>
      execute<T>(path, method, body, undefined, rotatesSession),
    );
    writes = next.catch(() => undefined);
    return next;
  },
};
