import type { BackendBinding } from "../src/shared/api/backend-transport";
declare global {
  interface CloudflareEnv {
    BACKEND?: BackendBinding;
    EDGE_PROXY_SECRET?: string;
  }
}
